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

"""OCR ingestion: provider adapters, parser fallbacks, guards, and caching."""

from __future__ import annotations

import asyncio
import base64
import hashlib
from dataclasses import replace
from datetime import UTC, datetime
from io import BytesIO
from typing import Any

import pytest

from app.common.errors import StorageNotFoundError
from app.common.storage_keys import (
    document_media_key,
    document_media_prefix,
    ocr_artifact_key,
    ocr_artifact_prefix,
    spreadsheet_table_prefix,
)
from app.modules.graph.api import GraphBatch
from app.modules.index.api import ReplaceDocumentIndex
from app.modules.ingestion.api import (
    IngestDocumentCommand,
    PermanentIngestionFailure,
    TransientIngestionFailure,
)
from app.modules.ingestion.internal.chunking import DeterministicChunker
from app.modules.ingestion.internal.entity_extraction import graph_units
from app.modules.ingestion.internal.extraction import DocumentTextExtractor
from app.modules.ingestion.internal.ocr import (
    OcrIngestionService,
    OcrRecognition,
    _parse_tesseract_tsv,
    oracle_fingerprint,
)
from app.modules.ingestion.internal.ocr_artifacts import load_ocr_artifact, save_ocr_artifact
from app.modules.ingestion.internal.pipeline import (
    DocumentIndexLifecycleService,
    DocumentIngestionPipeline,
)

pytest.importorskip("pypdf")
pytest.importorskip("pypdfium2")
pytest.importorskip("PIL")

TENANT_ID = "00000000-0000-0000-0000-000000000002"
KNOWLEDGE_BASE_ID = "00000000-0000-0000-0000-000000000004"
DOCUMENT_ID = "37f34742-0be6-4f82-9839-39303fabe2d7"
DOCUMENT_KEY = (
    f"tenants/{TENANT_ID}/knowledge-bases/{KNOWLEDGE_BASE_ID}/documents/{DOCUMENT_ID}/scanned.pdf"
)
OCR_PREFIX = ocr_artifact_prefix(TENANT_ID, KNOWLEDGE_BASE_ID, DOCUMENT_ID)

# 1x1 transparent PNG.
TINY_PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAAABJRU5ErkJggg=="
)

# A native sentence long enough to pass the 32-character text-layer gate.
NATIVE_SENTENCE = (
    "Hello native text layer: this paragraph is longer than the OCR gate of 32 visible characters"
)


def minimal_pdf_pages(texts: list[str | None]) -> bytes:
    """Multi-page PDF fixture; None means a text-free (scanned-style) page."""
    kids: list[str] = []
    page_objects: list[bytes] = []
    next_id = 3
    for text in texts:
        page_id = next_id
        next_id += 1
        kids.append(f"{page_id} 0 R")
        if text is None:
            page_objects.append(b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>")
            continue
        content_id = next_id
        next_id += 1
        font_id = next_id
        next_id += 1
        stream = f"BT /F1 24 Tf 72 720 Td ({text}) Tj ET".encode("ascii")
        page_objects.append(
            f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
            f"/Resources << /Font << /F1 {font_id} 0 R >> >> "
            f"/Contents {content_id} 0 R >>".encode()
        )
        page_objects.append(f"<< /Length {len(stream)} >>\nstream\n{stream}\nendstream".encode())
        page_objects.append(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    objects: list[bytes] = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        (
            b"<< /Type /Pages /Kids ["
            + b" ".join(kid.encode() for kid in kids)
            + f"] /Count {len(texts)} >>".encode()
        ),
        *page_objects,
    ]
    pdf = b"%PDF-1.4\n"
    offsets: list[int] = []
    for index, obj in enumerate(objects, start=1):
        offsets.append(len(pdf))
        pdf += f"{index} 0 obj\n".encode("ascii") + obj + b"\nendobj\n"
    xref = len(pdf)
    pdf += f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode("ascii")
    for offset in offsets:
        pdf += f"{offset:010d} 00000 n \n".encode("ascii")
    pdf += (
        f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n"
    ).encode("ascii")
    return pdf


def _jpeg_fixture(width: int, height: int) -> bytes:
    """A minimal JPEG rendered with PIL; FakeProvider does not read the pixels."""
    from PIL import Image

    buffer = BytesIO()
    Image.new("RGB", (width, height), "white").save(buffer, format="JPEG", quality=90)
    return buffer.getvalue()


def pdf_with_text_and_image(image_bytes: bytes, width: int, height: int) -> bytes:
    """Single-page PDF containing a native text line and a JPEG image XObject."""
    content = (
        f"q {width} 0 0 {height} 72 600 cm /Im0 Do Q".encode("ascii")
        + b"\n"
        + f"BT /F1 18 Tf 72 100 Td ({NATIVE_SENTENCE}) Tj ET".encode("ascii")
    )
    image_object = (
        b"<< /Type /XObject /Subtype /Image /Width %d /Height %d "
        b"/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length %d >>\nstream\n"
        % (width, height, len(image_bytes))
        + image_bytes
        + b"\nendstream"
    )
    objects: list[bytes] = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [5 0 R] /Count 1 >>",
        image_object,  # 3
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",  # 4
        (
            b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
            b"/Resources << /XObject << /Im0 3 0 R >> "
            b"/Font << /F1 4 0 R >> >> /Contents 6 0 R >>"
        ),  # 5
        b"<< /Length %d >>\nstream\n%s\nendstream" % (len(content), content),  # 6
    ]
    pdf = b"%PDF-1.4\n"
    offsets: list[int] = []
    for index, obj in enumerate(objects, start=1):
        offsets.append(len(pdf))
        pdf += f"{index} 0 obj\n".encode("ascii") + obj + b"\nendobj\n"
    xref = len(pdf)
    pdf += f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode("ascii")
    for offset in offsets:
        pdf += f"{offset:010d} 00000 n \n".encode("ascii")
    pdf += (
        f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n"
    ).encode("ascii")
    return pdf


def docx_with_image(*images: bytes) -> bytes:
    """Word file embedding one picture per passed image (distinct bytes to avoid dedupe)."""
    python_docx = pytest.importorskip("docx")
    document = python_docx.Document()
    document.add_paragraph(NATIVE_SENTENCE)
    for image in images:
        document.add_picture(BytesIO(image))
    document.add_paragraph("Closing paragraph after the embedded picture")
    buffer = BytesIO()
    document.save(buffer)
    return buffer.getvalue()


def xlsx_with_image(*images: bytes) -> bytes:
    openpyxl = pytest.importorskip("openpyxl")
    drawing_image = pytest.importorskip("openpyxl.drawing.image")
    buffer = BytesIO()
    workbook = openpyxl.Workbook()
    sheet = workbook.active
    sheet["A1"] = 1
    sheet["A2"] = 2
    for index, image in enumerate(images, start=1):
        sheet.add_image(drawing_image.Image(BytesIO(image)), f"C{index * 20}")
    workbook.save(buffer)
    return buffer.getvalue()


class FakeProvider:
    """Fixed-recognition OCR provider that records the images it received."""

    name = "tesseract-fake"

    def __init__(self, text: str = "Văn bản tiếng Việt nhận dạng", confidence: float | None = 91.5):
        self.text = text
        self.confidence = confidence
        self.calls: list[bytes] = []

    def fingerprint_tag(self) -> str:
        return "fake:1"

    async def recognize(self, image_bytes: bytes, *, image_format: str) -> OcrRecognition:
        self.calls.append(image_bytes)
        return OcrRecognition(text=self.text, confidence=self.confidence)


class FailingProvider(FakeProvider):
    async def recognize(self, image_bytes: bytes, *, image_format: str) -> OcrRecognition:
        raise PermanentIngestionFailure("provider must not be called")


class FlakyProvider(FakeProvider):
    """Fails on configured call ordinals, records every attempt, then succeeds."""

    def __init__(self, fail_at: set[int], error: Exception | None = None, **kwargs: Any):
        super().__init__(**kwargs)
        self.fail_at = fail_at
        self.error = error or PermanentIngestionFailure("tesseract is not installed")

    async def recognize(self, image_bytes: bytes, *, image_format: str) -> OcrRecognition:
        if len(self.calls) in self.fail_at:
            self.calls.append(image_bytes)
            raise self.error
        return await super().recognize(image_bytes, image_format=image_format)


class SlowProvider(FakeProvider):
    async def recognize(self, image_bytes: bytes, *, image_format: str) -> OcrRecognition:
        del image_bytes, image_format
        await asyncio.sleep(5)
        raise AssertionError("never reached")


class NeverIndex:
    async def replace_document(self, command: ReplaceDocumentIndex) -> None:
        del command

    async def delete_document(self, tenant_id: str, document_id: str) -> None:
        del tenant_id, document_id


class StubGraph:
    async def replace_source(self, batch: GraphBatch) -> None:
        del batch

    async def delete_source(self, tenant_id: str, document_id: str) -> None:
        del tenant_id, document_id


class NeverExtracted:
    async def extract(self, command: object, chunks: object) -> GraphBatch:
        del command, chunks
        return GraphBatch(
            tenant_id=TENANT_ID,
            knowledge_base_id=KNOWLEDGE_BASE_ID,
            source_id=DOCUMENT_ID,
            source_name="scanned.pdf",
            units=graph_units(DOCUMENT_ID, "scanned.pdf", []),
        )


class MemoryObjectStore:
    def __init__(self) -> None:
        self.objects: dict[str, bytes] = {DOCUMENT_KEY: minimal_pdf_pages([None])}
        self.deleted_prefixes: list[str] = []

    async def download(self, storage_key: str) -> bytes:
        try:
            return self.objects[storage_key]
        except KeyError as exc:
            raise StorageNotFoundError(f"missing object: {storage_key}") from exc

    async def upload(self, storage_key: str, data: bytes, *, content_type: str) -> None:
        self.objects[storage_key] = data

    async def delete_prefix(self, storage_prefix: str) -> None:
        self.deleted_prefixes.append(storage_prefix)
        for key in tuple(self.objects):
            if key.startswith(storage_prefix):
                del self.objects[key]


def command() -> IngestDocumentCommand:
    return IngestDocumentCommand(
        schema_version="1.0",
        event_id="event-1",
        job_id="job-1",
        tenant_id=TENANT_ID,
        knowledge_base_id=KNOWLEDGE_BASE_ID,
        document_id=DOCUMENT_ID,
        uploader_id="user-1",
        storage_key=DOCUMENT_KEY,
        file_name="scanned.pdf",
        content_type="application/pdf",
        file_size_bytes=1,
        occurred_at=datetime.now(UTC),
    )


def service(provider: Any | None = None, **overrides: Any) -> OcrIngestionService:
    defaults: dict[str, Any] = {
        "dpi": 300,
        "min_text_chars": 32,
        "max_pages": 300,
        "max_megapixels_per_page": 25.0,
        "timeout_seconds_per_page": 60.0,
        "page_concurrency": 2,
    }
    defaults.update(overrides)
    return OcrIngestionService(provider or FakeProvider(), **defaults)


def prepare_pipeline(
    store: MemoryObjectStore, ocr_service: OcrIngestionService
) -> DocumentIngestionPipeline:
    return DocumentIngestionPipeline(
        store=store,
        extractor=DocumentTextExtractor(ocr=ocr_service),
        chunker=DeterministicChunker(),
        embedder=object(),
        sparse_encoder=object(),
        vector_store=NeverIndex(),  # type: ignore[arg-type]
        graph_store=StubGraph(),  # type: ignore[arg-type]
        graph_extractor=NeverExtracted(),  # type: ignore[arg-type]
        ocr=ocr_service,
    )


# --------------------------------------------------------------------------
# Pure helpers and fingerprints
# --------------------------------------------------------------------------


def test_oracle_fingerprint_is_stable_and_setting_sensitive() -> None:
    raw = "0" * 64
    first = oracle_fingerprint("tesseract:vie+eng:3", 300, raw)
    assert first == oracle_fingerprint("tesseract:vie+eng:3", 300, raw)
    assert first != oracle_fingerprint("tesseract:vie+eng:3", 200, raw)
    assert first != oracle_fingerprint("fake:1", 300, raw)
    assert len(first) == 32


def test_tesseract_tsv_parse_groups_lines_and_scores_confidence() -> None:
    tsv = "\n".join(
        [
            "level\tpage\tblock\tpar\tline\tword\tleft\ttop\twidth\theight\tconf\ttext",
            "5\t1\t1\t1\t1\t1\t0\t0\t10\t10\t95\tXin",
            "5\t1\t1\t1\t1\t2\t12\t0\t10\t10\t93\tchào",
            "5\t1\t1\t1\t2\t1\t0\t20\t10\t10\t80\tthế",
            "5\t1\t2\t1\t3\t1\t0\t40\t10\t10\t-1\ttiếng",
        ]
    )

    text, confidence = _parse_tesseract_tsv(tsv)

    assert text == "Xin chào\nthế\ntiếng"
    assert confidence == round((95 + 93 + 80) / 3, 2)


def test_ocr_artifact_round_trip_and_fingerprint_mismatch() -> None:
    document = DocumentTextExtractor(ocr=service()).parse(
        minimal_pdf_pages([None]),
        content_type="application/pdf",
        file_name="scanned.pdf",
    )

    async def scenario() -> None:
        fingerprint = oracle_fingerprint("fake:1", 300, "0" * 64)
        store = MemoryObjectStore()
        await save_ocr_artifact(
            store,
            tenant_id=TENANT_ID,
            knowledge_base_id=KNOWLEDGE_BASE_ID,
            document_id=DOCUMENT_ID,
            fingerprint=fingerprint,
            parsed=document,
        )
        restored = await load_ocr_artifact(
            store,
            tenant_id=TENANT_ID,
            knowledge_base_id=KNOWLEDGE_BASE_ID,
            document_id=DOCUMENT_ID,
            fingerprint=fingerprint,
        )
        assert restored == document
        mismatched = await load_ocr_artifact(
            store,
            tenant_id=TENANT_ID,
            knowledge_base_id=KNOWLEDGE_BASE_ID,
            document_id=DOCUMENT_ID,
            fingerprint="different",
        )
        assert mismatched is None

    asyncio.run(scenario())


def test_ocr_artifact_missing_or_corrupt_falls_back_to_none() -> None:
    async def scenario() -> None:
        store = MemoryObjectStore()
        store.objects[f"{OCR_PREFIX}some.json"] = b"not json"
        missing = await load_ocr_artifact(
            store,
            tenant_id=TENANT_ID,
            knowledge_base_id=KNOWLEDGE_BASE_ID,
            document_id=DOCUMENT_ID,
            fingerprint="anything",
        )
        assert missing is None
        corrupt = await load_ocr_artifact(
            store,
            tenant_id=TENANT_ID,
            knowledge_base_id=KNOWLEDGE_BASE_ID,
            document_id=DOCUMENT_ID,
            fingerprint="some",
        )
        assert corrupt is None

    asyncio.run(scenario())


# --------------------------------------------------------------------------
# Parser behavior
# --------------------------------------------------------------------------


def test_scanned_pdf_falls_back_to_ocr_and_labels_sources() -> None:
    parsed = DocumentTextExtractor(ocr=service()).parse(
        minimal_pdf_pages([None]),
        content_type="application/pdf",
        file_name="scanned.pdf",
    )

    assert parsed.text_source == "ocr"
    assert parsed.page_count == 1
    assert all(block.parser_version == "ocr-v1" for block in parsed.blocks)
    assert all(block.page_number == 1 for block in parsed.blocks)
    assert "Văn bản tiếng Việt" in parsed.blocks[0].text


def test_mixed_pdf_uses_native_and_ocr_pages() -> None:
    provider = FakeProvider()

    parsed = DocumentTextExtractor(ocr=service(provider)).parse(
        minimal_pdf_pages([NATIVE_SENTENCE, None]),
        content_type="application/pdf",
        file_name="mixed.pdf",
    )

    assert parsed.text_source == "mixed"
    assert parsed.page_count == 2
    native = [block for block in parsed.blocks if block.parser_version == "digital-v1"]
    recognized = [block for block in parsed.blocks if block.parser_version == "ocr-v1"]
    assert [block.page_number for block in native] == [1]
    assert [block.page_number for block in recognized] == [2]
    assert len(provider.calls) == 1


def test_native_pdf_with_text_layer_never_calls_ocr() -> None:
    provider = FakeProvider()

    parsed = DocumentTextExtractor(ocr=service(provider)).parse(
        minimal_pdf_pages([NATIVE_SENTENCE]),
        content_type="application/pdf",
        file_name="digital.pdf",
    )

    assert provider.calls == []
    assert parsed.text_source == "text"
    assert all(block.parser_version == "digital-v1" for block in parsed.blocks)


def test_ocr_disabled_scanned_pdf_keeps_legacy_error() -> None:
    with pytest.raises(PermanentIngestionFailure, match="scanned PDFs are not supported"):
        DocumentTextExtractor().parse(
            minimal_pdf_pages([None]),
            content_type="application/pdf",
            file_name="scanned.pdf",
        )


def test_ocr_page_budget_is_enforced() -> None:
    with pytest.raises(PermanentIngestionFailure, match="OCR is limited to 1"):
        DocumentTextExtractor(ocr=service(max_pages=1)).parse(
            minimal_pdf_pages([None, None]),
            content_type="application/pdf",
            file_name="many.pdf",
        )


def test_ocr_page_timeout_maps_to_permanent_failure() -> None:
    ocr_service = service(SlowProvider(), timeout_seconds_per_page=0.05)

    with pytest.raises(PermanentIngestionFailure, match="OCR timed out"):
        DocumentTextExtractor(ocr=ocr_service).parse(
            minimal_pdf_pages([None]),
            content_type="application/pdf",
            file_name="slow.pdf",
        )


def test_ocr_refuses_to_run_on_the_ingestion_event_loop() -> None:
    async def scenario() -> None:
        with pytest.raises(TransientIngestionFailure, match="off the ingestion event loop"):
            DocumentTextExtractor(ocr=service()).parse(
                minimal_pdf_pages([None]),
                content_type="application/pdf",
                file_name="scanned.pdf",
            )

    asyncio.run(scenario())


def test_image_parser_recognizes_and_labels_one_page() -> None:
    parsed = DocumentTextExtractor(ocr=service()).parse(
        TINY_PNG,
        content_type="image/png",
        file_name="scan.png",
    )

    assert parsed.text_source == "ocr"
    assert parsed.page_count == 1
    assert all(block.parser_version == "ocr-v1" for block in parsed.blocks)
    assert all(block.page_number == 1 for block in parsed.blocks)


def test_image_parser_rejects_extension_content_type_mismatch() -> None:
    with pytest.raises(PermanentIngestionFailure, match="do not match"):
        DocumentTextExtractor(ocr=service()).parse(
            TINY_PNG,
            content_type="image/png",
            file_name="scan.txt",
        )


def test_default_registry_still_rejects_images_without_ocr() -> None:
    with pytest.raises(PermanentIngestionFailure, match="Unsupported document content type"):
        DocumentTextExtractor().parse(
            TINY_PNG,
            content_type="image/png",
            file_name="scan.png",
        )


def test_image_normalization_respects_megapixel_cap() -> None:
    buffer = BytesIO()
    from PIL import Image

    Image.new("RGB", (1000, 1000), "white").save(buffer, format="PNG")
    provider = FakeProvider()

    ocr_service = service(provider, max_megapixels_per_page=0.2)
    recognition = ocr_service.ocr_image(
        buffer.getvalue(), content_type="image/png", label="big.png"
    )

    assert recognition.text == provider.text
    with Image.open(BytesIO(provider.calls[0])) as downgraded:
        assert downgraded.size[0] * downgraded.size[1] <= int(0.2 * 1_000_000)


# --------------------------------------------------------------------------
# Pipeline artifact cache
# --------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_pipeline_caches_ocr_artifact_and_reuses_it() -> None:
    store = MemoryObjectStore()
    provider = FakeProvider()
    ocr_service = service(provider)
    first = prepare_pipeline(store, ocr_service)

    prepared = await first.prepare(command())

    assert prepared.parsed.text_source == "ocr"
    assert provider.calls, "the first parse must run OCR"
    saved_key = ocr_artifact_key(
        TENANT_ID, KNOWLEDGE_BASE_ID, DOCUMENT_ID, ocr_service.fingerprint(
            hashlib.sha256(minimal_pdf_pages([None])).hexdigest()
        )
    )
    assert saved_key in store.objects

    second_provider = FailingProvider()
    second_ocr = service(second_provider)
    replayed = await prepare_pipeline(store, second_ocr).prepare(command())

    assert second_provider.calls == [], "a fingerprint hit must skip recognition"
    assert replayed.parsed == prepared.parsed
    assert replayed.chunks == prepared.chunks


@pytest.mark.asyncio
async def test_prepare_resolves_media_object_keys_and_persists_assets() -> None:
    store = MemoryObjectStore()
    jpeg = _jpeg_fixture(240, 120)
    store.objects[DOCUMENT_KEY] = pdf_with_text_and_image(jpeg, 240, 120)

    prepared = await prepare_pipeline(
        store, service(FakeProvider(), embedded_images=True)
    ).prepare(command())

    keys = {
        document_media_key(TENANT_ID, KNOWLEDGE_BASE_ID, DOCUMENT_ID, asset.name)
        for asset in prepared.parsed.media
    }
    assert keys, "the OCR'd image becomes a citable asset"
    assert keys <= set(store.objects), "asset bytes persist with the OCR artifact"
    image_chunks = [chunk for chunk in prepared.chunks if chunk.image_object_key]
    assert image_chunks, "embedded-image blocks carry their object key"
    for chunk in image_chunks:
        assert chunk.image_object_key in keys
        assert chunk.image_media_type == "image/jpeg"
        assert chunk.image_width == 240
        assert chunk.image_height == 120

    # Replay through the OCR cache: descriptors arrive without bytes, and the
    # chunks must resolve to exactly the same object keys.
    replayed = await prepare_pipeline(
        store, service(FailingProvider(), embedded_images=True)
    ).prepare(command())
    assert replayed.chunks == prepared.chunks


@pytest.mark.asyncio
async def test_pipeline_skips_artifact_for_native_text_documents() -> None:
    store = MemoryObjectStore()
    store.objects[DOCUMENT_KEY] = minimal_pdf_pages([NATIVE_SENTENCE])
    provider = FakeProvider()

    prepared = await prepare_pipeline(store, service(provider)).prepare(command())

    assert provider.calls == []
    assert prepared.parsed.text_source == "text"
    assert not any(key.startswith(OCR_PREFIX) for key in store.objects)


@pytest.mark.asyncio
async def test_pipeline_ignores_corrupt_ocr_artifact() -> None:
    store = MemoryObjectStore()
    fingerprint = service().fingerprint(hashlib.sha256(minimal_pdf_pages([None])).hexdigest())
    corrupt_line = b"{corrupt"
    corrupt_key = ocr_artifact_key(TENANT_ID, KNOWLEDGE_BASE_ID, DOCUMENT_ID, fingerprint)
    store.objects[corrupt_key] = corrupt_line
    provider = FakeProvider()

    prepared = await prepare_pipeline(store, service(provider)).prepare(command())

    assert provider.calls, "corrupt cache entries fall back to a full OCR parse"
    assert prepared.parsed.text_source == "ocr"


@pytest.mark.asyncio
async def test_pipeline_cleanup_deletes_ocr_artifacts() -> None:
    store = MemoryObjectStore()
    pipeline = prepare_pipeline(store, service())

    await pipeline.prepare(command())
    assert any(key.startswith(OCR_PREFIX) for key in store.objects)

    await pipeline.cleanup(command())

    assert store.deleted_prefixes == [
        spreadsheet_table_prefix(TENANT_ID, KNOWLEDGE_BASE_ID, DOCUMENT_ID),
        document_media_prefix(TENANT_ID, KNOWLEDGE_BASE_ID, DOCUMENT_ID),
        OCR_PREFIX,
    ]


@pytest.mark.asyncio
async def test_lifecycle_delete_document_deletes_ocr_artifacts() -> None:
    store = MemoryObjectStore()
    lifecycle = DocumentIndexLifecycleService(
        NeverIndex(),  # type: ignore[arg-type]
        StubGraph(),  # type: ignore[arg-type]
        store,
        ocr=service(),
    )

    await lifecycle.delete_document(TENANT_ID, KNOWLEDGE_BASE_ID, DOCUMENT_ID)

    assert OCR_PREFIX in store.deleted_prefixes


# --------------------------------------------------------------------------
# Embedded-image OCR (PDF mixed pages, DOCX, XLSX) — off by default
# --------------------------------------------------------------------------


def _document_content_type(name: str) -> str:
    if name.endswith(".pdf"):
        return "application/pdf"
    if name.endswith(".docx"):
        return "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"


def _parse_with_ocr(data: bytes, name: str, provider: Any, **overrides: Any) -> Any:
    return DocumentTextExtractor(ocr=service(provider, **overrides)).parse(
        data,
        content_type=_document_content_type(name),
        file_name=name,
    )


def test_embedded_image_text_is_ocrd_when_enabled() -> None:
    provider = FakeProvider()
    jpeg = _jpeg_fixture(240, 120)
    data = pdf_with_text_and_image(jpeg, 240, 120)

    parsed = _parse_with_ocr(data, "annotated.pdf", provider, embedded_images=True)

    assert parsed.text_source == "mixed"
    native = [block for block in parsed.blocks if block.parser_version == "digital-v1"]
    embedded = [
        block
        for block in parsed.blocks
        if block.parser_version == "ocr-v1" and block.page_number == 1
    ]
    assert native and embedded
    assert embedded[0].metadata == {
        "ocr_source": "embedded-image",
        "image_media_type": "image/jpeg",
        "image_width": 240,
        "image_height": 120,
        "image_name": embedded[0].metadata["image_name"],
    }
    assert len(parsed.media) == 1, "the OCR'd image is a citable asset"
    assert parsed.media[0].blob, "the parse path carries the asset bytes"
    assert provider.calls, "the embedded JPEG must reach the OCR provider"
    assert len(provider.calls) == 1, "the page itself must not be rasterized again"


def test_embedded_image_ocr_stays_off_by_default() -> None:
    provider = FakeProvider()
    data = pdf_with_text_and_image(_jpeg_fixture(240, 120), 240, 120)

    parsed = _parse_with_ocr(data, "annotated.pdf", provider)

    assert provider.calls == []
    assert parsed.text_source == "text"
    assert all(block.parser_version == "digital-v1" for block in parsed.blocks)


def test_tiny_embedded_images_are_skipped() -> None:
    provider = FakeProvider()
    data = pdf_with_text_and_image(_jpeg_fixture(16, 16), 16, 16)

    parsed = _parse_with_ocr(data, "annotated.pdf", provider, embedded_images=True)

    assert provider.calls == [], "icons below 32px must not reach the provider"
    assert parsed.text_source == "text"


def test_embedded_image_short_ocr_text_is_dropped() -> None:
    provider = FakeProvider(text="logo")
    data = pdf_with_text_and_image(_jpeg_fixture(240, 120), 240, 120)

    parsed = _parse_with_ocr(data, "annotated.pdf", provider, embedded_images=True)

    assert provider.calls, "the image is OCR'd before the char filter applies"
    assert parsed.text_source == "text"
    assert all(block.parser_version == "digital-v1" for block in parsed.blocks)


def test_docx_embedded_images_are_ocrd_when_enabled() -> None:
    provider = FakeProvider()
    data = docx_with_image(_jpeg_fixture(240, 120))

    parsed = _parse_with_ocr(data, "report.docx", provider, embedded_images=True)

    assert parsed.text_source == "mixed"
    native = [block for block in parsed.blocks if block.parser_version != "ocr-v1"]
    embedded = [block for block in parsed.blocks if block.parser_version == "ocr-v1"]
    assert native and embedded
    assert embedded[0].metadata == {
        "ocr_source": "embedded-image",
        "ocr_container": "docx",
        "image_media_type": "image/jpeg",
        "image_width": 240,
        "image_height": 120,
        "image_name": embedded[0].metadata["image_name"],
    }
    assert embedded[0].page_number == 1, "a Word file without breaks starts on page 1"
    assert len({block.unit_id for block in parsed.blocks}) == len(parsed.blocks)
    assert provider.calls


def test_docx_media_blocks_carry_page_and_section_provenance() -> None:
    python_docx = pytest.importorskip("docx")
    document = python_docx.Document()
    document.add_paragraph("Trang đầu tiên chỉ có text nội dung dài một chút")
    document.add_page_break()
    document.add_heading("Phụ lục ảnh", level=1)
    document.add_picture(BytesIO(_jpeg_fixture(240, 120)))
    document.add_paragraph("Đoạn sau ảnh nằm cùng trang 2")
    buffer = BytesIO()
    document.save(buffer)
    provider = FakeProvider()

    parsed = _parse_with_ocr(
        buffer.getvalue(), "annex.docx", provider, embedded_images=True
    )

    embedded = [block for block in parsed.blocks if block.parser_version == "ocr-v1"]
    assert len(embedded) == 1
    assert embedded[0].page_number == 2, "one page-break marker puts the image on page 2"
    assert embedded[0].section_path == ("Phụ lục ảnh",)
    assert embedded[0].heading_context == "Phụ lục ảnh"
    assert parsed.text_source == "mixed"


def test_docx_embedded_ocr_stays_off_by_default() -> None:
    provider = FakeProvider()
    data = docx_with_image(_jpeg_fixture(240, 120))

    parsed = _parse_with_ocr(data, "report.docx", provider)

    assert provider.calls == []
    assert parsed.text_source == "text"
    assert all(block.parser_version != "ocr-v1" for block in parsed.blocks)


def test_docx_with_only_embedded_images_ingests_through_ocr() -> None:
    python_docx = pytest.importorskip("docx")
    document = python_docx.Document()
    document.add_picture(BytesIO(_jpeg_fixture(240, 120)))
    buffer = BytesIO()
    document.save(buffer)
    provider = FakeProvider()

    parsed = _parse_with_ocr(buffer.getvalue(), "picture-only.docx", provider, embedded_images=True)

    assert parsed.text_source == "ocr"
    assert all(block.parser_version == "ocr-v1" for block in parsed.blocks)
    assert provider.calls, "an image-only DOCX must not be rejected as textless"


def test_xlsx_embedded_images_are_ocrd_when_enabled() -> None:
    provider = FakeProvider()
    data = xlsx_with_image(_jpeg_fixture(240, 120))

    parsed = _parse_with_ocr(data, "book.xlsx", provider, embedded_images=True)

    assert parsed.modality == "spreadsheet"
    assert parsed.text_source == "mixed"
    rows = [block for block in parsed.blocks if block.block_type in ("table", "row")]
    embedded = [block for block in parsed.blocks if block.parser_version == "ocr-v1"]
    assert rows and embedded
    assert embedded[-1].metadata == {
        "ocr_source": "embedded-image",
        "ocr_container": "xlsx",
        "image_media_type": "image/jpeg",
        "image_width": 240,
        "image_height": 120,
        "image_name": embedded[-1].metadata["image_name"],
    }
    assert embedded[-1].sheet_name == "Sheet", "images are attributed to their sheet"
    assert len({block.unit_id for block in parsed.blocks}) == len(parsed.blocks)
    assert provider.calls


def test_xlsx_embedded_ocr_stays_off_by_default() -> None:
    provider = FakeProvider()
    data = xlsx_with_image(_jpeg_fixture(240, 120))

    parsed = _parse_with_ocr(data, "book.xlsx", provider)

    assert provider.calls == []
    assert parsed.text_source == "text"
    assert all(block.parser_version != "ocr-v1" for block in parsed.blocks)


def docx_command() -> IngestDocumentCommand:
    return replace(
        command(),
        file_name="report.docx",
        content_type="application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    )


@pytest.mark.asyncio
async def test_pipeline_caches_embedded_ocr_artifact() -> None:
    store = MemoryObjectStore()
    data = docx_with_image(_jpeg_fixture(240, 120))
    store.objects[DOCUMENT_KEY] = data
    provider = FakeProvider()
    ocr_srv = service(provider, embedded_images=True)

    prepared = await prepare_pipeline(store, ocr_srv).prepare(docx_command())

    assert prepared.parsed.text_source == "mixed"
    assert provider.calls
    fingerprint = ocr_srv.fingerprint(hashlib.sha256(data).hexdigest())
    assert (
        ocr_artifact_key(TENANT_ID, KNOWLEDGE_BASE_ID, DOCUMENT_ID, fingerprint)
        in store.objects
    )

    replayed = await prepare_pipeline(
        store, service(FailingProvider(), embedded_images=True)
    ).prepare(docx_command())

    assert replayed.parsed == prepared.parsed
    assert replayed.chunks == prepared.chunks


def test_embedded_ocr_skips_undecodable_media_as_none() -> None:
    provider = FakeProvider()
    embedded = service(provider, embedded_images=True)

    assert (
        embedded.ocr_embedded_image_best_effort(
            b"not-an-image", content_type="", label="blob.bin"
        )
        is None
    )
    assert provider.calls == [], "undecodable media is a data skip, not a provider call"


def test_single_embedded_provider_error_is_tolerated() -> None:
    provider = FailingProvider()
    data = pdf_with_text_and_image(_jpeg_fixture(240, 120), 240, 120)

    parsed = _parse_with_ocr(data, "annotated.pdf", provider, embedded_images=True)

    assert parsed.text_source == "text", "one failing image must not fail the document"
    assert all(block.parser_version == "digital-v1" for block in parsed.blocks)


def test_docx_embedded_provider_failure_breaches_streak() -> None:
    provider = FlakyProvider(set(range(10)))
    data = docx_with_image(
        _jpeg_fixture(240, 120), _jpeg_fixture(240, 121), _jpeg_fixture(240, 122)
    )

    with pytest.raises(PermanentIngestionFailure, match="failed 3 provider calls in a row"):
        _parse_with_ocr(data, "report.docx", provider, embedded_images=True)
    assert len(provider.calls) == 3, "the streak aborts at the third consecutive error"


def test_embedded_streak_failure_keeps_transient_kind() -> None:
    provider = FlakyProvider(
        set(range(10)), error=TransientIngestionFailure("ollama is down")
    )
    data = xlsx_with_image(
        _jpeg_fixture(240, 120), _jpeg_fixture(240, 121), _jpeg_fixture(240, 122)
    )

    with pytest.raises(TransientIngestionFailure, match="in a row"):
        _parse_with_ocr(data, "book.xlsx", provider, embedded_images=True)


def test_embedded_streak_resets_after_success() -> None:
    provider = FlakyProvider({0})
    data = docx_with_image(_jpeg_fixture(240, 120), _jpeg_fixture(240, 121))

    parsed = _parse_with_ocr(data, "report.docx", provider, embedded_images=True)

    assert parsed.text_source == "mixed", "successes must reset the error streak"
    assert len(provider.calls) == 2
    assert sum(1 for block in parsed.blocks if block.parser_version == "ocr-v1") == 1
