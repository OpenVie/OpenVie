from __future__ import annotations

import asyncio
import re
import time
from collections import Counter
from collections.abc import Sequence
from dataclasses import dataclass, replace
from typing import Any

from app.common.metrics import (
    AI_CONTEXT_UNITS,
    AI_FUSION_CANDIDATES,
    AI_RETRIEVAL_CHANNEL_RESULTS,
    AI_RETRIEVAL_CHANNEL_SECONDS,
    AI_RETRIEVAL_FALLBACKS_TOTAL,
    AI_ROUTER_PROFILES_TOTAL,
)
from app.modules.graph.api import GraphQueryApi, GraphSearchQuery
from app.modules.index.api import (
    IndexSparseVector,
    KnowledgeIndexQuery,
    KnowledgeIndexQueryApi,
    NeighborQuery,
    SparseKnowledgeIndexQuery,
)
from app.modules.model.api import SparseEmbeddingApi, TextEmbeddingApi
from app.modules.retrieval.api import (
    QueryProfile,
    RetrievalApi,
    RetrievalFingerprint,
    RetrievalPlan,
    RetrievalQuery,
)
from app.modules.retrieval.api import (
    RetrievedKnowledgeUnit as RetrievedChunk,
)
from app.modules.retrieval.internal.cache import retrieval_configuration_fingerprint
from app.modules.retrieval.internal.config import RetrievalConfig
from app.modules.retrieval.internal.reranking import Reranker


@dataclass(frozen=True, slots=True)
class RetrievalWeights:
    dense: float
    sparse: float
    graph: float


class QueryRouter:
    """Deterministic Vietnamese-friendly router with explicit precedence."""

    _CALCULATION = re.compile(
        r"(?:\b(?:sum|total|average|avg|count|minimum|maximum|top|bottom)\b|"
        r"tính|tổng|trung bình|đếm|lớn nhất|nhỏ nhất|cao nhất|thấp nhất|"
        r"phần trăm|tỷ lệ|doanh thu|chi phí|bảng tính|spreadsheet|excel)",
        re.IGNORECASE,
    )
    _RELATIONAL = re.compile(
        r"(?:quan hệ|liên quan|kết nối|thuộc về|phụ thuộc|giữa .+ và|"
        r"relationship|related to|connected to|depends on|between .+ and)",
        re.IGNORECASE,
    )
    _QUOTED = re.compile(r"[\"“”'‘’][^\"“”'‘’]{2,}[\"“”'‘’]")
    _IDENTIFIER = re.compile(
        r"(?:\b[A-Z]{2,}[\-_]?\d+[A-Z0-9\-_]*\b|\b\d+[A-Z][A-Z0-9\-_]*\b|"
        r"\b(?:SKU|MÃ|CODE|ID|MST|SĐT|PHONE|EMAIL)\s*[:#-]?\s*\S+)",
        re.IGNORECASE,
    )
    _PRICE = re.compile(
        r"(?:\b\d[\d.,]*\s*(?:₫|đ|vnd|usd|eur|dollar|triệu|nghìn|ngàn)\b|"
        r"(?:giá|price|cost)\s+(?:của|of|là|is)?)",
        re.IGNORECASE,
    )

    def __init__(self, settings: RetrievalConfig):
        self._weights = {
            QueryProfile.SEMANTIC: RetrievalWeights(
                settings.SEMANTIC_DENSE_WEIGHT,
                settings.SEMANTIC_SPARSE_WEIGHT,
                settings.SEMANTIC_GRAPH_WEIGHT,
            ),
            QueryProfile.EXACT: RetrievalWeights(
                settings.EXACT_DENSE_WEIGHT,
                settings.EXACT_SPARSE_WEIGHT,
                settings.EXACT_GRAPH_WEIGHT,
            ),
            QueryProfile.RELATIONAL: RetrievalWeights(
                settings.RELATIONAL_DENSE_WEIGHT,
                settings.RELATIONAL_SPARSE_WEIGHT,
                settings.RELATIONAL_GRAPH_WEIGHT,
            ),
            QueryProfile.CALCULATION: RetrievalWeights(
                settings.CALCULATION_DENSE_WEIGHT,
                settings.CALCULATION_SPARSE_WEIGHT,
                settings.CALCULATION_GRAPH_WEIGHT,
            ),
        }

    def route(self, query_text: str) -> QueryProfile:
        if self._CALCULATION.search(query_text):
            return QueryProfile.CALCULATION
        if self._RELATIONAL.search(query_text):
            return QueryProfile.RELATIONAL
        if (
            self._QUOTED.search(query_text)
            or self._IDENTIFIER.search(query_text)
            or self._PRICE.search(query_text)
        ):
            return QueryProfile.EXACT
        return QueryProfile.SEMANTIC

    def weights(self, profile: QueryProfile) -> RetrievalWeights:
        return self._weights[profile]


class HybridRetriever:
    def __init__(
        self,
        *,
        settings: RetrievalConfig,
        index: KnowledgeIndexQueryApi | None = None,
        sparse_encoder: SparseEmbeddingApi | None = None,
        graph: GraphQueryApi,
        reranker: Reranker | None = None,
        router: QueryRouter | None = None,
        dense: Any | None = None,
        sparse: Any | None = None,
        neighbor_loader: Any | None = None,
    ):
        self._settings = settings
        self._index = index
        self._sparse_encoder = sparse_encoder
        self._legacy_dense = dense
        self._legacy_sparse = sparse
        self._legacy_neighbors = neighbor_loader
        self._graph = graph
        self._reranker = reranker
        self._router = router or QueryRouter(settings)

    async def retrieve(
        self,
        *,
        tenant_id: str,
        knowledge_base_id: str,
        query_text: str,
        query_vector: Sequence[float],
        document_ids: Sequence[str] | None = None,
    ) -> list[RetrievedChunk]:
        profile = self._router.route(query_text)
        weights = self._router.weights(profile)
        AI_ROUTER_PROFILES_TOTAL.labels(profile=profile.value).inc()
        sparse_vector = (
            await self._sparse_encoder.embed_query(query_text)
            if self._sparse_encoder is not None
            else None
        )

        channel_results = await asyncio.gather(
            self._timed_channel(
                "dense",
                (
                    self._legacy_dense.retrieve(
                        tenant_id=tenant_id,
                        knowledge_base_id=knowledge_base_id,
                        query_text=query_text,
                        query_vector=query_vector,
                        limit=self._settings.DENSE_CANDIDATE_COUNT,
                        document_ids=document_ids,
                    )
                    if self._legacy_dense is not None
                    else self._dense_results(
                        tenant_id, knowledge_base_id, query_vector, document_ids
                    )
                ),
            ),
            self._timed_channel(
                "sparse",
                (
                    self._legacy_sparse.retrieve(
                        tenant_id=tenant_id,
                        knowledge_base_id=knowledge_base_id,
                        query_text=query_text,
                        limit=self._settings.SPARSE_CANDIDATE_COUNT,
                        document_ids=document_ids,
                    )
                    if self._legacy_sparse is not None
                    else self._sparse_results(
                        tenant_id,
                        knowledge_base_id,
                        sparse_vector,
                        document_ids,
                    )
                ),
            ),
            self._timed_channel(
                "graph",
                self._graph_results(
                    tenant_id=tenant_id,
                    knowledge_base_id=knowledge_base_id,
                    query_text=query_text,
                    document_ids=document_ids,
                ),
            ),
            return_exceptions=True,
        )
        normalized: list[list[RetrievedChunk]] = []
        for channel, result in zip(("dense", "sparse", "graph"), channel_results, strict=True):
            if isinstance(result, BaseException):
                AI_RETRIEVAL_FALLBACKS_TOTAL.labels(component=channel).inc()
                normalized.append([])
            else:
                normalized.append(result)
        dense_results, sparse_results, graph_results = normalized
        fused = weighted_reciprocal_rank_fusion(
            dense_results,
            sparse_results,
            graph_results,
            weights=weights,
            limit=self._settings.FUSION_CANDIDATE_COUNT,
            k=self._settings.RRF_K,
        )
        AI_FUSION_CANDIDATES.observe(len(fused))
        reranked = await self._rerank(query_text, fused)
        primary = select_diverse_evidence(
            reranked,
            limit=self._settings.PRIMARY_CONTEXT_TOP_K,
            per_document_soft_limit=self._settings.CONTEXT_DOCUMENT_SOFT_LIMIT,
        )
        final = await self._expand_neighbors(
            tenant_id=tenant_id,
            knowledge_base_id=knowledge_base_id,
            primary=primary,
            document_ids=document_ids,
        )
        AI_CONTEXT_UNITS.observe(len(final))
        return final

    async def _timed_channel(self, channel: str, operation: Any) -> list[RetrievedChunk]:
        started_at = time.perf_counter()
        outcome = "success"
        try:
            result = await operation
            AI_RETRIEVAL_CHANNEL_RESULTS.labels(channel=channel).observe(len(result))
            return result
        except Exception:
            outcome = "error"
            raise
        finally:
            AI_RETRIEVAL_CHANNEL_SECONDS.labels(channel=channel, outcome=outcome).observe(
                time.perf_counter() - started_at
            )

    async def _dense_results(
        self,
        tenant_id: str,
        knowledge_base_id: str,
        query_vector: Sequence[float],
        document_ids: Sequence[str] | None,
    ) -> list[RetrievedChunk]:
        assert self._index is not None
        rows = await self._index.search_dense(
            KnowledgeIndexQuery(
                tenant_id=tenant_id,
                knowledge_base_id=knowledge_base_id,
                query_vector=tuple(float(value) for value in query_vector),
                limit=self._settings.DENSE_CANDIDATE_COUNT,
                document_ids=(tuple(document_ids) if document_ids is not None else None),
            )
        )
        return [_retrieved(row) for row in rows]

    async def _sparse_results(
        self,
        tenant_id: str,
        knowledge_base_id: str,
        query_vector: Any,
        document_ids: Sequence[str] | None,
    ) -> list[RetrievedChunk]:
        assert self._index is not None
        rows = await self._index.search_sparse(
            SparseKnowledgeIndexQuery(
                tenant_id=tenant_id,
                knowledge_base_id=knowledge_base_id,
                query_vector=IndexSparseVector(
                    indices=tuple(query_vector.indices), values=tuple(query_vector.values)
                ),
                limit=self._settings.SPARSE_CANDIDATE_COUNT,
                document_ids=(tuple(document_ids) if document_ids is not None else None),
            )
        )
        return [_retrieved(row) for row in rows]

    async def _graph_results(
        self,
        *,
        tenant_id: str,
        knowledge_base_id: str,
        query_text: str,
        document_ids: Sequence[str] | None,
    ) -> list[RetrievedChunk]:
        rows = await self._graph.search(
            GraphSearchQuery(
                tenant_id=tenant_id,
                knowledge_base_id=knowledge_base_id,
                query=query_text,
                limit=self._settings.GRAPH_CANDIDATE_COUNT,
                max_hops=self._settings.GRAPH_MAX_HOPS,
                document_ids=(tuple(document_ids) if document_ids is not None else None),
            )
        )
        # Keep a caller-side guard for mixed-version deployments and defensive isolation.
        allowed = set(document_ids) if document_ids is not None else None
        return [
            _retrieved(row)
            for row in rows
            if allowed is None
            or (row.get("document_id") if isinstance(row, dict) else row.document_id) in allowed
        ]

    async def _rerank(
        self, query_text: str, candidates: list[RetrievedChunk]
    ) -> list[RetrievedChunk]:
        if not candidates or self._reranker is None or not self._settings.RERANKER_ENABLED:
            return candidates
        try:
            return await self._reranker.rerank(query_text, candidates)
        except Exception:
            AI_RETRIEVAL_FALLBACKS_TOTAL.labels(component="reranker").inc()
            return candidates

    async def _expand_neighbors(
        self,
        *,
        tenant_id: str,
        knowledge_base_id: str,
        primary: list[RetrievedChunk],
        document_ids: Sequence[str] | None,
    ) -> list[RetrievedChunk]:
        final = list(primary[: self._settings.FINAL_CONTEXT_TOP_K])
        seen = {_identity(item) for item in final}
        added = 0
        for item in primary:
            if (
                added >= self._settings.NEIGHBOR_EXPANSION_LIMIT
                or len(final) >= self._settings.FINAL_CONTEXT_TOP_K
            ):
                break
            try:
                if self._legacy_neighbors is not None:
                    neighbors = await self._legacy_neighbors.load(
                        tenant_id=tenant_id,
                        knowledge_base_id=knowledge_base_id,
                        primary=item,
                        document_ids=document_ids,
                    )
                else:
                    assert self._index is not None
                    rows = await self._index.load_neighbors(
                        NeighborQuery(
                            tenant_id=tenant_id,
                            knowledge_base_id=knowledge_base_id,
                            document_id=item.document_id,
                            chunk_index=item.chunk_index,
                            section_path=item.section_path,
                            document_ids=(
                                tuple(document_ids) if document_ids is not None else None
                            ),
                        )
                    )
                    neighbors = [_retrieved(row) for row in rows]
            except Exception:
                AI_RETRIEVAL_FALLBACKS_TOTAL.labels(component="neighbors").inc()
                continue
            for neighbor in neighbors:
                key = _identity(neighbor)
                if key in seen:
                    continue
                final.append(neighbor)
                seen.add(key)
                added += 1
                if (
                    added >= self._settings.NEIGHBOR_EXPANSION_LIMIT
                    or len(final) >= self._settings.FINAL_CONTEXT_TOP_K
                ):
                    break
        return final


def weighted_reciprocal_rank_fusion(
    dense: Sequence[RetrievedChunk],
    sparse: Sequence[RetrievedChunk],
    graph: Sequence[RetrievedChunk],
    *,
    weights: RetrievalWeights,
    limit: int = 30,
    k: int = 30,
) -> list[RetrievedChunk]:
    scores: dict[tuple[str, str], float] = {}
    chunks: dict[tuple[str, str], RetrievedChunk] = {}
    for weight, results in (
        (weights.dense, dense),
        (weights.sparse, sparse),
        (weights.graph, graph),
    ):
        for rank, chunk in enumerate(results, start=1):
            key = _identity(chunk)
            scores[key] = scores.get(key, 0.0) + weight / (k + rank)
            chunks.setdefault(key, chunk)
    ordered = sorted(scores, key=lambda key: (-scores[key], key))[:limit]
    return [replace(chunks[key], score=scores[key]) for key in ordered]


def select_diverse_evidence(
    candidates: Sequence[RetrievedChunk], *, limit: int = 5, per_document_soft_limit: int = 2
) -> list[RetrievedChunk]:
    selected: list[RetrievedChunk] = []
    deferred: list[RetrievedChunk] = []
    counts: Counter[str] = Counter()
    for candidate in candidates:
        if counts[candidate.document_id] < per_document_soft_limit:
            selected.append(candidate)
            counts[candidate.document_id] += 1
            if len(selected) == limit:
                return selected
        else:
            deferred.append(candidate)
    for candidate in deferred:
        selected.append(candidate)
        if len(selected) == limit:
            break
    return selected


def _identity(chunk: RetrievedChunk) -> tuple[str, str]:
    return chunk.document_id, chunk.unit_id or str(chunk.chunk_index)


def _retrieved(row: Any) -> RetrievedChunk:
    if isinstance(row, dict):
        return RetrievedChunk(
            document_id=str(row["document_id"]),
            source_name=str(row["source_name"]),
            page_number=(int(row["page_number"]) if row.get("page_number") is not None else None),
            chunk_index=int(row.get("chunk_index", 0)),
            text=str(row["text"]),
            score=float(row.get("score", 1.0)),
            unit_id=str(row["unit_id"]),
            modality=str(row.get("modality", "document")),
            section_path=tuple(str(item) for item in row.get("section_path", [])),
            block_type=str(row["block_type"]) if row.get("block_type") else None,
            sheet_name=str(row["sheet_name"]) if row.get("sheet_name") else None,
            cell_range=str(row["cell_range"]) if row.get("cell_range") else None,
            table_id=str(row["table_id"]) if row.get("table_id") else None,
        )
    return RetrievedChunk(
        document_id=str(row.document_id),
        source_name=str(row.source_name),
        page_number=row.page_number,
        chunk_index=int(row.chunk_index),
        text=str(row.text),
        score=float(row.score),
        unit_id=row.unit_id,
        modality=row.modality,
        section_path=tuple(row.section_path),
        block_type=row.block_type,
        sheet_name=row.sheet_name,
        cell_range=row.cell_range,
        table_id=row.table_id,
    )


class RetrievalService(RetrievalApi):
    def __init__(
        self,
        settings: RetrievalConfig,
        *,
        delegate: Any,
        embedder: TextEmbeddingApi,
        router: QueryRouter | None = None,
    ) -> None:
        self._delegate = delegate
        self._embedder = embedder
        self._router = router or QueryRouter(settings)
        self._fingerprint = retrieval_configuration_fingerprint(settings)

    def plan(self, query_text: str) -> RetrievalPlan:
        return RetrievalPlan(
            RetrievalFingerprint(
                profile=self._router.route(query_text), configuration=self._fingerprint
            )
        )

    async def retrieve(self, query: RetrievalQuery) -> list[RetrievedChunk]:
        vector = (
            list(query.query_vector)
            if query.query_vector is not None
            else await self._embedder.embed_query(query.query_text)
        )
        return await self._delegate.retrieve(
            tenant_id=query.tenant_id,
            knowledge_base_id=query.knowledge_base_id,
            query_text=query.query_text,
            query_vector=vector,
            authoritative_revision=query.authoritative_revision,
            document_ids=query.document_ids,
        )
