import pytest

from app.bootstrap.settings import Settings
from app.bootstrap.workers import WorkerManager


def test_environment_parsing() -> None:
    configured = Settings(
        CORS_ORIGINS="http://localhost:3000, http://127.0.0.1:3000",
        WORKER_KINDS="document",
    )

    assert configured.cors_origins == [
        "http://localhost:3000",
        "http://127.0.0.1:3000",
    ]
    assert configured.worker_kinds == ("document",)


@pytest.mark.asyncio
async def test_worker_manager_lifecycle() -> None:
    configured = Settings(WORKER_KINDS="document", WORKER_POLL_INTERVAL_SECONDS=0.01)
    manager = WorkerManager(configured)

    await manager.start()
    assert all(state.running for state in manager.states.values())

    await manager.stop()
    assert not any(state.running for state in manager.states.values())


def test_worker_manager_rejects_unknown_worker() -> None:
    configured = Settings(WORKER_KINDS="ocr")

    with pytest.raises(ValueError, match="Unsupported worker kinds"):
        WorkerManager(configured)
