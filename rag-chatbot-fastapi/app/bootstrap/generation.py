from __future__ import annotations

from dataclasses import dataclass

from redis.asyncio import Redis

from app.bootstrap.configuration import (
    generation_config,
    graph_config,
    index_config,
    model_config,
    retrieval_config,
    storage_config,
)
from app.bootstrap.settings import Settings
from app.common.cache import CacheStore
from app.common.storage import SeaweedS3DocumentStore
from app.modules.generation.api import GenerationApi
from app.modules.generation.internal.calculation import SpreadsheetCalculationCoordinator
from app.modules.generation.internal.generator import (
    GenerationRetrievalAdapter,
    GenerationService,
)
from app.modules.generation.internal.semantic_answer_cache import SemanticAnswerCache
from app.modules.generation.internal.service import RagChatService
from app.modules.graph.internal.service import GraphServiceClient
from app.modules.index.internal.qdrant_search import QdrantKnowledgeIndexQuery
from app.modules.model.api import ChatModelApi, TextEmbeddingApi
from app.modules.model.internal.chat import create_chat_model
from app.modules.model.internal.sparse import FastEmbedSparseEncoder
from app.modules.retrieval.api import RetrievalApi
from app.modules.retrieval.internal.cache import CachedRetriever, RetrievalCacheKeyBuilder
from app.modules.retrieval.internal.reranking import TeiReranker
from app.modules.retrieval.internal.retrieval import HybridRetriever, RetrievalService


@dataclass(slots=True)
class RagRuntime:
    generation: GenerationApi
    retrieval: RetrievalApi
    semantic_answer_cache: SemanticAnswerCache | None

    @classmethod
    def create(
        cls,
        settings: Settings,
        *,
        embedder: TextEmbeddingApi,
        cache_store: CacheStore,
        redis_client: Redis,
    ) -> RagRuntime:
        retrieval_settings = retrieval_config(settings)
        generation_settings = generation_config(settings)
        model_settings = model_config(settings)
        graph = GraphServiceClient(graph_config(settings))
        hybrid = HybridRetriever(
            settings=retrieval_settings,
            index=QdrantKnowledgeIndexQuery(index_config(settings)),
            sparse_encoder=FastEmbedSparseEncoder(model_settings),
            graph=graph,
            reranker=TeiReranker(retrieval_settings),
        )
        cached = CachedRetriever(
            hybrid,
            cache_store=cache_store,
            key_builder=RetrievalCacheKeyBuilder(retrieval_settings),
            ttl_seconds=settings.RETRIEVAL_CACHE_TTL_SECONDS,
            max_results=settings.FINAL_CONTEXT_TOP_K,
            enabled=settings.CACHE_ENABLED and settings.RETRIEVAL_CACHE_ENABLED,
        )
        retrieval = RetrievalService(
            retrieval_settings,
            delegate=cached,
            embedder=embedder,
        )
        semantic: SemanticAnswerCache | None = None
        if settings.CACHE_ENABLED and settings.SEMANTIC_ANSWER_CACHE_MODE in {"shadow", "serve"}:
            semantic = SemanticAnswerCache(
                generation_settings,
                redis_client=redis_client,
                retrieval=retrieval,
            )
        chat_model: ChatModelApi = create_chat_model(model_settings)
        orchestrator = RagChatService(
            settings=generation_settings,
            embedder=embedder,
            retriever=GenerationRetrievalAdapter(retrieval),
            chat_model=chat_model,
            calculations=SpreadsheetCalculationCoordinator(
                SeaweedS3DocumentStore(storage_config(settings)),
                create_chat_model(model_settings),
            ),
            semantic_answer_cache=semantic,
        )
        return cls(
            generation=GenerationService(
                orchestrator, default_locale=settings.DEFAULT_LOCALE
            ),
            retrieval=retrieval,
            semantic_answer_cache=semantic,
        )
