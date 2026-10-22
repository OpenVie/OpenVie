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

from datetime import UTC, datetime
from enum import StrEnum
from typing import Any
from uuid import UUID, uuid4

from pydantic import BaseModel, ConfigDict, Field


class LakehouseDataset(StrEnum):
    AUDIT_EVENTS = "audit_events"
    CHAT_ANALYTICS = "chat_analytics"
    DOCUMENT_LIFECYCLE = "document_lifecycle"
    WORKFLOW_DECISIONS = "workflow_decisions"


class CdcOperation(StrEnum):
    INSERT = "INSERT"
    UPDATE = "UPDATE"
    DELETE = "DELETE"
    SNAPSHOT = "SNAPSHOT"


class CdcRecord(BaseModel):
    model_config = ConfigDict(extra="ignore")

    event_id: UUID = Field(default_factory=uuid4)
    tenant_id: UUID
    dataset: LakehouseDataset
    op: CdcOperation = CdcOperation.INSERT
    timestamp: datetime = Field(default_factory=lambda: datetime.now(UTC))
    data: dict[str, Any]


class LakehouseBatchIngestRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    batch_id: UUID = Field(default_factory=uuid4)
    tenant_id: UUID
    dataset: LakehouseDataset
    records: list[dict[str, Any]] = Field(min_length=1)


class LakehouseQueryRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    query: str = Field(min_length=1, max_length=10000)
    tenant_id: UUID
    limit: int = Field(default=1000, ge=1, le=50000)


class LakehouseQueryResult(BaseModel):
    columns: list[str]
    rows: list[list[Any]]
    row_count: int
    execution_time_ms: float
    scanned_partitions: int
    dataset: str | None = None


class ChatAnalyticsSummary(BaseModel):
    tenant_id: str
    total_turns: int
    completed_turns: int
    failed_turns: int
    avg_duration_ms: float
    total_tokens: int
    sensitive_turn_count: int
    daily_volume: list[dict[str, Any]]
    query_types: dict[str, int]


class DocumentAnalyticsSummary(BaseModel):
    tenant_id: str
    total_documents: int
    total_bytes: int
    total_chunks: int
    total_entities: int
    content_types: dict[str, int]
    avg_ingestion_seconds: float


class AuditAnalyticsSummary(BaseModel):
    tenant_id: str
    total_events: int
    top_actions: dict[str, int]
    top_actors: dict[str, int]
    recent_events_count_24h: int


class WorkflowAnalyticsSummary(BaseModel):
    tenant_id: str
    total_processes: int
    approved_count: int
    rejected_count: int
    escalated_count: int
    approval_rate_percent: float
    avg_duration_minutes: float
