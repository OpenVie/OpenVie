from __future__ import annotations

import random
import re
import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from datetime import timedelta
from enum import StrEnum
from typing import Protocol

from redis.asyncio import Redis

from app.common.metrics import (
    observe_cache_duration,
    observe_cache_payload,
    record_cache_operation,
    record_redis_operation,
)


class CacheReadStatus(StrEnum):
    HIT = "HIT"
    MISS = "MISS"
    BYPASS = "BYPASS"
    ERROR = "ERROR"


class CacheOperationStatus(StrEnum):
    SUCCESS = "SUCCESS"
    BYPASS = "BYPASS"
    ERROR = "ERROR"


@dataclass(frozen=True)
class CacheReadResult:
    status: CacheReadStatus
    value: bytes | None = None

    def __post_init__(self) -> None:
        if self.status is CacheReadStatus.HIT and self.value is None:
            raise ValueError("A cache hit requires a value")
        if self.status is not CacheReadStatus.HIT and self.value is not None:
            raise ValueError("Only a cache hit may contain a value")


class CacheStore(Protocol):
    async def get(self, cache_name: str, full_key: str) -> CacheReadResult: ...

    async def put(
        self, cache_name: str, full_key: str, value: bytes, base_ttl: timedelta
    ) -> CacheOperationStatus: ...

    async def delete(self, cache_name: str, full_key: str) -> CacheOperationStatus: ...

    async def get_many(
        self, cache_name: str, full_keys: Sequence[str]
    ) -> list[CacheReadResult]: ...

    async def put_many(
        self,
        cache_name: str,
        entries: Sequence[tuple[str, bytes]],
        base_ttl: timedelta,
    ) -> CacheOperationStatus: ...

    async def delete_many(
        self, cache_name: str, full_keys: Sequence[str]
    ) -> CacheOperationStatus: ...


class CacheKeyFactory:
    _trusted_part = re.compile(r"[A-Za-z0-9._-]+")

    def __init__(self, prefix: str = "ccn:v1") -> None:
        if not prefix.strip():
            raise ValueError("Cache key prefix must not be blank")
        self._prefix = prefix.rstrip(":")

    def build(self, domain: str, *trusted_segments: str) -> str:
        parts = [self._validate(domain), *(self._validate(value) for value in trusted_segments)]
        return ":".join((self._prefix, *parts))

    def _validate(self, value: str) -> str:
        if not self._trusted_part.fullmatch(value):
            raise ValueError("Cache key parts must be trusted opaque segments")
        return value


class TtlJitter:
    def __init__(
        self,
        jitter_percent: int = 10,
        random_value: Callable[[], float] = random.random,
    ) -> None:
        if not 0 <= jitter_percent <= 100:
            raise ValueError("TTL jitter percent must be between 0 and 100")
        self._jitter_percent = jitter_percent
        self._random_value = random_value

    def apply(self, base_ttl: timedelta) -> int:
        base_seconds = max(1.0, base_ttl.total_seconds())
        factor = 1.0 + ((self._random_value() * 2.0 - 1.0) * self._jitter_percent / 100.0)
        return max(1, round(base_seconds * factor))


class RedisCacheStore:
    def __init__(
        self,
        client: Redis,
        *,
        enabled: bool,
        ttl_jitter: TtlJitter,
    ) -> None:
        self._client = client
        self._enabled = enabled
        self._ttl_jitter = ttl_jitter

    async def get(self, cache_name: str, full_key: str) -> CacheReadResult:
        started = time.perf_counter()
        try:
            if not self._enabled:
                record_cache_operation(cache_name, "bypass")
                return CacheReadResult(CacheReadStatus.BYPASS)
            value = await self._client.get(full_key)
            record_redis_operation("cache", "get", "success")
            if value is None:
                record_cache_operation(cache_name, "miss")
                return CacheReadResult(CacheReadStatus.MISS)
            payload = bytes(value)
            record_cache_operation(cache_name, "hit")
            observe_cache_payload(cache_name, len(payload))
            return CacheReadResult(CacheReadStatus.HIT, payload)
        except Exception:
            record_redis_operation("cache", "get", "error")
            record_cache_operation(cache_name, "error")
            return CacheReadResult(CacheReadStatus.ERROR)
        finally:
            observe_cache_duration(cache_name, "get", time.perf_counter() - started)

    async def put(
        self, cache_name: str, full_key: str, value: bytes, base_ttl: timedelta
    ) -> CacheOperationStatus:
        started = time.perf_counter()
        try:
            if not self._enabled:
                record_cache_operation(cache_name, "bypass")
                return CacheOperationStatus.BYPASS
            await self._client.set(full_key, value, ex=self._ttl_jitter.apply(base_ttl))
            record_redis_operation("cache", "set", "success")
            record_cache_operation(cache_name, "write")
            observe_cache_payload(cache_name, len(value))
            return CacheOperationStatus.SUCCESS
        except Exception:
            record_redis_operation("cache", "set", "error")
            record_cache_operation(cache_name, "error")
            return CacheOperationStatus.ERROR
        finally:
            observe_cache_duration(cache_name, "put", time.perf_counter() - started)

    async def delete(self, cache_name: str, full_key: str) -> CacheOperationStatus:
        started = time.perf_counter()
        try:
            if not self._enabled:
                record_cache_operation(cache_name, "bypass")
                return CacheOperationStatus.BYPASS
            await self._client.delete(full_key)
            record_redis_operation("cache", "delete", "success")
            record_cache_operation(cache_name, "invalidate")
            return CacheOperationStatus.SUCCESS
        except Exception:
            record_redis_operation("cache", "delete", "error")
            record_cache_operation(cache_name, "error")
            return CacheOperationStatus.ERROR
        finally:
            observe_cache_duration(cache_name, "delete", time.perf_counter() - started)

    async def get_many(self, cache_name: str, full_keys: Sequence[str]) -> list[CacheReadResult]:
        if not full_keys:
            return []
        started = time.perf_counter()
        try:
            if not self._enabled:
                for _ in full_keys:
                    record_cache_operation(cache_name, "bypass")
                return [CacheReadResult(CacheReadStatus.BYPASS) for _ in full_keys]
            values = await self._client.mget(list(full_keys))
            if len(values) != len(full_keys):
                raise ValueError("Redis MGET returned an unexpected value count")
            record_redis_operation("cache", "mget", "success")
            results: list[CacheReadResult] = []
            for value in values:
                if value is None:
                    record_cache_operation(cache_name, "miss")
                    results.append(CacheReadResult(CacheReadStatus.MISS))
                    continue
                payload = bytes(value)
                record_cache_operation(cache_name, "hit")
                observe_cache_payload(cache_name, len(payload))
                results.append(CacheReadResult(CacheReadStatus.HIT, payload))
            return results
        except Exception:
            record_redis_operation("cache", "mget", "error")
            for _ in full_keys:
                record_cache_operation(cache_name, "error")
            return [CacheReadResult(CacheReadStatus.ERROR) for _ in full_keys]
        finally:
            observe_cache_duration(cache_name, "get_many", time.perf_counter() - started)

    async def put_many(
        self,
        cache_name: str,
        entries: Sequence[tuple[str, bytes]],
        base_ttl: timedelta,
    ) -> CacheOperationStatus:
        if not entries:
            return CacheOperationStatus.SUCCESS
        started = time.perf_counter()
        try:
            if not self._enabled:
                for _ in entries:
                    record_cache_operation(cache_name, "bypass")
                return CacheOperationStatus.BYPASS
            async with self._client.pipeline(transaction=False) as pipeline:
                for full_key, value in entries:
                    pipeline.set(full_key, value, ex=self._ttl_jitter.apply(base_ttl))
                await pipeline.execute()
            record_redis_operation("cache", "pipeline-set", "success")
            for _, value in entries:
                record_cache_operation(cache_name, "write")
                observe_cache_payload(cache_name, len(value))
            return CacheOperationStatus.SUCCESS
        except Exception:
            record_redis_operation("cache", "pipeline-set", "error")
            for _ in entries:
                record_cache_operation(cache_name, "error")
            return CacheOperationStatus.ERROR
        finally:
            observe_cache_duration(cache_name, "put_many", time.perf_counter() - started)

    async def delete_many(self, cache_name: str, full_keys: Sequence[str]) -> CacheOperationStatus:
        if not full_keys:
            return CacheOperationStatus.SUCCESS
        started = time.perf_counter()
        try:
            if not self._enabled:
                for _ in full_keys:
                    record_cache_operation(cache_name, "bypass")
                return CacheOperationStatus.BYPASS
            await self._client.delete(*full_keys)
            record_redis_operation("cache", "delete-many", "success")
            for _ in full_keys:
                record_cache_operation(cache_name, "invalidate")
            return CacheOperationStatus.SUCCESS
        except Exception:
            record_redis_operation("cache", "delete-many", "error")
            for _ in full_keys:
                record_cache_operation(cache_name, "error")
            return CacheOperationStatus.ERROR
        finally:
            observe_cache_duration(cache_name, "delete_many", time.perf_counter() - started)
