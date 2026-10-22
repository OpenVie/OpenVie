# Copyright 2026 LinkedNodeDigital
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""OCR for scanned PDFs and standalone images.

Design notes:
- Default provider is Tesseract (Apache-2.0) executed through its TSV output so
  a single call yields both the recognized text and per-word confidences.
- ``ollama-vlm`` is the operator-choice backend for hard pages; it posts the
  rendered page to the configured Ollama vision model.
- All work runs off the ingestion event loop: ``DocumentTextExtractor.parse``
  stays synchronous and the pipeline invokes it through ``asyncio.to_thread``;
  inside the parser the service spins a dedicated loop per document, bounded by
  ``INGESTION_OCR_PAGE_CONCURRENCY``.
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import logging
import tempfile
import time
from collections.abc import Callable, Coroutine, Sequence
from dataclasses import dataclass
from io import BytesIO
from typing import Any, Protocol, TypeVar

import httpx

from app.common.metrics import AI_INGESTION_OCR_PAGES_TOTAL, AI_INGESTION_OCR_SECONDS
from app.modules.ingestion.api import PermanentIngestionFailure, TransientIngestionFailure

logger = logging.getLogger(__name__)

_OCR_ARTIFACT_SCHEMA = "ocr-artifact-v1"
_TESSERACT_INPUT_FORMATS = {"PNG": ".png", "JPEG": ".jpg", "WEBP": ".webp", "TIFF": ".tiff"}
_MIME_FORMATS = {"image/png": "PNG", "image/jpeg": "JPEG", "image/webp": "WEBP"}
# Logos, icons, and decorative rules usually OCR to a few stray glyphs; the
# best-effort embedded path drops anything below this threshold instead of
# polluting the knowledge index with noise.
_EMBEDDED_MIN_TEXT_CHARS = 8


class _UnreadableImageError(PermanentIngestionFailure):
    """Internal marker: the image bytes could not be decoded for OCR.

    Subclassing keeps the legacy "Image document could not be decoded for OCR"
    message intact for every caller while letting the embedded-image path tell
    data-quality failures (skip silently) apart from provider outages (streak).
    """

_T = TypeVar("_T")


@dataclass(frozen=True, slots=True)
class OcrRecognition:
    """Recognized page text plus an optional mean word confidence (0-100)."""

    text: str
    confidence: float | None = None


class OcrProvider(Protocol):
    name: str

    async def recognize(self, image_bytes: bytes, *, image_format: str) -> OcrRecognition: ...

    def fingerprint_tag(self) -> str: ...


def oracle_fingerprint(provider_tag: str, dpi: int, raw_sha256: str) -> str:
    """Stable fingerprint binding an OCR artifact to its inputs and settings."""
    material = f"{_OCR_ARTIFACT_SCHEMA}|{provider_tag}|{dpi}|{raw_sha256}"
    return hashlib.sha256(material.encode("utf-8")).hexdigest()[:32]


class TesseractOcrProvider:
    """Runs the tesseract CLI and parses its TSV output (text + confidence)."""

    name = "tesseract"

    def __init__(self, languages: str, psm: int, timeout_seconds: float):
        self._languages = languages.strip()
        self._psm = psm
        self._timeout_seconds = timeout_seconds

    def fingerprint_tag(self) -> str:
        return f"tesseract:{self._languages}:{self._psm}"

    async def recognize(self, image_bytes: bytes, *, image_format: str) -> OcrRecognition:
        started_at = time.perf_counter()
        outcome = "success"
        try:
            text, confidence = await self._run(image_bytes, image_format)
        except Exception:
            outcome = "error"
            raise
        finally:
            AI_INGESTION_OCR_SECONDS.labels(provider=self.name, outcome=outcome).observe(
                time.perf_counter() - started_at
            )
            AI_INGESTION_OCR_PAGES_TOTAL.labels(provider=self.name, outcome=outcome).inc()
        return OcrRecognition(text=text, confidence=confidence)

    async def _run(self, image_bytes: bytes, image_format: str) -> tuple[str, float | None]:
        del image_format  # the rendered/written file name already has the right suffix
        with tempfile.NamedTemporaryFile(suffix=".png", delete=True) as handle:
            await asyncio.to_thread(handle.write, image_bytes)
            handle.flush()
            try:
                process = await asyncio.create_subprocess_exec(
                    "tesseract",
                    handle.name,
                    "stdout",
                    "-l",
                    self._languages,
                    "--psm",
                    str(self._psm),
                    "tsv",
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.PIPE,
                )
            except (FileNotFoundError, OSError) as exc:
                raise PermanentIngestionFailure(
                    "OCR provider tesseract is not installed on the worker host"
                ) from exc
            try:
                stdout, stderr = await asyncio.wait_for(
                    process.communicate(), timeout=self._timeout_seconds
                )
            except TimeoutError as exc:
                process.kill()
                await process.communicate()
                raise PermanentIngestionFailure(
                    f"OCR timed out after {self._timeout_seconds:g} seconds for one page"
                ) from exc
        if process.returncode != 0:
            detail = (stderr or b"").decode("utf-8", errors="replace").strip()
            raise PermanentIngestionFailure(
                f"OCR could not recognize the page image: {detail[:200] or process.returncode}"
            )
        return _parse_tesseract_tsv(stdout.decode("utf-8", errors="replace"))


def _parse_tesseract_tsv(tsv: str) -> tuple[str, float | None]:
    """Assemble TSV level-5 word rows into lines with mean confidences."""
    line_words: list[list[str]] = []
    line_marks: list[list[float]] = []
    confidences: list[float] = []
    index_by_key: dict[tuple[int, int, int], int] = {}
    for raw_line in tsv.splitlines()[1:]:
        parts = raw_line.split("\t")
        if len(parts) < 12 or parts[0].strip() != "5":
            continue
        try:
            confidence = float(parts[10])
            key = (int(parts[2]), int(parts[3]), int(parts[4]))
        except ValueError:
            continue
        word = parts[11].strip()
        index = index_by_key.setdefault(key, len(line_words))
        if index == len(line_words):
            line_words.append([])
            line_marks.append([])
        if word:
            line_words[index].append(word)
        if confidence >= 0:
            line_marks[index].append(confidence)
            confidences.append(confidence)
    text = "\n".join(" ".join(words) for words in line_words if words)
    overall = round(sum(confidences) / len(confidences), 2) if confidences else None
    return text, overall


class OllamaVlmOcrProvider:
    """Vision-language OCR backend via Ollama's native /api/chat endpoint."""

    name = "ollama-vlm"

    def __init__(
        self,
        *,
        base_url: str,
        model_id: str,
        timeout_seconds: float,
        max_output_tokens: int,
        prompt: str | None = None,
    ):
        self._base_url = base_url.rstrip("/")
        self._model_id = model_id
        self._timeout_seconds = timeout_seconds
        self._max_output_tokens = max_output_tokens
        self._prompt = prompt or (
            "Extract every character of text from this document image. Preserve "
            "the original reading order, line breaks, tables, and codes. The "
            "document may be Vietnamese or English. Output only the text, with "
            "no commentary, quotes, or markdown fences."
        )

    def fingerprint_tag(self) -> str:
        return f"ollama-vlm:{self._model_id}"

    async def recognize(self, image_bytes: bytes, *, image_format: str) -> OcrRecognition:
        del image_format  # the base64 payload delegates decoding to the model
        started_at = time.perf_counter()
        try:
            text = await self._run(base64.b64encode(image_bytes).decode("ascii"))
        except (PermanentIngestionFailure, TransientIngestionFailure):
            outcome = "error"
            raise
        except Exception:
            outcome = "error"
            raise TransientIngestionFailure("OCR vision model request failed") from None
        else:
            outcome = "success"
        finally:
            AI_INGESTION_OCR_SECONDS.labels(provider=self.name, outcome=outcome).observe(
                time.perf_counter() - started_at
            )
            AI_INGESTION_OCR_PAGES_TOTAL.labels(provider=self.name, outcome=outcome).inc()
        return OcrRecognition(text=text, confidence=None)

    async def _run(self, image_b64: str) -> str:
        payload: dict[str, Any] = {
            "model": self._model_id,
            "messages": [
                {
                    "role": "user",
                    "content": self._prompt,
                    "images": [image_b64],
                },
            ],
            "stream": False,
            "options": {"num_predict": self._max_output_tokens},
        }
        try:
            async with httpx.AsyncClient(timeout=self._timeout_seconds) as client:
                response = await client.post(f"{self._base_url}/api/chat", json=payload)
                response.raise_for_status()
                body = response.json()
        except httpx.HTTPStatusError as exc:
            raise PermanentIngestionFailure(
                f"OCR vision model returned {exc.response.status_code}"
            ) from exc
        except httpx.HTTPError as exc:
            raise TransientIngestionFailure("OCR vision model is unavailable") from exc
        content = str(((body or {}).get("message") or {}).get("content") or "")
        return _strip_code_fence(content)


def _strip_code_fence(text: str) -> str:
    value = text.strip()
    if value.startswith("```"):
        value = value.lstrip("` \n")
        value = value.split("```", 1)[0].strip()
    return value


class OcrIngestionService:
    """Guards, page-parallelism, and metrics on top of an OCR provider."""

    def __init__(
        self,
        provider: OcrProvider,
        *,
        dpi: int,
        min_text_chars: int,
        max_pages: int,
        max_megapixels_per_page: float,
        timeout_seconds_per_page: float,
        page_concurrency: int,
        embedded_images: bool = False,
    ):
        self._provider = provider
        self._dpi = dpi
        self._min_text_chars = min_text_chars
        self._max_pages = max_pages
        self._max_pixels = max_megapixels_per_page * 1_000_000
        self._timeout_seconds = timeout_seconds_per_page
        self._page_concurrency = page_concurrency
        self._embedded_images = embedded_images

    @property
    def provider_name(self) -> str:
        return self._provider.name

    @property
    def min_text_chars(self) -> int:
        return self._min_text_chars

    @property
    def embedded_images(self) -> bool:
        """OCR images embedded inside PDF/DOCX/XLSX documents (best effort)."""
        return self._embedded_images

    def fingerprint(self, raw_sha256: str) -> str:
        return oracle_fingerprint(self._provider.fingerprint_tag(), self._dpi, raw_sha256)

    # -- entry points used by parsers (synchronous contexts) -------------------

    def ocr_pdf_pages(
        self, pdf_bytes: bytes, page_indexes: Sequence[int], *, label: str
    ) -> dict[int, OcrRecognition]:
        """Recognize the given zero-based PDF pages; returns {page_number: ...}."""
        if not page_indexes:
            return {}
        return self._drive(
            lambda: self._ocr_pdf_pages_async(pdf_bytes, page_indexes, label=label)
        )

    def ocr_image(self, image_bytes: bytes, *, content_type: str, label: str) -> OcrRecognition:
        return self._drive(
            lambda: self._ocr_image_async(image_bytes, content_type=content_type, label=label)
        )

    def ocr_embedded_image_best_effort(
        self, image_bytes: bytes, *, content_type: str, label: str
    ) -> OcrRecognition | None:
        """Best-effort OCR for an image embedded in another document.

        Skipped results (below the embedded-text threshold, unusable media) come
        back as ``None``; systematic provider failures still surface through the
        same error mapping as page OCR because the caller logs and continues.
        """
        return self._drive(
            lambda: self._ocr_embedded_async(
                image_bytes, content_type=content_type, label=label
            )
        )

    def _drive(self, factory: Callable[[], Coroutine[Any, Any, _T]]) -> _T:
        try:
            asyncio.get_running_loop()
        except RuntimeError:
            return asyncio.run(factory())
        raise TransientIngestionFailure(
            "OCR must run off the ingestion event loop (call the parser through "
            "asyncio.to_thread)"
        )

    # -- async cores -----------------------------------------------------------

    async def _ocr_pdf_pages_async(
        self, pdf_bytes: bytes, page_indexes: Sequence[int], *, label: str
    ) -> dict[int, OcrRecognition]:
        semaphore = asyncio.Semaphore(self._page_concurrency)

        async def recognize(index: int) -> tuple[int, OcrRecognition]:
            async with semaphore:
                png = await asyncio.to_thread(self._rasterize_pdf_page, pdf_bytes, index)
                return index + 1, await self._run_provider(png)

        gathered = await asyncio.gather(*(recognize(index) for index in page_indexes))
        return self._validated(dict(gathered), label)

    async def _ocr_image_async(
        self, image_bytes: bytes, *, content_type: str, label: str
    ) -> OcrRecognition:
        normalized, image_format = await asyncio.to_thread(
            self._normalize_image, image_bytes, content_type, label
        )
        recognition = await self._run_provider(normalized, image_format)
        return self._validated_single(recognition, label)

    async def _ocr_embedded_async(
        self, image_bytes: bytes, *, content_type: str, label: str
    ) -> OcrRecognition | None:
        try:
            normalized, image_format = await asyncio.to_thread(
                self._normalize_image, image_bytes, content_type, label
            )
        except _UnreadableImageError:
            logger.warning("Skipping unreadable embedded image %s", label)
            return None
        recognition = await self._run_provider(normalized, image_format)
        if len(recognition.text.strip()) < _EMBEDDED_MIN_TEXT_CHARS:
            logger.warning(
                "Skipping embedded image %s: OCR produced fewer than %s characters",
                label,
                _EMBEDDED_MIN_TEXT_CHARS,
            )
            return None
        return recognition

    async def _run_provider(self, png: bytes, image_format: str = "PNG") -> OcrRecognition:
        try:
            return await asyncio.wait_for(
                self._provider.recognize(png, image_format=image_format),
                timeout=self._timeout_seconds,
            )
        except TimeoutError as exc:
            raise PermanentIngestionFailure(
                f"OCR timed out after {self._timeout_seconds:g} seconds for one page"
            ) from exc

    # -- guards ---------------------------------------------------------------

    def _rasterize_pdf_page(self, pdf_bytes: bytes, page_index: int) -> bytes:
        try:
            import pypdfium2 as pdfium
        except ImportError as exc:  # pragma: no cover - dependency ships with the service
            raise PermanentIngestionFailure("PDF rasterizer dependency is not installed") from exc
        try:
            document = pdfium.PdfDocument(pdf_bytes)
        except Exception as exc:
            raise PermanentIngestionFailure("PDF document could not be opened for OCR") from exc
        page = None
        try:
            try:
                page = document[page_index]
                width, height = page.get_size()
                scale = self._dpi / 72.0
                pixels = (width * scale) * (height * scale)
                if pixels > self._max_pixels:
                    scale *= (self._max_pixels / pixels) ** 0.5
                bitmap = page.render(scale=scale)
                frame = bitmap.to_pil()
            finally:
                if page is not None:
                    page.close()
                document.close()
        except PermanentIngestionFailure:
            raise
        except Exception as exc:
            raise PermanentIngestionFailure(
                f"PDF page {page_index + 1} could not be rendered for OCR"
            ) from exc
        buffer = BytesIO()
        frame.save(buffer, format="PNG")
        return buffer.getvalue()

    def _normalize_image(
        self, image_bytes: bytes, content_type: str, label: str
    ) -> tuple[bytes, str]:
        try:
            from PIL import Image
        except ImportError as exc:  # pragma: no cover - dependency ships with the service
            raise PermanentIngestionFailure("Image decoder dependency is not installed") from exc
        try:
            with Image.open(BytesIO(image_bytes)) as frame:
                width = max(int(frame.size[0]), 1)
                height = max(int(frame.size[1]), 1)
                declared = str(frame.format or "").upper()
            image_format = _MIME_FORMATS.get(
                content_type.lower().split(";", 1)[0].strip(), declared
            )
        except Exception as exc:
            raise _UnreadableImageError(
                f"Image document could not be decoded for OCR: {label}"
            ) from exc
        pixels = width * height
        if pixels <= self._max_pixels and image_format.upper() in _TESSERACT_INPUT_FORMATS:
            return image_bytes, image_format.upper()
        # Adaptive downscale (mirrors the PDF DPI guard) keeps the memory cap.
        try:
            with Image.open(BytesIO(image_bytes)) as frame:
                ratio = min(1.0, (self._max_pixels / pixels) ** 0.5)
                resized = frame.convert("RGB").resize(
                    (max(1, int(width * ratio)), max(1, int(height * ratio)))
                )
                buffer = BytesIO()
                resized.save(buffer, format="PNG")
                return buffer.getvalue(), "PNG"
        except Exception as exc:
            raise PermanentIngestionFailure(
                f"Image document could not be bounded for OCR: {label}"
            ) from exc

    def check_page_budget(self, page_count: int, *, label: str) -> None:
        if page_count > self._max_pages:
            raise PermanentIngestionFailure(
                f"Document {label} has {page_count} pages; OCR is limited to {self._max_pages}"
            )

    def _validated(self, pages: dict[int, OcrRecognition], label: str) -> dict[int, OcrRecognition]:
        for page_number, recognition in pages.items():
            if not recognition.text.strip():
                raise PermanentIngestionFailure(
                    f"OCR returned no text for {label}, page {page_number}"
                )
        return pages

    def _validated_single(self, recognition: OcrRecognition, label: str) -> OcrRecognition:
        if not recognition.text.strip():
            raise PermanentIngestionFailure(f"OCR returned no text for {label}")
        return recognition

