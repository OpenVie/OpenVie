from __future__ import annotations

from dataclasses import asdict

from app.modules.generation.api import GenerationCacheMaintenanceApi


async def run(
    maintenance: GenerationCacheMaintenanceApi, *, apply: bool, max_batches: int
) -> dict[str, int | bool]:
    return asdict(
        await maintenance.cleanup_expired(max_batches=max_batches, apply=apply)
    )
