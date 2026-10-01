from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient

from app.modules.graph.api import (
    GraphBatch,
    GraphEntity,
    GraphRelation,
    GraphSearchQuery,
)
from app.modules.graph.internal.service import KuzuGraphRepository
from app.modules.graph.transport.http import create_graph_app

FIXTURE = Path(__file__).parent / "fixtures" / "graph_traversal_v1.json"


def _fixture() -> dict[str, Any]:
    return json.loads(FIXTURE.read_text(encoding="utf-8"))


def _batch(payload: dict[str, Any]) -> GraphBatch:
    return GraphBatch(
        tenant_id=str(payload["tenant_id"]),
        knowledge_base_id=str(payload["knowledge_base_id"]),
        source_id=str(payload["source_id"]),
        source_name=str(payload["source_name"]),
        units=tuple(payload["units"]),
        entities=tuple(GraphEntity(**item) for item in payload.get("entities", [])),
        relations=tuple(GraphRelation(**item) for item in payload.get("relations", [])),
    )


def _repository(tmp_path: Path, name: str = "graph") -> KuzuGraphRepository:
    return KuzuGraphRepository(str(tmp_path / name))


def test_versioned_fixture_covers_bounded_and_reverse_traversal(tmp_path: Path) -> None:
    fixture = _fixture()
    batch = _batch(fixture["batch"])
    repository = _repository(tmp_path)
    repository.replace_source(batch)

    for expectation in fixture["expectations"]:
        results = repository.search(
            GraphSearchQuery(
                tenant_id=batch.tenant_id,
                knowledge_base_id=batch.knowledge_base_id,
                query=str(expectation["query"]),
                max_hops=int(expectation["max_hops"]),
            )
        )
        assert [item.unit_id for item in results] == expectation["unit_ids"]


def test_predicate_overlap_hop_decay_cycles_and_duplicates_are_deterministic(
    tmp_path: Path,
) -> None:
    repository = _repository(tmp_path)
    repository.replace_source(
        GraphBatch(
            tenant_id="tenant-a",
            knowledge_base_id="kb-a",
            source_id="doc-a",
            source_name="relations.txt",
            units=(
                {"unit_id": "u0", "text": "Alpha profile"},
                {"unit_id": "u1", "text": "Alpha owns Beta"},
                {"unit_id": "u2", "text": "Alpha is located near Omega"},
                {"unit_id": "u3", "text": "Beta serves Gamma"},
                {"unit_id": "u4", "text": "Gamma closes the cycle to Alpha"},
            ),
            entities=(
                GraphEntity("Alpha", "alpha", "organization", "u0"),
                GraphEntity("Beta", "beta", "product", "u1"),
                GraphEntity("Omega", "omega", "place", "u2"),
                GraphEntity("Gamma", "gamma", "service", "u3"),
            ),
            relations=(
                GraphRelation("alpha", "owns", "beta", "u1"),
                GraphRelation("alpha", "located near", "omega", "u2"),
                GraphRelation("beta", "serves", "gamma", "u3"),
                GraphRelation("gamma", "links", "alpha", "u4"),
                GraphRelation("alpha", "owns", "beta", "u1"),
            ),
        )
    )

    request = GraphSearchQuery(
        tenant_id="tenant-a",
        knowledge_base_id="kb-a",
        query="What does Alpha own?",
        max_hops=3,
    )
    first = repository.search(request)
    second = repository.search(request)

    assert [(item.document_id, item.unit_id, item.score) for item in first] == [
        (item.document_id, item.unit_id, item.score) for item in second
    ]
    assert len({(item.document_id, item.unit_id) for item in first}) == len(first)
    positions = {item.unit_id: index for index, item in enumerate(first)}
    assert positions["u1"] < positions["u2"]
    assert first[positions["u1"]].score > first[positions["u3"]].score


def test_document_scope_is_applied_before_limit_and_cannot_be_used_as_a_bridge(
    tmp_path: Path,
) -> None:
    repository = _repository(tmp_path)
    repository.replace_source(
        GraphBatch(
            tenant_id="tenant-a",
            knowledge_base_id="kb-a",
            source_id="a-blocked",
            source_name="blocked.txt",
            units=({"unit_id": "blocked", "text": "Alpha blocked evidence"},),
            entities=(GraphEntity("Alpha", "alpha", "organization", "blocked"),),
        )
    )
    repository.replace_source(
        GraphBatch(
            tenant_id="tenant-a",
            knowledge_base_id="kb-a",
            source_id="z-allowed",
            source_name="allowed.txt",
            units=({"unit_id": "allowed", "text": "Alpha allowed evidence"},),
            entities=(GraphEntity("Alpha", "alpha", "organization", "allowed"),),
        )
    )
    scoped = repository.search(
        GraphSearchQuery(
            tenant_id="tenant-a",
            knowledge_base_id="kb-a",
            query="Alpha",
            limit=1,
            document_ids=("z-allowed",),
        )
    )
    assert [(item.document_id, item.unit_id) for item in scoped] == [
        ("z-allowed", "allowed")
    ]
    assert (
        repository.search(
            GraphSearchQuery(
                tenant_id="tenant-a",
                knowledge_base_id="kb-a",
                query="Alpha",
                document_ids=(),
            )
        )
        == []
    )

    repository.replace_source(
        GraphBatch(
            tenant_id="tenant-a",
            knowledge_base_id="kb-a",
            source_id="bridge-blocked",
            source_name="bridge.txt",
            units=({"unit_id": "bridge", "text": "Alpha links Beta"},),
            entities=(
                GraphEntity("Alpha", "alpha", "organization", "bridge"),
                GraphEntity("Beta", "beta", "product", "bridge"),
            ),
            relations=(GraphRelation("alpha", "links", "beta", "bridge"),),
        )
    )
    repository.replace_source(
        GraphBatch(
            tenant_id="tenant-a",
            knowledge_base_id="kb-a",
            source_id="second-allowed",
            source_name="second.txt",
            units=({"unit_id": "downstream", "text": "Beta serves Gamma"},),
            entities=(
                GraphEntity("Beta", "beta", "product", "downstream"),
                GraphEntity("Gamma", "gamma", "service", "downstream"),
            ),
            relations=(GraphRelation("beta", "serves", "gamma", "downstream"),),
        )
    )
    without_bridge = repository.search(
        GraphSearchQuery(
            tenant_id="tenant-a",
            knowledge_base_id="kb-a",
            query="Alpha",
            max_hops=2,
            document_ids=("z-allowed", "second-allowed"),
        )
    )
    assert [(item.document_id, item.unit_id) for item in without_bridge] == [
        ("z-allowed", "allowed")
    ]


def test_replacement_deletes_old_relations_and_preserves_shared_entities(
    tmp_path: Path,
) -> None:
    repository = _repository(tmp_path)
    repository.replace_source(
        GraphBatch(
            tenant_id="tenant-a",
            knowledge_base_id="kb-a",
            source_id="doc-shared",
            source_name="shared.txt",
            units=({"unit_id": "beta-unit", "text": "Beta remains shared"},),
            entities=(GraphEntity("Beta", "beta", "product", "beta-unit"),),
        )
    )
    repository.replace_source(
        GraphBatch(
            tenant_id="tenant-a",
            knowledge_base_id="kb-a",
            source_id="doc-replaced",
            source_name="old.txt",
            units=(
                {"unit_id": "alpha-unit", "text": "Alpha profile"},
                {"unit_id": "reused-unit", "text": "Alpha owns Beta"},
            ),
            entities=(
                GraphEntity("Alpha", "alpha", "organization", "alpha-unit"),
                GraphEntity("Beta", "beta", "product", "reused-unit"),
            ),
            relations=(GraphRelation("alpha", "owns", "beta", "reused-unit"),),
        )
    )
    repository.replace_source(
        GraphBatch(
            tenant_id="tenant-a",
            knowledge_base_id="kb-a",
            source_id="doc-replaced",
            source_name="new.txt",
            units=(
                {"unit_id": "alpha-unit", "text": "Alpha profile updated"},
                {"unit_id": "reused-unit", "text": "No relationship remains"},
            ),
            entities=(
                GraphEntity("Alpha", "alpha", "organization", "alpha-unit"),
            ),
        )
    )

    alpha_results = repository.search(
        GraphSearchQuery("tenant-a", "kb-a", "Alpha", max_hops=1)
    )
    assert [item.unit_id for item in alpha_results] == ["alpha-unit"]
    assert repository.search(GraphSearchQuery("tenant-a", "kb-a", "Beta"))[0].document_id == (
        "doc-shared"
    )

    repository.delete_source("tenant-b", "doc-shared")
    assert repository.search(GraphSearchQuery("tenant-a", "kb-a", "Beta"))[0].document_id == (
        "doc-shared"
    )
    repository.delete_source("tenant-a", "doc-replaced")
    repository.delete_source("tenant-a", "doc-replaced")
    assert repository.search(GraphSearchQuery("tenant-a", "kb-a", "Alpha")) == []
    assert repository.search(GraphSearchQuery("tenant-a", "kb-a", "Beta"))[0].document_id == (
        "doc-shared"
    )


def test_graph_http_contract_is_additive_and_response_shape_is_stable(tmp_path: Path) -> None:
    fixture = _fixture()
    batch = _batch(fixture["batch"])
    repository = _repository(tmp_path)
    repository.replace_source(batch)
    app = create_graph_app(
        repository=repository,
        internal_token="graph-token",
        database_path=str(tmp_path / "graph"),
    )

    with TestClient(app) as client:
        unauthorized = client.post(
            "/internal/v1/search",
            json={
                "tenant_id": batch.tenant_id,
                "knowledge_base_id": batch.knowledge_base_id,
                "query": "Alpha",
            },
        )
        assert unauthorized.status_code == 401

        legacy = client.post(
            "/internal/v1/search",
            headers={"X-Graph-Token": "graph-token"},
            json={
                "tenant_id": batch.tenant_id,
                "knowledge_base_id": batch.knowledge_base_id,
                "query": "Alpha",
                "limit": 10,
            },
        )
        assert legacy.status_code == 200
        assert [item["unit_id"] for item in legacy.json()["results"]] == ["u0"]

        multi_hop = client.post(
            "/internal/v1/search",
            headers={"X-Graph-Token": "graph-token"},
            json={
                "tenant_id": batch.tenant_id,
                "knowledge_base_id": batch.knowledge_base_id,
                "query": "Alpha",
                "limit": 10,
                "max_hops": 3,
                "document_ids": [batch.source_id],
            },
        )
        assert multi_hop.status_code == 200
        assert [item["unit_id"] for item in multi_hop.json()["results"]] == [
            "u0",
            "u1",
            "u2",
            "u3",
        ]
        assert set(multi_hop.json()["results"][0]) == {
            "entity",
            "unit_id",
            "document_id",
            "source_name",
            "text",
            "page_number",
            "section_path",
            "sheet_name",
            "cell_range",
            "score",
        }

        invalid_hops = client.post(
            "/internal/v1/search",
            headers={"X-Graph-Token": "graph-token"},
            json={
                "tenant_id": batch.tenant_id,
                "knowledge_base_id": batch.knowledge_base_id,
                "query": "Alpha",
                "max_hops": 4,
            },
        )
        assert invalid_hops.status_code == 422

        invalid_document = client.post(
            "/internal/v1/search",
            headers={"X-Graph-Token": "graph-token"},
            json={
                "tenant_id": batch.tenant_id,
                "knowledge_base_id": batch.knowledge_base_id,
                "query": "Alpha",
                "document_ids": [""],
            },
        )
        assert invalid_document.status_code == 422


def test_graph_search_query_validates_bounds_and_canonicalizes_documents() -> None:
    query = GraphSearchQuery(
        "tenant-a",
        "kb-a",
        "Alpha",
        document_ids=("doc-b", "doc-a", "doc-b"),
    )
    assert query.document_ids == ("doc-a", "doc-b")
    with pytest.raises(ValueError, match="max_hops"):
        GraphSearchQuery("tenant-a", "kb-a", "Alpha", max_hops=4)
    with pytest.raises(ValueError, match="limit"):
        GraphSearchQuery("tenant-a", "kb-a", "Alpha", limit=0)
