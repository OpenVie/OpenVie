from __future__ import annotations

import asyncio
import json
import os
from urllib.parse import urlsplit, urlunsplit
from uuid import uuid4

import pytest
import redis.asyncio as redis

from app.modules.ingestion.internal.checkpoints import (
    ClaimStatus,
    IngestionPhase,
    RedisIngestionCheckpointStore,
)


def _database_14_url(url: str) -> str:
    parts = urlsplit(url)
    return urlunsplit((parts.scheme, parts.netloc, "/14", parts.query, parts.fragment))


@pytest.mark.asyncio
@pytest.mark.skipif(not os.getenv("REDIS_TEST_URL"), reason="REDIS_TEST_URL is not set")
async def test_checkpoint_claim_resume_mismatch_expiry_and_terminal_state() -> None:
    client = redis.from_url(
        _database_14_url(os.environ["REDIS_TEST_URL"]), decode_responses=False
    )
    prefix = f"ccn:test:{uuid4().hex}"
    store = RedisIngestionCheckpointStore(
        client, prefix=prefix, retention_seconds=60, lease_seconds=1
    )
    event_id = str(uuid4())
    job_id = str(uuid4())
    payload = json.dumps(
        {"schema_version": "1.0", "event_id": event_id, "job_id": job_id},
        sort_keys=True,
    ).encode()
    try:
        first = await store.claim(event_id=event_id, job_id=job_id, request_payload=payload)
        assert first.status is ClaimStatus.CLAIMED
        busy = await store.claim(event_id=event_id, job_id=job_id, request_payload=payload)
        assert busy.status is ClaimStatus.BUSY

        await asyncio.sleep(1.1)
        resumed = await store.claim(event_id=event_id, job_id=job_id, request_payload=payload)
        assert resumed.status is ClaimStatus.RESUMED
        assert resumed.phase is IngestionPhase.CLAIMED
        assert resumed.lease_token is not None

        mismatch = await store.claim(
            event_id=event_id,
            job_id=job_id,
            request_payload=payload.replace(b"1.0", b"1.1"),
        )
        assert mismatch.status is ClaimStatus.PAYLOAD_MISMATCH

        await store.transition(
            job_id,
            resumed.lease_token,
            IngestionPhase.INDEX_REPLACED,
            chunk_count=4,
        )
        assert await store.incomplete_requests(limit=10) == [
            resumed.canonical_request
        ]
        await store.transition(
            job_id,
            resumed.lease_token,
            IngestionPhase.COMPLETE,
            chunk_count=4,
        )
        assert await store.incomplete_requests(limit=10) == []
    finally:
        keys = [key async for key in client.scan_iter(match=f"{prefix}:*")]
        if keys:
            await client.delete(*keys)
        await client.aclose()
