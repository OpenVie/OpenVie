import asyncio

import pytest

from app.common.concurrent_loads import ConcurrentLoadTracker


def test_same_key_overlap_different_key_isolation_and_hashed_state(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    starts: list[tuple[str, int]] = []
    finishes: list[str] = []
    monkeypatch.setattr(
        "app.common.concurrent_loads.record_authoritative_load_started",
        lambda cache, concurrency: starts.append((cache, concurrency)),
    )
    monkeypatch.setattr(
        "app.common.concurrent_loads.record_authoritative_load_finished", finishes.append
    )
    tracker = ConcurrentLoadTracker()
    raw_key = "tenant:model:private-query"

    with tracker.observe("embedding", raw_key):
        with tracker.observe("embedding", raw_key):
            with tracker.observe("embedding", "different-query"):
                assert tracker.active_key_count == 2
                hashes = tracker.active_key_hashes()
                assert raw_key not in "".join(hashes)
                assert sorted(hashes.values()) == [1, 2]

    assert starts == [("embedding", 1), ("embedding", 2), ("embedding", 1)]
    assert finishes == ["embedding", "embedding", "embedding"]
    assert tracker.active_key_count == 0


def test_tracker_cleans_up_after_exception(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        "app.common.concurrent_loads.record_authoritative_load_started", lambda *_: None
    )
    monkeypatch.setattr(
        "app.common.concurrent_loads.record_authoritative_load_finished", lambda *_: None
    )
    tracker = ConcurrentLoadTracker()

    with pytest.raises(RuntimeError):
        with tracker.observe("retrieval", "key"):
            raise RuntimeError("backend failed")

    assert tracker.active_key_count == 0


def test_metric_failure_does_not_change_execution_or_leak_state(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def fail_metrics(*_args: object) -> None:
        raise RuntimeError("registry unavailable")

    monkeypatch.setattr(
        "app.common.concurrent_loads.record_authoritative_load_started", fail_metrics
    )
    tracker = ConcurrentLoadTracker()

    with tracker.observe("embedding", "private-key"):
        assert tracker.active_key_count == 1

    assert tracker.active_key_count == 0


@pytest.mark.asyncio
async def test_tracker_cleans_up_after_cancellation_and_timeout(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        "app.common.concurrent_loads.record_authoritative_load_started", lambda *_: None
    )
    monkeypatch.setattr(
        "app.common.concurrent_loads.record_authoritative_load_finished", lambda *_: None
    )
    tracker = ConcurrentLoadTracker()
    entered = asyncio.Event()

    async def cancelled_load() -> None:
        with tracker.observe("embedding", "cancelled"):
            entered.set()
            await asyncio.Event().wait()

    task = asyncio.create_task(cancelled_load())
    await entered.wait()
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert tracker.active_key_count == 0

    with pytest.raises(TimeoutError):
        async with asyncio.timeout(0.01):
            with tracker.observe("retrieval", "timeout"):
                await asyncio.Event().wait()
    assert tracker.active_key_count == 0
