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
from datetime import UTC, datetime
from uuid import uuid4

import pyarrow.parquet as pq
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.modules.lakehouse.api import LakehouseDataset
from app.modules.lakehouse.internal.engine import DuckDbLakehouseEngine
from app.modules.lakehouse.internal.sink import LakehouseArchiveSink
from app.modules.lakehouse.transport.http import router as lakehouse_router
from app.modules.lakehouse.transport.http import set_lakehouse_runtime


class MemoryStore:
    def __init__(self) -> None:
        self.objects: dict[str, bytes] = {}

    async def upload(self, storage_key: str, data: bytes, *, content_type: str) -> None:
        self.objects[storage_key] = data

    async def download(self, storage_key: str) -> bytes:
        if storage_key not in self.objects:
            raise KeyError(storage_key)
        return self.objects[storage_key]


TENANT_A = "00000000-0000-0000-0000-000000000001"
TENANT_B = "00000000-0000-0000-0000-000000000002"


@pytest.mark.asyncio
async def test_sink_writes_valid_snappy_parquet_partition() -> None:
    store = MemoryStore()
    sink = LakehouseArchiveSink(store)

    records = [
        {
            "turn_id": str(uuid4()),
            "session_id": str(uuid4()),
            "status": "COMPLETED",
            "query_type": "search",
            "duration_ms": 150,
            "prompt_tokens": 50,
            "completion_tokens": 120,
            "has_sensitive_flag": False,
        },
        {
            "turn_id": str(uuid4()),
            "session_id": str(uuid4()),
            "status": "COMPLETED",
            "query_type": "calculation",
            "duration_ms": 320,
            "prompt_tokens": 80,
            "completion_tokens": 200,
            "has_sensitive_flag": True,
        },
    ]

    key = await sink.write_partition(
        tenant_id=TENANT_A,
        dataset=LakehouseDataset.CHAT_ANALYTICS,
        records=records,
        batch_id="batch-001",
        timestamp=datetime(2026, 10, 1, 12, 0, 0, tzinfo=UTC),
    )

    expected = f"lakehouse/tenants/{TENANT_A}/chat_analytics/year=2026/month=10/batch-001.parquet"
    assert key == expected
    assert key in store.objects

    tbl = pq.read_table(io.BytesIO(store.objects[key]))
    assert tbl.num_rows == 2
    assert "tenant_id" in tbl.column_names
    assert "created_at" in tbl.column_names
    assert tbl.column("tenant_id")[0].as_py() == TENANT_A


@pytest.mark.asyncio
async def test_engine_enforces_tenant_isolation() -> None:
    store = MemoryStore()
    sink = LakehouseArchiveSink(store)
    engine = DuckDbLakehouseEngine(store)

    key_a = await sink.write_partition(
        tenant_id=TENANT_A,
        dataset=LakehouseDataset.CHAT_ANALYTICS,
        records=[
            {
                "turn_id": "a-1",
                "status": "COMPLETED",
                "prompt_tokens": 100,
                "completion_tokens": 50,
                "duration_ms": 100,
                "has_sensitive_flag": False,
                "query_type": "search",
            },
            {
                "turn_id": "a-2",
                "status": "COMPLETED",
                "prompt_tokens": 150,
                "completion_tokens": 70,
                "duration_ms": 120,
                "has_sensitive_flag": False,
                "query_type": "search",
            },
        ],
    )
    engine.register_partition_bytes(key_a, store.objects[key_a])

    key_b = await sink.write_partition(
        tenant_id=TENANT_B,
        dataset=LakehouseDataset.CHAT_ANALYTICS,
        records=[
            {
                "turn_id": "b-1",
                "status": "COMPLETED",
                "prompt_tokens": 999,
                "completion_tokens": 999,
                "duration_ms": 999,
                "has_sensitive_flag": True,
                "query_type": "greeting",
            },
        ],
    )
    engine.register_partition_bytes(key_b, store.objects[key_b])

    res_a = await engine.execute_query(
        query="SELECT turn_id, prompt_tokens FROM chat_analytics",
        tenant_id=TENANT_A,
        dataset=LakehouseDataset.CHAT_ANALYTICS,
    )
    assert res_a.row_count == 2
    assert all(r[0].startswith("a-") for r in res_a.rows)

    res_b = await engine.execute_query(
        query="SELECT turn_id, prompt_tokens FROM chat_analytics",
        tenant_id=TENANT_B,
        dataset=LakehouseDataset.CHAT_ANALYTICS,
    )
    assert res_b.row_count == 1
    assert res_b.rows[0][0] == "b-1"


@pytest.mark.asyncio
async def test_engine_rejects_modifying_sql() -> None:
    store = MemoryStore()
    engine = DuckDbLakehouseEngine(store)

    prohibited_queries = [
        "DROP TABLE chat_analytics",
        "DELETE FROM chat_analytics WHERE 1=1",
        "INSERT INTO chat_analytics VALUES ('x')",
        "UPDATE chat_analytics SET status = 'HACKED'",
        "ATTACH 'evil.db' AS evil",
    ]

    for sql in prohibited_queries:
        with pytest.raises(ValueError, match="prohibited modifying"):
            await engine.execute_query(
                query=sql,
                tenant_id=TENANT_A,
                dataset=LakehouseDataset.CHAT_ANALYTICS,
            )


@pytest.mark.asyncio
async def test_engine_chat_summary_aggregations() -> None:
    store = MemoryStore()
    sink = LakehouseArchiveSink(store)
    engine = DuckDbLakehouseEngine(store)

    key = await sink.write_partition(
        tenant_id=TENANT_A,
        dataset=LakehouseDataset.CHAT_ANALYTICS,
        records=[
            {
                "turn_id": "t1",
                "status": "COMPLETED",
                "duration_ms": 100,
                "prompt_tokens": 40,
                "completion_tokens": 60,
                "has_sensitive_flag": False,
                "query_type": "search",
            },
            {
                "turn_id": "t2",
                "status": "COMPLETED",
                "duration_ms": 200,
                "prompt_tokens": 50,
                "completion_tokens": 50,
                "has_sensitive_flag": True,
                "query_type": "calculation",
            },
            {
                "turn_id": "t3",
                "status": "FAILED",
                "duration_ms": 50,
                "prompt_tokens": 10,
                "completion_tokens": 0,
                "has_sensitive_flag": False,
                "query_type": "search",
            },
        ],
    )
    engine.register_partition_bytes(key, store.objects[key])

    summary = await engine.get_chat_summary(TENANT_A)
    assert summary.total_turns == 3
    assert summary.completed_turns == 2
    assert summary.failed_turns == 1
    assert summary.total_tokens == 210
    assert summary.sensitive_turn_count == 1
    assert summary.avg_duration_ms == pytest.approx(116.67, 0.1)
    assert summary.query_types == {"search": 2, "calculation": 1}


@pytest.mark.asyncio
async def test_engine_empty_dataset_returns_zero_summary() -> None:
    store = MemoryStore()
    engine = DuckDbLakehouseEngine(store)

    summary = await engine.get_document_summary(TENANT_A)
    assert summary.total_documents == 0
    assert summary.total_bytes == 0
    assert summary.total_chunks == 0
    assert summary.content_types == {}


@pytest.mark.asyncio
async def test_engine_document_and_audit_analytics() -> None:
    store = MemoryStore()
    sink = LakehouseArchiveSink(store)
    engine = DuckDbLakehouseEngine(store)

    doc_key = await sink.write_partition(
        tenant_id=TENANT_A,
        dataset=LakehouseDataset.DOCUMENT_LIFECYCLE,
        records=[
            {
                "document_id": "d1",
                "content_type": "application/pdf",
                "file_size_bytes": 10240,
                "chunk_count": 8,
                "entity_count": 4,
                "duration_seconds": 1.5,
                "ingestion_status": "COMPLETED",
            },
            {
                "document_id": "d2",
                "content_type": "text/markdown",
                "file_size_bytes": 2048,
                "chunk_count": 2,
                "entity_count": 1,
                "duration_seconds": 0.5,
                "ingestion_status": "COMPLETED",
            },
        ],
    )
    engine.register_partition_bytes(doc_key, store.objects[doc_key])

    doc_summary = await engine.get_document_summary(TENANT_A)
    assert doc_summary.total_documents == 2
    assert doc_summary.total_bytes == 12288
    assert doc_summary.total_chunks == 10
    assert doc_summary.total_entities == 5
    assert doc_summary.content_types == {"application/pdf": 1, "text/markdown": 1}

    audit_key = await sink.write_partition(
        tenant_id=TENANT_A,
        dataset=LakehouseDataset.AUDIT_EVENTS,
        records=[
            {"event_id": "e1", "user_id": "u1", "action": "USER_LOGIN"},
            {"event_id": "e2", "user_id": "u1", "action": "DOCUMENT_UPLOAD"},
            {"event_id": "e3", "user_id": "u2", "action": "USER_LOGIN"},
        ],
    )
    engine.register_partition_bytes(audit_key, store.objects[audit_key])

    audit_summary = await engine.get_audit_summary(TENANT_A)
    assert audit_summary.total_events == 3
    assert audit_summary.top_actions == {"USER_LOGIN": 2, "DOCUMENT_UPLOAD": 1}
    assert audit_summary.top_actors == {"u1": 2, "u2": 1}


def test_lakehouse_fastapi_endpoints() -> None:
    store = MemoryStore()
    sink = LakehouseArchiveSink(store)
    engine = DuckDbLakehouseEngine(store)
    set_lakehouse_runtime(engine, sink)

    app = FastAPI()
    app.include_router(lakehouse_router)

    with TestClient(app) as client:
        health_resp = client.get("/api/v1/lakehouse/health")
        assert health_resp.status_code == 200
        assert health_resp.json()["engine"] == "DuckDB-Snappy-Parquet"

        archive_resp = client.post(
            "/api/v1/lakehouse/archive",
            json={
                "batch_id": "00000000-0000-0000-0000-000000000099",
                "tenant_id": TENANT_A,
                "dataset": "chat_analytics",
                "records": [
                    {
                        "turn_id": "api-turn-1",
                        "status": "COMPLETED",
                        "query_type": "search",
                        "duration_ms": 180,
                        "prompt_tokens": 60,
                        "completion_tokens": 100,
                        "has_sensitive_flag": False,
                    }
                ],
            },
        )
        assert archive_resp.status_code == 201
        data = archive_resp.json()
        assert data["status"] == "archived"
        assert data["record_count"] == 1

        query_resp = client.post(
            "/api/v1/lakehouse/query?dataset=chat_analytics",
            json={
                "query": "SELECT turn_id, status FROM chat_analytics",
                "tenant_id": TENANT_A,
            },
        )
        assert query_resp.status_code == 200
        q_data = query_resp.json()
        assert q_data["row_count"] == 1
        assert q_data["rows"][0][0] == "api-turn-1"

        analytics_resp = client.get(f"/api/v1/lakehouse/analytics/chat?tenant_id={TENANT_A}")
        assert analytics_resp.status_code == 200
        a_data = analytics_resp.json()
        assert a_data["total_turns"] == 1
        assert a_data["completed_turns"] == 1
        assert a_data["total_tokens"] == 160

        quality_resp = client.get(f"/api/v1/lakehouse/analytics/quality?tenant_id={TENANT_A}")
        assert quality_resp.status_code == 200
        q_summary = quality_resp.json()
        assert q_summary["tenant_id"] == TENANT_A
        assert q_summary["status"] in ("PASSED", "WARNING")
        assert q_summary["overall_score"] >= 90.0
        assert len(q_summary["checks"]) >= 5

        doc_id = str(uuid4())
        lineage_resp = client.get(
            f"/api/v1/lakehouse/analytics/lineage/documents/{doc_id}?tenant_id={TENANT_A}"
        )
        assert lineage_resp.status_code == 200
        l_graph = lineage_resp.json()
        assert l_graph["document_id"] == doc_id
        assert len(l_graph["nodes"]) >= 5
        assert len(l_graph["edges"]) >= 5
