from __future__ import annotations

import os
from datetime import timedelta
from urllib.parse import urlsplit, urlunsplit
from uuid import uuid4

import pytest
import redis.asyncio as redis
from redis.exceptions import RedisError

from app.bootstrap.settings import Settings
from app.common.cache import (
    CacheOperationStatus,
    CacheReadStatus,
    RedisCacheStore,
    TtlJitter,
)
from app.modules.generation.internal.models import RetrievedChunk
from app.modules.model.internal.embedding import (
    CachedEmbeddingClient,
    EmbeddingCacheKeyBuilder,
    EmbeddingVectorCodec,
)
from app.modules.retrieval.internal.cache import CachedRetriever, RetrievalCacheKeyBuilder


def _database_15_url(url: str) -> str:
    parts = urlsplit(url)
    return urlunsplit((parts.scheme, parts.netloc, "/15", parts.query, parts.fragment))


@pytest.mark.asyncio
@pytest.mark.skipif(not os.getenv("REDIS_TEST_URL"), reason="REDIS_TEST_URL is not set")
async def test_cache_contract_against_real_redis() -> None:
    client = redis.from_url(
        _database_15_url(os.environ["REDIS_TEST_URL"]),
        decode_responses=False,
        socket_connect_timeout=1,
        socket_timeout=1,
    )
    prefix = f"ccn:test:{uuid4().hex}"
    key = f"{prefix}:raw"
    store = RedisCacheStore(
        client,
        enabled=True,
        ttl_jitter=TtlJitter(0, lambda: 0.5),
    )
    try:
        assert (await store.get("foundation", key)).status is CacheReadStatus.MISS
        assert (
            await store.put("foundation", key, b"\x00\xffpayload", timedelta(seconds=30))
            is CacheOperationStatus.SUCCESS
        )
        result = await store.get("foundation", key)
        assert result.status is CacheReadStatus.HIT
        assert result.value == b"\x00\xffpayload"
        assert await client.ttl(key) > 0
        assert await store.delete("foundation", key) is CacheOperationStatus.SUCCESS
        assert (await store.get("foundation", key)).status is CacheReadStatus.MISS

        keys = [f"{prefix}:batch:1", f"{prefix}:batch:2"]
        assert (
            await store.put_many(
                "embedding",
                [(keys[0], b"one"), (keys[1], b"two")],
                timedelta(seconds=30),
            )
            is CacheOperationStatus.SUCCESS
        )
        batch = await store.get_many("embedding", keys)
        assert [item.value for item in batch] == [b"one", b"two"]
        assert all(ttl > 0 for ttl in [await client.ttl(item) for item in keys])
        assert await store.delete_many("embedding", [keys[0]]) is CacheOperationStatus.SUCCESS
        assert await client.get(keys[0]) is None
        assert await client.get(keys[1]) == b"two"
        await client.delete(keys[1])
    finally:
        try:
            await client.delete(key)
        except RedisError:
            pass
        await client.aclose()


@pytest.mark.asyncio
@pytest.mark.skipif(not os.getenv("REDIS_TEST_URL"), reason="REDIS_TEST_URL is not set")
async def test_embedding_cache_real_redis_corrupt_recovery_and_duplicate_batch() -> None:
    client = redis.from_url(
        _database_15_url(os.environ["REDIS_TEST_URL"]),
        decode_responses=False,
        socket_connect_timeout=1,
        socket_timeout=1,
    )
    prefix = f"ccn:test:{uuid4().hex}"
    builder = EmbeddingCacheKeyBuilder(prefix=prefix, model_id="model", dimension=3)
    keys = [builder.build("same"), builder.build("other")]

    class Delegate:
        def __init__(self) -> None:
            self.calls: list[list[str]] = []

        async def embed_documents(self, texts: list[str]) -> list[list[float]]:
            self.calls.append(list(texts))
            values = {"same": [1.0, 2.0, 3.0], "other": [4.0, 5.0, 6.0]}
            return [values[text] for text in texts]

        async def embed_query(self, text: str) -> list[float]:
            return (await self.embed_documents([text]))[0]

    delegate = Delegate()
    store = RedisCacheStore(client, enabled=True, ttl_jitter=TtlJitter(0))
    embedding = CachedEmbeddingClient(
        delegate,
        cache_store=store,
        key_builder=builder,
        dimension=3,
        ttl_seconds=30,
        enabled=True,
    )
    try:
        first = await embedding.embed_documents(["same", "same", "other"])
        second = await embedding.embed_documents(["other", "same"])
        assert first[0] == first[1]
        assert second == [[4.0, 5.0, 6.0], [1.0, 2.0, 3.0]]
        assert delegate.calls == [["same", "other"]]
        assert all(ttl > 0 for ttl in [await client.ttl(key) for key in keys])

        await client.set(keys[0], b"corrupt", ex=30)
        recovered = await embedding.embed_documents(["same", "other"])
        assert recovered == [[1.0, 2.0, 3.0], [4.0, 5.0, 6.0]]
        assert delegate.calls == [["same", "other"], ["same"]]
        recovered_payload = await client.get(keys[0])
        assert recovered_payload is not None
        assert EmbeddingVectorCodec.decode(bytes(recovered_payload), dimension=3) == [
            1.0,
            2.0,
            3.0,
        ]
    finally:
        await client.delete(*keys)
        await client.aclose()


@pytest.mark.asyncio
@pytest.mark.skipif(not os.getenv("REDIS_TEST_URL"), reason="REDIS_TEST_URL is not set")
async def test_retrieval_cache_real_redis_ttl_corrupt_recovery_and_revision_miss() -> None:
    client = redis.from_url(
        _database_15_url(os.environ["REDIS_TEST_URL"]),
        decode_responses=False,
        socket_connect_timeout=1,
        socket_timeout=1,
    )
    prefix = f"ccn:test:{uuid4().hex}"
    tenant_id = str(uuid4())
    knowledge_base_id = str(uuid4())
    document_id = str(uuid4())
    settings = Settings(
        _env_file=(),
        CACHE_KEY_PREFIX=prefix,
        FINAL_CONTEXT_TOP_K=8,
    )
    builder = RetrievalCacheKeyBuilder(settings)

    class Revision:
        value = 1

        async def current_revision(self, tenant: str, knowledge_base: str) -> int:
            del tenant, knowledge_base
            return self.value

        async def increment(self, tenant: str, knowledge_base: str) -> int:
            del tenant, knowledge_base
            self.value += 1
            return self.value

    class Delegate:
        calls = 0

        async def retrieve(self, **kwargs: object) -> list[RetrievedChunk]:
            del kwargs
            self.calls += 1
            return [
                RetrievedChunk(
                    document_id=document_id,
                    source_name="source.txt",
                    page_number=1,
                    chunk_index=0,
                    text="content",
                    score=0.9,
                    unit_id=str(uuid4()),
                )
            ]

    revision = Revision()
    delegate = Delegate()
    store = RedisCacheStore(client, enabled=True, ttl_jitter=TtlJitter(0))
    retrieval = CachedRetriever(
        delegate,
        cache_store=store,
        revision_store=revision,
        key_builder=builder,
        ttl_seconds=30,
        max_results=8,
        enabled=True,
    )
    arguments = {
        "tenant_id": tenant_id,
        "knowledge_base_id": knowledge_base_id,
        "query_text": "same query",
        "query_vector": [0.1, 0.2],
        "document_ids": [document_id],
    }
    keys = [
        builder.build(
            tenant_id=tenant_id,
            knowledge_base_id=knowledge_base_id,
            revision=value,
            query_text="same query",
            document_ids=[document_id],
        )
        for value in (1, 2)
    ]
    try:
        first = await retrieval.retrieve(**arguments)
        second = await retrieval.retrieve(**arguments)
        assert first == second
        assert delegate.calls == 1
        assert await client.ttl(keys[0]) > 0

        await client.set(keys[0], b"corrupt", ex=30)
        await retrieval.retrieve(**arguments)
        assert delegate.calls == 2

        revision.value = 2
        await retrieval.retrieve(**arguments)
        assert delegate.calls == 3
        assert await client.ttl(keys[1]) > 0
    finally:
        await client.delete(*keys)
        await client.aclose()


@pytest.mark.asyncio
async def test_unreachable_real_client_returns_error_instead_of_raising() -> None:
    client = redis.from_url(
        "redis://127.0.0.1:1/15",
        decode_responses=False,
        socket_connect_timeout=0.05,
        socket_timeout=0.05,
    )
    store = RedisCacheStore(client, enabled=True, ttl_jitter=TtlJitter())
    try:
        assert (
            await store.get("foundation", "ccn:v1:unreachable:test")
        ).status is CacheReadStatus.ERROR
    finally:
        await client.aclose()
