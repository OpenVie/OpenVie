from __future__ import annotations

import asyncio
import math
import struct
from collections.abc import Sequence
from datetime import timedelta

import pytest
from prometheus_client import REGISTRY

from app.bootstrap.settings import Settings
from app.common.cache import (
    CacheOperationStatus,
    CacheReadResult,
    CacheReadStatus,
    CacheStore,
)
from app.common.concurrent_loads import ConcurrentLoadTracker
from app.modules.model.internal.embedding import (
    CachedEmbeddingClient,
    EmbeddingCacheKeyBuilder,
    EmbeddingClient,
    EmbeddingVectorCodec,
    OllamaEmbeddingClient,
    normalize_embedding_text,
)


class MemoryCacheStore(CacheStore):
    def __init__(self) -> None:
        self.values: dict[str, bytes] = {}
        self.deleted: list[str] = []
        self.put_ttls: list[timedelta] = []
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
        self.put_ttls.append(base_ttl)
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
        return CacheOperationStatus.ERROR if self.write_error else CacheOperationStatus.SUCCESS

    async def delete_many(self, cache_name: str, full_keys: Sequence[str]) -> CacheOperationStatus:
        for key in full_keys:
            await self.delete(cache_name, key)
        return CacheOperationStatus.SUCCESS


class RecordingEmbeddingClient:
    def __init__(self, vectors: dict[str, list[float]]) -> None:
        self.vectors = vectors
        self.query_calls: list[str] = []
        self.document_calls: list[list[str]] = []
        self.error: Exception | None = None

    async def embed_query(self, text: str) -> list[float]:
        self.query_calls.append(text)
        if self.error:
            raise self.error
        return self.vectors[text]

    async def embed_documents(self, texts: Sequence[str]) -> list[list[float]]:
        self.document_calls.append(list(texts))
        if self.error:
            raise self.error
        return [self.vectors[text] for text in texts]


def _cached(
    delegate: EmbeddingClient,
    store: MemoryCacheStore,
    *,
    enabled: bool = True,
    model_id: str = "embedding-model",
    dimension: int = 3,
    concurrent_loads: ConcurrentLoadTracker | None = None,
) -> CachedEmbeddingClient:
    return CachedEmbeddingClient(
        delegate,
        cache_store=store,
        key_builder=EmbeddingCacheKeyBuilder(
            prefix="ccn:v1", model_id=model_id, dimension=dimension
        ),
        dimension=dimension,
        ttl_seconds=86400,
        enabled=enabled,
        concurrent_loads=concurrent_loads,
    )


def test_normalization_only_changes_line_endings_and_unicode_composition() -> None:
    assert normalize_embedding_text(" A\r\ne\u0301\rZ ") == " A\né\nZ "
    assert normalize_embedding_text("MiXeD  ") == "MiXeD  "


def test_keys_are_content_free_and_isolated_by_model_dimension_and_normalization() -> None:
    text = normalize_embedding_text("tenant secret é")
    first = EmbeddingCacheKeyBuilder(prefix="ccn:v1", model_id="model-a", dimension=3).build(text)
    same = EmbeddingCacheKeyBuilder(prefix="ccn:v1", model_id="model-a", dimension=3).build(
        normalize_embedding_text("tenant secret e\u0301")
    )
    other_model = EmbeddingCacheKeyBuilder(prefix="ccn:v1", model_id="model-b", dimension=3).build(
        text
    )
    other_dimension = EmbeddingCacheKeyBuilder(
        prefix="ccn:v1", model_id="model-a", dimension=4
    ).build(text)

    assert first == same
    assert first != other_model
    assert first != other_dimension
    assert "tenant" not in first and "secret" not in first and "model-a" not in first
    assert first.startswith("ccn:v1:embedding:model:")
    assert ":dim:3:norm:1:text:" in first


def test_binary_codec_round_trip_is_float32_and_rejects_malformed_values() -> None:
    payload = EmbeddingVectorCodec.encode([0.1, -2.5, 3.25], dimension=3)
    decoded = EmbeddingVectorCodec.decode(payload, dimension=3)

    assert payload[:4] == b"CCNE"
    assert decoded == pytest.approx([0.1, -2.5, 3.25], abs=1e-7)
    with pytest.raises(ValueError):
        EmbeddingVectorCodec.decode(b"BAD!" + payload[4:], dimension=3)
    with pytest.raises(ValueError):
        EmbeddingVectorCodec.decode(payload[:4] + b"\x02" + payload[5:], dimension=3)
    with pytest.raises(ValueError):
        EmbeddingVectorCodec.decode(payload[:-1], dimension=3)
    with pytest.raises(ValueError):
        EmbeddingVectorCodec.decode(payload, dimension=2)
    with pytest.raises(ValueError):
        EmbeddingVectorCodec.encode([1.0, math.inf, 3.0], dimension=3)

    non_finite = payload[:9] + struct.pack(">3f", 1.0, math.nan, 3.0)
    with pytest.raises(ValueError):
        EmbeddingVectorCodec.decode(non_finite, dimension=3)


@pytest.mark.asyncio
async def test_query_miss_fill_hit_uses_canonical_text_and_configured_ttl() -> None:
    store = MemoryCacheStore()
    delegate = RecordingEmbeddingClient({"é\nquery": [0.1, 0.2, 0.3]})
    client = _cached(delegate, store)

    first = await client.embed_query("e\u0301\r\nquery")
    second = await client.embed_query("é\nquery")

    assert first == [0.1, 0.2, 0.3]
    assert second == pytest.approx(first, abs=1e-7)
    assert delegate.query_calls == ["é\nquery"]
    assert store.put_ttls == [timedelta(seconds=86400)]


@pytest.mark.asyncio
async def test_query_overlap_is_observed_without_coalescing_or_waiting(
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
    entered = 0
    release = asyncio.Event()

    class BlockingEmbeddingClient:
        async def embed_query(self, text: str) -> list[float]:
            nonlocal entered
            del text
            entered += 1
            if entered == 2:
                release.set()
            await release.wait()
            return [1.0, 2.0, 3.0]

        async def embed_documents(self, texts: Sequence[str]) -> list[list[float]]:
            return [[1.0, 2.0, 3.0] for _ in texts]

    tracker = ConcurrentLoadTracker()
    client = _cached(BlockingEmbeddingClient(), MemoryCacheStore(), concurrent_loads=tracker)

    results = await asyncio.gather(client.embed_query("same"), client.embed_query("same"))

    assert results == [[1.0, 2.0, 3.0], [1.0, 2.0, 3.0]]
    assert starts == [1, 2]
    assert tracker.active_key_count == 0


@pytest.mark.asyncio
async def test_document_batch_observes_each_unique_missed_key_and_preserves_order(
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
    tracker = ConcurrentLoadTracker()
    delegate = RecordingEmbeddingClient({"first": [1.0, 0.0, 0.0], "second": [0.0, 1.0, 0.0]})
    client = _cached(delegate, MemoryCacheStore(), concurrent_loads=tracker)

    result = await client.embed_documents(["first", "first", "second"])

    assert result == [[1.0, 0.0, 0.0], [1.0, 0.0, 0.0], [0.0, 1.0, 0.0]]
    assert delegate.document_calls == [["first", "second"]]
    assert starts == [1, 1]
    assert tracker.active_key_count == 0


@pytest.mark.asyncio
async def test_query_disabled_and_redis_errors_bypass_or_fail_open() -> None:
    delegate = RecordingEmbeddingClient({"query": [1.0, 2.0, 3.0]})
    disabled_store = MemoryCacheStore()
    assert await _cached(delegate, disabled_store, enabled=False).embed_query("query") == [
        1.0,
        2.0,
        3.0,
    ]
    assert disabled_store.values == {}

    failing_store = MemoryCacheStore()
    failing_store.read_error = True
    failing_store.write_error = True
    assert await _cached(delegate, failing_store).embed_query("query") == [1.0, 2.0, 3.0]
    assert delegate.query_calls == ["query", "query"]


@pytest.mark.asyncio
async def test_authoritative_exceptions_propagate_and_corrupt_entries_are_cleaned() -> None:
    store = MemoryCacheStore()
    delegate = RecordingEmbeddingClient({"query": [1.0, 2.0, 3.0]})
    client = _cached(delegate, store)
    key = EmbeddingCacheKeyBuilder(prefix="ccn:v1", model_id="embedding-model", dimension=3).build(
        "query"
    )
    store.values[key] = b"corrupt"

    assert await client.embed_query("query") == [1.0, 2.0, 3.0]
    assert store.deleted == [key]

    delegate.error = RuntimeError("ollama unavailable")
    store.values.clear()
    with pytest.raises(RuntimeError, match="ollama unavailable"):
        await client.embed_query("query")


@pytest.mark.asyncio
async def test_document_batch_deduplicates_misses_and_restores_order_and_duplicates() -> None:
    store = MemoryCacheStore()
    delegate = RecordingEmbeddingClient(
        {
            "hit": [1.0, 1.0, 1.0],
            "miss": [2.0, 2.0, 2.0],
            "other": [3.0, 3.0, 3.0],
        }
    )
    client = _cached(delegate, store)
    hit_key = EmbeddingCacheKeyBuilder(
        prefix="ccn:v1", model_id="embedding-model", dimension=3
    ).build("hit")
    store.values[hit_key] = EmbeddingVectorCodec.encode([1.0, 1.0, 1.0], dimension=3)

    result = await client.embed_documents(["hit", "miss", "hit", "other", "miss"])

    assert result == [
        [1.0, 1.0, 1.0],
        [2.0, 2.0, 2.0],
        [1.0, 1.0, 1.0],
        [3.0, 3.0, 3.0],
        [2.0, 2.0, 2.0],
    ]
    assert delegate.document_calls == [["miss", "other"]]
    assert len(store.values) == 3


@pytest.mark.asyncio
async def test_unique_misses_still_respect_underlying_ollama_batch_limit(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[list[str]] = []

    class Response:
        def __init__(self, inputs: list[str]) -> None:
            self._inputs = inputs

        def raise_for_status(self) -> None:
            return None

        def json(self) -> dict[str, list[list[float]]]:
            return {
                "embeddings": [
                    [float(int(text.removeprefix("text-"))), 1.0] for text in self._inputs
                ]
            }

    class HttpClient:
        def __init__(self, timeout: float) -> None:
            del timeout

        async def __aenter__(self) -> HttpClient:
            return self

        async def __aexit__(self, *args: object) -> None:
            return None

        async def post(self, url: str, json: dict[str, object]) -> Response:
            del url
            inputs = [str(value) for value in json["input"]]  # type: ignore[index]
            calls.append(inputs)
            return Response(inputs)

    monkeypatch.setattr("app.modules.model.internal.embedding.httpx.AsyncClient", HttpClient)
    settings = Settings(_env_file=(), TEXT_EMBEDDING_DIMENSION=2, TEXT_EMBEDDING_BATCH_SIZE=2)
    store = MemoryCacheStore()
    client = CachedEmbeddingClient(
        OllamaEmbeddingClient(settings),
        cache_store=store,
        key_builder=EmbeddingCacheKeyBuilder(
            prefix="ccn:v1", model_id=settings.TEXT_EMBEDDING_MODEL_ID, dimension=2
        ),
        dimension=2,
        ttl_seconds=60,
        enabled=True,
    )
    labels = {"operation": "documents", "provider": "ollama", "outcome": "success"}
    before = REGISTRY.get_sample_value("cacanode_ai_embedding_requests_total", labels) or 0

    result = await client.embed_documents(
        ["text-1", "text-2", "text-1", "text-3", "text-4", "text-5"]
    )

    assert calls == [["text-1", "text-2"], ["text-3", "text-4"], ["text-5"]]
    assert [vector[0] for vector in result] == [1.0, 2.0, 1.0, 3.0, 4.0, 5.0]
    assert (
        REGISTRY.get_sample_value("cacanode_ai_embedding_requests_total", labels) or 0
    ) == before + 3
