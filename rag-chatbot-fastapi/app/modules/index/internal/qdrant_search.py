from __future__ import annotations

from collections.abc import Sequence
from typing import Any

from qdrant_client import AsyncQdrantClient, models

from app.modules.index.api import (
    KnowledgeIndexQuery,
    KnowledgeIndexResult,
    NeighborQuery,
    SparseKnowledgeIndexQuery,
)


class QdrantKnowledgeIndexQuery:
    _ELIGIBLE_BLOCK_TYPES = {"paragraph", "page", "quote"}

    def __init__(self, settings: Any, client: AsyncQdrantClient | None = None):
        self._collection = settings.QDRANT_COLLECTION
        self._dense_vector_name = settings.QDRANT_DENSE_VECTOR_NAME
        self._sparse_vector_name = settings.QDRANT_SPARSE_VECTOR_NAME
        self._tenant_field = settings.QDRANT_TENANT_FIELD
        self._knowledge_base_field = settings.QDRANT_KNOWLEDGE_BASE_FIELD
        self._client = client or AsyncQdrantClient(
            url=settings.QDRANT_URL,
            api_key=settings.QDRANT_API_KEY or None,
            check_compatibility=False,
        )

    async def search_dense(self, query: KnowledgeIndexQuery) -> list[KnowledgeIndexResult]:
        response = await self._client.query_points(
            collection_name=self._collection,
            query=list(query.query_vector),
            using=self._dense_vector_name,
            query_filter=self._filter(
                query.tenant_id, query.knowledge_base_id, query.document_ids
            ),
            limit=query.limit,
            with_payload=True,
            with_vectors=False,
        )
        return [item for point in response.points if (item := self._from_point(point))]

    async def search_sparse(
        self, query: SparseKnowledgeIndexQuery
    ) -> list[KnowledgeIndexResult]:
        response = await self._client.query_points(
            collection_name=self._collection,
            query=models.SparseVector(
                indices=list(query.query_vector.indices),
                values=list(query.query_vector.values),
            ),
            using=self._sparse_vector_name,
            query_filter=self._filter(
                query.tenant_id, query.knowledge_base_id, query.document_ids
            ),
            limit=query.limit,
            with_payload=True,
            with_vectors=False,
        )
        return [item for point in response.points if (item := self._from_point(point))]

    async def load_neighbors(self, query: NeighborQuery) -> list[KnowledgeIndexResult]:
        response = await self._client.scroll(
            collection_name=self._collection,
            scroll_filter=self._filter(
                query.tenant_id,
                query.knowledge_base_id,
                (query.document_id,),
                (
                    models.FieldCondition(
                        key="chunk_index",
                        match=models.MatchAny(
                            any=[query.chunk_index - 1, query.chunk_index + 1]
                        ),
                    ),
                ),
            ),
            limit=4,
            with_payload=True,
            with_vectors=False,
        )
        points = response[0] if isinstance(response, tuple) else response.points
        neighbors = [item for point in points if (item := self._from_point(point))]
        return sorted(
            (
                item
                for item in neighbors
                if item.section_path == query.section_path
                and item.block_type in self._ELIGIBLE_BLOCK_TYPES
                and item.modality in {None, "document"}
            ),
            key=lambda item: (abs(item.chunk_index - query.chunk_index), item.chunk_index),
        )

    async def list_document_units(
        self, tenant_id: str, document_id: str
    ) -> list[KnowledgeIndexResult]:
        if not await self._client.collection_exists(self._collection):
            return []
        records: list[Any] = []
        offset: Any = None
        while True:
            page, next_offset = await self._client.scroll(
                collection_name=self._collection,
                scroll_filter=models.Filter(
                    must=[
                        models.FieldCondition(
                            key=self._tenant_field,
                            match=models.MatchValue(value=tenant_id),
                        ),
                        models.FieldCondition(
                            key="document_id", match=models.MatchValue(value=document_id)
                        ),
                    ]
                ),
                limit=256,
                offset=offset,
                with_payload=True,
                with_vectors=False,
            )
            records.extend(page)
            if next_offset is None or next_offset == offset:
                break
            offset = next_offset
        return sorted(
            (item for point in records if (item := self._from_point(point))),
            key=lambda item: item.chunk_index,
        )

    def _filter(
        self,
        tenant_id: str,
        knowledge_base_id: str,
        document_ids: Sequence[str] | None,
        extra: Sequence[models.Condition] = (),
    ) -> models.Filter:
        conditions: list[models.Condition] = [
            models.FieldCondition(
                key=self._tenant_field, match=models.MatchValue(value=tenant_id)
            ),
            models.FieldCondition(
                key=self._knowledge_base_field,
                match=models.MatchValue(value=knowledge_base_id),
            ),
        ]
        if document_ids is not None:
            conditions.append(
                models.FieldCondition(
                    key="document_id", match=models.MatchAny(any=list(document_ids))
                )
            )
        conditions.extend(extra)
        return models.Filter(must=conditions)

    @staticmethod
    def _from_point(point: Any) -> KnowledgeIndexResult | None:
        payload = point.payload or {}
        required = ("text", "document_id", "source_name", "chunk_index")
        if any(payload.get(name) is None for name in required):
            return None
        page_number = payload.get("page_number")
        return KnowledgeIndexResult(
            document_id=str(payload["document_id"]),
            source_name=str(payload["source_name"]),
            page_number=int(page_number) if page_number is not None else None,
            chunk_index=int(payload["chunk_index"]),
            text=str(payload["text"]),
            score=float(getattr(point, "score", 0.0) or 0.0),
            unit_id=str(payload["unit_id"]) if payload.get("unit_id") else None,
            modality=str(payload["modality"]) if payload.get("modality") else None,
            section_path=tuple(str(item) for item in payload.get("section_path", [])),
            block_type=str(payload["block_type"]) if payload.get("block_type") else None,
            heading_context=(
                str(payload["heading_context"]) if payload.get("heading_context") else None
            ),
            sheet_name=str(payload["sheet_name"]) if payload.get("sheet_name") else None,
            cell_range=str(payload["cell_range"]) if payload.get("cell_range") else None,
            table_id=str(payload["table_id"]) if payload.get("table_id") else None,
            source_start=(
                int(payload["source_start"]) if payload.get("source_start") is not None else None
            ),
            source_end=(
                int(payload["source_end"]) if payload.get("source_end") is not None else None
            ),
        )
