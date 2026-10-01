from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True, slots=True)
class GraphEntity:
    name: str
    normalized_name: str
    entity_type: str
    evidence_unit_id: str
    aliases: tuple[str, ...] = ()


@dataclass(frozen=True, slots=True)
class GraphRelation:
    subject_normalized_name: str
    predicate: str
    object_normalized_name: str
    evidence_unit_id: str


@dataclass(frozen=True, slots=True)
class GraphUnit:
    unit_id: str
    document_id: str
    source_name: str
    text: str
    chunk_index: int
    page_number: int | None = None
    modality: str = "document"
    section_path: tuple[str, ...] = ()
    block_type: str | None = None
    sheet_name: str | None = None
    cell_range: str | None = None
    table_id: str | None = None


@dataclass(frozen=True, slots=True)
class GraphBatch:
    tenant_id: str
    knowledge_base_id: str
    source_id: str
    source_name: str
    units: tuple[GraphUnit, ...]
    entities: tuple[GraphEntity, ...] = ()
    relations: tuple[GraphRelation, ...] = ()
    extraction_version: str = "entity-relations-v1"

    def __post_init__(self) -> None:
        converted = tuple(
            unit
            if isinstance(unit, GraphUnit)
            else GraphUnit(
                unit_id=str(unit.get("unit_id") or index),
                document_id=self.source_id,
                source_name=self.source_name,
                text=str(unit.get("text") or ""),
                chunk_index=int(unit.get("chunk_index", index)),
                page_number=unit.get("page_number"),
                modality=str(unit.get("modality") or "document"),
                section_path=tuple(unit.get("section_path", ())),
                block_type=unit.get("block_type"),
                sheet_name=unit.get("sheet_name"),
                cell_range=unit.get("cell_range"),
                table_id=unit.get("table_id"),
            )
            for index, unit in enumerate(self.units)
        )
        object.__setattr__(self, "units", converted)
        unit_ids = {unit.unit_id for unit in converted}
        entity_names = {entity.normalized_name for entity in self.entities}
        if any(entity.evidence_unit_id not in unit_ids for entity in self.entities):
            raise ValueError("Entity mention references an unknown evidence unit")
        if any(
            relation.evidence_unit_id not in unit_ids
            or relation.subject_normalized_name not in entity_names
            or relation.object_normalized_name not in entity_names
            for relation in self.relations
        ):
            raise ValueError("Relation is not grounded in known entities and evidence")


@dataclass(frozen=True, slots=True)
class GraphSearchQuery:
    tenant_id: str
    knowledge_base_id: str
    query: str
    limit: int = 10
    max_hops: int = 0
    document_ids: tuple[str, ...] | None = None

    def __post_init__(self) -> None:
        if not self.tenant_id or not self.knowledge_base_id:
            raise ValueError("Graph search scope must be non-empty")
        if not self.query.strip():
            raise ValueError("Graph search query must be non-empty")
        if not 1 <= self.limit <= 100:
            raise ValueError("Graph search limit must be between 1 and 100")
        if not 0 <= self.max_hops <= 3:
            raise ValueError("Graph search max_hops must be between 0 and 3")
        if self.document_ids is None:
            return
        if any(not str(document_id).strip() for document_id in self.document_ids):
            raise ValueError("Graph search document identifiers must be non-empty")
        object.__setattr__(
            self,
            "document_ids",
            tuple(sorted({str(document_id) for document_id in self.document_ids})),
        )


@dataclass(frozen=True, slots=True)
class GraphSearchResult:
    unit_id: str
    document_id: str
    source_name: str
    text: str
    chunk_index: int = 0
    score: float = 1.0
    entity: str | None = None
    page_number: int | None = None
    modality: str = "document"
    section_path: tuple[str, ...] = ()
    block_type: str | None = None
    sheet_name: str | None = None
    cell_range: str | None = None
    table_id: str | None = None


class GraphError(Exception):
    pass


class GraphRejectedError(GraphError):
    pass


class GraphUnavailableError(GraphError):
    pass


class GraphProjectionApi(Protocol):
    async def replace_source(self, batch: GraphBatch) -> None: ...

    async def delete_source(self, tenant_id: str, document_id: str) -> None: ...


class GraphQueryApi(Protocol):
    async def search(self, query: GraphSearchQuery) -> Sequence[GraphSearchResult]: ...


__all__ = [
    "GraphBatch",
    "GraphEntity",
    "GraphError",
    "GraphProjectionApi",
    "GraphQueryApi",
    "GraphRejectedError",
    "GraphRelation",
    "GraphSearchQuery",
    "GraphSearchResult",
    "GraphUnavailableError",
    "GraphUnit",
]
