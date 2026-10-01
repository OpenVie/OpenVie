from __future__ import annotations

import re
import unicodedata
from collections.abc import Sequence
from dataclasses import dataclass, field
from typing import Protocol

from app.modules.graph.api import GraphSearchQuery, GraphSearchResult

MAX_SEED_ENTITIES = 32
GRAPH_BEAM_WIDTH = 256
MAX_EDGES_PER_DIRECTION_PER_HOP = 4_096
HOP_DECAY = 0.75
PREDICATE_BOOST = 0.25
EVIDENCE_SUPPORT_BONUS = 0.05
MAX_ADDITIONAL_SUPPORTS = 4

_TOKEN = re.compile(r"[\w-]{2,}", re.UNICODE)


@dataclass(frozen=True, slots=True)
class GraphEntityRecord:
    entity_id: str
    normalized_name: str
    aliases: tuple[str, ...] = ()


@dataclass(frozen=True, slots=True)
class GraphEvidenceUnit:
    unit_id: str
    document_id: str
    source_name: str
    text: str
    page_number: int | None = None
    section_path: tuple[str, ...] = ()
    sheet_name: str | None = None
    cell_range: str | None = None

    @property
    def identity(self) -> tuple[str, str]:
        return self.document_id, self.unit_id


@dataclass(frozen=True, slots=True)
class GraphMentionRecord:
    entity_id: str
    evidence: GraphEvidenceUnit


@dataclass(frozen=True, slots=True)
class GraphEdgeRecord:
    subject_id: str
    predicate: str
    object_id: str
    source_id: str
    evidence_unit_id: str

    @property
    def identity(self) -> tuple[str, str, str, str, str]:
        return (
            self.subject_id,
            self.predicate,
            self.object_id,
            self.source_id,
            self.evidence_unit_id,
        )

    @property
    def evidence_identity(self) -> tuple[str, str]:
        return self.source_id, self.evidence_unit_id


class GraphSearchRepository(Protocol):
    def list_search_entities(self, request: GraphSearchQuery) -> Sequence[GraphEntityRecord]: ...

    def load_entity_mentions(
        self, request: GraphSearchQuery, entity_ids: Sequence[str]
    ) -> Sequence[GraphMentionRecord]: ...

    def load_graph_edges(
        self,
        request: GraphSearchQuery,
        frontier_ids: Sequence[str],
        *,
        outgoing: bool,
        limit: int,
    ) -> Sequence[GraphEdgeRecord]: ...

    def load_evidence_units(
        self, request: GraphSearchQuery, identities: Sequence[tuple[str, str]]
    ) -> Sequence[GraphEvidenceUnit]: ...


@dataclass(frozen=True, slots=True)
class _Seed:
    entity: GraphEntityRecord
    score: float


@dataclass(frozen=True, slots=True)
class _PathState:
    seed: _Seed
    entity_ids: tuple[str, ...]
    edges: tuple[GraphEdgeRecord, ...] = ()
    predicate_overlap_total: float = 0.0
    score: float = 0.0

    @property
    def current_entity_id(self) -> str:
        return self.entity_ids[-1]

    @property
    def signature(self) -> tuple[object, ...]:
        return (
            self.seed.entity.entity_id,
            self.entity_ids,
            tuple(edge.identity for edge in self.edges),
        )


@dataclass(slots=True)
class _RankedEvidence:
    evidence: GraphEvidenceUnit
    best_contribution: float = 0.0
    matched_entity: str | None = None
    supports: set[tuple[object, ...]] = field(default_factory=set)

    def add(
        self,
        *,
        contribution: float,
        matched_entity: str,
        support: tuple[object, ...],
    ) -> None:
        self.supports.add(support)
        if contribution > self.best_contribution:
            self.best_contribution = contribution
            self.matched_entity = matched_entity
        elif contribution == self.best_contribution and (
            self.matched_entity is None or matched_entity < self.matched_entity
        ):
            self.matched_entity = matched_entity

    @property
    def score(self) -> float:
        additional_supports = min(max(len(self.supports) - 1, 0), MAX_ADDITIONAL_SUPPORTS)
        return self.best_contribution * (
            1.0 + EVIDENCE_SUPPORT_BONUS * additional_supports
        )


class DeterministicGraphSearch:
    """Bounded evidence-grounded beam search over graph relationships."""

    def __init__(self, repository: GraphSearchRepository):
        self._repository = repository

    def search(self, request: GraphSearchQuery) -> list[GraphSearchResult]:
        if request.document_ids == ():
            return []
        normalized_query = _normalize(request.query)
        query_tokens = frozenset(_tokens(normalized_query))
        seeds = self._select_seeds(request, normalized_query, query_tokens)
        if not seeds:
            return []

        ranked: dict[tuple[str, str], _RankedEvidence] = {}
        seeds_by_id = {seed.entity.entity_id: seed for seed in seeds}
        for mention in self._repository.load_entity_mentions(
            request, tuple(sorted(seeds_by_id))
        ):
            seed = seeds_by_id.get(mention.entity_id)
            if seed is None or not _in_document_scope(mention.evidence, request.document_ids):
                continue
            _add_evidence(
                ranked,
                mention.evidence,
                contribution=seed.score,
                matched_entity=seed.entity.normalized_name,
                support=(
                    "mention",
                    seed.entity.entity_id,
                    mention.evidence.document_id,
                    mention.evidence.unit_id,
                ),
            )

        frontier = [
            _PathState(
                seed=seed,
                entity_ids=(seed.entity.entity_id,),
                score=seed.score,
            )
            for seed in seeds
        ]
        for hop in range(1, request.max_hops + 1):
            frontier = self._expand(request, frontier, query_tokens, hop, ranked)
            if not frontier:
                break

        results = [
            GraphSearchResult(
                entity=item.matched_entity,
                unit_id=item.evidence.unit_id,
                document_id=item.evidence.document_id,
                source_name=item.evidence.source_name,
                text=item.evidence.text,
                page_number=item.evidence.page_number,
                section_path=item.evidence.section_path,
                sheet_name=item.evidence.sheet_name,
                cell_range=item.evidence.cell_range,
                chunk_index=0,
                score=item.score,
            )
            for item in ranked.values()
        ]
        return sorted(
            results,
            key=lambda item: (
                -item.score,
                item.document_id,
                item.unit_id,
                item.entity or "",
            ),
        )[: request.limit]

    def _select_seeds(
        self,
        request: GraphSearchQuery,
        normalized_query: str,
        query_tokens: frozenset[str],
    ) -> list[_Seed]:
        seeds = [
            _Seed(entity, score)
            for entity in self._repository.list_search_entities(request)
            if (score := _entity_score(entity, normalized_query, query_tokens)) > 0.0
        ]
        return sorted(
            seeds,
            key=lambda item: (
                -item.score,
                item.entity.normalized_name,
                item.entity.entity_id,
            ),
        )[:MAX_SEED_ENTITIES]

    def _expand(
        self,
        request: GraphSearchQuery,
        frontier: Sequence[_PathState],
        query_tokens: frozenset[str],
        hop: int,
        ranked: dict[tuple[str, str], _RankedEvidence],
    ) -> list[_PathState]:
        frontier_ids = tuple(sorted({state.current_entity_id for state in frontier}))
        raw_edges = [
            *self._repository.load_graph_edges(
                request,
                frontier_ids,
                outgoing=True,
                limit=MAX_EDGES_PER_DIRECTION_PER_HOP,
            ),
            *self._repository.load_graph_edges(
                request,
                frontier_ids,
                outgoing=False,
                limit=MAX_EDGES_PER_DIRECTION_PER_HOP,
            ),
        ]
        edges = sorted({edge.identity: edge for edge in raw_edges}.values(), key=_edge_sort_key)
        if not edges:
            return []

        evidence_by_identity = {
            evidence.identity: evidence
            for evidence in self._repository.load_evidence_units(
                request,
                tuple(sorted({edge.evidence_identity for edge in edges})),
            )
            if _in_document_scope(evidence, request.document_ids)
        }
        valid_edges = [
            edge for edge in edges if edge.evidence_identity in evidence_by_identity
        ]
        if not valid_edges:
            return []

        adjacency: dict[str, list[tuple[GraphEdgeRecord, str]]] = {}
        frontier_set = set(frontier_ids)
        for edge in valid_edges:
            if edge.subject_id in frontier_set:
                adjacency.setdefault(edge.subject_id, []).append((edge, edge.object_id))
            if edge.object_id in frontier_set:
                adjacency.setdefault(edge.object_id, []).append((edge, edge.subject_id))
        for items in adjacency.values():
            items.sort(key=lambda item: (_edge_sort_key(item[0]), item[1]))

        expanded: dict[tuple[object, ...], _PathState] = {}
        for state in sorted(frontier, key=_path_sort_key):
            for edge, neighbor_id in adjacency.get(state.current_entity_id, []):
                if neighbor_id in state.entity_ids:
                    continue
                predicate_overlap_total = (
                    state.predicate_overlap_total
                    + _predicate_overlap(edge.predicate, query_tokens)
                )
                score = state.seed.score * (HOP_DECAY**hop) * (
                    1.0 + PREDICATE_BOOST * predicate_overlap_total / hop
                )
                candidate = _PathState(
                    seed=state.seed,
                    entity_ids=(*state.entity_ids, neighbor_id),
                    edges=(*state.edges, edge),
                    predicate_overlap_total=predicate_overlap_total,
                    score=score,
                )
                existing = expanded.get(candidate.signature)
                if existing is None or _path_sort_key(candidate) < _path_sort_key(existing):
                    expanded[candidate.signature] = candidate

        retained = sorted(expanded.values(), key=_path_sort_key)[:GRAPH_BEAM_WIDTH]
        for state in retained:
            edge = state.edges[-1]
            evidence = evidence_by_identity[edge.evidence_identity]
            _add_evidence(
                ranked,
                evidence,
                contribution=state.score,
                matched_entity=state.seed.entity.normalized_name,
                support=("relation", *edge.identity),
            )
        return retained


def _add_evidence(
    ranked: dict[tuple[str, str], _RankedEvidence],
    evidence: GraphEvidenceUnit,
    *,
    contribution: float,
    matched_entity: str,
    support: tuple[object, ...],
) -> None:
    candidate = ranked.setdefault(evidence.identity, _RankedEvidence(evidence=evidence))
    candidate.add(
        contribution=contribution,
        matched_entity=matched_entity,
        support=support,
    )


def _normalize(value: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", value).casefold().split())


def _tokens(value: str) -> tuple[str, ...]:
    return tuple(_TOKEN.findall(value))


def _entity_score(
    entity: GraphEntityRecord,
    normalized_query: str,
    query_tokens: frozenset[str],
) -> float:
    phrases = tuple(
        phrase
        for phrase in dict.fromkeys(
            _normalize(value) for value in (entity.normalized_name, *entity.aliases)
        )
        if phrase
    )
    entity_tokens = {token for phrase in phrases for token in _tokens(phrase)}
    overlap = len(query_tokens.intersection(entity_tokens)) / max(len(entity_tokens), 1)
    phrase_bonus = float(any(_phrase_occurs(phrase, normalized_query) for phrase in phrases))
    return overlap + phrase_bonus


def _predicate_overlap(predicate: str, query_tokens: frozenset[str]) -> float:
    predicate_tokens = set(_tokens(_normalize(predicate)))
    return len(query_tokens.intersection(predicate_tokens)) / max(len(predicate_tokens), 1)


def _phrase_occurs(phrase: str, normalized_query: str) -> bool:
    return bool(
        re.search(
            rf"(?<![\w-]){re.escape(phrase)}(?![\w-])",
            normalized_query,
            flags=re.UNICODE,
        )
    )


def _in_document_scope(
    evidence: GraphEvidenceUnit, document_ids: tuple[str, ...] | None
) -> bool:
    return document_ids is None or evidence.document_id in document_ids


def _edge_sort_key(edge: GraphEdgeRecord) -> tuple[str, str, str, str, str]:
    return edge.identity


def _path_sort_key(state: _PathState) -> tuple[object, ...]:
    return (-state.score, state.signature)
