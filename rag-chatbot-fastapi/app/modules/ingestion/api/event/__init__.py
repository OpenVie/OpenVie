from __future__ import annotations

from dataclasses import asdict, dataclass
from enum import StrEnum
from uuid import NAMESPACE_URL, uuid5


class IngestionStatus(StrEnum):
    PROCESSING = "PROCESSING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


@dataclass(frozen=True, slots=True)
class IngestionStatusEvent:
    schema_version: str
    event_id: str
    job_id: str | None
    tenant_id: str | None
    document_id: str | None
    status: IngestionStatus
    chunk_count: int | None = None
    error_message: str | None = None

    def as_payload(self) -> dict[str, object]:
        value = asdict(self)
        value["status"] = self.status.value
        return value


def status_event(
    *,
    schema_version: str,
    job_id: object | None,
    tenant_id: object | None,
    document_id: object | None,
    status: str,
    chunk_count: int | None = None,
    error_message: str | None = None,
) -> dict[str, object]:
    event = IngestionStatusEvent(
        schema_version=schema_version,
        event_id=str(uuid5(NAMESPACE_URL, f"{schema_version}:{job_id}:{status}")),
        job_id=str(job_id) if job_id is not None else None,
        tenant_id=str(tenant_id) if tenant_id is not None else None,
        document_id=str(document_id) if document_id is not None else None,
        status=IngestionStatus(status),
        chunk_count=chunk_count,
        error_message=error_message,
    )
    return event.as_payload()


__all__ = ["IngestionStatus", "IngestionStatusEvent", "status_event"]
