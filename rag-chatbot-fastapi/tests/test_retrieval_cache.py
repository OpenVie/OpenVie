from __future__ import annotations

import asyncio
import json
import math
from collections.abc import Sequence
from datetime import timedelta
from uuid import uuid4

import pytest

from app.bootstrap.settings import Settings
from app.common.cache import (
    CacheOperationStatus,
    CacheReadResult,
    CacheReadStatus,
    CacheStore,
)
from app.common.concurrent_loads import ConcurrentLoadTracker
from app.modules.generation.internal.models import RetrievedChunk
from app.modules.retrieval.internal.cache import (
    RETRIEVAL_PIPELINE_VERSION,
    CachedRetriever,
    RetrievalCacheKeyBuilder,
    RetrievedChunkCacheCodec,
    retrieval_configuration_fingerprint,
)


def test_graph_traversal_configuration_changes_the_versioned_cache_fingerprint() -> None:
    assert RETRIEVAL_PIPELINE_VERSION == 2
    assert retrieval_configuration_fingerprint(
        Settings(_env_file=(), GRAPH_MAX_HOPS=0)
    ) != retrieval_configuration_fingerprint(Settings(_env_file=(), GRAPH_MAX_HOPS=3))


class MemoryCacheStore(CacheStore):
    def __init__(self) -> None:
        self.values: dict[str, bytes] = {}
        self.deleted: list[str] = []
        self.ttls: list[timedelta] = []
        self.read_error = False
        self.write_error = False

    async def get(self, cache_name: str, full_key: str) -> CacheReadResult:
        del cache_name
        if self.read_error:
            return CacheReadResult(CacheReadStatus.ERROR)
        value = self.values.get(full_key)
        if value is None:
            return CacheReadResult(CacheReadStatus.MISS)
        return CacheReadResult(CacheReadStatus.HIT, value)

    async def put(
        self, cache_name: str, full_key: str, value: bytes, base_ttl: timedelta
    ) -> CacheOperationStatus:
        del cache_name
        self.ttls.append(base_ttl)
        if self.write_error:
            return CacheOperationStatus.ERROR
        self.values[full_key] = value
        return CacheOperationStatus.SUCCESS

    async def delete(self, cache_name: str, full_key: str) -> CacheOperationStatus:
        del cache_name
        self.deleted.append(full_key)
        self.values.pop(full_key, None)
        return CacheOperationStatus.SUCCESS

    async def get_many(self, cache_name: str, full_keys: Sequence[str]) -> list[CacheReadResult]:
        return [await self.get(cache_name, key) for key in full_keys]

    async def put_many(
        self,
        cache_name: str,
        entries: Sequence[tuple[str, bytes]],
        base_ttl: timedelta,
    ) -> CacheOperationStatus:
        for key, value in entries:
            await self.put(cache_name, key, value, base_ttl)
        return CacheOperationStatus.SUCCESS

    async def delete_many(self, cache_name: str, full_keys: Sequence[str]) -> CacheOperationStatus:
        for key in full_keys:
            await self.delete(cache_name, key)
        return CacheOperationStatus.SUCCESS


class RevisionStore:
    def __init__(self, revision: int = 1) -> None:
        self.revision = revision
        self.error: Exception | None = None
        self.reads: list[tuple[str, str]] = []

    async def current_revision(self, tenant_id: str, knowledge_base_id: str) -> int:
        self.reads.append((tenant_id, knowledge_base_id))
        if self.error:
            raise self.error
        return self.revision

    async def increment(self, tenant_id: str, knowledge_base_id: str) -> int:
        del tenant_id, knowledge_base_id
        self.revision += 1
        return self.revision


class Retriever:
    def __init__(self, chunks: list[RetrievedChunk]) -> None:
        self.chunks = chunks
        self.calls: list[dict[str, object]] = []
        self.error: Exception | None = None

    async def retrieve(self, **kwargs: object) -> list[RetrievedChunk]:
        self.calls.append(kwargs)
        if self.error:
            raise self.error
        return self.chunks


def _chunk(document_id: str | None = None) -> RetrievedChunk:
    return RetrievedChunk(
        document_id=document_id or str(uuid4()),
        source_name="Chính sách.pdf",
        page_number=2,
        chunk_index=3,
        text="Nội dung chính sách.",
        score=0.875,
        unit_id=str(uuid4()),
        modality="document",
        section_path=("Mục 1", "Chi tiết"),
        block_type="paragraph",
        sheet_name=None,
        cell_range=None,
        table_id=None,
    )


def _client(
    delegate: Retriever,
    store: MemoryCacheStore,
    revision_store: RevisionStore,
    *,
    settings: Settings | None = None,
    enabled: bool = True,
    concurrent_loads: ConcurrentLoadTracker | None = None,
) -> CachedRetriever:
    configured = settings or Settings(_env_file=(), FINAL_CONTEXT_TOP_K=8)
    return CachedRetriever(
        delegate,
        cache_store=store,
        revision_store=revision_store,
        key_builder=RetrievalCacheKeyBuilder(configured),
        ttl_seconds=120,
        max_results=configured.FINAL_CONTEXT_TOP_K,
        enabled=enabled,
        concurrent_loads=concurrent_loads,
    )


def test_key_is_content_free_stable_for_sorted_visibility_and_isolated() -> None:
    tenant = str(uuid4())
    knowledge_base = str(uuid4())
    first_document = str(uuid4())
    second_document = str(uuid4())
    settings = Settings(_env_file=())
    builder = RetrievalCacheKeyBuilder(settings)
    first = builder.build(
        tenant_id=tenant,
        knowledge_base_id=knowledge_base,
        revision=7,
        query_text="bí mật\r\ne\u0301",
        document_ids=[second_document, first_document, first_document],
    )
    reordered = builder.build(
        tenant_id=tenant,
        knowledge_base_id=knowledge_base,
        revision=7,
        query_text="bí mật\né",
        document_ids=[first_document, second_document],
    )

    assert first == reordered
    assert "bí mật" not in first
    assert settings.TEXT_EMBEDDING_MODEL_ID not in first
    assert first != builder.build(
        tenant_id=tenant,
        knowledge_base_id=knowledge_base,
        revision=8,
        query_text="bí mật\né",
        document_ids=[first_document, second_document],
    )
    assert first != builder.build(
        tenant_id=str(uuid4()),
        knowledge_base_id=knowledge_base,
        revision=7,
        query_text="bí mật\né",
        document_ids=[first_document, second_document],
    )
    assert first != builder.build(
        tenant_id=tenant,
        knowledge_base_id=str(uuid4()),
        revision=7,
        query_text="bí mật\né",
        document_ids=[first_document, second_document],
    )
    assert first != builder.build(
        tenant_id=tenant,
        knowledge_base_id=knowledge_base,
        revision=7,
        query_text="bí mật\né",
        document_ids=[first_document],
    )
    changed_config = RetrievalCacheKeyBuilder(
        settings.model_copy(update={"RRF_K": settings.RRF_K + 1})
    ).build(
        tenant_id=tenant,
        knowledge_base_id=knowledge_base,
        revision=7,
        query_text="bí mật\né",
        document_ids=[first_document, second_document],
    )
    assert first != changed_config


def test_chunk_codec_round_trip_and_strict_validation() -> None:
    chunk = _chunk()
    payload = RetrievedChunkCacheCodec.encode([chunk])

    assert RetrievedChunkCacheCodec.decode(
        payload, max_results=8, allowed_document_ids=[chunk.document_id]
    ) == [chunk]

    raw = json.loads(payload)
    raw["schema_version"] = 2
    with pytest.raises(ValueError, match="schema"):
        RetrievedChunkCacheCodec.decode(
            json.dumps(raw).encode(), max_results=8, allowed_document_ids=None
        )
    raw = json.loads(payload)
    raw["chunks"][0]["unexpected"] = True
    with pytest.raises(ValueError, match="malformed"):
        RetrievedChunkCacheCodec.decode(
            json.dumps(raw).encode(), max_results=8, allowed_document_ids=None
        )
    raw = json.loads(payload)
    raw["chunks"][0]["document_id"] = "not-a-uuid"
    with pytest.raises(ValueError):
        RetrievedChunkCacheCodec.decode(
            json.dumps(raw).encode(), max_results=8, allowed_document_ids=None
        )
    raw = json.loads(payload)
    raw["chunks"][0]["score"] = math.inf
    with pytest.raises(ValueError, match="finite"):
        RetrievedChunkCacheCodec.decode(
            json.dumps(raw).encode(), max_results=8, allowed_document_ids=None
        )
    with pytest.raises(ValueError, match="visible"):
        RetrievedChunkCacheCodec.decode(payload, max_results=8, allowed_document_ids=[str(uuid4())])
    with pytest.raises(ValueError, match="count"):
        RetrievedChunkCacheCodec.decode(payload, max_results=0, allowed_document_ids=None)


@pytest.mark.asyncio
async def test_miss_fill_hit_and_revision_change_skip_authoritative_pipeline() -> None:
    tenant = str(uuid4())
    knowledge_base = str(uuid4())
    chunk = _chunk()
    store = MemoryCacheStore()
    revision = RevisionStore(4)
    delegate = Retriever([chunk])
    client = _client(delegate, store, revision)
    arguments = {
        "tenant_id": tenant,
        "knowledge_base_id": knowledge_base,
        "query_text": "same query",
        "query_vector": [0.1, 0.2],
        "document_ids": [chunk.document_id],
    }

    first = await client.retrieve(**arguments)
    second = await client.retrieve(**arguments)
    revision.revision = 5
    third = await client.retrieve(**arguments)

    assert first == [chunk]
    assert second == [chunk]
    assert third == [chunk]
    assert len(delegate.calls) == 2
    assert store.ttls == [timedelta(seconds=120), timedelta(seconds=120)]


@pytest.mark.asyncio
async def test_same_retrieval_key_overlap_is_observed_without_changing_results(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    starts: list[int] = []
    monkeypatch.setattr(
        "app.common.concurrent_loads.record_authoritative_load_started",
        lambda _cache, concurrency: starts.append(concurrency),
    )
    monkeypatch.setattr(
        "app.common.concurrent_loads.record_authoritative_load_finished", lambda *_: None
    )
    chunk = _chunk()
    entered = 0
    release = asyncio.Event()

    class BlockingRetriever(Retriever):
        async def retrieve(self, **kwargs: object) -> list[RetrievedChunk]:
            nonlocal entered
            self.calls.append(kwargs)
            entered += 1
            if entered == 2:
                release.set()
            await release.wait()
            return self.chunks

    tracker = ConcurrentLoadTracker()
    delegate = BlockingRetriever([chunk])
    client = _client(
        delegate,
        MemoryCacheStore(),
        RevisionStore(),
        concurrent_loads=tracker,
    )
    arguments = {
        "tenant_id": str(uuid4()),
        "knowledge_base_id": str(uuid4()),
        "query_text": "same query",
        "query_vector": [0.1],
        "document_ids": [chunk.document_id],
    }

    results = await asyncio.gather(client.retrieve(**arguments), client.retrieve(**arguments))

    assert results == [[chunk], [chunk]]
    assert len(delegate.calls) == 2
    assert starts == [1, 2]
    assert tracker.active_key_count == 0


@pytest.mark.asyncio
async def test_empty_authoritative_results_are_cached() -> None:
    delegate = Retriever([])
    store = MemoryCacheStore()
    revision = RevisionStore()
    client = _client(delegate, store, revision)
    arguments = {
        "tenant_id": str(uuid4()),
        "knowledge_base_id": str(uuid4()),
        "query_text": "no results",
        "query_vector": [0.1],
        "document_ids": None,
    }

    assert await client.retrieve(**arguments) == []
    assert await client.retrieve(**arguments) == []
    assert len(delegate.calls) == 1


@pytest.mark.asyncio
async def test_disabled_revision_failure_and_redis_failures_fall_back() -> None:
    chunk = _chunk()
    tenant = str(uuid4())
    knowledge_base = str(uuid4())
    arguments = {
        "tenant_id": tenant,
        "knowledge_base_id": knowledge_base,
        "query_text": "query",
        "query_vector": [0.1],
        "document_ids": None,
    }
    delegate = Retriever([chunk])
    store = MemoryCacheStore()
    revision = RevisionStore()

    assert await _client(delegate, store, revision, enabled=False).retrieve(**arguments) == [chunk]
    revision.error = ConnectionError("postgres unavailable")
    assert await _client(delegate, store, revision).retrieve(**arguments) == [chunk]
    revision.error = None
    store.read_error = True
    store.write_error = True
    assert await _client(delegate, store, revision).retrieve(**arguments) == [chunk]
    assert len(delegate.calls) == 3


@pytest.mark.asyncio
async def test_corrupt_entry_is_deleted_and_authoritative_exception_propagates() -> None:
    tenant = str(uuid4())
    knowledge_base = str(uuid4())
    chunk = _chunk()
    store = MemoryCacheStore()
    revision = RevisionStore(2)
    delegate = Retriever([chunk])
    settings = Settings(_env_file=())
    builder = RetrievalCacheKeyBuilder(settings)
    key = builder.build(
        tenant_id=tenant,
        knowledge_base_id=knowledge_base,
        revision=2,
        query_text="query",
        document_ids=[chunk.document_id],
    )
    store.values[key] = b"corrupt"
    client = CachedRetriever(
        delegate,
        cache_store=store,
        revision_store=revision,
        key_builder=builder,
        ttl_seconds=120,
        max_results=settings.FINAL_CONTEXT_TOP_K,
        enabled=True,
    )

    assert await client.retrieve(
        tenant_id=tenant,
        knowledge_base_id=knowledge_base,
        query_text="query",
        query_vector=[0.1],
        document_ids=[chunk.document_id],
    ) == [chunk]
    assert store.deleted == [key]

    store.values.clear()
    delegate.error = RuntimeError("qdrant unavailable")
    with pytest.raises(RuntimeError, match="qdrant unavailable"):
        await client.retrieve(
            tenant_id=tenant,
            knowledge_base_id=knowledge_base,
            query_text="other",
            query_vector=[0.2],
            document_ids=[chunk.document_id],
        )
