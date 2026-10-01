from __future__ import annotations

import hashlib
import json
import time
from collections.abc import Awaitable
from dataclasses import dataclass
from enum import StrEnum
from typing import Any, cast
from uuid import uuid4

from redis.asyncio import Redis

from app.modules.ingestion.api.diagnostics import IngestionDiagnostic


class IngestionPhase(StrEnum):
    CLAIMED = "CLAIMED"
    PROCESSING_PUBLISHED = "PROCESSING_PUBLISHED"
    INDEX_REPLACED = "INDEX_REPLACED"
    GRAPH_REPLACED = "GRAPH_REPLACED"
    COMPLETED_PUBLISHED = "COMPLETED_PUBLISHED"
    COMPLETE = "COMPLETE"
    CLEANUP_PENDING = "CLEANUP_PENDING"
    FAILED_PUBLISHED = "FAILED_PUBLISHED"
    FAILED = "FAILED"


class ClaimStatus(StrEnum):
    CLAIMED = "CLAIMED"
    RESUMED = "RESUMED"
    BUSY = "BUSY"
    COMPLETE = "COMPLETE"
    PAYLOAD_MISMATCH = "PAYLOAD_MISMATCH"


@dataclass(frozen=True, slots=True)
class CheckpointClaim:
    status: ClaimStatus
    phase: IngestionPhase
    lease_token: str | None
    canonical_request: bytes
    payload_hash: str


_CLAIM_SCRIPT = """
local existing_hash = redis.call('GET', KEYS[1])
if existing_hash and existing_hash ~= ARGV[1] then return {'PAYLOAD_MISMATCH', '', ''} end
if not existing_hash then redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[5]) end
local job_hash = redis.call('HGET', KEYS[2], 'payload_hash')
if job_hash and job_hash ~= ARGV[1] then return {'PAYLOAD_MISMATCH', '', ''} end
local phase = redis.call('HGET', KEYS[2], 'phase')
if phase == 'COMPLETE' or phase == 'FAILED' then return {'COMPLETE', phase, ''} end
local lease = redis.call('GET', KEYS[3])
if lease then return {'BUSY', phase or 'CLAIMED', ''} end
redis.call('SET', KEYS[3], ARGV[2], 'EX', ARGV[4])
if not phase then
  redis.call('HSET', KEYS[2],
    'event_id', ARGV[6], 'job_id', ARGV[7], 'canonical_request', ARGV[3],
    'payload_hash', ARGV[1], 'phase', 'CLAIMED', 'chunk_count', '', 'error', '',
    'created_at', ARGV[8], 'updated_at', ARGV[8],
    'processing_published', '0', 'completed_published', '0', 'failed_published', '0')
  redis.call('EXPIRE', KEYS[2], ARGV[5])
  return {'CLAIMED', 'CLAIMED', ARGV[2]}
end
redis.call('HSET', KEYS[2], 'updated_at', ARGV[8])
redis.call('EXPIRE', KEYS[2], ARGV[5])
return {'RESUMED', phase, ARGV[2]}
"""

_RENEW_SCRIPT = """
if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
redis.call('EXPIRE', KEYS[1], ARGV[2])
redis.call('HSET', KEYS[2], 'updated_at', ARGV[3])
return 1
"""

_TRANSITION_SCRIPT = """
if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
redis.call('HSET', KEYS[2], 'phase', ARGV[2], 'updated_at', ARGV[3])
if ARGV[4] ~= '' then redis.call('HSET', KEYS[2], 'chunk_count', ARGV[4]) end
if ARGV[5] ~= '' then redis.call('HSET', KEYS[2], 'error', ARGV[5]) end
if ARGV[6] ~= '' then redis.call('HSET', KEYS[2], ARGV[6], '1') end
redis.call('EXPIRE', KEYS[2], ARGV[7])
return 1
"""

_RELEASE_SCRIPT = """
if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
return 0
"""


class RedisIngestionCheckpointStore:
    def __init__(
        self,
        redis_client: Redis,
        *,
        prefix: str = "ccn:v1",
        retention_seconds: int = 30 * 24 * 60 * 60,
        lease_seconds: int = 300,
    ) -> None:
        self._redis = redis_client
        self._prefix = prefix.rstrip(":")
        self._retention = retention_seconds
        self._lease = lease_seconds

    async def claim(
        self, *, event_id: str, job_id: str, request_payload: bytes
    ) -> CheckpointClaim:
        canonical = canonical_request_json(request_payload)
        payload_hash = hashlib.sha256(canonical).hexdigest()
        token = str(uuid4())
        now = str(int(time.time()))
        raw = await cast(Awaitable[Any], self._redis.eval(
            _CLAIM_SCRIPT,
            3,
            self.event_key(event_id),
            self.job_key(job_id),
            self.lease_key(job_id),
            payload_hash,
            token,
            canonical,
            self._lease,
            self._retention,
            event_id,
            job_id,
            now,
        ))
        values = [_text(item) for item in raw]
        status = ClaimStatus(values[0])
        phase = IngestionPhase(values[1] or IngestionPhase.CLAIMED)
        return CheckpointClaim(
            status=status,
            phase=phase,
            lease_token=values[2] or None,
            canonical_request=canonical,
            payload_hash=payload_hash,
        )

    async def renew(self, job_id: str, lease_token: str) -> bool:
        result = await cast(Awaitable[Any], self._redis.eval(
            _RENEW_SCRIPT,
            2,
            self.lease_key(job_id),
            self.job_key(job_id),
            lease_token,
            self._lease,
            str(int(time.time())),
        ))
        return bool(result)

    async def transition(
        self,
        job_id: str,
        lease_token: str,
        phase: IngestionPhase,
        *,
        chunk_count: int | None = None,
        error: str | None = None,
        published_flag: str = "",
    ) -> None:
        bounded_error = (error or "")[:2000]
        result = await cast(Awaitable[Any], self._redis.eval(
            _TRANSITION_SCRIPT,
            2,
            self.lease_key(job_id),
            self.job_key(job_id),
            lease_token,
            phase.value,
            str(int(time.time())),
            "" if chunk_count is None else str(chunk_count),
            bounded_error,
            published_flag,
            self._retention,
        ))
        if not result:
            raise RuntimeError("Ingestion checkpoint lease was lost")

    async def release(self, job_id: str, lease_token: str) -> None:
        await cast(Awaitable[Any], self._redis.eval(
            _RELEASE_SCRIPT, 1, self.lease_key(job_id), lease_token
        ))

    async def incomplete_requests(self, *, limit: int) -> list[bytes]:
        requests: list[bytes] = []
        cursor: int = 0
        pattern = f"{self._prefix}:ingestion:job:*"
        while len(requests) < limit:
            cursor, keys = await self._redis.scan(
                cursor=cursor, match=pattern, count=min(limit, 100)
            )
            for key in keys:
                values = await cast(
                    Awaitable[list[Any]],
                    self._redis.hmget(key, ["phase", "canonical_request"]),
                )
                phase = _text(values[0])
                if phase not in {IngestionPhase.COMPLETE, IngestionPhase.FAILED} and values[1]:
                    value = values[1]
                    requests.append(value if isinstance(value, bytes) else str(value).encode())
                    if len(requests) >= limit:
                        break
            if cursor == 0:
                break
        return requests

    async def inspect(self, *, scan_limit: int) -> IngestionDiagnostic:
        inspected = 0
        incomplete = 0
        cursor: int = 0
        pattern = f"{self._prefix}:ingestion:job:*"
        truncated = False
        while inspected < scan_limit:
            cursor, keys = await self._redis.scan(
                cursor=cursor,
                match=pattern,
                count=min(scan_limit - inspected, 100),
            )
            remaining = scan_limit - inspected
            selected = keys[:remaining]
            for key in selected:
                phase = _text(
                    await cast(Awaitable[Any], self._redis.hget(key, "phase"))
                )
                if phase not in {IngestionPhase.COMPLETE, IngestionPhase.FAILED}:
                    incomplete += 1
            inspected += len(selected)
            if len(keys) > len(selected):
                truncated = True
                break
            if cursor == 0:
                break
        if cursor != 0:
            truncated = True
        return IngestionDiagnostic(
            incomplete_checkpoint_count=incomplete,
            truncated=truncated,
        )

    def event_key(self, event_id: str) -> str:
        return f"{self._prefix}:ingestion:event:{event_id}"

    def job_key(self, job_id: str) -> str:
        return f"{self._prefix}:ingestion:job:{job_id}"

    def lease_key(self, job_id: str) -> str:
        return f"{self._prefix}:ingestion:lease:{job_id}"


def canonical_request_json(payload: bytes) -> bytes:
    value: Any = json.loads(payload.decode("utf-8"))
    if not isinstance(value, dict):
        raise ValueError("Ingestion request must be a JSON object")
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


def _text(value: Any) -> str:
    if isinstance(value, bytes):
        return value.decode("utf-8")
    return "" if value is None else str(value)
