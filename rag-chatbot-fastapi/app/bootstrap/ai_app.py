# Copyright 2026 LinkedNodeDigital
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import redis.asyncio as redis
from fastapi import FastAPI
from prometheus_client import make_asgi_app

from app.bootstrap.configuration import model_config, storage_config
from app.bootstrap.generation import RagRuntime
from app.bootstrap.grpc import start_grpc_server
from app.bootstrap.health import router as health_router
from app.bootstrap.settings import settings
from app.bootstrap.workers import WorkerManager
from app.common.cache import RedisCacheStore, TtlJitter
from app.common.middleware import RequestIdMiddleware
from app.common.storage import SeaweedS3DocumentStore
from app.modules.ingestion.internal.checkpoints import RedisIngestionCheckpointStore
from app.modules.lakehouse import (
    DuckDbLakehouseEngine,
    LakehouseArchiveSink,
    LakehouseCdcWorker,
    lakehouse_router,
    set_lakehouse_runtime,
)
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
    cdc_worker: LakehouseCdcWorker | None = None
    if settings.WORKER_MODE == "embedded" and settings.RABBITMQ_URL:
        store = SeaweedS3DocumentStore(storage_config(settings))
        lakehouse_sink = LakehouseArchiveSink(store)
        cdc_worker = LakehouseCdcWorker(settings.RABBITMQ_URL, lakehouse_sink)
        try:
            await cdc_worker.start()
        except Exception as exc:
            logger.warning("Could not start background Lakehouse CDC worker: %s", exc)
            cdc_worker = None
        app.state.lakehouse_cdc_worker = cdc_worker
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
        if cdc_worker is not None:
            await cdc_worker.stop()
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
    store = SeaweedS3DocumentStore(storage_config(settings))
    lakehouse_sink = LakehouseArchiveSink(store)
    lakehouse_engine = DuckDbLakehouseEngine(
        store,
        s3_endpoint=settings.SEAWEEDFS_S3_ENDPOINT,
        s3_bucket=settings.SEAWEEDFS_BUCKET,
        s3_access_key=settings.SEAWEEDFS_ACCESS_KEY,
        s3_secret_key=settings.SEAWEEDFS_SECRET_KEY,
    )
    set_lakehouse_runtime(lakehouse_engine, lakehouse_sink)
    application.include_router(lakehouse_router)
    application.mount("/metrics", make_asgi_app())

    @application.get("/health", include_in_schema=False)
    async def legacy_health() -> dict[str, str]:
        return {"status": "healthy", "env": settings.APP_ENV, "version": "1.0.0"}

    return application


app = create_app()
