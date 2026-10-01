from __future__ import annotations

import hashlib
import threading
from contextlib import AbstractContextManager
from types import TracebackType

from app.common.metrics import (
    record_authoritative_load_finished,
    record_authoritative_load_started,
)


class ConcurrentLoadTracker:
    """Observe same-key loads without retaining identities or changing scheduling."""

    def __init__(self) -> None:
        self._active: dict[tuple[str, str], int] = {}
        self._lock = threading.Lock()

    def observe(self, cache_name: str, cache_key: str) -> _LoadScope:
        key_hash = hashlib.sha256(cache_key.encode("utf-8")).hexdigest()
        tracked_key = (cache_name, key_hash)
        with self._lock:
            concurrency = self._active.get(tracked_key, 0) + 1
            self._active[tracked_key] = concurrency
        metrics_started = False
        try:
            record_authoritative_load_started(cache_name, concurrency)
            metrics_started = True
        except Exception:
            pass
        return _LoadScope(self, tracked_key, metrics_started)

    def _finish(self, tracked_key: tuple[str, str], metrics_started: bool) -> None:
        with self._lock:
            concurrency = self._active.get(tracked_key, 0)
            if concurrency <= 1:
                self._active.pop(tracked_key, None)
            else:
                self._active[tracked_key] = concurrency - 1
        if metrics_started:
            try:
                record_authoritative_load_finished(tracked_key[0])
            except Exception:
                pass

    def active_key_hashes(self) -> dict[str, int]:
        with self._lock:
            return {
                f"{cache_name}:{key_hash}": count
                for (cache_name, key_hash), count in self._active.items()
            }

    @property
    def active_key_count(self) -> int:
        with self._lock:
            return len(self._active)


class _LoadScope(AbstractContextManager[None]):
    def __init__(
        self,
        tracker: ConcurrentLoadTracker,
        tracked_key: tuple[str, str],
        metrics_started: bool,
    ) -> None:
        self._tracker = tracker
        self._tracked_key = tracked_key
        self._metrics_started = metrics_started
        self._closed = False

    def __enter__(self) -> None:
        return None

    def close(self) -> None:
        if not self._closed:
            self._closed = True
            self._tracker._finish(self._tracked_key, self._metrics_started)

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc_value: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        del exc_type, exc_value, traceback
        self.close()


AUTHORITATIVE_LOAD_TRACKER = ConcurrentLoadTracker()
