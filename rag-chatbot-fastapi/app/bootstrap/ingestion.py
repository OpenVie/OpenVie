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

from __future__ import annotations

from app.bootstrap.configuration import graph_config, index_config, model_config, storage_config
from app.bootstrap.settings import Settings
from app.common.storage import SeaweedS3DocumentStore
from app.modules.graph.internal.service import GraphServiceClient
from app.modules.index.internal.qdrant_commands import QdrantKnowledgeIndex
from app.modules.ingestion.internal.chunking import DeterministicChunker
from app.modules.ingestion.internal.entity_extraction import EntityRelationExtractor
from app.modules.ingestion.internal.extraction import DocumentTextExtractor
from app.modules.ingestion.internal.ocr import (
    OcrIngestionService,
    OllamaVlmOcrProvider,
    TesseractOcrProvider,
)
from app.modules.ingestion.internal.pipeline import DocumentIngestionPipeline
from app.modules.ingestion.internal.transcription import (
    AsrIngestionService,
    FasterWhisperTranscriptionProvider,
    OpenAiCompatibleAsrProvider,
)
from app.modules.model.internal.chat import create_chat_model
from app.modules.model.internal.embedding import EmbeddingClient, OllamaEmbeddingClient
from app.modules.model.internal.sparse import FastEmbedSparseEncoder


def create_ocr_service(settings: Settings) -> OcrIngestionService | None:
    """Build the OCR adapter from settings; ``None`` keeps the text-only path."""
    if not settings.INGESTION_OCR_ENABLED:
        return None
    if settings.INGESTION_OCR_PROVIDER == "tesseract":
        provider: TesseractOcrProvider | OllamaVlmOcrProvider = TesseractOcrProvider(
            languages=settings.INGESTION_OCR_LANGUAGES,
            psm=settings.INGESTION_OCR_PSM,
            timeout_seconds=settings.INGESTION_OCR_TIMEOUT_SECONDS_PER_PAGE,
        )
    else:
        provider = OllamaVlmOcrProvider(
            base_url=settings.OCR_VLM_BASE_URL,
            model_id=settings.OCR_VLM_MODEL_ID,
            timeout_seconds=settings.OCR_VLM_TIMEOUT_SECONDS,
            max_output_tokens=settings.OCR_VLM_MAX_OUTPUT_TOKENS,
        )
    return OcrIngestionService(
        provider,
        dpi=settings.INGESTION_OCR_DPI,
        min_text_chars=settings.INGESTION_OCR_MIN_TEXT_CHARS_PER_PAGE,
        max_pages=settings.INGESTION_OCR_MAX_PAGES,
        max_megapixels_per_page=settings.INGESTION_OCR_MAX_MEGAPIXELS_PER_PAGE,
        timeout_seconds_per_page=settings.INGESTION_OCR_TIMEOUT_SECONDS_PER_PAGE,
        page_concurrency=settings.INGESTION_OCR_PAGE_CONCURRENCY,
        embedded_images=settings.INGESTION_OCR_EMBEDDED_IMAGES,
    )


def create_asr_service(settings: Settings) -> AsrIngestionService | None:
    """Build the ASR adapter from settings; ``None`` keeps the text-only path."""
    if not settings.INGESTION_ASR_ENABLED:
        return None
    if settings.INGESTION_ASR_PROVIDER == "faster-whisper":
        provider: FasterWhisperTranscriptionProvider | OpenAiCompatibleAsrProvider = (
            FasterWhisperTranscriptionProvider(
                model_id=settings.INGESTION_ASR_MODEL_ID,
                device=settings.INGESTION_ASR_DEVICE,
                compute_type=settings.INGESTION_ASR_COMPUTE_TYPE,
                language=settings.INGESTION_ASR_LANGUAGE,
                vad_filter=settings.INGESTION_ASR_VAD_FILTER,
            )
        )
    else:
        provider = OpenAiCompatibleAsrProvider(
            base_url=settings.ASR_BASE_URL,
            model_id=settings.INGESTION_ASR_MODEL_ID,
            timeout_seconds=settings.ASR_ENDPOINT_TIMEOUT_SECONDS,
            language=settings.INGESTION_ASR_LANGUAGE,
        )
    return AsrIngestionService(
        provider,
        timestamp_bucket_seconds=settings.INGESTION_ASR_TIMESTAMP_BUCKET_SECONDS,
        max_duration_seconds=settings.INGESTION_ASR_MAX_DURATION_SECONDS,
        timeout_seconds=settings.INGESTION_ASR_TIMEOUT_SECONDS,
        keyframe_interval_seconds=settings.INGESTION_VIDEO_KEYFRAME_INTERVAL_SECONDS,
        max_keyframes=settings.INGESTION_VIDEO_MAX_KEYFRAMES,
    )


def create_document_ingestion_pipeline(
    settings: Settings,
    *,
    embedder: EmbeddingClient | None = None,
) -> DocumentIngestionPipeline:
    extraction_settings = settings.model_copy(
        update={"LLM_MAX_OUTPUT_TOKENS": settings.GRAPH_EXTRACTION_MAX_OUTPUT_TOKENS}
    )
    ocr_service = create_ocr_service(settings)
    asr_service = create_asr_service(settings)
    return DocumentIngestionPipeline(
        store=SeaweedS3DocumentStore(storage_config(settings)),
        extractor=DocumentTextExtractor(
            ocr=ocr_service,
            asr=asr_service,
            video_enabled=settings.INGESTION_VIDEO_ENABLED,
            video_keyframe_ocr=settings.INGESTION_VIDEO_KEYFRAME_OCR,
        ),
        chunker=DeterministicChunker(),
        embedder=embedder or OllamaEmbeddingClient(model_config(settings)),
        sparse_encoder=FastEmbedSparseEncoder(model_config(settings)),
        vector_store=QdrantKnowledgeIndex(index_config(settings)),
        graph_store=GraphServiceClient(graph_config(settings)),
        graph_extractor=EntityRelationExtractor(
            create_chat_model(
                model_config(extraction_settings),
                reasoning_effort=settings.GRAPH_EXTRACTION_REASONING_EFFORT,
            ),
            batch_size=settings.GRAPH_EXTRACTION_BATCH_SIZE,
            max_concurrency=settings.INGESTION_WORKER_CONCURRENCY,
            max_entities_per_unit=settings.GRAPH_EXTRACTION_MAX_ENTITIES_PER_UNIT,
            max_relations_per_unit=settings.GRAPH_EXTRACTION_MAX_RELATIONS_PER_UNIT,
        ),
        graph_extraction_enabled=settings.GRAPH_EXTRACTION_ENABLED,
        ocr=ocr_service,
        asr=asr_service,
    )
