from __future__ import annotations

import hashlib
import json
import math
import time
from collections.abc import Sequence
from datetime import timedelta
from typing import Any, Protocol
from uuid import UUID

from app.common.cache import CacheReadStatus, CacheStore
from app.common.concurrent_loads import AUTHORITATIVE_LOAD_TRACKER, ConcurrentLoadTracker
from app.common.metrics import (
    AI_RETRIEVAL_SECONDS,
    observe_authoritative_duration,
    record_cache_operation,
)
from app.modules.model.api import normalize_embedding_text
from app.modules.retrieval.api import RetrievedKnowledgeUnit as RetrievedChunk
from app.modules.retrieval.internal.config import RetrievalConfig

CACHE_NAME = "retrieval"
RETRIEVAL_CACHE_SCHEMA_VERSION = 1
RETRIEVAL_PIPELINE_VERSION = 2


def retrieval_configuration_fingerprint(settings: RetrievalConfig) -> str:
    configuration = {
        "pipeline_version": RETRIEVAL_PIPELINE_VERSION,
        "qdrant_url": settings.QDRANT_URL,
        "collection": settings.QDRANT_COLLECTION,
        "dense_vector": settings.QDRANT_DENSE_VECTOR_NAME,
        "sparse_vector": settings.QDRANT_SPARSE_VECTOR_NAME,
        "tenant_field": settings.QDRANT_TENANT_FIELD,
        "knowledge_base_field": settings.QDRANT_KNOWLEDGE_BASE_FIELD,
        "embedding_model": settings.TEXT_EMBEDDING_MODEL_ID,
        "embedding_base_url": settings.TEXT_EMBEDDING_BASE_URL,
        "embedding_dimension": settings.TEXT_EMBEDDING_DIMENSION,
        "embedding_normalization_version": 1,
        "sparse_model": settings.SPARSE_MODEL_ID,
        "graph_max_hops": settings.GRAPH_MAX_HOPS,
        "graph_service_url": settings.GRAPH_SERVICE_URL,
        "graph_timeout_seconds": settings.GRAPH_TIMEOUT_SECONDS,
        "candidate_counts": {
            "dense": settings.DENSE_CANDIDATE_COUNT,
            "sparse": settings.SPARSE_CANDIDATE_COUNT,
            "graph": settings.GRAPH_CANDIDATE_COUNT,
            "fusion": settings.FUSION_CANDIDATE_COUNT,
        },
        "rrf_k": settings.RRF_K,
        "weights": {
            "semantic": [
                settings.SEMANTIC_DENSE_WEIGHT,
                settings.SEMANTIC_SPARSE_WEIGHT,
                settings.SEMANTIC_GRAPH_WEIGHT,
            ],
            "exact": [
                settings.EXACT_DENSE_WEIGHT,
                settings.EXACT_SPARSE_WEIGHT,
                settings.EXACT_GRAPH_WEIGHT,
            ],
            "relational": [
                settings.RELATIONAL_DENSE_WEIGHT,
                settings.RELATIONAL_SPARSE_WEIGHT,
                settings.RELATIONAL_GRAPH_WEIGHT,
            ],
            "calculation": [
                settings.CALCULATION_DENSE_WEIGHT,
                settings.CALCULATION_SPARSE_WEIGHT,
                settings.CALCULATION_GRAPH_WEIGHT,
            ],
        },
        "reranker_enabled": settings.RERANKER_ENABLED,
        "reranker_model": settings.RERANKER_MODEL_ID,
        "reranker_url": settings.RERANKER_URL,
        "reranker_timeout_seconds": settings.RERANKER_TIMEOUT_SECONDS,
        "primary_context_top_k": settings.PRIMARY_CONTEXT_TOP_K,
        "final_context_top_k": settings.FINAL_CONTEXT_TOP_K,
        "context_document_soft_limit": settings.CONTEXT_DOCUMENT_SOFT_LIMIT,
        "neighbor_expansion_limit": settings.NEIGHBOR_EXPANSION_LIMIT,
    }
    canonical = json.dumps(
        configuration, sort_keys=True, separators=(",", ":"), ensure_ascii=False
    ).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


class RetrievalClient(Protocol):
    async def retrieve(
        self,
        *,
        tenant_id: str,
        knowledge_base_id: str,
        query_text: str,
        query_vector: Sequence[float],
        document_ids: Sequence[str] | None = None,
    ) -> list[RetrievedChunk]: ...


class RetrievalCacheKeyBuilder:
    def __init__(self, settings: RetrievalConfig) -> None:
        self._prefix = settings.CACHE_KEY_PREFIX.rstrip(":")
        self._configuration_hash = retrieval_configuration_fingerprint(settings)

    def build(
        self,
        *,
        tenant_id: str,
        knowledge_base_id: str,
        revision: int,
        query_text: str,
        document_ids: Sequence[str] | None,
    ) -> str:
        if revision < 0:
            raise ValueError("Knowledge-base revision must not be negative")
        tenant = str(UUID(tenant_id))
        knowledge_base = str(UUID(knowledge_base_id))
        query_hash = hashlib.sha256(
            normalize_embedding_text(query_text).encode("utf-8")
        ).hexdigest()
        if document_ids is None:
            visible_identity = "all"
        else:
            visible = sorted({str(UUID(value)) for value in document_ids})
            canonical_visible = json.dumps(visible, separators=(",", ":")).encode("utf-8")
            visible_identity = hashlib.sha256(canonical_visible).hexdigest()
        return (
            f"{self._prefix}:retrieval:tenant:{tenant}:kb:{knowledge_base}:rev:{revision}:"
            f"visible:{visible_identity}:config:{self._configuration_hash}:query:{query_hash}"
        )


class RetrievedChunkCacheCodec:
    _CHUNK_FIELDS = {
        "document_id",
        "source_name",
        "page_number",
        "chunk_index",
        "text",
        "score",
        "unit_id",
        "modality",
        "section_path",
        "block_type",
        "sheet_name",
        "cell_range",
        "table_id",
    }

    @classmethod
    def encode(cls, chunks: Sequence[RetrievedChunk]) -> bytes:
        payload = {
            "schema_version": RETRIEVAL_CACHE_SCHEMA_VERSION,
            "chunks": [
                {
                    "document_id": chunk.document_id,
                    "source_name": chunk.source_name,
                    "page_number": chunk.page_number,
                    "chunk_index": chunk.chunk_index,
                    "text": chunk.text,
                    "score": chunk.score,
                    "unit_id": chunk.unit_id,
                    "modality": chunk.modality,
                    "section_path": list(chunk.section_path),
                    "block_type": chunk.block_type,
                    "sheet_name": chunk.sheet_name,
                    "cell_range": chunk.cell_range,
                    "table_id": chunk.table_id,
                }
                for chunk in chunks
            ],
        }
        return json.dumps(
            payload,
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
            allow_nan=False,
        ).encode("utf-8")

    @classmethod
    def decode(
        cls,
        payload: bytes,
        *,
        max_results: int,
        allowed_document_ids: Sequence[str] | None,
    ) -> list[RetrievedChunk]:
        try:
            raw = json.loads(payload.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ValueError("Retrieval cache payload is not valid JSON") from exc
        if not isinstance(raw, dict) or set(raw) != {"schema_version", "chunks"}:
            raise ValueError("Retrieval cache envelope is malformed")
        if raw["schema_version"] != RETRIEVAL_CACHE_SCHEMA_VERSION:
            raise ValueError("Retrieval cache schema is unsupported")
        items = raw["chunks"]
        if not isinstance(items, list) or len(items) > max_results:
            raise ValueError("Retrieval cache result count is invalid")
        allowed = (
            {str(UUID(value)) for value in allowed_document_ids}
            if allowed_document_ids is not None
            else None
        )
        return [cls._decode_chunk(item, allowed) for item in items]

    @classmethod
    def _decode_chunk(cls, item: Any, allowed_document_ids: set[str] | None) -> RetrievedChunk:
        if not isinstance(item, dict) or set(item) != cls._CHUNK_FIELDS:
            raise ValueError("Retrieval cache chunk is malformed")
        document_id = str(UUID(cls._required_string(item["document_id"])))
        if allowed_document_ids is not None and document_id not in allowed_document_ids:
            raise ValueError("Retrieval cache chunk is outside the visible document set")
        page_number = cls._optional_integer(item["page_number"], minimum=1)
        chunk_index = cls._required_integer(item["chunk_index"], minimum=0)
        score = item["score"]
        if isinstance(score, bool) or not isinstance(score, int | float):
            raise ValueError("Retrieval cache score is malformed")
        score_value = float(score)
        if not math.isfinite(score_value):
            raise ValueError("Retrieval cache score must be finite")
        section_path = item["section_path"]
        if not isinstance(section_path, list) or not all(
            isinstance(value, str) for value in section_path
        ):
            raise ValueError("Retrieval cache section path is malformed")
        return RetrievedChunk(
            document_id=document_id,
            source_name=cls._required_string(item["source_name"]),
            page_number=page_number,
            chunk_index=chunk_index,
            text=cls._required_string(item["text"], allow_empty=True),
            score=score_value,
            unit_id=cls._optional_string(item["unit_id"]),
            modality=cls._optional_string(item["modality"]),
            section_path=tuple(section_path),
            block_type=cls._optional_string(item["block_type"]),
            sheet_name=cls._optional_string(item["sheet_name"]),
            cell_range=cls._optional_string(item["cell_range"]),
            table_id=cls._optional_string(item["table_id"]),
        )

    @staticmethod
    def _required_string(value: Any, *, allow_empty: bool = False) -> str:
        if not isinstance(value, str) or (not allow_empty and not value):
            raise ValueError("Retrieval cache string field is malformed")
        return value

    @staticmethod
    def _optional_string(value: Any) -> str | None:
        if value is None:
            return None
        if not isinstance(value, str):
            raise ValueError("Retrieval cache optional string field is malformed")
        return value

    @staticmethod
    def _required_integer(value: Any, *, minimum: int) -> int:
        if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
            raise ValueError("Retrieval cache integer field is malformed")
        return value

    @classmethod
    def _optional_integer(cls, value: Any, *, minimum: int) -> int | None:
        if value is None:
            return None
        return cls._required_integer(value, minimum=minimum)


class CachedRetriever:
    def __init__(
        self,
        delegate: RetrievalClient,
        *,
        cache_store: CacheStore,
        revision_store: Any | None = None,
        key_builder: RetrievalCacheKeyBuilder,
        ttl_seconds: int,
        max_results: int,
        enabled: bool,
        concurrent_loads: ConcurrentLoadTracker | None = None,
    ) -> None:
        self._delegate = delegate
        self._cache_store = cache_store
        self._revision_store = revision_store
        self._key_builder = key_builder
        self._ttl = timedelta(seconds=ttl_seconds)
        self._max_results = max_results
        self._enabled = enabled
        self._concurrent_loads = concurrent_loads or AUTHORITATIVE_LOAD_TRACKER

    async def retrieve(
        self,
        *,
        tenant_id: str,
        knowledge_base_id: str,
        query_text: str,
        query_vector: Sequence[float],
        authoritative_revision: int = 0,
        document_ids: Sequence[str] | None = None,
    ) -> list[RetrievedChunk]:
        started = time.perf_counter()
        outcome = "success"
        try:
            if not self._enabled:
                record_cache_operation(CACHE_NAME, "bypass")
                return await self._authoritative(
                    tenant_id=tenant_id,
                    knowledge_base_id=knowledge_base_id,
                    query_text=query_text,
                    query_vector=query_vector,
                    document_ids=document_ids,
                )
            try:
                revision = (
                    await self._revision_store.current_revision(tenant_id, knowledge_base_id)
                    if self._revision_store is not None
                    else authoritative_revision
                )
                key = self._key_builder.build(
                    tenant_id=tenant_id,
                    knowledge_base_id=knowledge_base_id,
                    revision=revision,
                    query_text=query_text,
                    document_ids=document_ids,
                )
            except Exception:
                record_cache_operation(CACHE_NAME, "error")
                return await self._authoritative(
                    tracking_key=self._tracking_key(
                        tenant_id=tenant_id,
                        knowledge_base_id=knowledge_base_id,
                        query_text=query_text,
                        document_ids=document_ids,
                    ),
                    tenant_id=tenant_id,
                    knowledge_base_id=knowledge_base_id,
                    query_text=query_text,
                    query_vector=query_vector,
                    document_ids=document_ids,
                )

            cached = await self._cache_store.get(CACHE_NAME, key)
            if cached.status is CacheReadStatus.HIT:
                try:
                    assert cached.value is not None
                    return RetrievedChunkCacheCodec.decode(
                        cached.value,
                        max_results=self._max_results,
                        allowed_document_ids=document_ids,
                    )
                except ValueError:
                    await self._cache_store.delete(CACHE_NAME, key)

            chunks = await self._authoritative(
                tracking_key=key,
                tenant_id=tenant_id,
                knowledge_base_id=knowledge_base_id,
                query_text=query_text,
                query_vector=query_vector,
                document_ids=document_ids,
            )
            try:
                payload = RetrievedChunkCacheCodec.encode(chunks)
                RetrievedChunkCacheCodec.decode(
                    payload,
                    max_results=self._max_results,
                    allowed_document_ids=document_ids,
                )
            except (TypeError, ValueError):
                return chunks
            await self._cache_store.put(CACHE_NAME, key, payload, self._ttl)
            return chunks
        except Exception:
            outcome = "error"
            raise
        finally:
            AI_RETRIEVAL_SECONDS.labels(provider="hybrid", outcome=outcome).observe(
                time.perf_counter() - started
            )

    async def _authoritative(
        self,
        *,
        tracking_key: str | None = None,
        tenant_id: str,
        knowledge_base_id: str,
        query_text: str,
        query_vector: Sequence[float],
        document_ids: Sequence[str] | None,
    ) -> list[RetrievedChunk]:
        scope = self._concurrent_loads.observe(CACHE_NAME, tracking_key) if tracking_key else None
        try:
            started = time.perf_counter()
            outcome = "success"
            try:
                return await self._delegate.retrieve(
                    tenant_id=tenant_id,
                    knowledge_base_id=knowledge_base_id,
                    query_text=query_text,
                    query_vector=query_vector,
                    document_ids=document_ids,
                )
            except Exception:
                outcome = "error"
                raise
            finally:
                observe_authoritative_duration(CACHE_NAME, outcome, time.perf_counter() - started)
        finally:
            if scope is not None:
                scope.close()

    def _tracking_key(
        self,
        *,
        tenant_id: str,
        knowledge_base_id: str,
        query_text: str,
        document_ids: Sequence[str] | None,
    ) -> str:
        visible = ",".join(sorted(document_ids)) if document_ids is not None else "all"
        return "\x1f".join((tenant_id, knowledge_base_id, query_text, visible))
