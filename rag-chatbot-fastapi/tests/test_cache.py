from __future__ import annotations

from datetime import timedelta
from unittest.mock import AsyncMock, Mock

import pytest

from app.common.cache import (
    CacheKeyFactory,
    CacheOperationStatus,
    CacheReadResult,
    CacheReadStatus,
    RedisCacheStore,
    TtlJitter,
)
from app.common.metrics import CACHE_OPERATIONS_TOTAL, REDIS_OPERATIONS_TOTAL


def test_cache_key_factory_builds_versioned_trusted_keys() -> None:
    factory = CacheKeyFactory("ccn:v1:")

    assert (
        factory.build("workspace", "tenant", "00000000-0000-0000-0000-000000000001")
        == "ccn:v1:workspace:tenant:00000000-0000-0000-0000-000000000001"
    )


def test_cache_key_factory_rejects_untrusted_segments() -> None:
    with pytest.raises(ValueError):
        CacheKeyFactory().build("retrieval", "raw query with spaces")


def test_ttl_jitter_is_deterministic_and_bounded() -> None:
    assert TtlJitter(10, lambda: 0.0).apply(timedelta(seconds=10)) == 9
    assert TtlJitter(10, lambda: 1.0).apply(timedelta(seconds=10)) == 11
    assert TtlJitter(10, lambda: 0.0).apply(timedelta(milliseconds=50)) == 1


@pytest.mark.asyncio
async def test_disabled_cache_bypasses_and_records_metric() -> None:
    client = AsyncMock()
    store = RedisCacheStore(client, enabled=False, ttl_jitter=TtlJitter())
    before = CACHE_OPERATIONS_TOTAL.labels("fastapi", "foundation", "bypass")._value.get()

    result = await store.get("foundation", "ccn:v1:test:key")

    assert result.status is CacheReadStatus.BYPASS
    assert result.value is None
    client.get.assert_not_awaited()
    assert (
        CACHE_OPERATIONS_TOTAL.labels("fastapi", "foundation", "bypass")._value.get() == before + 1
    )


@pytest.mark.asyncio
async def test_cache_hit_miss_write_delete_and_bytes_are_preserved() -> None:
    client = AsyncMock()
    client.get.side_effect = [b"\x00\xffpayload", None]
    store = RedisCacheStore(
        client,
        enabled=True,
        ttl_jitter=TtlJitter(10, lambda: 0.5),
    )
    writes_before = CACHE_OPERATIONS_TOTAL.labels("fastapi", "foundation", "write")._value.get()
    deletes_before = CACHE_OPERATIONS_TOTAL.labels(
        "fastapi", "foundation", "invalidate"
    )._value.get()

    hit = await store.get("foundation", "ccn:v1:test:hit")
    miss = await store.get("foundation", "ccn:v1:test:miss")
    write = await store.put(
        "foundation", "ccn:v1:test:key", b"\x00\xffpayload", timedelta(seconds=30)
    )
    deletion = await store.delete("foundation", "ccn:v1:test:key")

    assert hit.status is CacheReadStatus.HIT
    assert hit.value == b"\x00\xffpayload"
    assert miss.status is CacheReadStatus.MISS
    assert write is CacheOperationStatus.SUCCESS
    assert deletion is CacheOperationStatus.SUCCESS
    client.set.assert_awaited_once_with("ccn:v1:test:key", b"\x00\xffpayload", ex=30)
    client.delete.assert_awaited_once_with("ccn:v1:test:key")
    assert (
        CACHE_OPERATIONS_TOTAL.labels("fastapi", "foundation", "write")._value.get()
        == writes_before + 1
    )
    assert (
        CACHE_OPERATIONS_TOTAL.labels("fastapi", "foundation", "invalidate")._value.get()
        == deletes_before + 1
    )


@pytest.mark.asyncio
async def test_redis_errors_never_escape_cache_contract_and_increment_metrics() -> None:
    client = AsyncMock()
    client.get.side_effect = ConnectionError("unavailable")
    client.set.side_effect = TimeoutError("unavailable")
    client.delete.side_effect = ConnectionError("unavailable")
    store = RedisCacheStore(client, enabled=True, ttl_jitter=TtlJitter())
    before = REDIS_OPERATIONS_TOTAL.labels("fastapi", "cache", "get", "error")._value.get()

    read = await store.get("foundation", "ccn:v1:test:key")
    write = await store.put("foundation", "ccn:v1:test:key", b"payload", timedelta(seconds=30))
    deletion = await store.delete("foundation", "ccn:v1:test:key")

    assert read.status is CacheReadStatus.ERROR
    assert write is CacheOperationStatus.ERROR
    assert deletion is CacheOperationStatus.ERROR
    assert (
        REDIS_OPERATIONS_TOTAL.labels("fastapi", "cache", "get", "error")._value.get() == before + 1
    )


class FakePipeline:
    def __init__(self) -> None:
        self.commands: list[tuple[str, bytes, int]] = []
        self.executed = False

    async def __aenter__(self) -> FakePipeline:
        return self

    async def __aexit__(self, *args: object) -> None:
        return None

    def set(self, key: str, value: bytes, *, ex: int) -> None:
        self.commands.append((key, value, ex))

    async def execute(self) -> list[bool]:
        self.executed = True
        return [True for _ in self.commands]


@pytest.mark.asyncio
async def test_batch_cache_uses_mget_pipelined_jittered_sets_and_exact_delete() -> None:
    client = AsyncMock()
    client.mget.return_value = [b"first", None]
    pipeline = FakePipeline()
    client.pipeline = Mock(return_value=pipeline)
    store = RedisCacheStore(
        client,
        enabled=True,
        ttl_jitter=TtlJitter(10, iter((0.0, 1.0)).__next__),
    )

    reads = await store.get_many("embedding", ["key-1", "key-2"])
    write = await store.put_many(
        "embedding",
        [("key-1", b"one"), ("key-2", b"two")],
        timedelta(seconds=100),
    )
    deletion = await store.delete_many("embedding", ["key-1", "key-2"])

    assert reads == [
        CacheReadResult(CacheReadStatus.HIT, b"first"),
        CacheReadResult(CacheReadStatus.MISS),
    ]
    assert write is CacheOperationStatus.SUCCESS
    assert pipeline.commands == [("key-1", b"one", 90), ("key-2", b"two", 110)]
    assert pipeline.executed is True
    assert deletion is CacheOperationStatus.SUCCESS
    client.mget.assert_awaited_once_with(["key-1", "key-2"])
    client.delete.assert_awaited_once_with("key-1", "key-2")
