from __future__ import annotations

import asyncio
from collections.abc import Awaitable
from typing import Any

from fastapi import APIRouter, Request, status
from fastapi.responses import JSONResponse

from app.bootstrap.settings import settings
from app.bootstrap.workers import WorkerManager
from app.modules.ingestion.api.diagnostics import IngestionDiagnostics

router = APIRouter(tags=["health"])


@router.get("/health/live")
async def live() -> dict[str, str]:
    return {"status": "live", "environment": settings.APP_ENV}


@router.get("/health/ready")
async def ready(request: Request) -> JSONResponse:
    manager: WorkerManager | None = getattr(request.app.state, "worker_manager", None)
    workers = {
        kind: {"running": state.running, "capability": state.capability}
        for kind, state in (manager.states.items() if manager else [])
    }
    model_ready = settings.model_configured
    is_ready = model_ready or not settings.READINESS_REQUIRE_MODELS
    diagnostics = await _diagnostics(request)
    payload = {
        "status": "ready" if is_ready else "not_ready",
        "components": {
            "model": "configured" if model_ready else "not_configured",
            "workers": workers,
        },
        "diagnostics": diagnostics,
    }
    return JSONResponse(
        status_code=status.HTTP_200_OK if is_ready else status.HTTP_503_SERVICE_UNAVAILABLE,
        content=payload,
    )


async def _diagnostics(request: Request) -> dict[str, Any]:
    redis_client = getattr(request.app.state, "redis_client", None)
    ingestion: IngestionDiagnostics | None = getattr(
        request.app.state, "ingestion_diagnostics", None
    )
    redis_status, ingestion_status = await asyncio.gather(
        _bounded(_redis_status(redis_client)),
        _bounded(_ingestion_status(ingestion)),
    )
    return {
        "ingestion": (
            ingestion_status
            if isinstance(ingestion_status, dict)
            else {
                "status": "UNKNOWN",
                "incomplete_checkpoint_count": None,
                "truncated": None,
            }
        ),
        "connectivity": {
            "redis": redis_status if redis_status in {"UP", "DOWN"} else "DOWN",
        },
    }


async def _bounded(value: Awaitable[Any]) -> Any:
    try:
        return await asyncio.wait_for(
            value, timeout=settings.READINESS_DIAGNOSTICS_TIMEOUT_SECONDS
        )
    except Exception:
        return None


async def _redis_status(redis_client: Any) -> str:
    if redis_client is None:
        return "DOWN"
    try:
        return "UP" if await redis_client.ping() else "DOWN"
    except Exception:
        return "DOWN"


async def _ingestion_status(diagnostics: IngestionDiagnostics | None) -> dict[str, Any]:
    if diagnostics is None:
        raise RuntimeError("diagnostics unavailable")
    value = await diagnostics.inspect(scan_limit=settings.READINESS_INGESTION_SCAN_LIMIT)
    return {
        "status": "UP",
        "incomplete_checkpoint_count": value.incomplete_checkpoint_count,
        "truncated": value.truncated,
    }
