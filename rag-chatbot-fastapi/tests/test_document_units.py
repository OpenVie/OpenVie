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
