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

"""Optional ASR ingestion: providers, parsers, guards, buckets, and caching."""

from __future__ import annotations

import asyncio
import base64
import hashlib
from datetime import UTC, datetime
from typing import Any

import httpx
import pytest

from app.bootstrap.settings import Settings
from app.common.errors import StorageNotFoundError
from app.common.storage_keys import artifact_prefix, asr_artifact_key
from app.modules.graph.api import GraphBatch
from app.modules.index.api import ReplaceDocumentIndex
from app.modules.ingestion.api import (
    IngestDocumentCommand,
    PermanentIngestionFailure,
    TransientIngestionFailure,
)
from app.modules.ingestion.internal.chunking import DeterministicChunker
from app.modules.ingestion.internal.entity_extraction import graph_units
from app.modules.ingestion.internal.extraction import (
    AudioParser,
    DocumentTextExtractor,
    VideoParser,
)
from app.modules.ingestion.internal.ocr import OcrIngestionService, OcrRecognition
from app.modules.ingestion.internal.ocr_artifacts import load_asr_artifact, save_asr_artifact
from app.modules.ingestion.internal.pipeline import (
    DocumentIndexLifecycleService,
    DocumentIngestionPipeline,
)
from app.modules.ingestion.internal.transcription import (
    AsrIngestionService,
    OpenAiCompatibleAsrProvider,
    TranscriptResult,
    TranscriptSegment,
    _parse_duration,
    _segments_from_verbose_json,
    asr_fingerprint,
)

TENANT_ID = "00000000-0000-0000-0000-000000000002"
KNOWLEDGE_BASE_ID = "00000000-0000-0000-0000-000000000004"
DOCUMENT_ID = "37f34742-0be6-4f82-9839-39303fabe2d7"
DOCUMENT_KEY = (
    f"tenants/{TENANT_ID}/knowledge-bases/{KNOWLEDGE_BASE_ID}/documents/{DOCUMENT_ID}/briefing.mp3"
)

# 1x1 transparent PNG fixture.
TINY_PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAAABJRU5ErkJggg=="
)

TRANSCRIPT = TranscriptResult(
    segments=(
        TranscriptSegment(0.0, 12.5, "Xin chào các anh chị."),
        TranscriptSegment(63.0, 90.0, "Phần hai bắt đầu từ đây."),
    ),
    language="vi",
    duration_seconds=90.0,
)


class FakeTranscriptionProvider:
    """Fixed-transcript provider that records the payloads it received."""

    name = "faster-whisper-fake"
    native_decode = True

    def __init__(
        self,
        result: TranscriptResult | None = None,
        error: Exception | None = None,
    ):
        self.result = result or TRANSCRIPT
        self.error = error
        self.calls: list[tuple[bytes, str]] = []

    def fingerprint_tag(self) -> str:
        return "fake-asr:1"

    async def transcribe(self, audio_bytes: bytes, *, audio_format: str) -> TranscriptResult:
        self.calls.append((audio_bytes, audio_format))
        if self.error is not None:
            raise self.error
        return self.result


@pytest.fixture()
def asr() -> AsrIngestionService:
    return AsrIngestionService(
        FakeTranscriptionProvider(), timestamp_bucket_seconds=60, timeout_seconds=30
    )


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


class NeverExtract:
    async def extract(self, command: Any, chunks: Any) -> GraphBatch:
        del command, chunks
        return GraphBatch(
            tenant_id=TENANT_ID,
            knowledge_base_id=KNOWLEDGE_BASE_ID,
            source_id=DOCUMENT_ID,
            source_name="briefing.mp3",
            units=graph_units(DOCUMENT_ID, "briefing.mp3", []),
        )


class MemoryObjectStore:
    def __init__(self) -> None:
        self.objects: dict[str, bytes] = {DOCUMENT_KEY: b"ID3 fake audio"}
        self.deleted_prefixes: list[str] = []

    async def download(self, storage_key: str) -> bytes:
        try:
            return self.objects[storage_key]
        except KeyError as exc:
            raise StorageNotFoundError(f"missing object: {storage_key}") from exc

    async def upload(self, storage_key: str, data: bytes, *, content_type: str) -> None:
        del content_type
        self.objects[storage_key] = data

    async def delete_prefix(self, storage_prefix: str) -> None:
        self.deleted_prefixes.append(storage_prefix)
        for key in tuple(self.objects):
            if key.startswith(storage_prefix):
                del self.objects[key]


def command(
    file_name: str = "briefing.mp3",
    content_type: str = "audio/mpeg",
) -> IngestDocumentCommand:
    return IngestDocumentCommand(
        schema_version="1.0",
        event_id="event-media-1",
        job_id="job-media-1",
        tenant_id=TENANT_ID,
        knowledge_base_id=KNOWLEDGE_BASE_ID,
        document_id=DOCUMENT_ID,
        uploader_id="user-1",
        storage_key=(
            f"tenants/{TENANT_ID}/knowledge-bases/{KNOWLEDGE_BASE_ID}/"
            f"documents/{DOCUMENT_ID}/{file_name}"
        ),
        file_name=file_name,
        content_type=content_type,
        file_size_bytes=16,
        occurred_at=datetime.now(UTC),
    )


def media_pipeline(
    store: MemoryObjectStore,
    extractor: DocumentTextExtractor,
    asr_service: AsrIngestionService | None,
) -> DocumentIngestionPipeline:
    return DocumentIngestionPipeline(
        store=store,
        extractor=extractor,
        chunker=DeterministicChunker(),
        embedder=object(),
        sparse_encoder=object(),
        vector_store=NeverIndex(),  # type: ignore[arg-type]
        graph_store=StubGraph(),  # type: ignore[arg-type]
        graph_extractor=NeverExtract(),  # type: ignore[arg-type]
        ocr=None,
        asr=asr_service,
    )


class FixedOcrProvider:
    name = "fixed-ocr"
    native_decode = False

    def __init__(self, text: str = "Chữ trên slide video"):
        self.text = text
        self.calls: list[bytes] = []

    def fingerprint_tag(self) -> str:
        return "fixed-ocr:1"

    async def recognize(self, image_bytes: bytes, *, image_format: str) -> OcrRecognition:
        del image_format
        self.calls.append(image_bytes)
        return OcrRecognition(text=self.text)


def ocr_service(provider: Any = None) -> OcrIngestionService:
    return OcrIngestionService(
        provider or FixedOcrProvider(),
        dpi=300,
        min_text_chars=32,
        max_pages=300,
        max_megapixels_per_page=25.0,
        timeout_seconds_per_page=60.0,
        page_concurrency=2,
    )


# --------------------------------------------------------------------------
# Fingerprints, timestamp buckets, and duration parsing
# --------------------------------------------------------------------------


def test_asr_fingerprint_is_stable_and_bucket_sensitive() -> None:
    raw = "0" * 64
    first = asr_fingerprint("fake-asr:1", 60, raw)
    assert first == asr_fingerprint("fake-asr:1", 60, raw)
    assert first != asr_fingerprint("fake-asr:1", 30, raw)
    assert first != asr_fingerprint("other:2", 60, raw)
    assert first != asr_fingerprint("fake-asr:1", 60, "1" * 64)
    assert len(first) == 32


def test_timestamp_buckets_map_seconds_to_page_anchors(asr: AsrIngestionService) -> None:
    assert asr.bucket_for_seconds(None) == 1
    assert asr.bucket_for_seconds(-5.0) == 1
    assert asr.bucket_for_seconds(0.0) == 1
    assert asr.bucket_for_seconds(59.9) == 1
    assert asr.bucket_for_seconds(60.0) == 2
    assert asr.bucket_for_seconds(123.0) == 3


def test_parse_duration_helper() -> None:
    assert _parse_duration("90.0\n") == 90.0
    assert _parse_duration("0\n") == 0.0
    assert _parse_duration("") is None
    assert _parse_duration("N/A") is None
    assert _parse_duration("inf") is None
    assert _parse_duration("-1.0\n") is None


# --------------------------------------------------------------------------
# AudioParser and extraction registry
# --------------------------------------------------------------------------


def test_audio_parser_produces_bucketed_transcript_blocks(asr: AsrIngestionService) -> None:
    parsed = AudioParser(asr).parse(
        b"fake-audio", file_name="briefing.mp3", content_type="audio/mpeg"
    )
    assert parsed.text_source == "asr"
    assert parsed.page_count == 2
    assert [block.page_number for block in parsed.blocks] == [1, 2]
    assert parsed.blocks[0].text == "Xin chào các anh chị."
    assert parsed.blocks[0].parser_version == "asr-v1"
    assert parsed.blocks[1].metadata["start_seconds"] == 63.0
    assert parsed.blocks[1].metadata["end_seconds"] == 90.0
    assert parsed.blocks[1].metadata["media_kind"] == "audio"


def test_audio_parser_chunks_carry_bucket_anchors(asr: AsrIngestionService) -> None:
    parsed = AudioParser(asr).parse(
        b"fake-audio", file_name="briefing.mp3", content_type="audio/mpeg"
    )
    chunks = DeterministicChunker().chunk(parsed)
    assert [chunk.page_number for chunk in chunks] == [1, 2]
    assert chunks[0].parser_version == "asr-v1"
    assert chunks[0].modality == "document"
def test_audio_parser_rejects_empty_transcripts() -> None:
    class EmptyAsr:
        def transcribe_audio(
            self, data: bytes, *, label: str, media_kind: str = "audio"
        ) -> TranscriptResult:
            del data, label, media_kind
            return TranscriptResult(segments=())

    with pytest.raises(PermanentIngestionFailure, match="no speech transcript"):
        AudioParser(EmptyAsr()).parse(b"fake-audio", file_name="briefing.mp3")  # type: ignore[arg-type]


def test_asr_service_rejects_empty_transcripts() -> None:
    blank = AsrIngestionService(
        FakeTranscriptionProvider(
            TranscriptResult(segments=(TranscriptSegment(0.0, 3.0, "   "),))
        ),
        timestamp_bucket_seconds=60,
    )
    with pytest.raises(PermanentIngestionFailure, match="ASR returned no transcript text"):
        blank.transcribe_audio(b"fake-audio", label="briefing.mp3")

def test_audio_parser_rejects_extension_content_type_mismatch(asr: AsrIngestionService) -> None:
    extractor = DocumentTextExtractor(asr=asr)
    with pytest.raises(PermanentIngestionFailure, match="do not match"):
        extractor.parse(b"fake-audio", file_name="briefing.mp3", content_type="application/pdf")


def test_default_registry_rejects_media_without_asr() -> None:
    extractor = DocumentTextExtractor()
    with pytest.raises(PermanentIngestionFailure, match="Unsupported document content type"):
        extractor.parse(b"fake", content_type="audio/mpeg", file_name="briefing.mp3")


def test_registry_with_only_ocr_rejects_media_too() -> None:
    extractor = DocumentTextExtractor(ocr=ocr_service())
    with pytest.raises(PermanentIngestionFailure, match="Unsupported document content type"):
        extractor.parse(b"fake", content_type="audio/mpeg", file_name="briefing.mp3")


def test_registry_with_asr_accepts_audio(asr: AsrIngestionService) -> None:
    extractor = DocumentTextExtractor(asr=asr)
    parsed = extractor.parse(b"fake", content_type="audio/mpeg", file_name="briefing.mp3")
    assert parsed.text_source == "asr"


def test_video_without_video_enabled_rejected(asr: AsrIngestionService) -> None:
    extractor = DocumentTextExtractor(asr=asr, video_enabled=False)
    with pytest.raises(PermanentIngestionFailure, match="Unsupported document content type"):
        extractor.parse(b"fake", content_type="video/mp4", file_name="clip.mp4")


# --------------------------------------------------------------------------
# VideoParser and keyframe OCR
# --------------------------------------------------------------------------


def test_video_parser_keyframe_ocr_blocks(
    asr: AsrIngestionService, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(
        "app.modules.ingestion.internal.transcription._extract_audio_track",
        lambda data, label: (b"fake-audio", ".wav"),
    )
    async def mock_probe(data: bytes, label: str) -> float:
        del data, label
        return 90.0

    monkeypatch.setattr(
        "app.modules.ingestion.internal.transcription._probe_duration",
        mock_probe,
    )
    monkeypatch.setattr(
        AsrIngestionService,
        "extract_video_keyframes",
        lambda self, data, label: [(5.0, TINY_PNG), (65.0, TINY_PNG)],
    )
    ocr = ocr_service(FixedOcrProvider(text="Chữ trên slide video"))
    video_parser = VideoParser(asr, ocr, keyframes_ocr=True)
    parsed = video_parser.parse(b"fake-video", file_name="clip.mp4")

    assert parsed.text_source == "mixed"
    assert parsed.page_count == 2
    assert len(parsed.blocks) == 4

    assert parsed.blocks[0].parser_version == "asr-v1"
    assert parsed.blocks[0].metadata["media_kind"] == "video"

    kf_block1 = parsed.blocks[2]
    assert kf_block1.parser_version == "ocr-v1"
    assert kf_block1.metadata["ocr_source"] == "video-keyframe"
    assert kf_block1.metadata["keyframe_seconds"] == 5.0
    assert kf_block1.page_number == 1
    assert kf_block1.text == "Chữ trên slide video"

    kf_block2 = parsed.blocks[3]
    assert kf_block2.parser_version == "ocr-v1"
    assert kf_block2.metadata["ocr_source"] == "video-keyframe"
    assert kf_block2.metadata["keyframe_seconds"] == 65.0
    assert kf_block2.page_number == 2


def test_video_parser_keyframe_streak_failure(
    asr: AsrIngestionService, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(
        "app.modules.ingestion.internal.transcription._extract_audio_track",
        lambda data, label: (b"fake-audio", ".wav"),
    )
    async def mock_probe(data: bytes, label: str) -> float:
        del data, label
        return 90.0

    monkeypatch.setattr(
        "app.modules.ingestion.internal.transcription._probe_duration",
        mock_probe,
    )
    monkeypatch.setattr(
        AsrIngestionService,
        "extract_video_keyframes",
        lambda self, data, label: [
            (1.0, TINY_PNG),
            (2.0, TINY_PNG),
            (3.0, TINY_PNG),
            (4.0, TINY_PNG),
        ],
    )

    class FailingKeyframeOcr:
        name = "failing-ocr"
        native_decode = False

        def fingerprint_tag(self) -> str:
            return "failing:1"

        async def recognize(self, image_bytes: bytes, *, image_format: str) -> OcrRecognition:
            del image_bytes, image_format
            raise TransientIngestionFailure("OCR temporary outage")

    ocr = ocr_service(FailingKeyframeOcr())
    video_parser = VideoParser(asr, ocr, keyframes_ocr=True)
    with pytest.raises(
        TransientIngestionFailure, match="Embedded-image OCR failed 3 provider calls in a row"
    ):
        video_parser.parse(b"fake-video", file_name="clip.mp4")


# --------------------------------------------------------------------------
# Provider payloads and OpenAI-compatible endpoint error handling
# --------------------------------------------------------------------------


def test_segments_from_verbose_json() -> None:
    payload = {
        "segments": [
            {"start": 0.0, "end": 2.5, "text": "Xin chào"},
            {"start": 2.5, "end": 5.0, "text": "Việt Nam"},
        ]
    }
    segments = _segments_from_verbose_json(payload, "test-asr")
    assert len(segments) == 2
    assert segments[0].start_seconds == 0.0
    assert segments[0].end_seconds == 2.5
    assert segments[0].text == "Xin chào"
    assert segments[1].text == "Việt Nam"

    # Plain-text fallback when segments list is missing
    plain = {"text": "Toàn bộ văn bản", "duration": 15.5}
    plain_segments = _segments_from_verbose_json(plain, "test-asr")
    assert len(plain_segments) == 1
    assert plain_segments[0].start_seconds == 0.0
    assert plain_segments[0].end_seconds == 15.5
    assert plain_segments[0].text == "Toàn bộ văn bản"

    # Empty segments with no text
    with pytest.raises(PermanentIngestionFailure, match="returned no transcript segments"):
        _segments_from_verbose_json(
            {"segments": [{"start": 0.0, "end": 1.0, "text": "  "}]}, "test"
        )
    # Empty text payload
    with pytest.raises(PermanentIngestionFailure, match="returned no transcript text"):
        _segments_from_verbose_json({"text": "  "}, "test")


@pytest.mark.asyncio
async def test_openai_compat_provider_error_handling(monkeypatch: pytest.MonkeyPatch) -> None:
    provider = OpenAiCompatibleAsrProvider(
        base_url="http://localhost:8000/v1",
        model_id="whisper-1",
        timeout_seconds=30.0,
        language="vi",
    )

    class MockClientStatusError:
        def __init__(self, *args: Any, **kwargs: Any) -> None:
            del args, kwargs

        async def __aenter__(self) -> MockClientStatusError:
            return self

        async def __aexit__(self, *args: Any) -> None:
            pass

        async def post(self, *args: Any, **kwargs: Any) -> Any:
            del args, kwargs
            request = httpx.Request("POST", "http://localhost:8000/v1/audio/transcriptions")
            response = httpx.Response(500, request=request)
            raise httpx.HTTPStatusError("Server error", request=request, response=response)

    monkeypatch.setattr(httpx, "AsyncClient", MockClientStatusError)
    with pytest.raises(PermanentIngestionFailure, match="returned 500"):
        await provider.transcribe(b"fake-audio", audio_format=".wav")

    class MockClientConnectError:
        def __init__(self, *args: Any, **kwargs: Any) -> None:
            del args, kwargs

        async def __aenter__(self) -> MockClientConnectError:
            return self

        async def __aexit__(self, *args: Any) -> None:
            pass

        async def post(self, *args: Any, **kwargs: Any) -> Any:
            del args, kwargs
            request = httpx.Request("POST", "http://localhost:8000/v1/audio/transcriptions")
            raise httpx.ConnectError("Connection refused", request=request)

    monkeypatch.setattr(httpx, "AsyncClient", MockClientConnectError)
    with pytest.raises(TransientIngestionFailure, match="service is unavailable"):
        await provider.transcribe(b"fake-audio", audio_format=".wav")


# --------------------------------------------------------------------------
# ASR Artifacts and Pipeline Caching
# --------------------------------------------------------------------------


def test_asr_artifact_round_trip_and_fingerprint_mismatch(asr: AsrIngestionService) -> None:
    document = AudioParser(asr).parse(
        b"fake-audio",
        content_type="audio/mpeg",
        file_name="briefing.mp3",
    )

    async def scenario() -> None:
        fingerprint = asr_fingerprint("fake-asr:1", 60, "0" * 64)
        store = MemoryObjectStore()
        await save_asr_artifact(
            store,
            tenant_id=TENANT_ID,
            knowledge_base_id=KNOWLEDGE_BASE_ID,
            document_id=DOCUMENT_ID,
            fingerprint=fingerprint,
            parsed=document,
        )
        saved_key = asr_artifact_key(
            TENANT_ID, KNOWLEDGE_BASE_ID, DOCUMENT_ID, fingerprint
        )
        assert saved_key in store.objects

        restored = await load_asr_artifact(
            store,
            tenant_id=TENANT_ID,
            knowledge_base_id=KNOWLEDGE_BASE_ID,
            document_id=DOCUMENT_ID,
            fingerprint=fingerprint,
        )
        assert restored == document

        mismatched = await load_asr_artifact(
            store,
            tenant_id=TENANT_ID,
            knowledge_base_id=KNOWLEDGE_BASE_ID,
            document_id=DOCUMENT_ID,
            fingerprint="different",
        )
        assert mismatched is None

        # Corrupted artifact falls back to None
        store.objects[saved_key] = b"not json"
        corrupted = await load_asr_artifact(
            store,
            tenant_id=TENANT_ID,
            knowledge_base_id=KNOWLEDGE_BASE_ID,
            document_id=DOCUMENT_ID,
            fingerprint=fingerprint,
        )
        assert corrupted is None

    asyncio.run(scenario())


@pytest.mark.asyncio
async def test_pipeline_caches_asr_artifact_and_reuses_it() -> None:
    store = MemoryObjectStore()
    provider = FakeTranscriptionProvider()
    asr = AsrIngestionService(provider, timestamp_bucket_seconds=60)
    pipeline = media_pipeline(store, DocumentTextExtractor(asr=asr), asr)

    prepared = await pipeline.prepare(command())

    assert prepared.parsed.text_source == "asr"
    assert len(provider.calls) == 1
    raw_hash = hashlib.sha256(b"ID3 fake audio").hexdigest()
    fingerprint = asr.fingerprint(raw_hash)
    saved_key = asr_artifact_key(TENANT_ID, KNOWLEDGE_BASE_ID, DOCUMENT_ID, fingerprint)
    assert saved_key in store.objects

    second_provider = FakeTranscriptionProvider(error=RuntimeError("must not be called on hit"))
    second_asr = AsrIngestionService(second_provider, timestamp_bucket_seconds=60)
    replayed = await media_pipeline(
        store, DocumentTextExtractor(asr=second_asr), second_asr
    ).prepare(command())

    assert len(second_provider.calls) == 0
    assert replayed.parsed == prepared.parsed
    assert replayed.chunks == prepared.chunks


@pytest.mark.asyncio
async def test_pipeline_cleanup_deletes_asr_prefix() -> None:
    store = MemoryObjectStore()
    provider = FakeTranscriptionProvider()
    asr = AsrIngestionService(provider, timestamp_bucket_seconds=60)
    pipeline = media_pipeline(store, DocumentTextExtractor(asr=asr), asr)

    await pipeline.prepare(command())
    asr_prefix = artifact_prefix("asr", TENANT_ID, KNOWLEDGE_BASE_ID, DOCUMENT_ID)
    assert any(key.startswith(asr_prefix) for key in store.objects)

    await pipeline.cleanup(command())
    assert asr_prefix in store.deleted_prefixes


@pytest.mark.asyncio
async def test_lifecycle_delete_document_deletes_asr_artifacts() -> None:
    store = MemoryObjectStore()
    asr = AsrIngestionService(FakeTranscriptionProvider(), timestamp_bucket_seconds=60)
    lifecycle = DocumentIndexLifecycleService(
        NeverIndex(),  # type: ignore[arg-type]
        StubGraph(),  # type: ignore[arg-type]
        store,
        ocr=None,
        asr=asr,
    )

    await lifecycle.delete_document(TENANT_ID, KNOWLEDGE_BASE_ID, DOCUMENT_ID)
    asr_prefix = artifact_prefix("asr", TENANT_ID, KNOWLEDGE_BASE_ID, DOCUMENT_ID)
    assert asr_prefix in store.deleted_prefixes


# --------------------------------------------------------------------------
# Settings validation for ASR and Video
# --------------------------------------------------------------------------


def test_settings_validation_for_asr_and_video() -> None:
    # Timestamp bucket bounds (1 - 600)
    with pytest.raises(ValueError, match="INGESTION_ASR_TIMESTAMP_BUCKET_SECONDS"):
        Settings(INGESTION_ASR_ENABLED=True, INGESTION_ASR_TIMESTAMP_BUCKET_SECONDS=0)
    with pytest.raises(ValueError, match="INGESTION_ASR_TIMESTAMP_BUCKET_SECONDS"):
        Settings(INGESTION_ASR_ENABLED=True, INGESTION_ASR_TIMESTAMP_BUCKET_SECONDS=601)

    # Max duration positive
    with pytest.raises(ValueError, match="INGESTION_ASR_MAX_DURATION_SECONDS"):
        Settings(INGESTION_ASR_ENABLED=True, INGESTION_ASR_MAX_DURATION_SECONDS=0)

    # Timeout positive
    with pytest.raises(ValueError, match="INGESTION_ASR_TIMEOUT_SECONDS"):
        Settings(INGESTION_ASR_ENABLED=True, INGESTION_ASR_TIMEOUT_SECONDS=-1.0)

    # OpenAI-compat requires model id and base url
    with pytest.raises(ValueError, match="INGESTION_ASR_MODEL_ID"):
        Settings(
            INGESTION_ASR_ENABLED=True,
            INGESTION_ASR_PROVIDER="openai-compat",
            INGESTION_ASR_MODEL_ID="  ",
        )
    with pytest.raises(ValueError, match="ASR_BASE_URL"):
        Settings(
            INGESTION_ASR_ENABLED=True,
            INGESTION_ASR_PROVIDER="openai-compat",
            INGESTION_ASR_MODEL_ID="whisper-1",
            ASR_BASE_URL="  ",
        )

    # Endpoint timeout bounds (10 - 21600)
    with pytest.raises(ValueError, match="ASR_ENDPOINT_TIMEOUT_SECONDS"):
        Settings(INGESTION_ASR_ENABLED=True, ASR_ENDPOINT_TIMEOUT_SECONDS=5.0)
    with pytest.raises(ValueError, match="ASR_ENDPOINT_TIMEOUT_SECONDS"):
        Settings(INGESTION_ASR_ENABLED=True, ASR_ENDPOINT_TIMEOUT_SECONDS=30000.0)

    # Video requires ASR enabled
    with pytest.raises(ValueError, match="INGESTION_VIDEO_.* switches require"):
        Settings(INGESTION_ASR_ENABLED=False, INGESTION_VIDEO_ENABLED=True)
    with pytest.raises(ValueError, match="INGESTION_VIDEO_.* switches require"):
        Settings(INGESTION_ASR_ENABLED=False, INGESTION_VIDEO_KEYFRAME_OCR=True)

    # Keyframe OCR requires OCR enabled
    with pytest.raises(ValueError, match="INGESTION_VIDEO_KEYFRAME_OCR requires"):
        Settings(
            INGESTION_ASR_ENABLED=True,
            INGESTION_VIDEO_ENABLED=True,
            INGESTION_VIDEO_KEYFRAME_OCR=True,
            INGESTION_OCR_ENABLED=False,
        )

    # Keyframe interval bounds (0 < interval <= 600)
    with pytest.raises(ValueError, match="INGESTION_VIDEO_KEYFRAME_INTERVAL_SECONDS"):
        Settings(
            INGESTION_ASR_ENABLED=True,
            INGESTION_VIDEO_ENABLED=True,
            INGESTION_VIDEO_KEYFRAME_INTERVAL_SECONDS=0,
        )
    with pytest.raises(ValueError, match="INGESTION_VIDEO_KEYFRAME_INTERVAL_SECONDS"):
        Settings(
            INGESTION_ASR_ENABLED=True,
            INGESTION_VIDEO_ENABLED=True,
            INGESTION_VIDEO_KEYFRAME_INTERVAL_SECONDS=601,
        )

    # Max keyframes bounds (0 <= max <= 200)
    with pytest.raises(ValueError, match="INGESTION_VIDEO_MAX_KEYFRAMES"):
        Settings(
            INGESTION_ASR_ENABLED=True,
            INGESTION_VIDEO_ENABLED=True,
            INGESTION_VIDEO_MAX_KEYFRAMES=-1,
        )
    with pytest.raises(ValueError, match="INGESTION_VIDEO_MAX_KEYFRAMES"):
        Settings(
            INGESTION_ASR_ENABLED=True,
            INGESTION_VIDEO_ENABLED=True,
            INGESTION_VIDEO_MAX_KEYFRAMES=201,
        )
