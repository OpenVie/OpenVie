from __future__ import annotations

from typing import Any
from uuid import NAMESPACE_URL, uuid5

from qdrant_client import AsyncQdrantClient, models

from app.modules.index.api import (
    IndexRejectedError,
    IndexUnavailableError,
    IndexUnit,
    ReplaceDocumentIndex,
)
from app.modules.index.internal.config import IndexConfig


class QdrantKnowledgeIndex:
    def __init__(self, settings: IndexConfig, client: AsyncQdrantClient | None = None):
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

    async def replace_document(self, command: ReplaceDocumentIndex) -> None:
        chunks = command.units
        embeddings = command.dense_vectors
        sparse_embeddings = command.sparse_vectors
        if len(chunks) != len(embeddings):
            raise IndexRejectedError("Chunk and embedding counts do not match")
        if not chunks:
            raise IndexRejectedError("Document contains no chunks to upsert")
        if sparse_embeddings and len(chunks) != len(sparse_embeddings):
            raise IndexRejectedError("Chunk and sparse embedding counts do not match")

        dimension = len(embeddings[0])
        if any(len(vector) != dimension for vector in embeddings):
            raise IndexRejectedError("Embedding dimensions are inconsistent")

        try:
            await self.ensure_collection(dimension)
            await self.delete_document(command.tenant_id, command.document_id)
            sparse_values = sparse_embeddings or (None,) * len(chunks)
            points = [
                models.PointStruct(
                    id=self.point_id(
                        command.document_id, chunk.unit_id or str(chunk.chunk_index)
                    ),
                    vector={
                        self._dense_vector_name: list(embedding),
                        **(
                            {
                                self._sparse_vector_name: models.SparseVector(
                                    indices=list(sparse.indices), values=list(sparse.values)
                                )
                            }
                            if sparse is not None
                            else {}
                        ),
                    },
                    payload=self._payload(command, chunk),
                )
                for chunk, embedding, sparse in zip(
                    chunks,
                    embeddings,
                    sparse_values,
                    strict=True,
                )
            ]
            await self._client.upsert(collection_name=self._collection, points=points, wait=True)
        except IndexRejectedError:
            raise
        except Exception as exc:
            raise IndexUnavailableError(
                f"Unable to upsert document chunks into Qdrant: {exc}"
            ) from exc

    async def upsert(
        self,
        event: Any,
        chunks: Any,
        embeddings: Any,
        sparse_embeddings: Any = None,
    ) -> None:
        await self.replace_document(
            ReplaceDocumentIndex(
                tenant_id=str(event.tenant_id),
                knowledge_base_id=str(event.knowledge_base_id),
                document_id=str(event.document_id),
                source_name=str(event.file_name),
                units=tuple(
                    IndexUnit(
                        unit_id=str(chunk.unit_id or chunk.chunk_index),
                        chunk_index=chunk.chunk_index,
                        text=chunk.text,
                        content_hash=chunk.content_hash,
                        source_name=str(event.file_name),
                        modality=chunk.modality,
                        block_type=chunk.block_type,
                        section_path=chunk.section_path,
                        heading_context=chunk.heading_context,
                        page_number=chunk.page_number,
                        sheet_name=chunk.sheet_name,
                        cell_range=chunk.cell_range,
                        table_id=chunk.table_id,
                        source_start=chunk.source_start,
                        source_end=chunk.source_end,
                        parser_version=chunk.parser_version,
                        chunker_version=chunk.chunker_version,
                    )
                    for chunk in chunks
                ),
                dense_vectors=tuple(tuple(vector) for vector in embeddings),
                sparse_vectors=tuple(sparse_embeddings or ()),
            )
        )

    async def delete_document(self, tenant_id: str, document_id: str) -> None:
        try:
            if not await self._client.collection_exists(self._collection):
                return
            await self._client.delete(
                collection_name=self._collection,
                points_selector=models.FilterSelector(
                    filter=models.Filter(
                        must=[
                            models.FieldCondition(
                                key=self._tenant_field,
                                match=models.MatchValue(value=tenant_id),
                            ),
                            models.FieldCondition(
                                key="document_id",
                                match=models.MatchValue(value=document_id),
                            ),
                        ]
                    )
                ),
                wait=True,
            )
        except Exception as exc:
            raise IndexUnavailableError(
                f"Unable to delete existing document vectors: {exc}"
            ) from exc

    async def ensure_collection(self, dimension: int) -> None:
        exists = await self._client.collection_exists(self._collection)
        if not exists:
            await self._client.create_collection(
                collection_name=self._collection,
                vectors_config={
                    self._dense_vector_name: models.VectorParams(
                        size=dimension, distance=models.Distance.COSINE
                    )
                },
                sparse_vectors_config={
                    self._sparse_vector_name: models.SparseVectorParams(
                        modifier=models.Modifier.IDF
                    )
                },
            )
            await self._ensure_payload_indexes()
            return

        info = await self._client.get_collection(self._collection)
        current = self._collection_dimension(info)
        if current != dimension:
            raise IndexRejectedError(
                "Qdrant collection dimension is "
                f"{current}, received embeddings with dimension {dimension}"
            )
        sparse_vectors = getattr(info.config.params, "sparse_vectors", None)
        if not isinstance(sparse_vectors, dict) or self._sparse_vector_name not in sparse_vectors:
            raise IndexRejectedError(
                f"Qdrant collection is missing sparse vector {self._sparse_vector_name}"
            )

    async def _ensure_payload_indexes(self) -> None:
        for field_name, schema in (
            (self._tenant_field, models.PayloadSchemaType.KEYWORD),
            (self._knowledge_base_field, models.PayloadSchemaType.KEYWORD),
            ("document_id", models.PayloadSchemaType.KEYWORD),
            ("chunk_index", models.PayloadSchemaType.INTEGER),
        ):
            await self._client.create_payload_index(
                collection_name=self._collection,
                field_name=field_name,
                field_schema=schema,
                wait=True,
            )

    def _collection_dimension(self, info: object) -> int:
        vectors = info.config.params.vectors  # type: ignore[attr-defined]
        if isinstance(vectors, dict):
            dense = vectors.get(self._dense_vector_name)
            if dense is None:
                raise IndexRejectedError(
                    f"Qdrant collection is missing dense vector {self._dense_vector_name}"
                )
            return int(dense.size)
        raise IndexRejectedError(
            f"Qdrant collection {self._collection} uses the legacy unnamed-vector schema; "
            "set QDRANT_COLLECTION=knowledge_units_v2 and reindex documents"
        )

    def _payload(self, command: ReplaceDocumentIndex, chunk: IndexUnit) -> dict[str, object]:
        return {
            self._tenant_field: command.tenant_id,
            self._knowledge_base_field: command.knowledge_base_id,
            "document_id": command.document_id,
            "source_name": command.source_name,
            "page_number": chunk.page_number,
            "chunk_index": chunk.chunk_index,
            "unit_id": chunk.unit_id,
            "modality": chunk.modality,
            "section_path": list(chunk.section_path),
            "block_type": chunk.block_type,
            "heading_context": chunk.heading_context,
            "sheet_name": chunk.sheet_name,
            "cell_range": chunk.cell_range,
            "table_id": chunk.table_id,
            "source_start": chunk.source_start,
            "source_end": chunk.source_end,
            "parser_version": chunk.parser_version,
            "chunker_version": chunk.chunker_version,
            "text": chunk.text,
            "content_hash": chunk.content_hash,
        }

    @staticmethod
    def point_id(document_id: str, unit_identity: int | str) -> str:
        return str(uuid5(NAMESPACE_URL, f"{document_id}:{unit_identity}"))
