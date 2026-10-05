from __future__ import annotations

import asyncio
import json
import re
from collections.abc import Sequence
from typing import Any

from pydantic import BaseModel, Field, field_validator, model_validator

from app.modules.graph.api import GraphBatch, GraphEntity, GraphRelation, GraphUnit
from app.modules.ingestion.api import (
    IngestDocumentCommand,
    PermanentIngestionFailure,
    TransientIngestionFailure,
)
from app.modules.ingestion.internal.chunking import TextChunk
from app.modules.model.api import ChatModelApi, ModelTimeoutError, ModelUnavailableError


class _EntityMention(BaseModel):
    name: str = Field(min_length=1, max_length=300)
    normalized_name: str = Field(min_length=1, max_length=300)
    entity_type: str = Field(min_length=1, max_length=80)
    aliases: tuple[str, ...] = ()
    evidence_unit_id: str

    @field_validator("normalized_name")
    @classmethod
    def normalize_name(cls, value: str) -> str:
        return " ".join(value.casefold().split())


class _EvidenceRelation(BaseModel):
    subject_normalized_name: str
    predicate: str = Field(min_length=1, max_length=120)
    object_normalized_name: str
    evidence_unit_id: str

    @model_validator(mode="after")
    def normalize_entities(self) -> _EvidenceRelation:
        self.subject_normalized_name = " ".join(self.subject_normalized_name.casefold().split())
        self.object_normalized_name = " ".join(self.object_normalized_name.casefold().split())
        return self


class EntityRelationExtractor:
    def __init__(
        self,
        model: ChatModelApi,
        *,
        batch_size: int = 12,
        output_limit_retries: int = 1,
        max_concurrency: int = 4,
    ) -> None:
        if output_limit_retries < 0:
            raise ValueError("output_limit_retries must be non-negative")
        if max_concurrency < 1:
            raise ValueError("max_concurrency must be at least 1")
        self._model = model
        self._batch_size = batch_size
        self._output_limit_retries = output_limit_retries
        self._max_concurrency = max_concurrency

    async def extract(
        self, event: IngestDocumentCommand, chunks: Sequence[TextChunk]
    ) -> GraphBatch:
        units = tuple(
            _graph_unit(str(event.document_id), event.file_name, chunk)
            for chunk in chunks
        )
        slices = [
            chunks[start : start + self._batch_size]
            for start in range(0, len(chunks), self._batch_size)
        ]
        if not slices:
            batch_results = []
        elif len(slices) == 1:
            batch_results = [await self._extract_batch(slices[0])]
        else:
            semaphore = asyncio.Semaphore(self._max_concurrency)

            async def _extract_bounded(
                batch_slice: Sequence[TextChunk],
            ) -> tuple[list[_EntityMention], list[_EvidenceRelation]]:
                async with semaphore:
                    return await self._extract_batch(batch_slice)

            batch_results = await asyncio.gather(*(_extract_bounded(s) for s in slices))

        entities: list[_EntityMention] = []
        relations: list[_EvidenceRelation] = []
        for batch_entities, batch_relations in batch_results:
            entities.extend(batch_entities)
            relations.extend(batch_relations)
        entities, relations = _filter_grounded_extraction(
            units, _deduplicate_entities(entities), _deduplicate_relations(relations)
        )
        return GraphBatch(
            tenant_id=str(event.tenant_id),
            knowledge_base_id=str(event.knowledge_base_id),
            source_id=str(event.document_id),
            source_name=event.file_name,
            units=units,
            entities=tuple(
                GraphEntity(
                    name=item.name,
                    normalized_name=item.normalized_name,
                    entity_type=item.entity_type,
                    aliases=item.aliases,
                    evidence_unit_id=item.evidence_unit_id,
                )
                for item in entities
            ),
            relations=tuple(
                GraphRelation(
                    subject_normalized_name=item.subject_normalized_name,
                    predicate=item.predicate,
                    object_normalized_name=item.object_normalized_name,
                    evidence_unit_id=item.evidence_unit_id,
                )
                for item in relations
            ),
        )

    async def _extract_batch(
        self, selected: Sequence[TextChunk], *, output_limit_attempt: int = 0
    ) -> tuple[list[_EntityMention], list[_EvidenceRelation]]:
        try:
            raw = await self._model.complete(
                [
                    {"role": "system", "content": _EXTRACTION_PROMPT},
                    {
                        "role": "user",
                        "content": json.dumps(
                            [_unit_payload(item) for item in selected], ensure_ascii=False
                        ),
                    },
                ]
            )
        except ModelTimeoutError as exc:
            if len(selected) > 1:
                midpoint = len(selected) // 2
                left = await self._extract_batch(selected[:midpoint])
                right = await self._extract_batch(selected[midpoint:])
                return left[0] + right[0], left[1] + right[1]
            raise TransientIngestionFailure("Graph extraction model request timed out") from exc
        except ModelUnavailableError as exc:
            if len(selected) > 1 and "finish_reason=length" in str(exc):
                midpoint = len(selected) // 2
                left = await self._extract_batch(selected[:midpoint])
                right = await self._extract_batch(selected[midpoint:])
                return left[0] + right[0], left[1] + right[1]
            if "finish_reason=length" in str(exc):
                if output_limit_attempt < self._output_limit_retries:
                    return await self._extract_batch(
                        selected, output_limit_attempt=output_limit_attempt + 1
                    )
                raise PermanentIngestionFailure(
                    "Graph extraction exceeded the configured model output limit"
                ) from exc
            raise TransientIngestionFailure("Graph extraction model request failed") from exc
        try:
            payload = json.loads(re.sub(r"^```(?:json)?\s*|\s*```$", "", raw.strip()))
            if not isinstance(payload, dict):
                raise TypeError("Graph extraction response must be a JSON object")
            return (
                _valid_entities(payload.get("entities")),
                _valid_relations(payload.get("relations")),
            )
        except (ValueError, TypeError, json.JSONDecodeError) as exc:
            if len(selected) > 1:
                midpoint = len(selected) // 2
                left = await self._extract_batch(selected[:midpoint])
                right = await self._extract_batch(selected[midpoint:])
                return left[0] + right[0], left[1] + right[1]
            raise PermanentIngestionFailure(
                "Entity extraction returned invalid structured JSON"
            ) from exc


def graph_units(
    document_id: str, source_name: str, chunks: Sequence[TextChunk]
) -> tuple[GraphUnit, ...]:
    return tuple(_graph_unit(document_id, source_name, chunk) for chunk in chunks)


def _graph_unit(document_id: str, source_name: str, chunk: TextChunk) -> GraphUnit:
    return GraphUnit(
        unit_id=chunk.unit_id or str(chunk.chunk_index),
        document_id=document_id,
        source_name=source_name,
        text=chunk.text,
        chunk_index=chunk.chunk_index,
        page_number=chunk.page_number,
        modality=chunk.modality,
        section_path=chunk.section_path,
        block_type=chunk.block_type,
        sheet_name=chunk.sheet_name,
        cell_range=chunk.cell_range,
        table_id=chunk.table_id,
    )


def _valid_entities(raw: Any) -> list[_EntityMention]:
    if not isinstance(raw, list):
        raise TypeError("Graph extraction entities must be an array")
    entities: list[_EntityMention] = []
    for item in raw:
        try:
            entities.append(_EntityMention.model_validate(item))
        except (TypeError, ValueError):
            continue
    return entities


def _valid_relations(raw: Any) -> list[_EvidenceRelation]:
    if not isinstance(raw, list):
        raise TypeError("Graph extraction relations must be an array")
    relations: list[_EvidenceRelation] = []
    for item in raw:
        try:
            relations.append(_EvidenceRelation.model_validate(item))
        except (TypeError, ValueError):
            continue
    return relations


def _deduplicate_entities(entities: Sequence[_EntityMention]) -> list[_EntityMention]:
    unique: dict[tuple[str, str], _EntityMention] = {}
    for entity in entities:
        unique.setdefault((entity.normalized_name, entity.evidence_unit_id), entity)
    return list(unique.values())


def _deduplicate_relations(relations: Sequence[_EvidenceRelation]) -> list[_EvidenceRelation]:
    unique: dict[tuple[str, str, str, str], _EvidenceRelation] = {}
    for relation in relations:
        key = (
            relation.subject_normalized_name,
            relation.predicate,
            relation.object_normalized_name,
            relation.evidence_unit_id,
        )
        unique.setdefault(key, relation)
    return list(unique.values())


def _filter_grounded_extraction(
    units: Sequence[GraphUnit],
    entities: Sequence[_EntityMention],
    relations: Sequence[_EvidenceRelation],
) -> tuple[list[_EntityMention], list[_EvidenceRelation]]:
    unit_ids = {unit.unit_id for unit in units}
    grounded_entities = [entity for entity in entities if entity.evidence_unit_id in unit_ids]
    entity_names = {entity.normalized_name for entity in grounded_entities}
    return grounded_entities, [
        relation
        for relation in relations
        if relation.evidence_unit_id in unit_ids
        and relation.subject_normalized_name in entity_names
        and relation.object_normalized_name in entity_names
    ]


def _unit_payload(chunk: TextChunk) -> dict[str, Any]:
    return {
        "unit_id": chunk.unit_id,
        "text": chunk.text,
        "page_number": chunk.page_number,
        "section_path": list(chunk.section_path),
        "sheet_name": chunk.sheet_name,
        "cell_range": chunk.cell_range,
    }


_EXTRACTION_PROMPT = """You extract only facts explicitly supported by the supplied knowledge units.
Return exactly one JSON object with two array fields: "entities" and "relations".
Each entity must have non-empty name, normalized_name, entity_type, and evidence_unit_id strings,
plus an aliases array. Each relation must have non-empty subject_normalized_name, predicate,
object_normalized_name, and evidence_unit_id strings. Omit any item whose required fields cannot
be populated; never emit placeholder objects or blank required strings.
Every item must cite one supplied unit_id. Do not infer unsupported facts.
Every relation subject and object must exactly match an entity normalized_name in the response.
Use empty arrays when no fully populated item exists."""
