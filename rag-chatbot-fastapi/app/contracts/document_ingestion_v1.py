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

from datetime import datetime
from typing import Any
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, ValidationError


class DocumentIngestRequestedEvent(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: str
    event_id: UUID
    job_id: UUID
    tenant_id: UUID
    knowledge_base_id: UUID
    document_id: UUID
    uploader_id: UUID
    storage_key: str = Field(min_length=1)
    file_name: str = Field(min_length=1)
    content_type: str = Field(min_length=1)
    file_size_bytes: int = Field(ge=0)
    occurred_at: datetime

    @classmethod
    def parse_payload(cls, payload: bytes) -> DocumentIngestRequestedEvent:
        try:
            return cls.model_validate_json(payload)
        except ValidationError as exc:
            raise ValueError(f"Invalid document ingestion event: {exc}") from exc


def partial_status_ids(payload: bytes) -> dict[str, Any]:
    try:
        raw = DocumentIngestRequestedEvent.model_validate_json(payload)
    except Exception:
        try:
            import json

            raw_payload = json.loads(payload.decode("utf-8"))
        except Exception:
            raw_payload = {}
        return {
            "schema_version": str(raw_payload.get("schema_version") or "1.0"),
            "job_id": raw_payload.get("job_id"),
            "tenant_id": raw_payload.get("tenant_id"),
            "document_id": raw_payload.get("document_id"),
        }
    return {
        "schema_version": raw.schema_version,
        "job_id": str(raw.job_id),
        "tenant_id": str(raw.tenant_id),
        "document_id": str(raw.document_id),
    }

