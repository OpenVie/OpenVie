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

import asyncio
import hashlib
from collections.abc import Awaitable, Sequence
from dataclasses import dataclass, replace
from pathlib import PurePosixPath

from app.common.errors import StorageUnavailableError
from app.common.storage import ObjectStorage, ObjectStorageWriter
from app.common.storage_keys import (
    artifact_prefix,
    document_media_key,
    document_media_prefix,
    spreadsheet_table_prefix,
)
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
from app.modules.ingestion.internal.ocr import OcrIngestionService
from app.modules.ingestion.internal.ocr_artifacts import load_parsed_artifact, save_parsed_artifact
from app.modules.ingestion.internal.transcription import AsrIngestionService
from app.modules.model.api import (
    ModelRejectedError,
    ModelUnavailableError,
    SparseEmbeddingApi,
    TextEmbeddingApi,
)

# Extensions handled by the optional ASR stage (audio_parser derivatives);
# everything OCR handles counts as the text/image document group.
MEDIA_SUFFIXES = frozenset(
    {
        ".mp3",
        ".wav",
        ".m4a",
        ".oga",
        ".ogg",
        ".opus",
        ".flac",
        ".mp4",
        ".mkv",
        ".mov",
        ".webm",
    }
)


@dataclass(frozen=True, slots=True)
class PreparedDocument:
    command: IngestDocumentCommand
    parsed: ParsedDocument
    chunks: tuple[TextChunk, ...]
    artifacts: tuple[SpreadsheetArtifact, ...]


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
        graph_extraction_enabled: bool = True,
        ocr: OcrIngestionService | None = None,
        asr: AsrIngestionService | None = None,
    ):
        self._store = store
        self._extractor = extractor
        self._chunker = chunker
        self._embedder = embedder
        self._sparse_encoder = sparse_encoder
        self._vector_store = vector_store
        self._graph_store = graph_store
        self._graph_extractor = graph_extractor
        self._graph_extraction_enabled = graph_extraction_enabled
        self._ocr = ocr
        self._asr = asr

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
        return IngestionOutcome(
            IngestionOutcomeStatus.COMPLETED,
            len(prepared.chunks),
            text_source=prepared.parsed.text_source,
            page_count=prepared.parsed.page_count,
        )

    async def prepare(self, event: IngestDocumentCommand) -> PreparedDocument:
        data = await self._store.download(event.storage_key)
        artifact_kind = self._artifact_kind(event.file_name)
        if artifact_kind is None:
            parsed = await asyncio.to_thread(
                self._extractor.parse,
                data,
                content_type=event.content_type,
                file_name=event.file_name,
            )
        else:
            parsed = await self._parse_with_artifact_cache(event, data, artifact_kind)
        media_object_keys = {
            asset.name: document_media_key(
                event.tenant_id,
                event.knowledge_base_id,
                event.document_id,
                asset.name,
            )
            for asset in parsed.media
        }
        chunks = self._chunker.chunk(parsed, media_object_keys=media_object_keys)
        if not chunks:
            raise PermanentIngestionFailure("Document contains no extractable text")
        artifacts = prepare_spreadsheet_artifacts(
            parsed,
            tenant_id=event.tenant_id,
            knowledge_base_id=event.knowledge_base_id,
            document_id=event.document_id,
        )
        return PreparedDocument(
            command=event,
            parsed=parsed,
            chunks=tuple(chunks),
            artifacts=artifacts,
        )

    def _artifact_kind(self, file_name: str) -> str | None:
        """Which derived-parse stage caches this document, if any.

        Audio/video files ride the ASR stage (only when it is wired);
        everything else stays with the OCR stage. Without either stage the
        parse runs directly, unchanged from the text-only pipeline.
        """
        suffix = PurePosixPath(file_name.lower()).suffix
        if self._asr is not None and suffix in MEDIA_SUFFIXES:
            return "asr"
        if self._ocr is not None and suffix not in MEDIA_SUFFIXES:
            return "ocr"
        return None

    async def _parse_with_artifact_cache(
        self, event: IngestDocumentCommand, data: bytes, artifact_kind: str
    ) -> ParsedDocument:
        """Cache derived-parse effort per (raw bytes, stage settings) fingerprint.

        A hit skips recognition entirely on reindex; a miss parses (OCR runs
        through ``asyncio.to_thread`` so the provider subprocess work stays off
        the ingestion event loop) and best-effort caches the outcome when the
        document actually leaned on the derived stage.
        """
        raw_sha256 = hashlib.sha256(data).hexdigest()
        if artifact_kind == "asr":
            assert self._asr is not None
            fingerprint = self._asr.fingerprint(raw_sha256)
        else:
            assert self._ocr is not None
            fingerprint = self._ocr.fingerprint(raw_sha256)
        cached = await load_parsed_artifact(
            self._store,
            tenant_id=event.tenant_id,
            knowledge_base_id=event.knowledge_base_id,
            document_id=event.document_id,
            fingerprint=fingerprint,
            artifact_kind=artifact_kind,
        )
        if cached is not None:
            return cached
        parsed = await asyncio.to_thread(
            self._extractor.parse,
            data,
            content_type=event.content_type,
            file_name=event.file_name,
        )
        # Citable image assets persist alongside the artifact: upload the
        # bytes first (artifact⇒assets invariant), then cache blob-free
        # descriptors so a cache hit compares equal to the original parse.
        media_blobs = {asset.name: asset.blob for asset in parsed.media if asset.blob}
        if media_blobs:
            parsed = replace(
                parsed,
                media=tuple(
                    replace(asset, blob=b"") if asset.blob else asset
                    for asset in parsed.media
                ),
            )
        if parsed.text_source != "text":
            await save_parsed_artifact(
                self._store,
                tenant_id=event.tenant_id,
                knowledge_base_id=event.knowledge_base_id,
                document_id=event.document_id,
                fingerprint=fingerprint,
                parsed=parsed,
                media_blobs=media_blobs,
                artifact_kind=artifact_kind,
            )
        return parsed

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
        event = prepared.command
        chunk_texts = [chunk.text for chunk in prepared.chunks]
        embeddings, sparse_embeddings = await asyncio.gather(
            self._embedder.embed_documents(chunk_texts),
            self._sparse_encoder.embed_documents(chunk_texts),
        )
        await self._vector_store.replace_document(
            ReplaceDocumentIndex(
                tenant_id=event.tenant_id,
                knowledge_base_id=event.knowledge_base_id,
                document_id=event.document_id,
                source_name=event.file_name,
                units=tuple(_index_unit(chunk) for chunk in prepared.chunks),
                dense_vectors=tuple(tuple(vector) for vector in embeddings),
                sparse_vectors=tuple(
                    IndexSparseVector(item.indices, item.values) for item in sparse_embeddings
                ),
            )
        )

    async def replace_graph(self, prepared: PreparedDocument) -> None:
        event = prepared.command
        if self._graph_extraction_enabled and prepared.parsed.modality == "document":
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
        operations: list[tuple[str, Awaitable[None]]] = [
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
                "document media",
                self._store.delete_prefix(
                    document_media_prefix(
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
        ]
        if self._ocr is not None:
            operations.append(
                (
                    "OCR artifacts",
                    self._store.delete_prefix(
                        artifact_prefix(
                            "ocr",
                            event.tenant_id,
                            event.knowledge_base_id,
                            event.document_id,
                        )
                    ),
                )
            )
        if self._asr is not None:
            operations.append(
                (
                    "ASR artifacts",
                    self._store.delete_prefix(
                        artifact_prefix(
                            "asr",
                            event.tenant_id,
                            event.knowledge_base_id,
                            event.document_id,
                        )
                    ),
                )
            )
        await _complete_cleanup(tuple(operations))


class DocumentIndexLifecycleService(DocumentIndexLifecycleApi):
    def __init__(
        self,
        index: KnowledgeIndexCommandApi,
        graph: GraphProjectionApi,
        artifacts: ObjectStorageWriter,
        ocr: OcrIngestionService | None = None,
        asr: AsrIngestionService | None = None,
    ) -> None:
        self._index = index
        self._graph = graph
        self._artifacts = artifacts
        self._ocr = ocr
        self._asr = asr

    async def delete_document(
        self, tenant_id: str, knowledge_base_id: str, document_id: str
    ) -> None:
        operations: list[tuple[str, Awaitable[None]]] = [
            (
                "spreadsheet artifacts",
                self._artifacts.delete_prefix(
                    spreadsheet_table_prefix(tenant_id, knowledge_base_id, document_id)
                ),
            ),
            (
                "document media",
                self._artifacts.delete_prefix(
                    document_media_prefix(tenant_id, knowledge_base_id, document_id)
                ),
            ),
        ]
        if self._ocr is not None:
            operations.append(
                (
                    "OCR artifacts",
                    self._artifacts.delete_prefix(
                        artifact_prefix("ocr", tenant_id, knowledge_base_id, document_id)
                    ),
                )
            )
        if self._asr is not None:
            operations.append(
                (
                    "ASR artifacts",
                    self._artifacts.delete_prefix(
                        artifact_prefix("asr", tenant_id, knowledge_base_id, document_id)
                    ),
                )
            )
        operations.append(
            ("vector index", self._index.delete_document(tenant_id, document_id))
        )
        operations.append(
            ("graph projection", self._graph.delete_source(tenant_id, document_id))
        )
        await _complete_cleanup(tuple(operations))


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
        image_object_key=chunk.image_object_key,
        image_media_type=chunk.image_media_type,
        image_width=chunk.image_width,
        image_height=chunk.image_height,
    )
