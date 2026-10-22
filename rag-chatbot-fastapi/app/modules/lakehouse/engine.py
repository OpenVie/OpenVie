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

import io
import logging
import re
import time
from typing import Any, Protocol

import duckdb
import pyarrow.parquet as pq

from app.modules.lakehouse.models import (
    AuditAnalyticsSummary,
    ChatAnalyticsSummary,
    DocumentAnalyticsSummary,
    LakehouseDataset,
    LakehouseQueryResult,
    WorkflowAnalyticsSummary,
)

logger = logging.getLogger(__name__)

DISALLOWED_SQL_PATTERNS = re.compile(
    r"\b(DROP|ALTER|DELETE|UPDATE|INSERT|ATTACH|DETACH|COPY|PRAGMA|EXPORT|INSTALL|LOAD)\b",
    re.IGNORECASE,
)


class PartitionLister(Protocol):
    async def list_partitions(self, tenant_id: str, dataset: str) -> list[str]: ...


class PartitionReader(Protocol):
    async def download(self, storage_key: str) -> bytes: ...


class DuckDbLakehouseEngine:
    """Analytical SQL query engine powered by DuckDB over Lakehouse Parquet partitions."""

    def __init__(
        self,
        store: PartitionReader,
        *,
        s3_endpoint: str | None = None,
        s3_bucket: str = "openvie",
        s3_access_key: str | None = None,
        s3_secret_key: str | None = None,
    ):
        self._store = store
        self._s3_endpoint = s3_endpoint
        self._s3_bucket = s3_bucket
        self._s3_access_key = s3_access_key
        self._s3_secret_key = s3_secret_key
        self._partition_cache: dict[str, bytes] = {}

    def register_partition_bytes(self, storage_key: str, data: bytes) -> None:
        """Register partition bytes for fast in-memory analytics and testing."""
        self._partition_cache[storage_key] = data

    async def execute_query(
        self,
        query: str,
        tenant_id: str,
        dataset: str | LakehouseDataset,
        *,
        limit: int = 1000,
        partitions: list[str] | None = None,
    ) -> LakehouseQueryResult:
        """Executes a read-only SQL query over the given dataset's Parquet partitions."""
        # Security validation: read-only SQL
        if DISALLOWED_SQL_PATTERNS.search(query):
            raise ValueError("Query contains prohibited modifying or administrative SQL keywords")

        dataset_name = dataset.value if isinstance(dataset, LakehouseDataset) else str(dataset)
        start_time = time.perf_counter()

        # Gather parquet tables for the requested dataset
        arrow_tables = []
        scanned_count = 0

        # Read from partition cache or download
        matching_keys = [
            k for k in (partitions or self._partition_cache.keys())
            if f"/tenants/{tenant_id}/{dataset_name}/" in k
        ]

        for k in matching_keys:
            data = self._partition_cache.get(k)
            if data is None and hasattr(self._store, "download"):
                try:
                    data = await self._store.download(k)
                except Exception as exc:
                    logger.warning("Failed to fetch partition %s: %s", k, exc)
                    continue
            if data:
                try:
                    tbl = pq.read_table(io.BytesIO(data))
                    arrow_tables.append(tbl)
                    scanned_count += 1
                except Exception as exc:
                    logger.warning("Failed to parse parquet partition %s: %s", k, exc)

        con = duckdb.connect()
        try:
            if arrow_tables:
                import pyarrow as pa
                combined = pa.concat_tables(arrow_tables)
                con.register("_raw_partition_data", combined)
                con.execute(
                    f"CREATE VIEW {dataset_name} AS SELECT * FROM _raw_partition_data "
                    f"WHERE tenant_id = '{tenant_id}'"
                )
            else:
                # Create empty schema table if no partitions exist yet
                self._create_empty_schema(con, dataset_name)

            # Ensure query executes with limit enforced
            sanitized_query = query.strip().rstrip(";")
            final_query = f"SELECT * FROM ({sanitized_query}) AS _subq LIMIT {limit}"

            cursor = con.execute(final_query)
            columns = [desc[0] for desc in cursor.description] if cursor.description else []
            rows = cursor.fetchall()
            execution_ms = (time.perf_counter() - start_time) * 1000.0

            return LakehouseQueryResult(
                columns=columns,
                rows=rows,
                row_count=len(rows),
                execution_time_ms=round(execution_ms, 2),
                scanned_partitions=scanned_count,
                dataset=dataset_name,
            )
        finally:
            con.close()

    def _create_empty_schema(self, con: duckdb.DuckDBPyConnection, dataset: str) -> None:
        """Creates empty view so queries return 0 rows rather than table-not-found errors."""
        schemas = {
            LakehouseDataset.CHAT_ANALYTICS.value: (
                "CREATE VIEW chat_analytics AS SELECT "
                "''::VARCHAR as turn_id, ''::VARCHAR as tenant_id, ''::VARCHAR as session_id, "
                "''::VARCHAR as user_id, ''::VARCHAR as status, ''::VARCHAR as query_type, "
                "0::INTEGER as prompt_tokens, 0::INTEGER as completion_tokens, 0::INTEGER as duration_ms, "
                "0::INTEGER as citation_count, false::BOOLEAN as has_sensitive_flag, now()::TIMESTAMP as created_at WHERE 1=0"
            ),
            LakehouseDataset.DOCUMENT_LIFECYCLE.value: (
                "CREATE VIEW document_lifecycle AS SELECT "
                "''::VARCHAR as document_id, ''::VARCHAR as tenant_id, ''::VARCHAR as knowledge_base_id, "
                "''::VARCHAR as uploader_id, ''::VARCHAR as file_name, ''::VARCHAR as content_type, "
                "0::BIGINT as file_size_bytes, 0::INTEGER as chunk_count, 0::INTEGER as entity_count, "
                "0::INTEGER as relation_count, ''::VARCHAR as ingestion_status, 0.0::DOUBLE as duration_seconds, "
                "now()::TIMESTAMP as created_at WHERE 1=0"
            ),
            LakehouseDataset.AUDIT_EVENTS.value: (
                "CREATE VIEW audit_events AS SELECT "
                "''::VARCHAR as event_id, ''::VARCHAR as tenant_id, ''::VARCHAR as user_id, "
                "''::VARCHAR as action, ''::VARCHAR as resource_type, ''::VARCHAR as resource_id, "
                "''::VARCHAR as ip_address, ''::VARCHAR as user_agent, now()::TIMESTAMP as created_at, "
                "''::VARCHAR as metadata WHERE 1=0"
            ),
            LakehouseDataset.WORKFLOW_DECISIONS.value: (
                "CREATE VIEW workflow_decisions AS SELECT "
                "''::VARCHAR as process_id, ''::VARCHAR as tenant_id, ''::VARCHAR as document_id, "
                "''::VARCHAR as submitter_id, ''::VARCHAR as decider_id, ''::VARCHAR as decision, "
                "1::INTEGER as level, false::BOOLEAN as is_sensitive, 0.0::DOUBLE as duration_minutes, "
                "now()::TIMESTAMP as created_at WHERE 1=0"
            ),
        }
        create_sql = schemas.get(
            dataset,
            f"CREATE VIEW {dataset} AS SELECT ''::VARCHAR as tenant_id, now()::TIMESTAMP as created_at WHERE 1=0"
        )
        con.execute(create_sql)

    async def get_chat_summary(self, tenant_id: str) -> ChatAnalyticsSummary:
        """Returns aggregated analytics on chat sessions and LLM usage."""
        query = (
            "SELECT "
            "count(*)::INTEGER as total_turns, "
            "coalesce(sum(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END), 0)::INTEGER as completed_turns, "
            "coalesce(sum(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), 0)::INTEGER as failed_turns, "
            "coalesce(avg(duration_ms), 0.0)::DOUBLE as avg_duration_ms, "
            "coalesce(sum(prompt_tokens + completion_tokens), 0)::INTEGER as total_tokens, "
            "coalesce(sum(CASE WHEN has_sensitive_flag THEN 1 ELSE 0 END), 0)::INTEGER as sensitive_count "
            "FROM chat_analytics"
        )
        res = await self.execute_query(query, tenant_id, LakehouseDataset.CHAT_ANALYTICS)
        row = res.rows[0] if res.rows else (0, 0, 0, 0.0, 0, 0)

        # Query types breakdown
        qt_query = "SELECT query_type, count(*)::INTEGER FROM chat_analytics GROUP BY query_type"
        qt_res = await self.execute_query(qt_query, tenant_id, LakehouseDataset.CHAT_ANALYTICS)
        query_types = {r[0]: r[1] for r in qt_res.rows if r[0]}

        return ChatAnalyticsSummary(
            tenant_id=tenant_id,
            total_turns=row[0] or 0,
            completed_turns=row[1] or 0,
            failed_turns=row[2] or 0,
            avg_duration_ms=round(float(row[3] or 0.0), 2),
            total_tokens=row[4] or 0,
            sensitive_turn_count=row[5] or 0,
            daily_volume=[],
            query_types=query_types,
        )

    async def get_document_summary(self, tenant_id: str) -> DocumentAnalyticsSummary:
        """Returns aggregated analytics on document ingestion volume and formats."""
        query = (
            "SELECT "
            "count(*)::INTEGER as total_docs, "
            "coalesce(sum(file_size_bytes), 0)::BIGINT as total_bytes, "
            "coalesce(sum(chunk_count), 0)::INTEGER as total_chunks, "
            "coalesce(sum(entity_count), 0)::INTEGER as total_entities, "
            "coalesce(avg(duration_seconds), 0.0)::DOUBLE as avg_seconds "
            "FROM document_lifecycle"
        )
        res = await self.execute_query(query, tenant_id, LakehouseDataset.DOCUMENT_LIFECYCLE)
        row = res.rows[0] if res.rows else (0, 0, 0, 0, 0.0)

        ct_query = "SELECT content_type, count(*)::INTEGER FROM document_lifecycle GROUP BY content_type"
        ct_res = await self.execute_query(ct_query, tenant_id, LakehouseDataset.DOCUMENT_LIFECYCLE)
        content_types = {r[0]: r[1] for r in ct_res.rows if r[0]}

        return DocumentAnalyticsSummary(
            tenant_id=tenant_id,
            total_documents=row[0] or 0,
            total_bytes=row[1] or 0,
            total_chunks=row[2] or 0,
            total_entities=row[3] or 0,
            content_types=content_types,
            avg_ingestion_seconds=round(float(row[4] or 0.0), 2),
        )

    async def get_audit_summary(self, tenant_id: str) -> AuditAnalyticsSummary:
        """Returns security and audit event metrics."""
        query = "SELECT count(*)::INTEGER FROM audit_events"
        res = await self.execute_query(query, tenant_id, LakehouseDataset.AUDIT_EVENTS)
        total = res.rows[0][0] if res.rows else 0

        action_query = "SELECT action, count(*)::INTEGER FROM audit_events GROUP BY action ORDER BY 2 DESC LIMIT 10"
        action_res = await self.execute_query(action_query, tenant_id, LakehouseDataset.AUDIT_EVENTS)
        top_actions = {r[0]: r[1] for r in action_res.rows if r[0]}

        actor_query = "SELECT user_id, count(*)::INTEGER FROM audit_events WHERE user_id IS NOT NULL GROUP BY user_id ORDER BY 2 DESC LIMIT 5"
        actor_res = await self.execute_query(actor_query, tenant_id, LakehouseDataset.AUDIT_EVENTS)
        top_actors = {r[0]: r[1] for r in actor_res.rows if r[0]}

        return AuditAnalyticsSummary(
            tenant_id=tenant_id,
            total_events=total or 0,
            top_actions=top_actions,
            top_actors=top_actors,
            recent_events_count_24h=total or 0,
        )

    async def get_workflow_summary(self, tenant_id: str) -> WorkflowAnalyticsSummary:
        """Returns document approval workflow and SLA analytics."""
        query = (
            "SELECT "
            "count(*)::INTEGER as total, "
            "coalesce(sum(CASE WHEN decision = 'APPROVE' THEN 1 ELSE 0 END), 0)::INTEGER as approved, "
            "coalesce(sum(CASE WHEN decision = 'REJECT' THEN 1 ELSE 0 END), 0)::INTEGER as rejected, "
            "coalesce(sum(CASE WHEN decision = 'ESCALATE' THEN 1 ELSE 0 END), 0)::INTEGER as escalated, "
            "coalesce(avg(duration_minutes), 0.0)::DOUBLE as avg_duration "
            "FROM workflow_decisions"
        )
        res = await self.execute_query(query, tenant_id, LakehouseDataset.WORKFLOW_DECISIONS)
        row = res.rows[0] if res.rows else (0, 0, 0, 0, 0.0)
        total = row[0] or 0
        approved = row[1] or 0
        rate = round((approved / total * 100.0), 2) if total > 0 else 0.0

        return WorkflowAnalyticsSummary(
            tenant_id=tenant_id,
            total_processes=total,
            approved_count=approved,
            rejected_count=row[2] or 0,
            escalated_count=row[3] or 0,
            approval_rate_percent=rate,
            avg_duration_minutes=round(float(row[4] or 0.0), 2),
        )
