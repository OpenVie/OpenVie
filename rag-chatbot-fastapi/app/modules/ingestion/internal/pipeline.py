from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Sequence
from dataclasses import dataclass

from app.common.errors import StorageUnavailableError
from app.common.storage import ObjectStorage, ObjectStorageWriter
from app.common.storage_keys import spreadsheet_table_prefix
from app.modules.graph.api import (
    GraphBatch,
    GraphProjectionApi,
    GraphRejectedError,
    GraphUnavailableError,
)
from app.modules.index.api import (
    IndexRejectedError,
    IndexSparseVector,
    IndexUnavailableError,
    IndexUnit,
    KnowledgeIndexCommandApi,
    ReplaceDocumentIndex,
)
from app.modules.ingestion.api import (
    DocumentIndexLifecycleApi,
    IngestDocumentCommand,
    IngestionApi,
    IngestionOutcome,
    IngestionOutcomeStatus,
    PermanentIngestionFailure,
    TransientIngestionFailure,
)
from app.modules.ingestion.internal.artifacts import (
    SpreadsheetArtifact,
    prepare_spreadsheet_artifacts,
    replace_spreadsheet_artifacts,
)
from app.modules.ingestion.internal.chunking import DeterministicChunker, TextChunk
from app.modules.ingestion.internal.entity_extraction import EntityRelationExtractor, graph_units
from app.modules.ingestion.internal.extraction import DocumentTextExtractor, ParsedDocument
from app.modules.model.api import (
    ModelRejectedError,
    ModelUnavailableError,
    SparseEmbeddingApi,
    TextEmbeddingApi,
)


@dataclass(frozen=True, slots=True)
class PreparedDocument:
    command: IngestDocumentCommand
    parsed: ParsedDocument
    chunks: tuple[TextChunk, ...]
    artifacts: tuple[SpreadsheetArtifact, ...]
    index_command: ReplaceDocumentIndex


class DocumentIngestionPipeline(IngestionApi):
    def __init__(
        self,
        *,
        store: ObjectStorage,
        extractor: DocumentTextExtractor,
        chunker: DeterministicChunker,
        embedder: TextEmbeddingApi,
        sparse_encoder: SparseEmbeddingApi,
        vector_store: KnowledgeIndexCommandApi,
        graph_store: GraphProjectionApi,
        graph_extractor: EntityRelationExtractor,
    ):
        self._store = store
        self._extractor = extractor
        self._chunker = chunker
        self._embedder = embedder
        self._sparse_encoder = sparse_encoder
        self._vector_store = vector_store
        self._graph_store = graph_store
        self._graph_extractor = graph_extractor

    async def process(self, command: IngestDocumentCommand) -> IngestionOutcome:
        try:
            return await self._process(command)
        except (
            StorageUnavailableError,
            ModelUnavailableError,
            IndexUnavailableError,
            GraphUnavailableError,
        ) as exc:
            raise TransientIngestionFailure(str(exc)) from exc
        except (ModelRejectedError, IndexRejectedError, GraphRejectedError) as exc:
            raise PermanentIngestionFailure(str(exc)) from exc

    async def _process(self, event: IngestDocumentCommand) -> IngestionOutcome:
        prepared = await self.prepare(event)
        try:
            await self.replace_artifacts(prepared)
            await self.replace_index(prepared)
            await self.replace_graph(prepared)
        except Exception as ingestion_error:
            try:
                await self.cleanup(event)
            except Exception as cleanup_error:
                cleanup_error.add_note(
                    f"Cleanup followed ingestion failure: {ingestion_error}"
                )
                raise
            raise
        return IngestionOutcome(IngestionOutcomeStatus.COMPLETED, len(prepared.chunks))

    async def prepare(self, event: IngestDocumentCommand) -> PreparedDocument:
        data = await self._store.download(event.storage_key)
        parsed = self._extractor.parse(
            data,
            content_type=event.content_type,
            file_name=event.file_name,
        )
        chunks = self._chunker.chunk(parsed)
        if not chunks:
            raise PermanentIngestionFailure("Document contains no extractable text")
        artifacts = prepare_spreadsheet_artifacts(
            parsed,
            tenant_id=event.tenant_id,
            knowledge_base_id=event.knowledge_base_id,
            document_id=event.document_id,
        )
        chunk_texts = [chunk.text for chunk in chunks]
        embeddings, sparse_embeddings = await asyncio.gather(
            self._embedder.embed_documents(chunk_texts),
            self._sparse_encoder.embed_documents(chunk_texts),
        )
        return PreparedDocument(
            command=event,
            parsed=parsed,
            chunks=tuple(chunks),
            artifacts=artifacts,
            index_command=ReplaceDocumentIndex(
                tenant_id=event.tenant_id,
                knowledge_base_id=event.knowledge_base_id,
                document_id=event.document_id,
                source_name=event.file_name,
                units=tuple(_index_unit(chunk) for chunk in chunks),
                dense_vectors=tuple(tuple(vector) for vector in embeddings),
                sparse_vectors=tuple(
                    IndexSparseVector(item.indices, item.values) for item in sparse_embeddings
                ),
            ),
        )

    async def replace_artifacts(self, prepared: PreparedDocument) -> None:
        event = prepared.command
        await replace_spreadsheet_artifacts(
            self._store,
            tenant_id=event.tenant_id,
            knowledge_base_id=event.knowledge_base_id,
            document_id=event.document_id,
            artifacts=prepared.artifacts,
        )

    async def replace_index(self, prepared: PreparedDocument) -> None:
        await self._vector_store.replace_document(prepared.index_command)

    async def replace_graph(self, prepared: PreparedDocument) -> None:
        event = prepared.command
        if prepared.parsed.modality == "document":
            batch = await self._graph_extractor.extract(event, prepared.chunks)
        else:
            batch = GraphBatch(
                tenant_id=event.tenant_id,
                knowledge_base_id=event.knowledge_base_id,
                source_id=event.document_id,
                source_name=event.file_name,
                units=graph_units(event.document_id, event.file_name, prepared.chunks),
            )
        await self._graph_store.replace_source(batch)

    async def cleanup(self, event: IngestDocumentCommand) -> None:
        await _complete_cleanup(
            (
                (
                    "spreadsheet artifacts",
                    self._store.delete_prefix(
                        spreadsheet_table_prefix(
                            event.tenant_id,
                            event.knowledge_base_id,
                            event.document_id,
                        )
                    ),
                ),
                (
                    "graph projection",
                    self._graph_store.delete_source(event.tenant_id, event.document_id),
                ),
                (
                    "vector index",
                    self._vector_store.delete_document(event.tenant_id, event.document_id),
                ),
            )
        )


class DocumentIndexLifecycleService(DocumentIndexLifecycleApi):
    def __init__(
        self,
        index: KnowledgeIndexCommandApi,
        graph: GraphProjectionApi,
        artifacts: ObjectStorageWriter,
    ) -> None:
        self._index = index
        self._graph = graph
        self._artifacts = artifacts

    async def delete_document(
        self, tenant_id: str, knowledge_base_id: str, document_id: str
    ) -> None:
        await _complete_cleanup(
            (
                (
                    "spreadsheet artifacts",
                    self._artifacts.delete_prefix(
                        spreadsheet_table_prefix(
                            tenant_id,
                            knowledge_base_id,
                            document_id,
                        )
                    ),
                ),
                ("vector index", self._index.delete_document(tenant_id, document_id)),
                ("graph projection", self._graph.delete_source(tenant_id, document_id)),
            )
        )


async def _complete_cleanup(
    operations: Sequence[tuple[str, Awaitable[None]]],
) -> None:
    failures: list[tuple[str, Exception]] = []
    for label, operation in operations:
        try:
            await operation
        except Exception as exc:
            failures.append((label, exc))
    if failures:
        first_label, first = failures[0]
        first.add_note(f"Cleanup failed for {first_label}")
        for label, failure in failures[1:]:
            first.add_note(f"Cleanup also failed for {label}: {failure}")
        raise first


def _index_unit(chunk: TextChunk) -> IndexUnit:
    return IndexUnit(
        unit_id=str(chunk.unit_id or chunk.chunk_index),
        chunk_index=int(chunk.chunk_index),
        text=str(chunk.text),
        content_hash=str(chunk.content_hash),
        source_name="",
        modality=str(chunk.modality),
        block_type=str(chunk.block_type),
        section_path=tuple(chunk.section_path),
        heading_context=chunk.heading_context,
        page_number=chunk.page_number,
        sheet_name=chunk.sheet_name,
        cell_range=chunk.cell_range,
        table_id=chunk.table_id,
        source_start=chunk.source_start,
        source_end=chunk.source_end,
        parser_version=str(chunk.parser_version),
        chunker_version=str(chunk.chunker_version),
    )
