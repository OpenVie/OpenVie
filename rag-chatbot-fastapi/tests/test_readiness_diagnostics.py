from __future__ import annotations

import asyncio
import json
from types import SimpleNamespace
from typing import Any

import pytest

from app.bootstrap import health
from app.modules.ingestion.internal.checkpoints import RedisIngestionCheckpointStore


class Redis:
    def __init__(self) -> None:
        self.scan_calls = 0
        self.hget_calls: list[tuple[Any, str]] = []

    async def ping(self) -> bool:
        return True

    async def scan(self, **_: Any) -> tuple[int, list[bytes]]:
        self.scan_calls += 1
        return 9, [b"checkpoint-1", b"checkpoint-2", b"checkpoint-3"]

    async def hget(self, key: Any, field: str) -> bytes:
        self.hget_calls.append((key, field))
        return b"CLAIMED"


def request(**state: Any) -> Any:
    return SimpleNamespace(app=SimpleNamespace(state=SimpleNamespace(**state)))


@pytest.mark.asyncio
async def test_readiness_diagnostics_are_additive_bounded_and_safe(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    redis = Redis()
    ingestion = RedisIngestionCheckpointStore(redis, prefix="private")  # type: ignore[arg-type]
    monkeypatch.setattr(health.settings, "READINESS_INGESTION_SCAN_LIMIT", 2)
    response = await health.ready(request(
        redis_client=redis,
        ingestion_diagnostics=ingestion,
        worker_manager=None,
    ))
    payload = json.loads(response.body)
    assert response.status_code == 200
    assert payload["status"] == "ready"
    assert payload["components"]["model"] in {"configured", "not_configured"}
    assert payload["diagnostics"] == {
        "ingestion": {
            "status": "UP",
            "incomplete_checkpoint_count": 2,
            "truncated": True,
        },
        "connectivity": {"redis": "UP"},
    }
    assert redis.scan_calls == 1
    assert len(redis.hget_calls) == 2
    serialized = response.body.decode().lower()
    forbidden = ("private", "checkpoint-1", "redis_url", "exception")
    assert all(value not in serialized for value in forbidden)


@pytest.mark.asyncio
async def test_diagnostic_failures_and_timeouts_do_not_change_readiness() -> None:
    class Broken:
        async def ping(self) -> bool:
            raise RuntimeError("redis://secret")

        async def inspect(self, **_: Any) -> Any:
            raise RuntimeError("payload and credential")

    response = await health.ready(request(
        redis_client=Broken(),
        ingestion_diagnostics=Broken(),
        worker_manager=None,
    ))
    payload = json.loads(response.body)
    assert payload["status"] in {"ready", "not_ready"}
    assert payload["diagnostics"]["ingestion"] == {
        "status": "UNKNOWN",
        "incomplete_checkpoint_count": None,
        "truncated": None,
    }
    assert payload["diagnostics"]["connectivity"] == {
        "redis": "DOWN",
    }
    assert "secret" not in response.body.decode().lower()


@pytest.mark.asyncio
async def test_one_timed_out_diagnostic_does_not_discard_completed_results(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class Slow:
        async def inspect(self, **_: Any) -> Any:
            await asyncio.sleep(1)

    redis = Redis()
    monkeypatch.setattr(health.settings, "READINESS_DIAGNOSTICS_TIMEOUT_SECONDS", 0.01)
    response = await health.ready(request(
        redis_client=redis,
        ingestion_diagnostics=Slow(),
        worker_manager=None,
    ))
    payload = json.loads(response.body)
    assert payload["diagnostics"]["connectivity"]["redis"] == "UP"
    assert payload["diagnostics"]["ingestion"]["status"] == "UNKNOWN"
