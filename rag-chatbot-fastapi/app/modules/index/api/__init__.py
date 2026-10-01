from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True, slots=True)
class IndexSparseVector:
    indices: tuple[int, ...]
    values: tuple[float, ...]


@dataclass(frozen=True, slots=True)
class IndexUnit:
    unit_id: str
    chunk_index: int
    text: str
    content_hash: str
    source_name: str
    modality: str
    block_type: str
    section_path: tuple[str, ...] = ()
    heading_context: str | None = None
    page_number: int | None = None
    sheet_name: str | None = None
    cell_range: str | None = None
    table_id: str | None = None
    source_start: int | None = None
    source_end: int | None = None
    parser_version: str = "digital-v1"
    chunker_version: str = "structural-v2"


@dataclass(frozen=True, slots=True)
class ReplaceDocumentIndex:
    tenant_id: str
    knowledge_base_id: str
    document_id: str
    source_name: str
    units: tuple[IndexUnit, ...]
    dense_vectors: tuple[tuple[float, ...], ...]
    sparse_vectors: tuple[IndexSparseVector, ...]


@dataclass(frozen=True, slots=True)
class KnowledgeIndexQuery:
    tenant_id: str
    knowledge_base_id: str
    query_vector: tuple[float, ...]
    limit: int
    document_ids: tuple[str, ...] | None = None


@dataclass(frozen=True, slots=True)
class SparseKnowledgeIndexQuery:
    tenant_id: str
    knowledge_base_id: str
    query_vector: IndexSparseVector
    limit: int
    document_ids: tuple[str, ...] | None = None


@dataclass(frozen=True, slots=True)
class NeighborQuery:
    tenant_id: str
    knowledge_base_id: str
    document_id: str
    chunk_index: int
    section_path: tuple[str, ...]
    document_ids: tuple[str, ...] | None = None


@dataclass(frozen=True, slots=True)
class KnowledgeIndexResult:
    document_id: str
    source_name: str
    page_number: int | None
    chunk_index: int
    text: str
    score: float
    unit_id: str | None = None
    modality: str | None = None
    section_path: tuple[str, ...] = ()
    block_type: str | None = None
    heading_context: str | None = None
    sheet_name: str | None = None
    cell_range: str | None = None
    table_id: str | None = None
    source_start: int | None = None
    source_end: int | None = None


DocumentUnit = KnowledgeIndexResult


class KnowledgeIndexError(Exception):
    pass


class IndexRejectedError(KnowledgeIndexError):
    pass


class IndexUnavailableError(KnowledgeIndexError):
    pass


class KnowledgeIndexCommandApi(Protocol):
    async def replace_document(self, command: ReplaceDocumentIndex) -> None: ...

    async def delete_document(self, tenant_id: str, document_id: str) -> None: ...


class KnowledgeIndexQueryApi(Protocol):
    async def search_dense(self, query: KnowledgeIndexQuery) -> Sequence[KnowledgeIndexResult]: ...

    async def search_sparse(
        self, query: SparseKnowledgeIndexQuery
    ) -> Sequence[KnowledgeIndexResult]: ...

    async def load_neighbors(self, query: NeighborQuery) -> Sequence[KnowledgeIndexResult]: ...

    async def list_document_units(
        self, tenant_id: str, document_id: str
    ) -> Sequence[KnowledgeIndexResult]: ...


__all__ = [
    "DocumentUnit",
    "IndexRejectedError",
    "IndexUnavailableError",
    "IndexUnit",
    "IndexSparseVector",
    "KnowledgeIndexCommandApi",
    "KnowledgeIndexError",
    "KnowledgeIndexQuery",
    "KnowledgeIndexQueryApi",
    "KnowledgeIndexResult",
    "NeighborQuery",
    "ReplaceDocumentIndex",
    "SparseKnowledgeIndexQuery",
]
