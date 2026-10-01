from __future__ import annotations

from app.bootstrap.configuration import graph_config, index_config, model_config, storage_config
from app.bootstrap.settings import Settings
from app.common.storage import SeaweedS3DocumentStore
from app.modules.graph.internal.service import GraphServiceClient
from app.modules.index.internal.qdrant_commands import QdrantKnowledgeIndex
from app.modules.ingestion.internal.chunking import DeterministicChunker
from app.modules.ingestion.internal.entity_extraction import EntityRelationExtractor
from app.modules.ingestion.internal.extraction import DocumentTextExtractor
from app.modules.ingestion.internal.pipeline import DocumentIngestionPipeline
from app.modules.model.internal.chat import create_chat_model
from app.modules.model.internal.embedding import EmbeddingClient, OllamaEmbeddingClient
from app.modules.model.internal.sparse import FastEmbedSparseEncoder


def create_document_ingestion_pipeline(
    settings: Settings,
    *,
    embedder: EmbeddingClient | None = None,
) -> DocumentIngestionPipeline:
    extraction_settings = settings.model_copy(
        update={"LLM_MAX_OUTPUT_TOKENS": settings.GRAPH_EXTRACTION_MAX_OUTPUT_TOKENS}
    )
    return DocumentIngestionPipeline(
        store=SeaweedS3DocumentStore(storage_config(settings)),
        extractor=DocumentTextExtractor(),
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
        ),
    )
