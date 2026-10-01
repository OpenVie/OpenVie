from __future__ import annotations

from datetime import UTC, datetime
from io import BytesIO

import pytest
from botocore.exceptions import ClientError

from app.common.errors import StorageNotFoundError, StorageUnavailableError
from app.common.storage import SeaweedS3DocumentStore
from app.common.storage_keys import spreadsheet_table_key, spreadsheet_table_prefix
from app.modules.generation.internal.calculation import (
    MISSING_TABLE_CLARIFICATION,
    SpreadsheetCalculationCoordinator,
)
from app.modules.graph.api import GraphBatch
from app.modules.index.api import IndexUnavailableError, ReplaceDocumentIndex
from app.modules.ingestion.api import IngestDocumentCommand, TransientIngestionFailure
from app.modules.ingestion.internal.artifacts import prepare_spreadsheet_artifacts
from app.modules.ingestion.internal.chunking import DeterministicChunker
from app.modules.ingestion.internal.extraction import DocumentTextExtractor
from app.modules.ingestion.internal.pipeline import (
    DocumentIndexLifecycleService,
    DocumentIngestionPipeline,
)
from app.modules.model.api import SparseEmbedding
from app.modules.retrieval.api import RetrievedKnowledgeUnit

TENANT_ID = "00000000-0000-0000-0000-000000000001"
KNOWLEDGE_BASE_ID = "00000000-0000-0000-0000-000000000004"
DOCUMENT_ID = "37f34742-0be6-4f82-9839-39303fabe2d7"
SOURCE_KEY = (
    f"tenants/{TENANT_ID}/knowledge-bases/{KNOWLEDGE_BASE_ID}/documents/"
    f"{DOCUMENT_ID}/sales.csv"
)
CSV_DATA = b"plan,price\nStarter,0\nPro,1199000\nBusiness,2999000\n"


class MemoryObjectStore:
    def __init__(self) -> None:
        self.objects: dict[str, bytes] = {SOURCE_KEY: CSV_DATA}
        self.deleted_prefixes: list[str] = []

    async def download(self, storage_key: str) -> bytes:
        try:
            return self.objects[storage_key]
        except KeyError as exc:
            raise StorageNotFoundError(storage_key) from exc

    async def upload(self, storage_key: str, data: bytes, *, content_type: str) -> None:
        assert content_type == "application/vnd.apache.parquet"
        self.objects[storage_key] = data

    async def delete_prefix(self, storage_prefix: str) -> None:
        self.deleted_prefixes.append(storage_prefix)
        for key in tuple(self.objects):
            if key.startswith(storage_prefix):
                del self.objects[key]


class FakeEmbedder:
    async def embed_documents(self, texts: list[str]) -> list[list[float]]:
        return [[float(index), 1.0] for index, _ in enumerate(texts)]

    async def embed_query(self, text: str) -> list[float]:
        del text
        return [0.0, 1.0]


class FakeSparseEncoder:
    async def embed_documents(self, texts: list[str]) -> list[SparseEmbedding]:
        return [SparseEmbedding((1,), (1.0,)) for _ in texts]

    async def embed_query(self, text: str) -> SparseEmbedding:
        del text
        return SparseEmbedding((1,), (1.0,))


class FakeIndex:
    def __init__(self, *, fail_replace: bool = False) -> None:
        self.fail_replace = fail_replace
        self.replaced: ReplaceDocumentIndex | None = None
        self.deleted: list[tuple[str, str]] = []

    async def replace_document(self, command: ReplaceDocumentIndex) -> None:
        if self.fail_replace:
            raise IndexUnavailableError("index unavailable")
        self.replaced = command

    async def delete_document(self, tenant_id: str, document_id: str) -> None:
        self.deleted.append((tenant_id, document_id))


class FakeGraph:
    def __init__(self) -> None:
        self.replaced: GraphBatch | None = None
        self.deleted: list[tuple[str, str]] = []

    async def replace_source(self, batch: GraphBatch) -> None:
        self.replaced = batch

    async def delete_source(self, tenant_id: str, document_id: str) -> None:
        self.deleted.append((tenant_id, document_id))


class UnexpectedGraphExtractor:
    async def extract(self, command: object, chunks: object) -> GraphBatch:
        del command, chunks
        raise AssertionError("Spreadsheet ingestion must not invoke graph extraction")


def command() -> IngestDocumentCommand:
    return IngestDocumentCommand(
        schema_version="1.0",
        event_id="event-1",
        job_id="job-1",
        tenant_id=TENANT_ID,
        knowledge_base_id=KNOWLEDGE_BASE_ID,
        document_id=DOCUMENT_ID,
        uploader_id="user-1",
        storage_key=SOURCE_KEY,
        file_name="sales.csv",
        content_type="text/csv",
        file_size_bytes=len(CSV_DATA),
        occurred_at=datetime.now(UTC),
    )


def pipeline(
    store: MemoryObjectStore, index: FakeIndex, graph: FakeGraph
) -> DocumentIngestionPipeline:
    return DocumentIngestionPipeline(
        store=store,
        extractor=DocumentTextExtractor(),
        chunker=DeterministicChunker(),
        embedder=FakeEmbedder(),
        sparse_encoder=FakeSparseEncoder(),
        vector_store=index,
        graph_store=graph,
        graph_extractor=UnexpectedGraphExtractor(),
    )


def spreadsheet_chunk(table_id: str) -> RetrievedKnowledgeUnit:
    return RetrievedKnowledgeUnit(
        document_id=DOCUMENT_ID,
        source_name="sales.csv",
        page_number=None,
        chunk_index=0,
        text="Sheet: sales; Columns: plan, price",
        score=1.0,
        unit_id="unit-1",
        modality="spreadsheet",
        block_type="sheet",
        sheet_name="sales",
        cell_range="A1:B4",
        table_id=table_id,
    )


@pytest.mark.asyncio
async def test_ingestion_persists_parquet_before_completing_index_and_graph() -> None:
    pl = pytest.importorskip("polars")
    store = MemoryObjectStore()
    index = FakeIndex()
    graph = FakeGraph()

    outcome = await pipeline(store, index, graph).process(command())

    parsed = DocumentTextExtractor().parse(CSV_DATA, content_type="text/csv", file_name="sales.csv")
    table = parsed.tables[0]
    artifact_key = spreadsheet_table_key(
        TENANT_ID, KNOWLEDGE_BASE_ID, DOCUMENT_ID, table.table_id
    )
    frame = pl.read_parquet(BytesIO(store.objects[artifact_key]))
    assert outcome.chunk_count == 4
    assert frame["plan"].to_list() == ["Starter", "Pro", "Business"]
    assert frame["price"].to_list() == [0, 1199000, 2999000]
    assert index.replaced is not None
    assert graph.replaced is not None


@pytest.mark.asyncio
async def test_partial_ingestion_failure_removes_artifacts_index_and_graph() -> None:
    store = MemoryObjectStore()
    index = FakeIndex(fail_replace=True)
    graph = FakeGraph()

    with pytest.raises(TransientIngestionFailure, match="index unavailable"):
        await pipeline(store, index, graph).process(command())

    prefix = spreadsheet_table_prefix(TENANT_ID, KNOWLEDGE_BASE_ID, DOCUMENT_ID)
    assert not any(key.startswith(prefix) for key in store.objects)
    assert index.deleted == [(TENANT_ID, DOCUMENT_ID)]
    assert graph.deleted == [(TENANT_ID, DOCUMENT_ID)]


@pytest.mark.asyncio
async def test_document_lifecycle_deletes_derived_artifacts() -> None:
    store = MemoryObjectStore()
    prefix = spreadsheet_table_prefix(TENANT_ID, KNOWLEDGE_BASE_ID, DOCUMENT_ID)
    store.objects[f"{prefix}table.parquet"] = b"parquet"
    index = FakeIndex()
    graph = FakeGraph()
    lifecycle = DocumentIndexLifecycleService(index, graph, store)

    await lifecycle.delete_document(TENANT_ID, KNOWLEDGE_BASE_ID, DOCUMENT_ID)

    assert f"{prefix}table.parquet" not in store.objects
    assert index.deleted == [(TENANT_ID, DOCUMENT_ID)]
    assert graph.deleted == [(TENANT_ID, DOCUMENT_ID)]


class MissingCalculationStore:
    async def download(self, storage_key: str) -> bytes:
        raise StorageNotFoundError(storage_key)


class UnexpectedModel:
    async def complete(self, messages: object) -> str:
        del messages
        raise AssertionError("Missing artifacts must not invoke the calculation planner")


@pytest.mark.asyncio
async def test_missing_calculation_artifact_returns_controlled_clarification() -> None:
    parsed = DocumentTextExtractor().parse(CSV_DATA, content_type="text/csv", file_name="sales.csv")
    coordinator = SpreadsheetCalculationCoordinator(MissingCalculationStore(), UnexpectedModel())

    result = await coordinator.prepare(
        tenant_id=TENANT_ID,
        knowledge_base_id=KNOWLEDGE_BASE_ID,
        question="What is the average price?",
        chunks=[spreadsheet_chunk(parsed.tables[0].table_id)],
    )

    assert result is not None
    assert result.clarification == MISSING_TABLE_CLARIFICATION


class MissingS3Client:
    def get_object(self, **kwargs: object) -> object:
        del kwargs
        raise ClientError(
            {
                "Error": {"Code": "NoSuchKey", "Message": "missing"},
                "ResponseMetadata": {"HTTPStatusCode": 404},
            },
            "GetObject",
        )


@pytest.mark.asyncio
async def test_seaweed_missing_object_has_distinct_error() -> None:
    store = object.__new__(SeaweedS3DocumentStore)
    store._bucket = "bucket"
    store._client = MissingS3Client()

    with pytest.raises(StorageNotFoundError, match="does not exist"):
        await store.download("missing.parquet")


class FailingArtifactStore(MemoryObjectStore):
    async def delete_prefix(self, storage_prefix: str) -> None:
        del storage_prefix
        raise StorageUnavailableError("storage unavailable")


@pytest.mark.asyncio
async def test_lifecycle_attempts_all_cleanup_targets_when_artifact_deletion_fails() -> None:
    index = FakeIndex()
    graph = FakeGraph()
    lifecycle = DocumentIndexLifecycleService(index, graph, FailingArtifactStore())

    with pytest.raises(StorageUnavailableError, match="storage unavailable"):
        await lifecycle.delete_document(TENANT_ID, KNOWLEDGE_BASE_ID, DOCUMENT_ID)

    assert index.deleted == [(TENANT_ID, DOCUMENT_ID)]
    assert graph.deleted == [(TENANT_ID, DOCUMENT_ID)]


def test_prepared_artifacts_use_deterministic_storage_identity() -> None:
    parsed = DocumentTextExtractor().parse(CSV_DATA, content_type="text/csv", file_name="sales.csv")

    artifacts = prepare_spreadsheet_artifacts(
        parsed,
        tenant_id=TENANT_ID,
        knowledge_base_id=KNOWLEDGE_BASE_ID,
        document_id=DOCUMENT_ID,
    )

    assert [item.storage_key for item in artifacts] == [
        spreadsheet_table_key(
            TENANT_ID,
            KNOWLEDGE_BASE_ID,
            DOCUMENT_ID,
            parsed.tables[0].table_id,
        )
    ]
