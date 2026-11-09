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

from typing import Any

import pytest

from app.bootstrap.settings import Settings
from app.modules.retrieval.api import RetrievedKnowledgeUnit
from app.modules.retrieval.internal.cache import (
    RetrievalCacheKeyBuilder,
    retrieval_configuration_fingerprint,
)
from app.modules.retrieval.internal.retrieval import (
    HybridRetriever,
)


def _chunk(doc_id: str, unit_id: str) -> RetrievedKnowledgeUnit:
    return RetrievedKnowledgeUnit(
        document_id=doc_id,
        source_name=f"{doc_id}.txt",
        page_number=1,
        chunk_index=0,
        text=f"text {doc_id} {unit_id}",
        score=0.9,
        unit_id=unit_id,
    )


class SpyGraphClient:
    def __init__(self, results: list[RetrievedKnowledgeUnit] | None = None) -> None:
        self.call_count = 0
        self.results = results or [_chunk("graph-doc", "u-graph")]

    async def search(self, request: Any) -> list[RetrievedKnowledgeUnit]:
        self.call_count += 1
        return self.results


class StubDenseRetriever:
    async def retrieve(self, **kwargs: Any) -> list[RetrievedKnowledgeUnit]:
        return [_chunk("dense-doc", "u-dense")]


class StubSparseRetriever:
    async def retrieve(self, **kwargs: Any) -> list[RetrievedKnowledgeUnit]:
        return [_chunk("sparse-doc", "u-sparse")]


@pytest.mark.asyncio
async def test_graph_retrieval_toggle() -> None:
    # 1. Enabled (default): graph client called once
    spy_enabled = SpyGraphClient()
    settings_enabled = Settings(
        GRAPH_RETRIEVAL_ENABLED=True,
        RERANKER_ENABLED=False,
    )
    retriever_enabled = HybridRetriever(
        settings=settings_enabled,
        dense=StubDenseRetriever(),
        sparse=StubSparseRetriever(),
        graph=spy_enabled,
    )
    res_enabled = await retriever_enabled.retrieve(
        tenant_id="tenant-1",
        knowledge_base_id="kb-1",
        query_text="hello",
        query_vector=[0.1] * 1024,
    )
    assert spy_enabled.call_count == 1
    assert any(c.document_id == "graph-doc" for c in res_enabled)

    # 2. Disabled: graph client called 0 times, result matches pure dense + sparse
    spy_disabled = SpyGraphClient()
    settings_disabled = Settings(
        GRAPH_RETRIEVAL_ENABLED=False,
        RERANKER_ENABLED=False,
    )
    retriever_disabled = HybridRetriever(
        settings=settings_disabled,
        dense=StubDenseRetriever(),
        sparse=StubSparseRetriever(),
        graph=spy_disabled,
    )
    res_disabled = await retriever_disabled.retrieve(
        tenant_id="tenant-1",
        knowledge_base_id="kb-1",
        query_text="hello",
        query_vector=[0.1] * 1024,
    )
    assert spy_disabled.call_count == 0
    assert not any(c.document_id == "graph-doc" for c in res_disabled)
    assert {c.document_id for c in res_disabled} == {"dense-doc", "sparse-doc"}

    # 3. GRAPH_MAX_HOPS=0: STILL calls graph (hops 0 is not a toggle)
    spy_hops0 = SpyGraphClient()
    settings_hops0 = Settings(
        GRAPH_RETRIEVAL_ENABLED=True,
        GRAPH_MAX_HOPS=0,
        RERANKER_ENABLED=False,
    )
    retriever_hops0 = HybridRetriever(
        settings=settings_hops0,
        dense=StubDenseRetriever(),
        sparse=StubSparseRetriever(),
        graph=spy_hops0,
    )
    await retriever_hops0.retrieve(
        tenant_id="tenant-1",
        knowledge_base_id="kb-1",
        query_text="hello",
        query_vector=[0.1] * 1024,
    )
    assert spy_hops0.call_count == 1


def test_graph_retrieval_fingerprint() -> None:
    settings_on = Settings(GRAPH_RETRIEVAL_ENABLED=True)
    settings_off = Settings(GRAPH_RETRIEVAL_ENABLED=False)

    fp_on = retrieval_configuration_fingerprint(settings_on)
    fp_off = retrieval_configuration_fingerprint(settings_off)

    assert fp_on != fp_off, "Fingerprint must differ when GRAPH_RETRIEVAL_ENABLED toggles"


def test_cache_policy_key() -> None:
    settings_a = Settings(GRAPH_RETRIEVAL_ENABLED=True)
    settings_b = Settings(GRAPH_RETRIEVAL_ENABLED=False)

    builder_a = RetrievalCacheKeyBuilder(settings_a)
    builder_b = RetrievalCacheKeyBuilder(settings_b)

    key_a = builder_a.build(
        tenant_id="00000000-0000-0000-0000-000000000001",
        knowledge_base_id="00000000-0000-0000-0000-000000000002",
        revision=1,
        query_text="test query",
        document_ids=None,
    )
    key_b = builder_b.build(
        tenant_id="00000000-0000-0000-0000-000000000001",
        knowledge_base_id="00000000-0000-0000-0000-000000000002",
        revision=1,
        query_text="test query",
        document_ids=None,
    )

    assert key_a != key_b, "Cache key must differ when retrieval settings toggle"


def test_legacy_adapter_guard_fail_fast() -> None:
    class LegacyDenseWithoutExclusions:
        async def retrieve(
            self,
            tenant_id: str,
            knowledge_base_id: str,
            query_text: str,
            query_vector: list[float],
            limit: int,
            document_ids: list[str] | None,
        ) -> list[RetrievedKnowledgeUnit]:
            return []

    with pytest.raises(
        ValueError,
        match="Legacy adapter dense does not accept excluded_document_kinds",
    ):
        HybridRetriever(
            settings=Settings(),
            dense=LegacyDenseWithoutExclusions(),  # type: ignore[arg-type]
            graph=SpyGraphClient(),
        )


@pytest.mark.asyncio
async def test_vector_path_containment() -> None:
    # Reranker should never add documents outside candidate set
    candidates = [_chunk("doc-1", "u1"), _chunk("doc-2", "u2")]

    class DummyReranker:
        async def rerank(
            self, query: str, chunks: list[RetrievedKnowledgeUnit]
        ) -> list[RetrievedKnowledgeUnit]:
            return list(reversed(chunks))

    retriever = HybridRetriever(
        settings=Settings(RERANKER_ENABLED=True),
        reranker=DummyReranker(),  # type: ignore[arg-type]
        graph=SpyGraphClient(),
    )
    reranked = await retriever._rerank("query", candidates)
    assert {c.document_id for c in reranked} == {"doc-1", "doc-2"}
