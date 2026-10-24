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
from typing import Any
from uuid import UUID

from fastapi import APIRouter, HTTPException, status

from app.modules.lakehouse.api import (
    AuditAnalyticsSummary,
    ChatAnalyticsSummary,
    DocumentAnalyticsSummary,
    DocumentLineageGraph,
    LakehouseBatchIngestRequest,
    LakehouseDataQualitySummary,
    LakehouseDataset,
    LakehouseQueryRequest,
    LakehouseQueryResult,
    WorkflowAnalyticsSummary,
)
from app.modules.lakehouse.internal.engine import DuckDbLakehouseEngine
from app.modules.lakehouse.internal.sink import LakehouseArchiveSink

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1/lakehouse", tags=["Lakehouse Analytics"])

_engine: DuckDbLakehouseEngine | None = None
_sink: LakehouseArchiveSink | None = None


def set_lakehouse_runtime(engine: DuckDbLakehouseEngine, sink: LakehouseArchiveSink) -> None:
    global _engine, _sink
    _engine = engine
    _sink = sink


def get_engine() -> DuckDbLakehouseEngine:
    if _engine is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Lakehouse query engine is not initialized",
        )
    return _engine


def get_sink() -> LakehouseArchiveSink:
    if _sink is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Lakehouse archive sink is not initialized",
        )
    return _sink


@router.get("/health")
async def lakehouse_health() -> dict[str, Any]:
    """Lakehouse status and supported datasets."""
    engine = get_engine()
    return {
        "status": "healthy",
        "engine": "DuckDB-Snappy-Parquet",
        "datasets": [ds.value for ds in LakehouseDataset],
        "cached_partitions": len(engine._partition_cache),
    }


@router.post("/query", response_model=LakehouseQueryResult)
async def query_lakehouse(
    req: LakehouseQueryRequest,
    dataset: LakehouseDataset = LakehouseDataset.CHAT_ANALYTICS,
) -> LakehouseQueryResult:
    """Executes a read-only DuckDB SQL query over Lakehouse Parquet partitions."""
    engine = get_engine()
    try:
        return await engine.execute_query(
            query=req.query,
            tenant_id=str(req.tenant_id),
            dataset=dataset,
            limit=req.limit,
        )
    except ValueError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc
    except Exception as exc:
        logger.error("Lakehouse query execution failed: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Lakehouse query execution failed: {exc}",
        ) from exc


@router.post("/archive", status_code=status.HTTP_201_CREATED)
async def archive_batch(
    req: LakehouseBatchIngestRequest,
) -> dict[str, Any]:
    """Writes a CDC record batch into the Lakehouse Parquet store."""
    sink = get_sink()
    engine = get_engine()
    try:
        storage_key = await sink.write_partition(
            tenant_id=str(req.tenant_id),
            dataset=req.dataset,
            records=req.records,
            batch_id=str(req.batch_id),
        )
        if hasattr(sink._store, "download"):
            try:
                data = await sink._store.download(storage_key)
                engine.register_partition_bytes(storage_key, data)
            except Exception:
                pass

        return {
            "status": "archived",
            "batch_id": str(req.batch_id),
            "storage_key": storage_key,
            "record_count": len(req.records),
        }
    except Exception as exc:
        logger.error("Failed to archive batch to lakehouse: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to archive batch to lakehouse: {exc}",
        ) from exc


@router.get("/analytics/chat", response_model=ChatAnalyticsSummary)
async def get_chat_analytics(
    tenant_id: UUID,
) -> ChatAnalyticsSummary:
    """Returns aggregated chat and LLM interaction analytics."""
    return await get_engine().get_chat_summary(str(tenant_id))


@router.get("/analytics/documents", response_model=DocumentAnalyticsSummary)
async def get_document_analytics(
    tenant_id: UUID,
) -> DocumentAnalyticsSummary:
    """Returns aggregated document ingestion metrics."""
    return await get_engine().get_document_summary(str(tenant_id))


@router.get("/analytics/audit", response_model=AuditAnalyticsSummary)
async def get_audit_analytics(
    tenant_id: UUID,
) -> AuditAnalyticsSummary:
    """Returns security and audit event metrics."""
    return await get_engine().get_audit_summary(str(tenant_id))


@router.get("/analytics/workflows", response_model=WorkflowAnalyticsSummary)
async def get_workflow_analytics(
    tenant_id: UUID,
) -> WorkflowAnalyticsSummary:
    """Returns document approval workflow and SLA metrics."""
    return await get_engine().get_workflow_summary(str(tenant_id))


@router.get("/analytics/quality", response_model=LakehouseDataQualitySummary)
async def get_data_quality_analytics(
    tenant_id: UUID,
) -> LakehouseDataQualitySummary:
    """Returns automated data quality verification and scoring across Lakehouse datasets."""
    return await get_engine().evaluate_data_quality(str(tenant_id))


@router.get("/analytics/lineage/documents/{document_id}", response_model=DocumentLineageGraph)
async def get_document_lineage_graph(
    document_id: UUID,
    tenant_id: UUID,
) -> DocumentLineageGraph:
    """Returns end-to-end data lineage DAG for a specific document."""
    return await get_engine().get_document_lineage(str(tenant_id), str(document_id))
