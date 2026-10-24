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

"""Optional speech transcription (ASR) for audio and video documents.

Design notes (mirrors ``ocr.py``):
- ``faster-whisper`` is the default local backend (MIT). It decodes audio
  containers itself through its bundled PyAV/FFmpeg libraries, so plain audio
  uploads never need a system ffmpeg binary. Video files still demux through
  the system ffmpeg: one 16 kHz mono audio track for transcription plus
  optional keyframes for OCR.
- ``openai-compat`` is the operator-choice backend: it posts the encoded
  payload to a configured ``/audio/transcriptions`` endpoint, which accepts
  WAV input everywhere. Audio payloads are normalized through the system
  ffmpeg first, so container decoding never depends on the remote server.
- All work runs off the ingestion event loop: ``DocumentTextExtractor.parse``
  stays synchronous and the pipeline invokes it through ``asyncio.to_thread``;
  this service spins a dedicated loop per document from inside the parser.
- Guards: duration probe (ffprobe, skipped when it is unavailable), a maximum
  accepted duration, a per-document transcription timeout, and timestamp
  buckets that anchor citations to wall-clock media positions.
"""

from __future__ import annotations

import asyncio
import hashlib
import io
import logging
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

import httpx

from app.common.metrics import (
    AI_INGESTION_ASR_SECONDS,
    AI_INGESTION_ASR_SEGMENTS_TOTAL,
)
from app.modules.ingestion.api import PermanentIngestionFailure, TransientIngestionFailure

logger = logging.getLogger(__name__)

_ASR_ARTIFACT_SCHEMA = "asr-artifact-v1"
_SHOWINFO_PTS = re.compile(r"pts_time:([0-9]+(?:\.[0-9]+)?)")
# ffmpeg showinfo writes one log line per delivered frame, in order, which the
# image2 muxer mirrors as frame%04d.jpg files; the zip below binds timestamps
# to their file bytes.


@dataclass(frozen=True, slots=True)
class TranscriptSegment:
    """One recognized utterance, bounded by wall-clock media seconds."""

    start_seconds: float
    end_seconds: float
    text: str


@dataclass(frozen=True, slots=True)
class TranscriptResult:
    """Ordered transcript segments plus optional source metadata."""

    segments: tuple[TranscriptSegment, ...]
    language: str | None = None
    duration_seconds: float | None = None


class TranscriptionProvider(Protocol):
    name: str
    #: Container decoding happens inside the provider (native PyAV); the
    #: service then hands the original bytes through instead of pre-encoding.
    native_decode: bool

    async def transcribe(self, audio_bytes: bytes, *, audio_format: str) -> TranscriptResult: ...

    def fingerprint_tag(self) -> str: ...


def asr_fingerprint(provider_tag: str, bucket_seconds: int, raw_sha256: str) -> str:
    """Stable fingerprint binding an ASR artifact to its inputs and settings.

    The timestamp bucket participates because cached transcript blocks carry
    bucket-derived ``page_number`` anchors; changing the bucket must reparse.
    """
    material = f"{_ASR_ARTIFACT_SCHEMA}|{provider_tag}|{bucket_seconds}|{raw_sha256}"
    return hashlib.sha256(material.encode("utf-8")).hexdigest()[:32]


class FasterWhisperTranscriptionProvider:
    """Local whisper transcription through the faster-whisper package."""

    name = "faster-whisper"
    native_decode = True

    def __init__(
        self,
        *,
        model_id: str,
        device: str,
        compute_type: str,
        language: str,
        vad_filter: bool,
    ):
        self._model_id = model_id
        self._device = device
        self._compute_type = compute_type
        self._language = language.strip().lower() or None
        self._vad_filter = vad_filter
        self._model: Any | None = None

    def fingerprint_tag(self) -> str:
        return (
            f"faster-whisper:{self._model_id}:{self._device}:{self._compute_type}:"
            f"{self._language or 'auto'}:{int(self._vad_filter)}"
        )

    def _ensure_model(self) -> Any:
        if self._model is None:
            try:
                from faster_whisper import WhisperModel
            except ImportError as exc:  # optional dependency
                raise PermanentIngestionFailure(
                    "ASR provider faster-whisper is not installed on the worker host"
                ) from exc
            try:
                self._model = WhisperModel(
                    self._model_id,
                    device=self._device,
                    compute_type=self._compute_type,
                )
            except Exception as exc:  # noqa: BLE001 - any model load failure is provider setup
                if self._device == "auto":
                    logger.warning("ASR load on auto failed (%s); falling back to CPU", exc)
                    self._device = "cpu"
                    compute_type = "int8" if self._compute_type == "default" else self._compute_type
                    self._model = WhisperModel(
                        self._model_id,
                        device="cpu",
                        compute_type=compute_type,
                    )
                else:
                    raise PermanentIngestionFailure(
                        f"ASR provider could not load the {self._model_id} model: "
                        f"{exc.__class__.__name__}"
                    ) from exc
        return self._model

    async def transcribe(self, audio_bytes: bytes, *, audio_format: str) -> TranscriptResult:
        del audio_format  # PyAV sniffs the container from the bytes themselves
        started_at = time.perf_counter()
        try:
            raw_segments, info = await asyncio.to_thread(
                self._transcribe_blocking, io.BytesIO(audio_bytes)
            )
        except PermanentIngestionFailure:
            outcome = "error"
            raise
        except Exception:
            outcome = "error"
            raise TransientIngestionFailure(
                "ASR transcription failed to decode the media payload"
            ) from None
        else:
            outcome = "success"
        finally:
            AI_INGESTION_ASR_SECONDS.labels(provider=self.name, outcome=outcome).observe(
                time.perf_counter() - started_at
            )
        collected = tuple(
            TranscriptSegment(
                start_seconds=round(float(segment.start), 3),
                end_seconds=round(float(segment.end), 3),
                text=str(segment.text or "").strip(),
            )
            for segment in raw_segments
        )
        AI_INGESTION_ASR_SEGMENTS_TOTAL.labels(provider=self.name, outcome=outcome).inc(
            max(1, len(collected))
        )
        duration = getattr(info, "duration", None)
        return TranscriptResult(
            segments=collected,
            language=getattr(info, "language", None),
            duration_seconds=round(float(duration), 3) if duration else None,
        )

    def _transcribe_blocking(self, source: io.BytesIO) -> tuple[Any, Any]:
        model = self._ensure_model()
        try:
            return model.transcribe(
                source,
                language=self._language,
                vad_filter=self._vad_filter,
            )
        except RuntimeError as exc:
            lowered = str(exc).lower()
            if self._device != "cpu" and ("cublas" in lowered or "cuda" in lowered):
                logger.warning("CUDA runtime failure (%s); falling back to CPU", exc)
                from faster_whisper import WhisperModel

                self._device = "cpu"
                compute_type = "int8" if self._compute_type == "default" else self._compute_type
                self._model = WhisperModel(
                    self._model_id,
                    device="cpu",
                    compute_type=compute_type,
                )
                source.seek(0)
                return self._model.transcribe(
                    source,
                    language=self._language,
                    vad_filter=self._vad_filter,
                )
            raise


class OpenAiCompatibleAsrProvider:
    """Operator-choice backend speaking the /v1/audio/transcriptions dialect."""

    name = "openai-compat"
    native_decode = False

    def __init__(
        self,
        *,
        base_url: str,
        model_id: str,
        timeout_seconds: float,
        language: str = "",
    ):
        self._base_url = base_url.rstrip("/")
        self._model_id = model_id
        self._timeout_seconds = timeout_seconds
        self._language = language.strip().lower()

    def fingerprint_tag(self) -> str:
        return f"openai-compat:{self._model_id}:{self._language or 'auto'}"

    async def transcribe(self, audio_bytes: bytes, *, audio_format: str) -> TranscriptResult:
        started_at = time.perf_counter()
        try:
            payload = await self._run(audio_bytes, audio_format)
        except (PermanentIngestionFailure, TransientIngestionFailure):
            outcome = "error"
            raise
        except Exception:
            outcome = "error"
            raise TransientIngestionFailure("ASR transcription request failed") from None
        else:
            outcome = "success"
        finally:
            AI_INGESTION_ASR_SECONDS.labels(provider=self.name, outcome=outcome).observe(
                time.perf_counter() - started_at
            )
        segments = _segments_from_verbose_json(payload, self.name)
        AI_INGESTION_ASR_SEGMENTS_TOTAL.labels(provider=self.name, outcome=outcome).inc(
            max(1, len(segments))
        )
        language = str(payload.get("language") or "") or None
        duration = payload.get("duration")
        return TranscriptResult(
            segments=segments,
            language=language,
            duration_seconds=(
                round(float(duration), 3) if isinstance(duration, (int, float)) else None
            ),
        )

    async def _run(self, audio_bytes: bytes, audio_format: str) -> dict[str, Any]:
        files = {"file": (f"payload{audio_format}", audio_bytes, "audio/wav")}
        payload: dict[str, Any] = {"model": self._model_id, "response_format": "verbose_json"}
        if self._language:
            payload["language"] = self._language
        try:
            async with httpx.AsyncClient(timeout=self._timeout_seconds) as client:
                response = await client.post(
                    f"{self._base_url}/audio/transcriptions", data=payload, files=files
                )
                response.raise_for_status()
                body = response.json()
        except httpx.HTTPStatusError as exc:
            raise PermanentIngestionFailure(
                f"ASR transcription service returned {exc.response.status_code}"
            ) from exc
        except httpx.HTTPError as exc:
            raise TransientIngestionFailure("ASR transcription service is unavailable") from exc
        if not isinstance(body, dict):
            raise PermanentIngestionFailure(
                "ASR transcription service returned an invalid payload"
            )
        return body


def _segments_from_verbose_json(
    payload: dict[str, Any], provider_name: str
) -> tuple[TranscriptSegment, ...]:
    """Parse verbose_json segments; fall back to one whole-duration segment.

    Endpoints without verbose_json support return plain ``{"text": ...}``
    bodies; treating those as a single segment keeps the pipeline receiving
    retrievable transcript text instead of failing the document.
    """
    raw_segments = payload.get("segments")
    if isinstance(raw_segments, list) and raw_segments:
        collected: list[TranscriptSegment] = []
        for item in raw_segments:
            if not isinstance(item, dict):
                continue
            text = str(item.get("text") or "").strip()
            if not text:
                continue
            try:
                start = float(item.get("start") or 0.0)
                end = float(item.get("end") or start)
            except (TypeError, ValueError):
                continue
            collected.append(
                TranscriptSegment(
                    start_seconds=round(start, 3),
                    end_seconds=round(max(start, end), 3),
                    text=text,
                )
            )
        if collected:
            return tuple(collected)
        raise PermanentIngestionFailure(
            f"{provider_name} returned no transcript segments with text"
        )
    text = str(payload.get("text") or "").strip()
    if not text:
        raise PermanentIngestionFailure(f"{provider_name} returned no transcript text")
    duration = payload.get("duration")
    return (
        TranscriptSegment(
            start_seconds=0.0,
            end_seconds=(
                round(float(duration), 3) if isinstance(duration, (int, float)) else 0.0
            ),
            text=text,
        ),
    )


class AsrIngestionService:
    """Guards and media demux on top of a transcription provider.

    The public surface mirrors ``OcrIngestionService``: parsers call the
    synchronous entry points, each of which runs its own event loop so the
    underlying subprocess and HTTP work never borrow the ingestion loop.
    """

    def __init__(
        self,
        provider: TranscriptionProvider,
        *,
        timestamp_bucket_seconds: int = 60,
        max_duration_seconds: float = 3600.0,
        timeout_seconds: float = 3600.0,
        keyframe_interval_seconds: float = 10.0,
        max_keyframes: int = 24,
    ):
        self._provider = provider
        self._bucket_seconds = timestamp_bucket_seconds
        self._max_duration_seconds = max_duration_seconds
        self._timeout_seconds = timeout_seconds
        self._keyframe_interval_seconds = keyframe_interval_seconds
        self._max_keyframes = max_keyframes

    @property
    def provider_name(self) -> str:
        return self._provider.name

    @property
    def keyframe_interval_seconds(self) -> float:
        return self._keyframe_interval_seconds

    @property
    def max_keyframes(self) -> int:
        return self._max_keyframes

    def fingerprint(self, raw_sha256: str) -> str:
        return asr_fingerprint(self._provider.fingerprint_tag(), self._bucket_seconds, raw_sha256)

    def bucket_for_seconds(self, seconds: float | None) -> int:
        """Citation anchor: the page-number-equivalent timestamp bucket (1-based)."""
        if seconds is None or seconds < 0:
            return 1
        return max(1, int(seconds // self._bucket_seconds) + 1)

    # -- entry points used by parsers (synchronous contexts) -------------------

    def transcribe_audio(
        self, data: bytes, *, label: str, media_kind: str = "audio"
    ) -> TranscriptResult:
        return self._drive(
            lambda: self._transcribe_async(data, label=label, media_kind=media_kind)
        )

    def extract_video_keyframes(self, data: bytes, *, label: str) -> list[tuple[float, bytes]]:
        return self._drive(
            lambda: _extract_video_keyframes_async(
                data,
                interval_seconds=self._keyframe_interval_seconds,
                max_keyframes=self._max_keyframes,
                label=label,
            )
        )

    def _drive(self, factory: Any) -> Any:
        try:
            asyncio.get_running_loop()
        except RuntimeError:
            return asyncio.run(factory())
        raise TransientIngestionFailure(
            "ASR must run off the ingestion event loop (call the parser through "
            "asyncio.to_thread)"
        )

    # -- async cores -----------------------------------------------------------

    async def _transcribe_async(
        self, data: bytes, *, label: str, media_kind: str
    ) -> TranscriptResult:
        if media_kind == "video":
            payload, audio_format = await asyncio.to_thread(_extract_audio_track, data, label)
        elif self._provider.native_decode:
            payload, audio_format = data, _container_suffix(data)
        else:
            payload, audio_format = await asyncio.to_thread(_normalize_audio, data, label)
        duration = await _probe_duration(payload if media_kind == "video" else data, label)
        if duration is not None and duration > self._max_duration_seconds:
            raise PermanentIngestionFailure(
                f"{media_kind.capitalize()} document {label} runs {duration / 60:.1f} minutes; "
                f"ASR is limited to {_minutes(self._max_duration_seconds)} minutes"
            )
        try:
            result = await asyncio.wait_for(
                self._provider.transcribe(payload, audio_format=audio_format),
                timeout=self._timeout_seconds,
            )
        except TimeoutError as exc:
            raise PermanentIngestionFailure(
                f"ASR transcription timed out after {self._timeout_seconds:g} seconds for {label}"
            ) from exc
        if not any(segment.text.strip() for segment in result.segments):
            raise PermanentIngestionFailure(f"ASR returned no transcript text for {label}")
        return result


def _minutes(seconds: float) -> str:
    return f"{seconds / 60:.1f}"


# --------------------------------------------------------------------------
# Media container helpers (system ffmpeg + ffprobe)
# --------------------------------------------------------------------------


def _container_suffix(data: bytes) -> str:
    """Best-effort extension for in-memory decoding (PyAV sniffs the bytes)."""
    for signature, suffix in (
        (b"OggS", ".ogg"),
        (b"fLaC", ".flac"),
        (b"RIFF", ".wav"),
        (b"ID3", ".mp3"),
    ):
        if data.startswith(signature):
            return suffix
    if len(data) > 11 and data[4:8] == b"ftyp":
        return ".m4a"
    return ".audio"


_FFMPEG_DECODE_ARGUMENTS = (
    "-nostdin",
    "-y",
    "-v",
    "error",
    "-i",
    "{input}",
    "-vn",
    "-ac",
    "1",
    "-ar",
    "16000",
    "-f",
    "wav",
    "{output}",
)


def _normalize_audio(data: bytes, label: str) -> tuple[bytes, str]:
    """16 kHz mono WAV for providers that cannot decode containers themselves."""
    return _run_media_tool(
        data,
        _FFMPEG_DECODE_ARGUMENTS,
        output_suffix=".wav",
        label=label,
        failure_message=f"{label}: audio document could not be decoded",
    )


def _extract_audio_track(data: bytes, label: str) -> tuple[bytes, str]:
    """Video demux: the audio track becomes a 16 kHz mono WAV payload."""
    return _run_media_tool(
        data,
        _FFMPEG_DECODE_ARGUMENTS,
        output_suffix=".wav",
        label=label,
        failure_message=f"{label}: video document could not be decoded",
    )


def _run_media_tool(
    data: bytes,
    arguments: tuple[str, ...],
    *,
    output_suffix: str,
    label: str,
    failure_message: str,
) -> tuple[bytes, str]:
    """One blocking ffmpeg pass over an in-memory payload.

    Runs inside the service's dedicated loop through ``asyncio.to_thread``.
    Any tool failure is permanent: a transient decode problem would only
    resurface identically on retry, so failing fast with a clear message is
    honest — the retryable paths (provider outages) still retry normally.
    """
    with tempfile.NamedTemporaryFile(delete=False) as handle:
        handle.write(data)
        input_path = handle.name
    output_path = input_path + output_suffix
    try:
        substituted = [
            argument.replace("{input}", input_path).replace("{output}", output_path)
            for argument in arguments
        ]
        try:
            process = subprocess.run(  # noqa: PLW1510 - checked returncode below
                [_tool_path("ffmpeg"), *substituted], capture_output=True
            )
        except FileNotFoundError as exc:
            raise PermanentIngestionFailure(
                "ffmpeg is not installed on the worker host; media ingestion "
                "requires the ffmpeg binary"
            ) from exc
        except OSError as exc:
            raise PermanentIngestionFailure(
                f"{failure_message} ({exc.__class__.__name__})"
            ) from exc
        if process.returncode != 0:
            detail = (process.stderr or b"").decode("utf-8", errors="replace").strip()
            logger.warning("ffmpeg failed for %s: %s", label, detail[:200])
            detail_suffix = f": {detail[:150]}" if detail else f" (exit code {process.returncode})"
            raise PermanentIngestionFailure(f"{failure_message}{detail_suffix}")
        with Path(output_path).open("rb") as output_handle:  # noqa: PTH123
            return output_handle.read(), output_suffix
    finally:
        _discard_file(input_path)
        _discard_file(output_path)


def _discard_file(path: str) -> None:
    try:
        Path(path).unlink(missing_ok=True)
    except OSError:
        pass

def _tool_path(tool_name: str) -> str:
    found = shutil.which(tool_name)
    if found:
        return found
    ext = ".exe" if os.name == "nt" else ""
    candidate = (
        Path(sys.prefix)
        / ("Scripts" if os.name == "nt" else "bin")
        / f"{tool_name}{ext}"
    )
    if candidate.is_file():
        return str(candidate)
    return tool_name


async def _probe_duration(data: bytes, label: str) -> float | None:
    """Media duration via ffprobe; None when the tool is unavailable or odd.

    A skipped guard falls back to the transcription timeout instead of
    silently allowing unbounded runs.
    """
    with tempfile.NamedTemporaryFile(delete=False) as handle:
        handle.write(data)
        input_path = handle.name
    try:
        try:
            process = await asyncio.create_subprocess_exec(
                _tool_path("ffprobe"),
                "-v",
                "error",
                "-show_entries",
                "format=duration",
                "-of",
                "default=nokey=1:noprint_wrappers=1",
                input_path,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
        except (FileNotFoundError, OSError):
            logger.warning(
                "ffprobe is unavailable; skipping the ASR duration guard for %s", label
            )
            return None
        stdout, _stderr = await process.communicate()
        if process.returncode != 0:
            return None
        return _parse_duration(stdout.decode("utf-8", errors="replace"))
    finally:
        await asyncio.to_thread(_discard_file, input_path)


def _parse_duration(output: str) -> float | None:
    try:
        value = float(output.strip().splitlines()[0].strip())
    except (IndexError, ValueError):
        return None
    return value if 0 <= value < float("inf") else None


async def _extract_video_keyframes_async(
    data: bytes,
    *,
    interval_seconds: float,
    max_keyframes: int,
    label: str,
) -> list[tuple[float, bytes]]:
    """Decode up to ``max_keyframes`` sampled frames with their timestamps.

    The ffmpeg ``fps`` filter pins the sample spacing and ``showinfo`` logs
    each delivered frame's presentation time; those log lines provide the
    exact wall-clock anchor for the OCR blocks derived from each frame.
    """
    if max_keyframes <= 0:
        return []
    with tempfile.TemporaryDirectory(prefix="openvie-keyframes-") as frames_dir:
        with tempfile.NamedTemporaryFile(delete=False) as handle:
            handle.write(data)
            input_path = handle.name
        try:
            process = await _spawn(
                (
                    _tool_path("ffmpeg"),
                    "-nostdin",
                    "-y",
                    "-v",
                    "info",
                    "-i",
                    input_path,
                    "-vf",
                    f"fps=1/{interval_seconds},showinfo",
                    "-frames:v",
                    str(max_keyframes),
                    "-q:v",
                    "2",
                    f"{frames_dir}/frame%04d.jpg",
                ),
                label=label,
                failure_message=f"{label}: video document could not be decoded for keyframes",
            )
            stdout, stderr = await process.communicate()
            if process.returncode != 0:
                detail = (stderr or b"").decode("utf-8", errors="replace").strip()
                logger.warning("keyframe extraction failed for %s: %s", label, detail[:200])
                raise PermanentIngestionFailure(
                    f"{label}: video document could not be decoded for keyframes"
                )
            del stdout
            timestamps = _SHOWINFO_PTS.findall((stderr or b"").decode("utf-8", errors="replace"))
            frames = await asyncio.to_thread(_read_keyframes, frames_dir)
            result: list[tuple[float, bytes]] = []
            for raw_timestamp, payload in zip(timestamps, frames, strict=False):
                try:
                    seconds = float(raw_timestamp)
                except ValueError:
                    continue
                result.append((seconds, payload))
            return result
        finally:
            await asyncio.to_thread(_discard_file, input_path)


async def _spawn(
    command: tuple[str, ...],
    *,
    label: str,
    failure_message: str,
) -> Any:
    """Spawn a subprocess, mapping a missing binary to a permanent failure."""
    import shutil

    try:
        return await asyncio.create_subprocess_exec(
            *command,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
    except (FileNotFoundError, OSError) as exc:
        details = failure_message if shutil.which(command[0]) is None else (
            f"{failure_message} (spawn failed)"
        )
        logger.warning("%s subprocess could not start for %s", command[0], label)
        raise PermanentIngestionFailure(details) from exc


def _read_keyframes(frames_dir: str) -> list[bytes]:
    """Read frame%04d.jpg until the first gap (ffmpeg writes them in order)."""
    frames: list[bytes] = []
    directory = Path(frames_dir)
    for number in range(1, 1_000_000):
        frame = directory / f"frame{number:04d}.jpg"
        if not frame.exists():
            break
        frames.append(frame.read_bytes())
    return frames
