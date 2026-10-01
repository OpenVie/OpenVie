from app.modules.ingestion.api import IngestionCheckpointMaintenanceApi


async def run(maintenance: IngestionCheckpointMaintenanceApi, *, limit: int) -> int:
    return await maintenance.republish_incomplete(limit=limit)
