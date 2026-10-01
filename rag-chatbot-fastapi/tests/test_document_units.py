from __future__ import annotations

from types import SimpleNamespace

import pytest

from app.bootstrap.settings import Settings
from app.modules.index.internal.qdrant_queries import QdrantDocumentUnitStore


class Qdrant:
    async def collection_exists(self, collection: str) -> bool:
        return collection == "knowledge_units_v2"

    async def scroll(self, **kwargs: object) -> tuple[list[object], None]:
        del kwargs
        return (
            [
                SimpleNamespace(
                    payload={
                        "tenant_id": "tenant-1",
                        "document_id": "doc-1",
                        "unit_id": "unit-2",
                        "chunk_index": 2,
                        "text": "second",
                    }
                ),
                SimpleNamespace(
                    payload={
                        "tenant_id": "tenant-1",
                        "document_id": "doc-1",
                        "unit_id": "unit-1",
                        "chunk_index": 1,
                        "text": "first",
                    }
                ),
            ],
            None,
        )


@pytest.mark.asyncio
async def test_document_units_are_tenant_scoped_and_sorted() -> None:
    store = QdrantDocumentUnitStore(Settings(_env_file=()), Qdrant())  # type: ignore[arg-type]
    units = await store.list_units(tenant_id="tenant-1", document_id="doc-1")
    assert [unit.unit_id for unit in units] == ["unit-1", "unit-2"]
