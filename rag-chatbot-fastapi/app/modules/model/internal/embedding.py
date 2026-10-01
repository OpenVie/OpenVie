from __future__ import annotations

import hashlib
import math
import struct
import time
from collections.abc import Sequence
from datetime import timedelta
from typing import Any, Protocol

import httpx

from app.common.cache import CacheReadStatus, CacheStore
from app.common.concurrent_loads import AUTHORITATIVE_LOAD_TRACKER, ConcurrentLoadTracker
from app.common.metrics import (
    AI_EMBEDDING_REQUESTS_TOTAL,
    AI_EMBEDDING_SECONDS,
    observe_authoritative_duration,
    record_cache_operation,
)
from app.modules.model.api import (
    EMBEDDING_NORMALIZATION_VERSION,
    ModelRejectedError,
    ModelUnavailableError,
    normalize_embedding_text,
)
from app.modules.model.internal.config import ModelConfig

CACHE_NAME = "embedding"
NORMALIZATION_VERSION = EMBEDDING_NORMALIZATION_VERSION


class EmbeddingClient(Protocol):
    async def embed_documents(self, texts: Sequence[str]) -> list[list[float]]: ...

    async def embed_query(self, text: str) -> list[float]: ...


class EmbeddingCacheKeyBuilder:
    def __init__(self, *, prefix: str, model_id: str, dimension: int) -> None:
        self._prefix = prefix.rstrip(":")
        self._model_hash = hashlib.sha256(model_id.encode("utf-8")).hexdigest()
        self._dimension = dimension

    def build(self, normalized_text: str) -> str:
        text_hash = hashlib.sha256(normalized_text.encode("utf-8")).hexdigest()
        return (
            f"{self._prefix}:embedding:model:{self._model_hash}:dim:{self._dimension}:"
            f"norm:{NORMALIZATION_VERSION}:text:{text_hash}"
        )


class EmbeddingVectorCodec:
    _HEADER = struct.Struct(">4sBI")
    _MAGIC = b"CCNE"
    _SCHEMA_VERSION = 1

    @classmethod
    def encode(cls, vector: Sequence[float], *, dimension: int) -> bytes:
        if len(vector) != dimension:
            raise ValueError("Embedding vector dimension does not match cache configuration")
        values = [float(value) for value in vector]
        if not all(math.isfinite(value) for value in values):
            raise ValueError("Embedding vectors must contain only finite values")
        return cls._HEADER.pack(cls._MAGIC, cls._SCHEMA_VERSION, dimension) + struct.pack(
            f">{dimension}f", *values
        )

    @classmethod
    def decode(cls, payload: bytes, *, dimension: int) -> list[float]:
        expected_length = cls._HEADER.size + dimension * 4
        if len(payload) != expected_length:
            raise ValueError("Embedding cache payload length is invalid")
        magic, schema_version, stored_dimension = cls._HEADER.unpack_from(payload)
        if magic != cls._MAGIC:
            raise ValueError("Embedding cache payload magic is invalid")
        if schema_version != cls._SCHEMA_VERSION:
            raise ValueError("Embedding cache payload schema is unsupported")
        if stored_dimension != dimension:
            raise ValueError("Embedding cache payload dimension is invalid")
        vector = list(struct.unpack_from(f">{dimension}f", payload, cls._HEADER.size))
        if not all(math.isfinite(value) for value in vector):
            raise ValueError("Embedding cache payload contains non-finite values")
        return vector


class CachedEmbeddingClient:
    def __init__(
        self,
        delegate: EmbeddingClient,
        *,
        cache_store: CacheStore,
        key_builder: EmbeddingCacheKeyBuilder,
        dimension: int,
        ttl_seconds: int,
        enabled: bool,
        concurrent_loads: ConcurrentLoadTracker | None = None,
    ) -> None:
        self._delegate = delegate
        self._cache_store = cache_store
        self._key_builder = key_builder
        self._dimension = dimension
        self._ttl = timedelta(seconds=ttl_seconds)
        self._enabled = enabled
        self._concurrent_loads = concurrent_loads or AUTHORITATIVE_LOAD_TRACKER

    async def embed_query(self, text: str) -> list[float]:
        normalized = normalize_embedding_text(text)
        if not self._enabled:
            record_cache_operation(CACHE_NAME, "bypass")
            return await self._delegate.embed_query(normalized)

        key = self._key_builder.build(normalized)
        cached = await self._cache_store.get(CACHE_NAME, key)
        if cached.status is CacheReadStatus.HIT:
            try:
                assert cached.value is not None
                return EmbeddingVectorCodec.decode(cached.value, dimension=self._dimension)
            except ValueError:
                await self._cache_store.delete(CACHE_NAME, key)

        with self._concurrent_loads.observe(CACHE_NAME, key):
            started = time.perf_counter()
            outcome = "success"
            try:
                vector = await self._delegate.embed_query(normalized)
            except Exception:
                outcome = "error"
                raise
            finally:
                observe_authoritative_duration(CACHE_NAME, outcome, time.perf_counter() - started)
        try:
            payload = EmbeddingVectorCodec.encode(vector, dimension=self._dimension)
        except (OverflowError, ValueError):
            return vector
        await self._cache_store.put(CACHE_NAME, key, payload, self._ttl)
        return vector

    async def embed_documents(self, texts: Sequence[str]) -> list[list[float]]:
        normalized_texts = [normalize_embedding_text(text) for text in texts]
        if not normalized_texts:
            return []
        if not self._enabled:
            for _ in normalized_texts:
                record_cache_operation(CACHE_NAME, "bypass")
            return await self._delegate.embed_documents(normalized_texts)

        unique_texts = list(dict.fromkeys(normalized_texts))
        keys = [self._key_builder.build(text) for text in unique_texts]
        reads = await self._cache_store.get_many(CACHE_NAME, keys)
        vectors: dict[str, list[float]] = {}
        corrupt_keys: list[str] = []
        misses: list[str] = []
        for text, key, read in zip(unique_texts, keys, reads, strict=True):
            if read.status is CacheReadStatus.HIT:
                try:
                    assert read.value is not None
                    vectors[text] = EmbeddingVectorCodec.decode(
                        read.value, dimension=self._dimension
                    )
                    continue
                except ValueError:
                    corrupt_keys.append(key)
            misses.append(text)
        if corrupt_keys:
            await self._cache_store.delete_many(CACHE_NAME, corrupt_keys)

        if misses:
            scopes = [
                self._concurrent_loads.observe(CACHE_NAME, self._key_builder.build(text))
                for text in misses
            ]
            try:
                started = time.perf_counter()
                outcome = "success"
                try:
                    loaded = await self._delegate.embed_documents(misses)
                    if len(loaded) != len(misses):
                        raise ModelUnavailableError(
                            "Embedding provider returned an unexpected vector count"
                        )
                except Exception:
                    outcome = "error"
                    raise
                finally:
                    observe_authoritative_duration(
                        CACHE_NAME, outcome, time.perf_counter() - started
                    )
            finally:
                for scope in reversed(scopes):
                    scope.close()
            entries: list[tuple[str, bytes]] = []
            for text, vector in zip(misses, loaded, strict=True):
                vectors[text] = vector
                try:
                    entries.append(
                        (
                            self._key_builder.build(text),
                            EmbeddingVectorCodec.encode(vector, dimension=self._dimension),
                        )
                    )
                except (OverflowError, ValueError):
                    continue
            await self._cache_store.put_many(CACHE_NAME, entries, self._ttl)
        return [vectors[text] for text in normalized_texts]


def create_embedding_client(settings: ModelConfig, cache_store: CacheStore) -> EmbeddingClient:
    return CachedEmbeddingClient(
        OllamaEmbeddingClient(settings),
        cache_store=cache_store,
        key_builder=EmbeddingCacheKeyBuilder(
            prefix=settings.CACHE_KEY_PREFIX,
            model_id=settings.TEXT_EMBEDDING_MODEL_ID,
            dimension=settings.TEXT_EMBEDDING_DIMENSION,
        ),
        dimension=settings.TEXT_EMBEDDING_DIMENSION,
        ttl_seconds=settings.EMBEDDING_CACHE_TTL_SECONDS,
        enabled=settings.CACHE_ENABLED and settings.EMBEDDING_CACHE_ENABLED,
    )


class OllamaEmbeddingClient:
    def __init__(self, settings: ModelConfig):
        self._base_url = settings.TEXT_EMBEDDING_BASE_URL.rstrip("/")
        self._model = settings.TEXT_EMBEDDING_MODEL_ID
        self._batch_size = settings.TEXT_EMBEDDING_BATCH_SIZE
        self._expected_dimension = settings.TEXT_EMBEDDING_DIMENSION
        self._timeout_seconds = settings.TEXT_EMBEDDING_TIMEOUT_SECONDS

    async def embed_documents(self, texts: Sequence[str]) -> list[list[float]]:
        started_at = time.perf_counter()
        outcome = "success"
        try:
            embeddings: list[list[float]] = []
            for start in range(0, len(texts), self._batch_size):
                batch = texts[start : start + self._batch_size]
                embeddings.extend(await self._embed_batch(batch, operation="documents"))
            return embeddings
        except Exception:
            outcome = "error"
            raise
        finally:
            AI_EMBEDDING_SECONDS.labels(
                operation="documents",
                provider="ollama",
                outcome=outcome,
            ).observe(time.perf_counter() - started_at)

    async def embed_query(self, text: str) -> list[float]:
        started_at = time.perf_counter()
        outcome = "success"
        try:
            embeddings = await self._embed_batch([text], operation="query")
            return embeddings[0]
        except Exception:
            outcome = "error"
            raise
        finally:
            AI_EMBEDDING_SECONDS.labels(
                operation="query",
                provider="ollama",
                outcome=outcome,
            ).observe(time.perf_counter() - started_at)

    async def _embed_batch(self, texts: Sequence[str], *, operation: str) -> list[list[float]]:
        outcome = "success"
        try:
            try:
                async with httpx.AsyncClient(timeout=self._timeout_seconds) as client:
                    response = await client.post(
                        f"{self._base_url}/api/embed",
                        json={"model": self._model, "input": list(texts)},
                    )
                    response.raise_for_status()
                    payload = response.json()
            except httpx.HTTPStatusError as exc:
                raise ModelUnavailableError(
                    f"Ollama embedding request failed with status {exc.response.status_code}"
                ) from exc
            except (httpx.HTTPError, ValueError) as exc:
                raise ModelUnavailableError(f"Ollama embedding request failed: {exc}") from exc

            if "error" in payload:
                raise ModelUnavailableError(f"Ollama embedding model error: {payload['error']}")

            parsed = self._parse_embeddings(payload)
            if len(parsed) != len(texts):
                raise ModelUnavailableError("Ollama returned an unexpected embedding count")
            for vector in parsed:
                if len(vector) != self._expected_dimension:
                    raise ModelRejectedError(
                        "Ollama returned embedding dimension "
                        f"{len(vector)} but expected {self._expected_dimension}"
                    )
            return parsed
        except Exception:
            outcome = "error"
            raise
        finally:
            AI_EMBEDDING_REQUESTS_TOTAL.labels(
                operation=operation, provider="ollama", outcome=outcome
            ).inc()

    def _parse_embeddings(self, payload: dict[str, Any]) -> list[list[float]]:
        raw = payload.get("embeddings")
        if raw is None and "embedding" in payload:
            raw = [payload["embedding"]]
        if not isinstance(raw, list):
            raise ModelUnavailableError("Ollama response did not include embeddings")
        parsed: list[list[float]] = []
        for item in raw:
            if not isinstance(item, list):
                raise ModelUnavailableError("Ollama embedding item is malformed")
            parsed.append([float(value) for value in item])
        return parsed
