from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import redis.asyncio as redis
from fastapi import FastAPI
from prometheus_client import make_asgi_app

from app.bootstrap.configuration import model_config
from app.bootstrap.generation import RagRuntime
from app.bootstrap.grpc import start_grpc_server
from app.bootstrap.health import router as health_router
from app.bootstrap.settings import settings
from app.bootstrap.workers import WorkerManager
from app.common.cache import RedisCacheStore, TtlJitter
from app.common.middleware import RequestIdMiddleware
from app.modules.ingestion.internal.checkpoints import RedisIngestionCheckpointStore
from app.modules.model.internal.embedding import create_embedding_client

logging.basicConfig(
    level=getattr(logging, settings.LOG_LEVEL.upper(), logging.INFO),
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    redis_client = redis.from_url(
        settings.REDIS_URL,
        decode_responses=False,
        socket_connect_timeout=settings.REDIS_CONNECT_TIMEOUT_SECONDS,
        socket_timeout=settings.REDIS_OPERATION_TIMEOUT_SECONDS,
    )
    cache_store = RedisCacheStore(
        redis_client,
        enabled=settings.CACHE_ENABLED,
        ttl_jitter=TtlJitter(settings.CACHE_TTL_JITTER_PERCENT),
    )
    embedding_client = create_embedding_client(model_config(settings), cache_store)
    runtime = RagRuntime.create(
        settings,
        embedder=embedding_client,
        cache_store=cache_store,
        redis_client=redis_client,
    )
    grpc_server = await start_grpc_server(settings, runtime, redis_client)
    manager: WorkerManager | None = None
    if settings.WORKER_MODE == "embedded":
        manager = WorkerManager(settings, embedder=embedding_client, redis_client=redis_client)
        await manager.start()
    app.state.redis_client = redis_client
    app.state.ingestion_diagnostics = RedisIngestionCheckpointStore(
        redis_client,
        prefix=settings.CACHE_KEY_PREFIX,
        retention_seconds=settings.INGESTION_CHECKPOINT_RETENTION_SECONDS,
        lease_seconds=settings.INGESTION_LEASE_SECONDS,
    )
    app.state.grpc_server = grpc_server
    app.state.worker_manager = manager
    logger.info("OpenVie internal inference service started")
    try:
        yield
    finally:
        if manager is not None:
            await manager.stop()
        await grpc_server.stop(grace=5)
        if runtime.semantic_answer_cache is not None:
            await runtime.semantic_answer_cache.close()
        await redis_client.aclose()


def create_app() -> FastAPI:
    application = FastAPI(
        title="OpenVie Internal Inference Service",
        description="Health and metrics for the internal gRPC inference service",
        version="1.0.0",
        lifespan=lifespan,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    application.add_middleware(RequestIdMiddleware)
    application.include_router(health_router)
    application.mount("/metrics", make_asgi_app())

    @application.get("/health", include_in_schema=False)
    async def legacy_health() -> dict[str, str]:
        return {"status": "healthy", "env": settings.APP_ENV, "version": "1.0.0"}

    return application


app = create_app()
