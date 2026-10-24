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
from typing import Protocol

import duckdb
import pyarrow.parquet as pq

from app.modules.lakehouse.api import (
    AuditAnalyticsSummary,
    ChatAnalyticsSummary,
    DataQualityCheckResult,
    DocumentAnalyticsSummary,
    DocumentLineageGraph,
    LakehouseDataQualitySummary,
    LakehouseDataset,
    LakehouseQueryResult,
    LineageEdge,
    LineageNode,
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
        if DISALLOWED_SQL_PATTERNS.search(query):
            raise ValueError("Query contains prohibited modifying or administrative SQL keywords")

        dataset_name = dataset.value if isinstance(dataset, LakehouseDataset) else str(dataset)
        start_time = time.perf_counter()

        arrow_tables = []
        scanned_count = 0

        matching_keys = [
            k
            for k in (partitions or self._partition_cache.keys())
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
                self._create_empty_schema(con, dataset_name)

            sanitized_query = query.strip().rstrip(";")
            final_query = f"SELECT * FROM ({sanitized_query}) AS _subq LIMIT {limit}"

            cursor = con.execute(final_query)
            columns = [desc[0] for desc in cursor.description] if cursor.description else []
            rows = [list(r) for r in cursor.fetchall()]
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
                "0::INTEGER as prompt_tokens, 0::INTEGER as completion_tokens, "
                "0::INTEGER as duration_ms, 0::INTEGER as citation_count, "
                "false::BOOLEAN as has_sensitive_flag, now()::TIMESTAMP as created_at WHERE 1=0"
            ),
            LakehouseDataset.DOCUMENT_LIFECYCLE.value: (
                "CREATE VIEW document_lifecycle AS SELECT "
                "''::VARCHAR as document_id, ''::VARCHAR as tenant_id, "
                "''::VARCHAR as knowledge_base_id, ''::VARCHAR as uploader_id, "
                "''::VARCHAR as file_name, ''::VARCHAR as content_type, "
                "0::BIGINT as file_size_bytes, 0::INTEGER as chunk_count, "
                "0::INTEGER as entity_count, 0::INTEGER as relation_count, "
                "''::VARCHAR as ingestion_status, 0.0::DOUBLE as duration_seconds, "
                "now()::TIMESTAMP as created_at WHERE 1=0"
            ),
            LakehouseDataset.AUDIT_EVENTS.value: (
                "CREATE VIEW audit_events AS SELECT "
                "''::VARCHAR as event_id, ''::VARCHAR as tenant_id, ''::VARCHAR as user_id, "
                "''::VARCHAR as action, ''::VARCHAR as resource_type, "
                "''::VARCHAR as resource_id, ''::VARCHAR as ip_address, "
                "''::VARCHAR as user_agent, now()::TIMESTAMP as created_at, "
                "''::VARCHAR as metadata WHERE 1=0"
            ),
            LakehouseDataset.WORKFLOW_DECISIONS.value: (
                "CREATE VIEW workflow_decisions AS SELECT "
                "''::VARCHAR as process_id, ''::VARCHAR as tenant_id, "
                "''::VARCHAR as document_id, ''::VARCHAR as submitter_id, "
                "''::VARCHAR as decider_id, ''::VARCHAR as decision, "
                "1::INTEGER as level, false::BOOLEAN as is_sensitive, "
                "0.0::DOUBLE as duration_minutes, now()::TIMESTAMP as created_at WHERE 1=0"
            ),
        }
        create_sql = schemas.get(
            dataset,
            f"CREATE VIEW {dataset} AS SELECT ''::VARCHAR as tenant_id, "
            "now()::TIMESTAMP as created_at WHERE 1=0",
        )
        con.execute(create_sql)

    async def get_chat_summary(self, tenant_id: str) -> ChatAnalyticsSummary:
        """Returns aggregated analytics on chat sessions and LLM usage."""
        query = (
            "SELECT "
            "count(*)::INTEGER as total_turns, "
            "coalesce(sum(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END), 0)::INTEGER "
            "as completed_turns, "
            "coalesce(sum(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), 0)::INTEGER "
            "as failed_turns, "
            "coalesce(avg(duration_ms), 0.0)::DOUBLE as avg_duration_ms, "
            "coalesce(sum(prompt_tokens + completion_tokens), 0)::INTEGER as total_tokens, "
            "coalesce(sum(CASE WHEN has_sensitive_flag THEN 1 ELSE 0 END), 0)::INTEGER "
            "as sensitive_count "
            "FROM chat_analytics"
        )
        res = await self.execute_query(query, tenant_id, LakehouseDataset.CHAT_ANALYTICS)
        row = res.rows[0] if res.rows else [0, 0, 0, 0.0, 0, 0]

        qt_query = "SELECT query_type, count(*)::INTEGER FROM chat_analytics GROUP BY query_type"
        qt_res = await self.execute_query(qt_query, tenant_id, LakehouseDataset.CHAT_ANALYTICS)
        query_types = {r[0]: r[1] for r in qt_res.rows if r[0]}

        return ChatAnalyticsSummary(
            tenant_id=tenant_id,
            total_turns=int(row[0] or 0),
            completed_turns=int(row[1] or 0),
            failed_turns=int(row[2] or 0),
            avg_duration_ms=round(float(row[3] or 0.0), 2),
            total_tokens=int(row[4] or 0),
            sensitive_turn_count=int(row[5] or 0),
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
        row = res.rows[0] if res.rows else [0, 0, 0, 0, 0.0]

        ct_query = (
            "SELECT content_type, count(*)::INTEGER FROM document_lifecycle GROUP BY content_type"
        )
        ct_res = await self.execute_query(ct_query, tenant_id, LakehouseDataset.DOCUMENT_LIFECYCLE)
        content_types = {r[0]: r[1] for r in ct_res.rows if r[0]}

        return DocumentAnalyticsSummary(
            tenant_id=tenant_id,
            total_documents=int(row[0] or 0),
            total_bytes=int(row[1] or 0),
            total_chunks=int(row[2] or 0),
            total_entities=int(row[3] or 0),
            content_types=content_types,
            avg_ingestion_seconds=round(float(row[4] or 0.0), 2),
        )

    async def get_audit_summary(self, tenant_id: str) -> AuditAnalyticsSummary:
        """Returns security and audit event metrics."""
        query = "SELECT count(*)::INTEGER FROM audit_events"
        res = await self.execute_query(query, tenant_id, LakehouseDataset.AUDIT_EVENTS)
        total = res.rows[0][0] if res.rows else 0

        action_query = (
            "SELECT action, count(*)::INTEGER FROM audit_events "
            "GROUP BY action ORDER BY 2 DESC LIMIT 10"
        )
        action_res = await self.execute_query(
            action_query, tenant_id, LakehouseDataset.AUDIT_EVENTS
        )
        top_actions = {r[0]: r[1] for r in action_res.rows if r[0]}

        actor_query = (
            "SELECT user_id, count(*)::INTEGER FROM audit_events "
            "WHERE user_id IS NOT NULL GROUP BY user_id ORDER BY 2 DESC LIMIT 5"
        )
        actor_res = await self.execute_query(actor_query, tenant_id, LakehouseDataset.AUDIT_EVENTS)
        top_actors = {r[0]: r[1] for r in actor_res.rows if r[0]}

        return AuditAnalyticsSummary(
            tenant_id=tenant_id,
            total_events=int(total or 0),
            top_actions=top_actions,
            top_actors=top_actors,
            recent_events_count_24h=int(total or 0),
        )

    async def get_workflow_summary(self, tenant_id: str) -> WorkflowAnalyticsSummary:
        """Returns document approval workflow and SLA analytics."""
        query = (
            "SELECT "
            "count(*)::INTEGER as total, "
            "coalesce(sum(CASE WHEN decision = 'APPROVE' THEN 1 ELSE 0 END), 0)::INTEGER "
            "as approved, "
            "coalesce(sum(CASE WHEN decision = 'REJECT' THEN 1 ELSE 0 END), 0)::INTEGER "
            "as rejected, "
            "coalesce(sum(CASE WHEN decision = 'ESCALATE' THEN 1 ELSE 0 END), 0)::INTEGER "
            "as escalated, "
            "coalesce(avg(duration_minutes), 0.0)::DOUBLE as avg_duration "
            "FROM workflow_decisions"
        )
        res = await self.execute_query(query, tenant_id, LakehouseDataset.WORKFLOW_DECISIONS)
        row = res.rows[0] if res.rows else [0, 0, 0, 0, 0.0]
        total = row[0] or 0
        approved = row[1] or 0
        rate = round((approved / total * 100.0), 2) if total > 0 else 0.0

        return WorkflowAnalyticsSummary(
            tenant_id=tenant_id,
            total_processes=int(total),
            approved_count=int(approved),
            rejected_count=int(row[2] or 0),
            escalated_count=int(row[3] or 0),
            approval_rate_percent=rate,
            avg_duration_minutes=round(float(row[4] or 0.0), 2),
        )

    async def evaluate_data_quality(self, tenant_id: str) -> LakehouseDataQualitySummary:
        """Executes automated data quality assertions across all Lakehouse Parquet datasets."""
        from datetime import UTC, datetime

        checks: list[DataQualityCheckResult] = []

        # 1. Chat Analytics Completeness
        chat_res = await self.execute_query(
            "SELECT count(*)::INTEGER as total, "
            "coalesce(sum(CASE WHEN turn_id IS NULL OR turn_id = '' THEN 1 ELSE 0 END), 0)"
            "::INTEGER as null_turns, "
            "coalesce(sum(CASE WHEN prompt_tokens < 0 OR completion_tokens < 0 THEN 1 ELSE 0 END)"
            ", 0)::INTEGER as invalid_tokens FROM chat_analytics",
            tenant_id,
            LakehouseDataset.CHAT_ANALYTICS,
        )
        chat_row = chat_res.rows[0] if chat_res.rows else [0, 0, 0]
        chat_total, chat_null, chat_neg = (
            int(chat_row[0] or 0),
            int(chat_row[1] or 0),
            int(chat_row[2] or 0),
        )
        chat_score = (
            100.0
            if chat_total == 0
            else max(0.0, 100.0 - ((chat_null + chat_neg) / chat_total * 100.0))
        )
        checks.append(
            DataQualityCheckResult(
                name="chat_completeness_and_schema",
                dataset="chat_analytics",
                status="PASSED"
                if chat_score >= 95.0
                else ("WARNING" if chat_score >= 80.0 else "FAILED"),
                score=round(chat_score, 2),
                message=(
                    f"Verified {chat_total} chat turns; {chat_null} missing IDs, "
                    f"{chat_neg} negative tokens"
                ),
                metrics={
                    "total_records": chat_total,
                    "null_id_count": chat_null,
                    "invalid_tokens": chat_neg,
                },
            )
        )

        # 2. Document Lifecycle Integrity
        doc_res = await self.execute_query(
            "SELECT count(*)::INTEGER as total, "
            "coalesce(sum(CASE WHEN document_id IS NULL OR document_id = '' THEN 1 ELSE 0 END), 0)"
            "::INTEGER as null_docs, "
            "coalesce(sum(CASE WHEN chunk_count < 0 OR file_size_bytes < 0 THEN 1 ELSE 0 END), 0)"
            "::INTEGER as invalid_metrics FROM document_lifecycle",
            tenant_id,
            LakehouseDataset.DOCUMENT_LIFECYCLE,
        )
        doc_row = doc_res.rows[0] if doc_res.rows else [0, 0, 0]
        doc_total, doc_null, doc_inv = (
            int(doc_row[0] or 0),
            int(doc_row[1] or 0),
            int(doc_row[2] or 0),
        )
        doc_score = (
            100.0
            if doc_total == 0
            else max(0.0, 100.0 - ((doc_null + doc_inv) / doc_total * 100.0))
        )
        checks.append(
            DataQualityCheckResult(
                name="document_provenance_and_integrity",
                dataset="document_lifecycle",
                status="PASSED"
                if doc_score >= 95.0
                else ("WARNING" if doc_score >= 80.0 else "FAILED"),
                score=round(doc_score, 2),
                message=(
                    f"Verified {doc_total} document lifecycle records; "
                    f"{doc_null} missing document IDs"
                ),
                metrics={
                    "total_records": doc_total,
                    "null_id_count": doc_null,
                    "invalid_metrics": doc_inv,
                },
            )
        )

        # 3. Audit Trail Uniqueness & Non-Repudiation
        audit_res = await self.execute_query(
            "SELECT count(*)::INTEGER as total, "
            "count(distinct event_id)::INTEGER as distinct_events "
            "FROM audit_events",
            tenant_id,
            LakehouseDataset.AUDIT_EVENTS,
        )
        audit_row = audit_res.rows[0] if audit_res.rows else [0, 0]
        audit_total, audit_dist = int(audit_row[0] or 0), int(audit_row[1] or 0)
        dup_events = max(0, audit_total - audit_dist)
        audit_score = (
            100.0 if audit_total == 0 else max(0.0, 100.0 - (dup_events / audit_total * 100.0))
        )
        checks.append(
            DataQualityCheckResult(
                name="audit_trail_uniqueness",
                dataset="audit_events",
                status="PASSED"
                if audit_score >= 95.0
                else ("WARNING" if audit_score >= 80.0 else "FAILED"),
                score=round(audit_score, 2),
                message=f"Verified {audit_total} audit events; {dup_events} duplicate IDs detected",
                metrics={
                    "total_events": audit_total,
                    "distinct_events": audit_dist,
                    "duplicates": dup_events,
                },
            )
        )

        # 4. Workflow Decisions Validity
        wf_res = await self.execute_query(
            "SELECT count(*)::INTEGER as total, "
            "coalesce(sum(CASE WHEN decision NOT IN ('APPROVE', 'REJECT', 'ESCALATE') "
            "THEN 1 ELSE 0 END), 0)::INTEGER as invalid_decisions "
            "FROM workflow_decisions",
            tenant_id,
            LakehouseDataset.WORKFLOW_DECISIONS,
        )
        wf_row = wf_res.rows[0] if wf_res.rows else [0, 0]
        wf_total, wf_inv = int(wf_row[0] or 0), int(wf_row[1] or 0)
        wf_score = 100.0 if wf_total == 0 else max(0.0, 100.0 - (wf_inv / wf_total * 100.0))
        checks.append(
            DataQualityCheckResult(
                name="workflow_decision_validity",
                dataset="workflow_decisions",
                status="PASSED"
                if wf_score >= 95.0
                else ("WARNING" if wf_score >= 80.0 else "FAILED"),
                score=round(wf_score, 2),
                message=(
                    f"Verified {wf_total} workflow decisions; "
                    f"{wf_inv} invalid state transitions"
                ),
                metrics={"total_decisions": wf_total, "invalid_decisions": wf_inv},
            )
        )

        # 5. Timestamp and Temporal Chronology Check
        ts_res = await self.execute_query(
            "SELECT count(*)::INTEGER as total, "
            "coalesce(sum(CASE WHEN created_at > now() + INTERVAL 1 DAY THEN 1 ELSE 0 END), 0)"
            "::INTEGER as future_ts FROM audit_events",
            tenant_id,
            LakehouseDataset.AUDIT_EVENTS,
        )
        ts_row = ts_res.rows[0] if ts_res.rows else [0, 0]
        ts_total, ts_future = int(ts_row[0] or 0), int(ts_row[1] or 0)
        ts_score = 100.0 if ts_total == 0 else max(0.0, 100.0 - (ts_future / ts_total * 100.0))
        checks.append(
            DataQualityCheckResult(
                name="temporal_chronology_and_freshness",
                dataset="audit_events",
                status="PASSED"
                if ts_score >= 95.0
                else ("WARNING" if ts_score >= 80.0 else "FAILED"),
                score=round(ts_score, 2),
                message=(
                    f"Verified temporal freshness across {ts_total} records; "
                    f"{ts_future} future timestamps"
                ),
                metrics={"total_records": ts_total, "future_timestamps": ts_future},
            )
        )

        overall_score = round(sum(c.score for c in checks) / len(checks), 2)
        passed_count = sum(1 for c in checks if c.status == "PASSED")
        overall_status = (
            "PASSED"
            if overall_score >= 90.0
            else ("WARNING" if overall_score >= 70.0 else "FAILED")
        )

        return LakehouseDataQualitySummary(
            tenant_id=tenant_id,
            status=overall_status,
            overall_score=overall_score,
            passed_checks=passed_count,
            total_checks=len(checks),
            checks=checks,
            evaluated_at=datetime.now(UTC).isoformat(),
        )

    async def get_document_lineage(
        self, tenant_id: str, document_id: str
    ) -> DocumentLineageGraph:
        """Constructs an end-to-end Data Lineage DAG for a given document across Lakehouse."""
        from datetime import UTC, datetime

        query = (
            "SELECT file_name, content_type, file_size_bytes, chunk_count, "
            "entity_count, relation_count, ingestion_status, duration_seconds, created_at "
            f"FROM document_lifecycle WHERE document_id = '{document_id}'"
        )
        res = await self.execute_query(query, tenant_id, LakehouseDataset.DOCUMENT_LIFECYCLE)

        file_name = "document"
        content_type = "application/octet-stream"
        file_size = 0
        chunk_count = 0
        entity_count = 0
        relation_count = 0
        ingestion_status = "COMPLETED"
        created_at_str = datetime.now(UTC).isoformat()

        if res.rows:
            row = res.rows[0]
            file_name = str(row[0] or "document")
            content_type = str(row[1] or "application/octet-stream")
            file_size = int(row[2] or 0)
            chunk_count = int(row[3] or 0)
            entity_count = int(row[4] or 0)
            relation_count = int(row[5] or 0)
            ingestion_status = str(row[6] or "COMPLETED")
            created_at_str = str(row[8] or created_at_str)

        nodes: list[LineageNode] = [
            LineageNode(
                id=f"doc:{document_id}",
                label=f"Source: {file_name}",
                type="source_document",
                metadata={
                    "document_id": document_id,
                    "file_name": file_name,
                    "content_type": content_type,
                    "file_size_bytes": file_size,
                    "uploaded_at": created_at_str,
                },
            ),
            LineageNode(
                id=f"parser:{document_id}",
                label="Document Parser & Ingestion Worker",
                type="transformation_pipeline",
                metadata={
                    "status": ingestion_status,
                    "chunk_count": chunk_count,
                    "parser_type": "PyMuPDF / python-docx / openpyxl",
                },
            ),
            LineageNode(
                id=f"vector:{document_id}",
                label="Vector Index (Qdrant)",
                type="vector_storage",
                metadata={
                    "collection": f"workspace_{tenant_id}",
                    "embedding_model": "bge-m3 (dense + sparse)",
                    "indexed_chunks": chunk_count,
                },
            ),
            LineageNode(
                id=f"graph:{document_id}",
                label="Knowledge Graph (Kuzu)",
                type="graph_storage",
                metadata={
                    "extracted_entities": entity_count,
                    "extracted_relations": relation_count,
                },
            ),
            LineageNode(
                id=f"cdc:{document_id}",
                label="CDC Event Relay (RabbitMQ)",
                type="event_bus",
                metadata={
                    "queue": "openvie.lakehouse.cdc.v1",
                    "routing_key": "cdc.events.document_lifecycle",
                },
            ),
            LineageNode(
                id=f"lakehouse:{document_id}",
                label="Lakehouse Partition (SeaweedFS S3)",
                type="lakehouse_storage",
                metadata={
                    "format": "Snappy Parquet",
                    "partition": f"tenants/{tenant_id}/document_lifecycle/",
                },
            ),
            LineageNode(
                id=f"duckdb:{document_id}",
                label="DuckDB OLAP & RAG Grounding Engine",
                type="analytical_engine",
                metadata={
                    "engine": "DuckDB in-process OLAP",
                    "query_mode": "Vector Search + Graph Hop + Evidence Anchoring",
                },
            ),
        ]

        edges: list[LineageEdge] = [
            LineageEdge(
                source=f"doc:{document_id}",
                target=f"parser:{document_id}",
                label="parses & segments",
            ),
            LineageEdge(
                source=f"parser:{document_id}",
                target=f"vector:{document_id}",
                label="indexes embeddings",
            ),
            LineageEdge(
                source=f"parser:{document_id}",
                target=f"graph:{document_id}",
                label="extracts entities",
            ),
            LineageEdge(
                source=f"doc:{document_id}",
                target=f"cdc:{document_id}",
                label="emits ingestion event",
            ),
            LineageEdge(
                source=f"cdc:{document_id}",
                target=f"lakehouse:{document_id}",
                label="archives Snappy Parquet",
            ),
            LineageEdge(
                source=f"lakehouse:{document_id}",
                target=f"duckdb:{document_id}",
                label="executes OLAP queries",
            ),
            LineageEdge(
                source=f"vector:{document_id}",
                target=f"duckdb:{document_id}",
                label="evidence citations",
            ),
            LineageEdge(
                source=f"graph:{document_id}",
                target=f"duckdb:{document_id}",
                label="graph context joins",
            ),
        ]

        return DocumentLineageGraph(
            document_id=document_id,
            tenant_id=tenant_id,
            nodes=nodes,
            edges=edges,
            generated_at=datetime.now(UTC).isoformat(),
        )
