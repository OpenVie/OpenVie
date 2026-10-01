import asyncio
import logging
from dataclasses import dataclass

from redis.asyncio import Redis

from app.bootstrap.configuration import ingestion_transport_config
from app.bootstrap.ingestion import create_document_ingestion_pipeline
from app.bootstrap.settings import Settings
from app.modules.ingestion.internal.checkpoints import RedisIngestionCheckpointStore
from app.modules.ingestion.transport.rabbitmq import DocumentWorker
from app.modules.model.api import TextEmbeddingApi

logger = logging.getLogger(__name__)

SUPPORTED_WORKERS = ("document",)


@dataclass(slots=True)
class WorkerState:
    kind: str
    running: bool = False
    capability: str = "ingestion"


class WorkerManager:
    """Runs the document ingestion worker lifecycle."""

    def __init__(
        self,
        settings: Settings,
        kinds: tuple[str, ...] | None = None,
        *,
        embedder: TextEmbeddingApi | None = None,
        redis_client: Redis | None = None,
    ):
        selected = kinds or settings.worker_kinds
        unknown = sorted(set(selected) - set(SUPPORTED_WORKERS))
        if unknown:
            raise ValueError(f"Unsupported worker kinds: {', '.join(unknown)}")
        self._settings = settings
        self._embedder = embedder
        self._checkpoints = (
            RedisIngestionCheckpointStore(
                redis_client,
                prefix=settings.CACHE_KEY_PREFIX,
                retention_seconds=settings.INGESTION_CHECKPOINT_RETENTION_SECONDS,
                lease_seconds=settings.INGESTION_LEASE_SECONDS,
            )
            if redis_client is not None
            else None
        )
        self._states = {kind: WorkerState(kind=kind) for kind in selected}
        self._tasks: list[asyncio.Task[None]] = []
        self._stop = asyncio.Event()

    @property
    def states(self) -> dict[str, WorkerState]:
        return self._states

    async def start(self) -> None:
        if self._tasks:
            return
        self._stop.clear()
        for state in self._states.values():
            state.running = True
            self._tasks.append(asyncio.create_task(self._run(state), name=f"worker-{state.kind}"))

    async def stop(self) -> None:
        self._stop.set()
        if self._tasks:
            await asyncio.gather(*self._tasks, return_exceptions=True)
        self._tasks.clear()
        for state in self._states.values():
            state.running = False

    async def _run(self, state: WorkerState) -> None:
        if state.kind != "document":
            raise ValueError(f"Unsupported worker kind: {state.kind}")
        logger.info("Worker %s started", state.kind)
        try:
            while not self._stop.is_set():
                worker = DocumentWorker(
                    ingestion_transport_config(self._settings),
                    create_document_ingestion_pipeline(self._settings, embedder=self._embedder),
                    checkpoints=self._checkpoints,
                )
                try:
                    await worker.run(self._stop)
                except asyncio.CancelledError:
                    raise
                except Exception:
                    logger.exception("Worker %s crashed; retrying", state.kind)
                    try:
                        await asyncio.wait_for(
                            self._stop.wait(),
                            timeout=self._settings.WORKER_POLL_INTERVAL_SECONDS,
                        )
                    except TimeoutError:
                        continue
        finally:
            logger.info("Worker %s stopped", state.kind)
