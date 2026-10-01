from __future__ import annotations

import hashlib
import json
from collections.abc import Iterator, Sequence
from contextlib import contextmanager
from dataclasses import asdict
from pathlib import Path
from threading import RLock
from typing import Any

import httpx
import kuzu

from app.modules.graph.api import (
    GraphBatch,
    GraphRejectedError,
    GraphSearchQuery,
    GraphSearchResult,
    GraphUnavailableError,
)
from app.modules.graph.internal.config import GraphConfig
from app.modules.graph.internal.search import (
    DeterministicGraphSearch,
    GraphEdgeRecord,
    GraphEntityRecord,
    GraphEvidenceUnit,
    GraphMentionRecord,
)


class KuzuGraphRepository:
    """Single-process Kuzu owner used only by graph_main."""

    def __init__(self, database_path: str):
        path = Path(database_path)
        path.parent.mkdir(parents=True, exist_ok=True)
        self._database = kuzu.Database(str(path))
        self._connection = kuzu.Connection(self._database)
        self._lock = RLock()
        self._schema()

    def _schema(self) -> None:
        statements = (
            "CREATE NODE TABLE IF NOT EXISTS Source(id STRING, tenant_id STRING, "
            "knowledge_base_id STRING, name STRING, PRIMARY KEY(id))",
            "CREATE NODE TABLE IF NOT EXISTS KnowledgeUnit(id STRING, source_id STRING, "
            "text STRING, page_number INT64, section_path STRING, sheet_name STRING, "
            "cell_range STRING, PRIMARY KEY(id))",
            "CREATE NODE TABLE IF NOT EXISTS Entity(id STRING, tenant_id STRING, "
            "knowledge_base_id STRING, normalized_name STRING, name STRING, "
            "entity_type STRING, aliases STRING, PRIMARY KEY(id))",
            "CREATE REL TABLE IF NOT EXISTS CONTAINS(FROM Source TO KnowledgeUnit)",
            "CREATE REL TABLE IF NOT EXISTS MENTIONS(FROM KnowledgeUnit TO Entity, "
            "evidence_unit_id STRING)",
            "CREATE REL TABLE IF NOT EXISTS RELATED_TO(FROM Entity TO Entity, predicate STRING, "
            "evidence_unit_id STRING, source_id STRING)",
        )
        with self._lock:
            for statement in statements:
                self._connection.execute(statement)

    def replace_source(self, batch: GraphBatch) -> None:
        with self._lock, self._transaction():
            self._delete_source(batch.tenant_id, batch.source_id)
            self._connection.execute(
                "CREATE (:Source {id: $id, tenant_id: $tenant, "
                "knowledge_base_id: $kb, name: $name})",
                {
                    "id": batch.source_id,
                    "tenant": batch.tenant_id,
                    "kb": batch.knowledge_base_id,
                    "name": batch.source_name,
                },
            )
            entity_metadata: dict[str, tuple[str, str, set[str]]] = {}
            mentions: set[tuple[str, str]] = set()
            for entity in sorted(
                batch.entities,
                key=lambda item: (
                    item.normalized_name,
                    item.name.casefold(),
                    item.name,
                    item.entity_type,
                    item.evidence_unit_id,
                    item.aliases,
                ),
            ):
                metadata = entity_metadata.get(entity.normalized_name)
                if metadata is None:
                    entity_metadata[entity.normalized_name] = (
                        entity.name,
                        entity.entity_type,
                        set(entity.aliases),
                    )
                else:
                    metadata[2].update(entity.aliases)
                mentions.add((entity.normalized_name, entity.evidence_unit_id))

            entities: dict[str, str] = {}
            for normalized_name in sorted(entity_metadata):
                name, entity_type, aliases = entity_metadata[normalized_name]
                entity_id = _entity_id(
                    batch.tenant_id, batch.knowledge_base_id, normalized_name
                )
                entities[normalized_name] = entity_id
                self._connection.execute(
                    "MERGE (e:Entity {id: $id}) SET e.tenant_id=$tenant, "
                    "e.knowledge_base_id=$kb, e.normalized_name=$normalized, e.name=$name, "
                    "e.entity_type=$type, e.aliases=$aliases",
                    {
                        "id": entity_id,
                        "tenant": batch.tenant_id,
                        "kb": batch.knowledge_base_id,
                        "normalized": normalized_name,
                        "name": name,
                        "type": entity_type,
                        "aliases": json.dumps(tuple(sorted(aliases)), ensure_ascii=False),
                    },
                )
            for unit in batch.units:
                self._connection.execute(
                    "MATCH (s:Source {id: $source}) CREATE (u:KnowledgeUnit {id: $id, "
                    "source_id: $source, text: $text, page_number: $page, "
                    "section_path: $section, "
                    "sheet_name: $sheet, cell_range: $range}) CREATE (s)-[:CONTAINS]->(u)",
                    {
                        "source": batch.source_id,
                        "id": _unit_node_id(batch.source_id, unit.unit_id),
                        "text": unit.text,
                        "page": unit.page_number,
                        "section": json.dumps(unit.section_path),
                        "sheet": unit.sheet_name,
                        "range": unit.cell_range,
                    },
                )
            for normalized_name, evidence_unit_id in sorted(mentions):
                self._connection.execute(
                    "MATCH (u:KnowledgeUnit {id: $unit}), (e:Entity {id: $entity}) "
                    "CREATE (u)-[:MENTIONS {evidence_unit_id: $evidence_unit}]->(e)",
                    {
                        "unit": _unit_node_id(batch.source_id, evidence_unit_id),
                        "evidence_unit": evidence_unit_id,
                        "entity": entities[normalized_name],
                    },
                )
            relations = {
                (
                    relation.subject_normalized_name,
                    relation.predicate,
                    relation.object_normalized_name,
                    relation.evidence_unit_id,
                )
                for relation in batch.relations
            }
            for subject, predicate, object_name, evidence_unit_id in sorted(relations):
                self._connection.execute(
                    "MATCH (a:Entity {id: $subject}), (b:Entity {id: $object}) "
                    "CREATE (a)-[:RELATED_TO {predicate: $predicate, evidence_unit_id: $unit, "
                    "source_id: $source}]->(b)",
                    {
                        "subject": entities[subject],
                        "object": entities[object_name],
                        "predicate": predicate,
                        "unit": evidence_unit_id,
                        "source": batch.source_id,
                    },
                )

    def delete_source(self, tenant_id: str, source_id: str) -> None:
        with self._lock, self._transaction():
            self._delete_source(tenant_id, source_id)

    @contextmanager
    def _transaction(self) -> Iterator[None]:
        self._connection.execute("BEGIN TRANSACTION")
        try:
            yield
            self._connection.execute("COMMIT")
        except Exception as exc:
            try:
                self._connection.execute("ROLLBACK")
            except Exception as rollback_exc:
                # Kuzu can automatically end a failed transaction after a constraint error.
                # Preserve the original persistence failure instead of replacing it with
                # "No active transaction for ROLLBACK".
                exc.add_note(f"Graph rollback also failed: {rollback_exc}")
            raise

    def _delete_source(self, tenant_id: str, source_id: str) -> None:
        ownership: Any = self._connection.execute(
            "MATCH (s:Source {id: $source, tenant_id: $tenant}) "
            "RETURN s.knowledge_base_id LIMIT 1",
            {"source": source_id, "tenant": tenant_id},
        )
        if not ownership.has_next():
            return
        knowledge_base_id = str(ownership.get_next()[0])
        self._connection.execute(
            "MATCH (a:Entity)-[r:RELATED_TO]->(b:Entity) "
            "WHERE r.source_id=$source DELETE r",
            {"source": source_id},
        )
        self._connection.execute(
            "MATCH (s:Source {id: $source, tenant_id: $tenant})-[:CONTAINS]->(u:KnowledgeUnit) "
            "DETACH DELETE u",
            {"source": source_id, "tenant": tenant_id},
        )
        self._connection.execute(
            "MATCH (s:Source {id: $source, tenant_id: $tenant}) DETACH DELETE s",
            {"source": source_id, "tenant": tenant_id},
        )
        self._connection.execute(
            "MATCH (e:Entity) WHERE e.tenant_id=$tenant AND e.knowledge_base_id=$kb "
            "AND NOT (e)<-[:MENTIONS]-(:KnowledgeUnit) DETACH DELETE e",
            {"tenant": tenant_id, "kb": knowledge_base_id},
        )

    def search(self, request: GraphSearchQuery) -> list[GraphSearchResult]:
        with self._lock:
            return DeterministicGraphSearch(self).search(request)

    def list_search_entities(self, request: GraphSearchQuery) -> list[GraphEntityRecord]:
        if request.document_ids == ():
            return []
        query = (
            "MATCH (s:Source)-[:CONTAINS]->(u:KnowledgeUnit)-[:MENTIONS]->(e:Entity) "
            "WHERE s.tenant_id=$tenant AND s.knowledge_base_id=$kb "
            "AND e.tenant_id=$tenant AND e.knowledge_base_id=$kb "
        )
        parameters = _search_parameters(request)
        if request.document_ids is not None:
            query += "AND s.id IN $documents "
        query += (
            "RETURN DISTINCT e.id, e.normalized_name, e.aliases "
            "ORDER BY e.normalized_name, e.id"
        )
        result: Any = self._connection.execute(query, parameters)
        rows: list[GraphEntityRecord] = []
        while result.has_next():
            row = result.get_next()
            rows.append(
                GraphEntityRecord(
                    entity_id=str(row[0]),
                    normalized_name=str(row[1]),
                    aliases=_json_string_tuple(row[2]),
                )
            )
        return rows

    def load_entity_mentions(
        self, request: GraphSearchQuery, entity_ids: Sequence[str]
    ) -> list[GraphMentionRecord]:
        if not entity_ids or request.document_ids == ():
            return []
        query = (
            "MATCH (s:Source)-[:CONTAINS]->(u:KnowledgeUnit)-[m:MENTIONS]->(e:Entity) "
            "WHERE s.tenant_id=$tenant AND s.knowledge_base_id=$kb "
            "AND e.tenant_id=$tenant AND e.knowledge_base_id=$kb "
            "AND e.id IN $entity_ids "
        )
        parameters = _search_parameters(request)
        parameters["entity_ids"] = list(entity_ids)
        if request.document_ids is not None:
            query += "AND s.id IN $documents "
        query += (
            "RETURN e.id, m.evidence_unit_id, u.source_id, s.name, u.text, "
            "u.page_number, u.section_path, u.sheet_name, u.cell_range "
            "ORDER BY u.source_id, m.evidence_unit_id, e.id"
        )
        result: Any = self._connection.execute(query, parameters)
        rows: list[GraphMentionRecord] = []
        while result.has_next():
            row = result.get_next()
            rows.append(
                GraphMentionRecord(
                    entity_id=str(row[0]),
                    evidence=_evidence_unit(row[1:]),
                )
            )
        return rows

    def load_graph_edges(
        self,
        request: GraphSearchQuery,
        frontier_ids: Sequence[str],
        *,
        outgoing: bool,
        limit: int,
    ) -> list[GraphEdgeRecord]:
        if not frontier_ids or request.document_ids == ():
            return []
        anchor = "a" if outgoing else "b"
        query = (
            "MATCH (a:Entity)-[r:RELATED_TO]->(b:Entity) "
            f"WHERE {anchor}.id IN $frontier_ids "
            "AND a.tenant_id=$tenant AND a.knowledge_base_id=$kb "
            "AND b.tenant_id=$tenant AND b.knowledge_base_id=$kb "
        )
        parameters = _search_parameters(request)
        parameters.update({"frontier_ids": list(frontier_ids), "edge_limit": limit})
        if request.document_ids is not None:
            query += "AND r.source_id IN $documents "
        query += (
            "RETURN DISTINCT a.id, r.predicate, b.id, r.source_id, r.evidence_unit_id "
            "ORDER BY a.id, r.predicate, b.id, r.source_id, r.evidence_unit_id "
            "LIMIT $edge_limit"
        )
        result: Any = self._connection.execute(query, parameters)
        rows: list[GraphEdgeRecord] = []
        while result.has_next():
            row = result.get_next()
            rows.append(
                GraphEdgeRecord(
                    subject_id=str(row[0]),
                    predicate=str(row[1]),
                    object_id=str(row[2]),
                    source_id=str(row[3]),
                    evidence_unit_id=str(row[4]),
                )
            )
        return rows

    def load_evidence_units(
        self, request: GraphSearchQuery, identities: Sequence[tuple[str, str]]
    ) -> list[GraphEvidenceUnit]:
        if not identities or request.document_ids == ():
            return []
        identities_by_node_id = {
            _unit_node_id(source_id, unit_id): (source_id, unit_id)
            for source_id, unit_id in identities
        }
        query = (
            "MATCH (s:Source)-[:CONTAINS]->(u:KnowledgeUnit) "
            "WHERE s.tenant_id=$tenant AND s.knowledge_base_id=$kb "
            "AND u.id IN $unit_node_ids "
        )
        parameters = _search_parameters(request)
        parameters["unit_node_ids"] = list(sorted(identities_by_node_id))
        if request.document_ids is not None:
            query += "AND s.id IN $documents "
        query += (
            "RETURN u.id, u.source_id, s.name, u.text, u.page_number, "
            "u.section_path, u.sheet_name, u.cell_range ORDER BY u.source_id, u.id"
        )
        result: Any = self._connection.execute(query, parameters)
        rows: list[GraphEvidenceUnit] = []
        while result.has_next():
            row = result.get_next()
            identity = identities_by_node_id.get(str(row[0]))
            if identity is None or identity[0] != str(row[1]):
                continue
            rows.append(
                _evidence_unit(
                    (
                        identity[1],
                        row[1],
                        row[2],
                        row[3],
                        row[4],
                        row[5],
                        row[6],
                        row[7],
                    )
                )
            )
        return rows


class GraphServiceClient:
    def __init__(self, settings: GraphConfig):
        self._url = settings.GRAPH_SERVICE_URL.rstrip("/")
        self._token = settings.GRAPH_INTERNAL_TOKEN
        self._timeout = settings.GRAPH_TIMEOUT_SECONDS

    async def replace_source(self, batch: GraphBatch) -> None:
        await self._request(
            "PUT",
            f"/internal/v1/sources/{batch.source_id}",
            {
                "tenant_id": batch.tenant_id,
                "knowledge_base_id": batch.knowledge_base_id,
                "source_id": batch.source_id,
                "source_name": batch.source_name,
                "units": [
                    {
                        "unit_id": unit.unit_id,
                        "text": unit.text,
                        "page_number": unit.page_number,
                        "section_path": list(unit.section_path),
                        "sheet_name": unit.sheet_name,
                        "cell_range": unit.cell_range,
                    }
                    for unit in batch.units
                ],
                "entities": [asdict(item) for item in batch.entities],
                "relations": [asdict(item) for item in batch.relations],
                "extraction_version": batch.extraction_version,
            },
        )

    async def delete_source(self, tenant_id: str, source_id: str) -> None:
        await self._request("DELETE", f"/internal/v1/sources/{source_id}", {"tenant_id": tenant_id})

    async def search(self, request: GraphSearchQuery) -> list[GraphSearchResult]:
        try:
            async with httpx.AsyncClient(timeout=self._timeout) as client:
                response = await client.post(
                    f"{self._url}/internal/v1/search",
                    json=asdict(request),
                    headers={"X-Graph-Token": self._token},
                )
                response.raise_for_status()
                rows: list[GraphSearchResult] = []
                for item in response.json().get("results", []):
                    payload = dict(item)
                    payload["section_path"] = tuple(payload.get("section_path", ()))
                    rows.append(GraphSearchResult(**payload))
                return rows
        except httpx.HTTPError as exc:
            raise GraphUnavailableError("Graph service is unavailable") from exc

    async def _request(self, method: str, path: str, payload: dict[str, Any]) -> None:
        try:
            async with httpx.AsyncClient(timeout=self._timeout) as client:
                response = await client.request(
                    method,
                    f"{self._url}{path}",
                    json=payload,
                    headers={"X-Graph-Token": self._token},
                )
                response.raise_for_status()
        except httpx.HTTPStatusError as exc:
            if 400 <= exc.response.status_code < 500:
                raise GraphRejectedError("Graph service rejected grounded extraction") from exc
            raise GraphUnavailableError(
                f"Graph service request failed with HTTP {exc.response.status_code}"
            ) from exc
        except httpx.HTTPError as exc:
            raise GraphUnavailableError("Graph service is unavailable") from exc


def _search_parameters(request: GraphSearchQuery) -> dict[str, Any]:
    parameters: dict[str, Any] = {
        "tenant": request.tenant_id,
        "kb": request.knowledge_base_id,
    }
    if request.document_ids is not None:
        parameters["documents"] = list(request.document_ids)
    return parameters


def _evidence_unit(row: Sequence[Any]) -> GraphEvidenceUnit:
    return GraphEvidenceUnit(
        unit_id=str(row[0]),
        document_id=str(row[1]),
        source_name=str(row[2]),
        text=str(row[3]),
        page_number=int(row[4]) if row[4] is not None else None,
        section_path=_json_string_tuple(row[5]),
        sheet_name=str(row[6]) if row[6] is not None else None,
        cell_range=str(row[7]) if row[7] is not None else None,
    )


def _json_string_tuple(value: Any) -> tuple[str, ...]:
    try:
        parsed = json.loads(str(value or "[]"))
    except (TypeError, ValueError, json.JSONDecodeError):
        return ()
    if not isinstance(parsed, list):
        return ()
    return tuple(str(item) for item in parsed)


def _entity_id(tenant_id: str, knowledge_base_id: str, normalized_name: str) -> str:
    return hashlib.sha256(
        f"{tenant_id}:{knowledge_base_id}:{normalized_name}".encode()
    ).hexdigest()[:32]


def _unit_node_id(source_id: str, unit_id: str) -> str:
    """Return the source-scoped identity used only for the internal Kuzu node key."""
    identity = json.dumps([source_id, unit_id], ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(identity.encode("utf-8")).hexdigest()[:32]
