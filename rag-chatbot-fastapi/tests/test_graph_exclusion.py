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

from pathlib import Path

from fastapi.testclient import TestClient

from app.modules.graph.api import (
    GraphBatch,
    GraphEntity,
    GraphSearchQuery,
    GraphUnit,
)
from app.modules.graph.internal.service import KuzuGraphRepository
from app.modules.graph.transport.http import create_graph_app


def _sample_batch(source_id: str, document_kind: str = "USER") -> GraphBatch:
    unit = GraphUnit(
        unit_id=f"u_{source_id}",
        document_id=source_id,
        source_name=f"{source_id}.md",
        text=f"Content for {source_id} mentioning Acme Corp",
        chunk_index=0,
    )
    entity = GraphEntity(
        normalized_name="acme corp",
        name="Acme Corp",
        entity_type="organization",
        evidence_unit_id=f"u_{source_id}",
    )
    return GraphBatch(
        tenant_id="tenant-1",
        knowledge_base_id="kb-1",
        source_id=source_id,
        source_name=f"{source_id}.md",
        units=(unit,),
        entities=(entity,),
        relations=(),
        document_kind=document_kind,
        source_ref=f"bizapp:{source_id}" if document_kind != "USER" else None,
    )


def test_graph_exclusion_legacy_nodes_treated_as_user(tmp_path: Path) -> None:
    repo = KuzuGraphRepository(str(tmp_path / "kuzu"))

    # Insert legacy node directly into Kuzu with NULL document_kind
    repo._connection.execute(
        "CREATE (:Source {id: 'legacy-doc', tenant_id: 'tenant-1',"
        " knowledge_base_id: 'kb-1', name: 'legacy.md'})"
    )
    repo._connection.execute(
        "CREATE (:KnowledgeUnit {id: 'legacy-doc:0', source_id: 'legacy-doc',"
        " text: 'Legacy info on Acme Corp', page_number: 1})"
    )
    repo._connection.execute(
        "MERGE (e:Entity {id: 'tenant-1:kb-1:acme corp'})"
        " SET e.tenant_id='tenant-1', e.knowledge_base_id='kb-1',"
        " e.normalized_name='acme corp', e.name='Acme Corp',"
        " e.entity_type='org', e.aliases='[\"Acme Corp\"]'"
    )
    repo._connection.execute(
        "MATCH (s:Source {id: 'legacy-doc'}), (u:KnowledgeUnit {id: 'legacy-doc:0'})"
        " CREATE (s)-[:CONTAINS]->(u)"
    )
    repo._connection.execute(
        "MATCH (u:KnowledgeUnit {id: 'legacy-doc:0'}), (e:Entity {id: 'tenant-1:kb-1:acme corp'})"
        " CREATE (u)-[:MENTIONS {evidence_unit_id: '0'}]->(e)"
    )

    # Legacy node has NULL document_kind. Exclude BIZAPP_RECORD and BIZAPP_DIGEST.
    query = GraphSearchQuery(
        tenant_id="tenant-1",
        knowledge_base_id="kb-1",
        query="Acme Corp",
        excluded_document_kinds=("BIZAPP_RECORD", "BIZAPP_DIGEST"),
    )
    results = repo.search(query)
    assert len(results) > 0
    assert any(r.document_id == "legacy-doc" for r in results)


def test_graph_exclusion_filters_out_excluded_kinds(tmp_path: Path) -> None:
    repo = KuzuGraphRepository(str(tmp_path / "kuzu"))

    user_batch = _sample_batch("user-doc", document_kind="USER")
    sys_batch = _sample_batch("sys-doc", document_kind="BIZAPP_RECORD")

    repo.replace_source(user_batch)
    repo.replace_source(sys_batch)

    # Without exclusions, both sources match
    all_results = repo.search(
        GraphSearchQuery(tenant_id="tenant-1", knowledge_base_id="kb-1", query="Acme Corp")
    )
    assert {r.document_id for r in all_results} == {"user-doc", "sys-doc"}

    # With exclusion, only user-doc is returned across all 4 queries
    filtered_query = GraphSearchQuery(
        tenant_id="tenant-1",
        knowledge_base_id="kb-1",
        query="Acme Corp",
        excluded_document_kinds=("BIZAPP_RECORD", "BIZAPP_DIGEST"),
    )
    filtered_results = repo.search(filtered_query)
    assert {r.document_id for r in filtered_results} == {"user-doc"}

    # Direct query test: list_search_entities
    entities = repo.list_search_entities(filtered_query)
    assert len(entities) > 0

    # Direct query test: load_entity_mentions
    mentions = repo.load_entity_mentions(filtered_query, [e.entity_id for e in entities])
    assert all(m.evidence.document_id != "sys-doc" for m in mentions)

    # Direct query test: load_evidence_units
    units = repo.load_evidence_units(
        filtered_query, [("sys-doc", "u_sys-doc"), ("user-doc", "u_user-doc")]
    )
    assert all(u.document_id != "sys-doc" for u in units)


def test_graph_exclusion_via_http_transport(tmp_path: Path) -> None:
    repo = KuzuGraphRepository(str(tmp_path / "kuzu"))
    repo.replace_source(_sample_batch("user-doc", document_kind="USER"))
    repo.replace_source(_sample_batch("sys-doc", document_kind="BIZAPP_RECORD"))

    app = create_graph_app(
        repository=repo, internal_token="test-token", database_path=str(tmp_path / "kuzu")
    )
    client = TestClient(app)

    response = client.post(
        "/internal/v1/search",
        headers={"X-Graph-Token": "test-token"},
        json={
            "tenant_id": "tenant-1",
            "knowledge_base_id": "kb-1",
            "query": "Acme Corp",
            "excluded_document_kinds": ["BIZAPP_RECORD", "BIZAPP_DIGEST"],
        },
    )
    assert response.status_code == 200
    results = response.json()["results"]
    assert len(results) > 0
    assert all(r["document_id"] != "sys-doc" for r in results)
    assert any(r["document_id"] == "user-doc" for r in results)
