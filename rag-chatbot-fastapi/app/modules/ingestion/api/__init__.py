from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from enum import StrEnum
from typing import Protocol

from app.modules.ingestion.api.errors import (
    IngestionError,
    PermanentIngestionFailure,
    TransientIngestionFailure,
)


@dataclass(frozen=True, slots=True)
class IngestDocumentCommand:
    schema_version: str
    event_id: str
    job_id: str
    tenant_id: str
    knowledge_base_id: str
    document_id: str
    uploader_id: str
    storage_key: str
    file_name: str
    content_type: str
    file_size_bytes: int
    occurred_at: datetime


class IngestionOutcomeStatus(StrEnum):
    COMPLETED = "COMPLETED"
    DUPLICATE = "DUPLICATE"


@dataclass(frozen=True, slots=True)
class IngestionOutcome:
    status: IngestionOutcomeStatus
    chunk_count: int


class IngestionApi(Protocol):
    async def process(self, command: IngestDocumentCommand) -> IngestionOutcome: ...


class DocumentIndexLifecycleApi(Protocol):
    async def delete_document(
        self, tenant_id: str, knowledge_base_id: str, document_id: str
    ) -> None: ...


class IngestionCheckpointMaintenanceApi(Protocol):
    async def republish_incomplete(self, *, limit: int) -> int: ...


@dataclass(frozen=True, slots=True)
class ContentExtractionCommand:
    file_bytes: bytes
    file_name: str
    content_type: str


@dataclass(frozen=True, slots=True)
class SourceSegment:
    segment_id: str
    text: str
    source_location: str


@dataclass(frozen=True, slots=True)
class ExtractedContent:
    normalized_text: str
    detected_content_type: str
    character_count: int
    page_count: int | None = None
    source_segments: tuple[SourceSegment, ...] = ()


class ContentExtractionApi(Protocol):
    async def extract(self, command: ContentExtractionCommand) -> ExtractedContent: ...


__all__ = [
    "DocumentIndexLifecycleApi",
    "ContentExtractionApi",
    "ContentExtractionCommand",
    "ExtractedContent",
    "SourceSegment",
    "IngestDocumentCommand",
    "IngestionApi",
    "IngestionCheckpointMaintenanceApi",
    "IngestionError",
    "IngestionOutcome",
    "IngestionOutcomeStatus",
    "PermanentIngestionFailure",
    "TransientIngestionFailure",
]
