from __future__ import annotations

import asyncio
from dataclasses import asdict
from typing import Annotated, Any

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

from app.modules.graph.api import (
    GraphBatch,
    GraphEntity,
    GraphRelation,
    GraphSearchQuery,
    GraphUnit,
)
from app.modules.graph.internal.service import KuzuGraphRepository


class GraphEntityPayload(BaseModel):
    name: str
    normalized_name: str
    entity_type: str
    aliases: tuple[str, ...] = ()
    evidence_unit_id: str


class GraphRelationPayload(BaseModel):
    subject_normalized_name: str
    predicate: str
    object_normalized_name: str
    evidence_unit_id: str


class GraphUnitPayload(BaseModel):
    unit_id: str
    text: str
    page_number: int | None = None
    section_path: tuple[str, ...] = ()
    sheet_name: str | None = None
    cell_range: str | None = None


class GraphBatchPayload(BaseModel):
    tenant_id: str
    knowledge_base_id: str
    source_id: str
    source_name: str
    units: tuple[GraphUnitPayload, ...]
    entities: tuple[GraphEntityPayload, ...] = ()
    relations: tuple[GraphRelationPayload, ...] = ()
    extraction_version: str = "entity-relations-v1"


class GraphSearchPayload(BaseModel):
    tenant_id: str
    knowledge_base_id: str
    query: str = Field(min_length=1, max_length=2000)
    limit: int = Field(default=10, ge=1, le=100)
    max_hops: int = Field(default=0, ge=0, le=3)
    document_ids: tuple[Annotated[str, Field(min_length=1)], ...] | None = None


class DeleteSourceRequest(BaseModel):
    tenant_id: str


def create_graph_app(
    *, repository: KuzuGraphRepository, internal_token: str, database_path: str
) -> FastAPI:
    app = FastAPI(title="Cacanode Graph Service", version="1.0.0")

    def authenticate(x_graph_token: str = Header(default="")) -> None:
        if not internal_token or x_graph_token != internal_token:
            raise HTTPException(status_code=401, detail="Invalid graph service credentials")

    @app.get("/health/live")
    async def live() -> dict[str, str]:
        return {"status": "live"}

    @app.get("/health/ready")
    async def ready() -> dict[str, str]:
        await asyncio.to_thread(lambda: repository)
        return {"status": "ready", "database_path": database_path}

    @app.put("/internal/v1/sources/{source_id}", dependencies=[Depends(authenticate)])
    async def replace_source(source_id: str, payload: GraphBatchPayload) -> dict[str, str]:
        if payload.source_id != source_id:
            raise HTTPException(status_code=400, detail="Source identifier mismatch")
        await asyncio.to_thread(repository.replace_source, _batch(payload))
        return {"status": "replaced", "source_id": source_id}

    @app.delete("/internal/v1/sources/{source_id}", dependencies=[Depends(authenticate)])
    async def delete_source(source_id: str, request: DeleteSourceRequest) -> dict[str, str]:
        await asyncio.to_thread(repository.delete_source, request.tenant_id, source_id)
        return {"status": "deleted", "source_id": source_id}

    @app.post("/internal/v1/search", dependencies=[Depends(authenticate)])
    async def search(payload: GraphSearchPayload) -> dict[str, object]:
        rows = await asyncio.to_thread(
            repository.search,
            GraphSearchQuery(
                tenant_id=payload.tenant_id,
                knowledge_base_id=payload.knowledge_base_id,
                query=payload.query,
                limit=payload.limit,
                max_hops=payload.max_hops,
                document_ids=payload.document_ids,
            ),
        )
        return {"results": [_search_payload(asdict(row)) for row in rows]}

    return app


def _batch(payload: GraphBatchPayload) -> GraphBatch:
    return GraphBatch(
        tenant_id=payload.tenant_id,
        knowledge_base_id=payload.knowledge_base_id,
        source_id=payload.source_id,
        source_name=payload.source_name,
        units=tuple(
            GraphUnit(
                unit_id=item.unit_id,
                document_id=payload.source_id,
                source_name=payload.source_name,
                text=item.text,
                chunk_index=index,
                page_number=item.page_number,
                section_path=item.section_path,
                sheet_name=item.sheet_name,
                cell_range=item.cell_range,
            )
            for index, item in enumerate(payload.units)
        ),
        entities=tuple(GraphEntity(**item.model_dump()) for item in payload.entities),
        relations=tuple(GraphRelation(**item.model_dump()) for item in payload.relations),
        extraction_version=payload.extraction_version,
    )


def _search_payload(row: dict[str, Any]) -> dict[str, Any]:
    return {
        key: row[key]
        for key in (
            "entity",
            "unit_id",
            "document_id",
            "source_name",
            "text",
            "page_number",
            "section_path",
            "sheet_name",
            "cell_range",
            "score",
        )
    }
