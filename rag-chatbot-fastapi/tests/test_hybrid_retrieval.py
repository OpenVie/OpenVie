from __future__ import annotations

from collections.abc import Sequence
from types import SimpleNamespace
from typing import Any

import pytest

from app.bootstrap.settings import Settings
from app.modules.generation.internal.models import RetrievedChunk
from app.modules.ingestion.internal.chunking import DeterministicChunker
from app.modules.ingestion.internal.extraction import KnowledgeBlock, ParsedDocument
from app.modules.retrieval.internal.reranking import TeiReranker
from app.modules.retrieval.internal.retrieval import (
    HybridRetriever,
    QueryProfile,
    QueryRouter,
    RetrievalWeights,
    select_diverse_evidence,
    weighted_reciprocal_rank_fusion,
)


def chunk(document: str, unit: str, score: float = 1.0) -> RetrievedChunk:
    return RetrievedChunk(
        document_id=document,
        source_name=f"{document}.txt",
        page_number=1,
        chunk_index=int(unit.removeprefix("u")),
        text=f"evidence {document} {unit}",
        score=score,
        unit_id=unit,
        modality="document",
        section_path=("Policy",),
        block_type="paragraph",
    )


def test_structural_chunks_use_zero_overlap_and_repeat_table_headers() -> None:
    chunker = DeterministicChunker(chunk_size=24, overlap=8)
    table = KnowledgeBlock(
        unit_id="table",
        block_type="table",
        text="name | price\n-----|------\napple | 10000\nbanana | 20000\norange | 30000",
    )

    chunks = chunker.chunk(ParsedDocument("document", (table,)))

    assert len(chunks) >= 3
    assert all(item.text.startswith("name | price") for item in chunks)
    assert chunks[0].unit_id == "table:0"
    assert chunks == chunker.chunk(ParsedDocument("document", (table,)))


def test_code_chunks_split_on_lines_without_character_overlap() -> None:
    chunker = DeterministicChunker(chunk_size=15, overlap=6)
    block = KnowledgeBlock(
        unit_id="code",
        block_type="code",
        text="first_line\nsecond_line\nthird_line",
    )

    chunks = chunker.chunk(ParsedDocument("document", (block,)))

    assert [item.text for item in chunks] == ["first_line", "second_line", "third_line"]


def test_router_uses_required_precedence() -> None:
    router = QueryRouter(Settings())

    assert router.route('Tính tổng giá cho mã "SKU-42"') is QueryProfile.CALCULATION
    assert router.route('Quan hệ giữa "A" và "B" là gì?') is QueryProfile.RELATIONAL
    assert router.route("Giá của mã SKU-42 là bao nhiêu?") is QueryProfile.EXACT
    assert router.route("Chính sách đổi trả như thế nào?") is QueryProfile.SEMANTIC


def test_weighted_rrf_deduplicates_by_document_and_unit() -> None:
    shared_dense = chunk("doc-a", "u1")
    shared_sparse = chunk("doc-a", "u1", 0.4)
    sparse_only = chunk("doc-b", "u2")

    fused = weighted_reciprocal_rank_fusion(
        [shared_dense],
        [sparse_only, shared_sparse],
        [],
        weights=RetrievalWeights(dense=0.25, sparse=0.60, graph=0.15),
        k=30,
        limit=30,
    )

    assert [(item.document_id, item.unit_id) for item in fused] == [
        ("doc-a", "u1"),
        ("doc-b", "u2"),
    ]


def test_diversity_selection_soft_limits_each_document_then_fills() -> None:
    candidates = [
        chunk("doc-a", "u1"),
        chunk("doc-a", "u2"),
        chunk("doc-a", "u3"),
        chunk("doc-b", "u4"),
        chunk("doc-c", "u5"),
        chunk("doc-a", "u6"),
    ]

    selected = select_diverse_evidence(candidates, limit=5, per_document_soft_limit=2)

    assert [(item.document_id, item.unit_id) for item in selected] == [
        ("doc-a", "u1"),
        ("doc-a", "u2"),
        ("doc-b", "u4"),
        ("doc-c", "u5"),
        ("doc-a", "u3"),
    ]


class FakeResponse:
    def raise_for_status(self) -> None:
        return None

    def json(self) -> list[dict[str, float | int]]:
        return [
            {"index": 1, "score": 0.9},
            {"index": 0, "score": 0.4},
            {"index": 2, "score": 0.4},
        ]


class FakeHttpClient:
    last_json: dict[str, Any]

    def __init__(self, timeout: float):
        self.timeout = timeout

    async def __aenter__(self) -> FakeHttpClient:
        return self

    async def __aexit__(self, *args: object) -> None:
        return None

    async def post(self, url: str, json: dict[str, Any]) -> FakeResponse:
        self.url = url
        FakeHttpClient.last_json = json
        return FakeResponse()


@pytest.mark.asyncio
async def test_tei_reranker_maps_scores_and_keeps_equal_scores_deterministic(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        "app.modules.retrieval.internal.reranking.httpx.AsyncClient", FakeHttpClient
    )
    reranker = TeiReranker(
        Settings(
            RERANKER_URL="http://reranker",
            RERANKER_MODEL_ID="Alibaba-NLP/gte-multilingual-reranker-base",
            RERANKER_TIMEOUT_SECONDS=3,
        )
    )
    candidates: Sequence[RetrievedChunk] = [
        chunk("doc-a", "u1"),
        chunk("doc-b", "u2"),
        chunk("doc-c", "u3"),
    ]

    results = await reranker.rerank("xin chào", candidates)

    assert [item.unit_id for item in results] == ["u2", "u1", "u3"]
    assert results[0].score == 0.9
    assert FakeHttpClient.last_json == {
        "query": "xin chào",
        "texts": [item.text for item in candidates],
        "truncate": True,
    }


def test_sparse_query_uses_named_vector_and_independent_filters() -> None:
    settings = Settings()
    assert settings.QDRANT_DENSE_VECTOR_NAME == "text_bge_m3_v1"
    assert settings.QDRANT_SPARSE_VECTOR_NAME == "text_bm25_v1"
    assert SimpleNamespace(value=settings.RRF_K).value == 30


class FakeDenseRetriever:
    def __init__(self, results: list[RetrievedChunk]):
        self.results = results
        self.calls: list[dict[str, Any]] = []

    async def retrieve(self, **kwargs: Any) -> list[RetrievedChunk]:
        self.calls.append(kwargs)
        return self.results


class FailingSparseRetriever:
    async def retrieve(self, **kwargs: Any) -> list[RetrievedChunk]:
        del kwargs
        raise RuntimeError("sparse unavailable")


class FakeGraphClient:
    def __init__(self) -> None:
        self.requests: list[Any] = []

    async def search(self, request: Any) -> list[dict[str, Any]]:
        self.requests.append(request)
        return [
            {
                "document_id": "blocked-doc",
                "source_name": "blocked.txt",
                "unit_id": "u9",
                "text": "blocked",
                "score": 5,
            }
        ]


class FakeNeighborLoader:
    async def load(self, *, primary: RetrievedChunk, **kwargs: Any) -> list[RetrievedChunk]:
        del kwargs
        return [chunk(primary.document_id, f"u{primary.chunk_index + 20}")]


@pytest.mark.asyncio
async def test_hybrid_retrieval_falls_back_and_caps_expanded_context() -> None:
    dense = FakeDenseRetriever(
        [
            chunk("doc-a", "u1"),
            chunk("doc-a", "u2"),
            chunk("doc-a", "u3"),
            chunk("doc-b", "u4"),
            chunk("doc-c", "u5"),
            chunk("doc-d", "u6"),
        ]
    )
    graph = FakeGraphClient()
    settings = Settings(
        RERANKER_ENABLED=False,
        PRIMARY_CONTEXT_TOP_K=5,
        FINAL_CONTEXT_TOP_K=8,
        NEIGHBOR_EXPANSION_LIMIT=3,
    )
    retriever = HybridRetriever(
        settings=settings,
        dense=dense,  # type: ignore[arg-type]
        sparse=FailingSparseRetriever(),  # type: ignore[arg-type]
        graph=graph,  # type: ignore[arg-type]
        neighbor_loader=FakeNeighborLoader(),  # type: ignore[arg-type]
    )

    results = await retriever.retrieve(
        tenant_id="tenant-1",
        knowledge_base_id="kb-1",
        query_text="chính sách",
        query_vector=[0.1],
        document_ids=["doc-a", "doc-b", "doc-c", "doc-d"],
    )

    assert len(results) == 8
    assert all(item.document_id != "blocked-doc" for item in results)
    assert dense.calls[0]["document_ids"] == ["doc-a", "doc-b", "doc-c", "doc-d"]
    assert graph.requests[0].tenant_id == "tenant-1"
    assert graph.requests[0].knowledge_base_id == "kb-1"
    assert graph.requests[0].max_hops == settings.GRAPH_MAX_HOPS
    assert graph.requests[0].document_ids == ("doc-a", "doc-b", "doc-c", "doc-d")
