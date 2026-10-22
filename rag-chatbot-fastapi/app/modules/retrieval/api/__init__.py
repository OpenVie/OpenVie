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

from dataclasses import dataclass
from enum import StrEnum
from typing import Protocol


class QueryProfile(StrEnum):
    SEMANTIC = "semantic"
    EXACT = "exact"
    RELATIONAL = "relational"
    CALCULATION = "calculation"


class VisibilityScope(StrEnum):
    ALL_TENANT_DOCUMENTS = "all_tenant_documents"
    CUSTOMER_VISIBLE_DOCUMENTS = "customer_visible_documents"


@dataclass(frozen=True, slots=True)
class RetrievalFingerprint:
    profile: QueryProfile
    configuration: str


@dataclass(frozen=True, slots=True)
class RetrievalPlan:
    fingerprint: RetrievalFingerprint


@dataclass(frozen=True, slots=True)
class RetrievalQuery:
    tenant_id: str
    knowledge_base_id: str
    query_text: str
    authoritative_revision: int
    query_vector: tuple[float, ...] | None = None
    visibility: VisibilityScope = VisibilityScope.ALL_TENANT_DOCUMENTS
    document_ids: tuple[str, ...] | None = None


@dataclass(frozen=True, slots=True)
class RetrievedKnowledgeUnit:
    document_id: str
    source_name: str
    page_number: int | None
    chunk_index: int
    text: str
    score: float
    unit_id: str | None = None
    modality: str | None = None
    section_path: tuple[str, ...] = ()
    block_type: str | None = None
    sheet_name: str | None = None
    cell_range: str | None = None
    table_id: str | None = None
    parser_version: str | None = None
    # Set when the unit was derived from a persisted, citable image asset.
    image_object_key: str | None = None
    image_media_type: str | None = None
    image_width: int | None = None
    image_height: int | None = None


class RetrievalError(Exception):
    pass


class RetrievalRejectedError(RetrievalError):
    pass


class RetrievalUnavailableError(RetrievalError):
    pass


class RetrievalApi(Protocol):
    def plan(self, query_text: str) -> RetrievalPlan: ...

    async def retrieve(self, query: RetrievalQuery) -> list[RetrievedKnowledgeUnit]: ...


__all__ = [
    "QueryProfile",
    "RetrievalApi",
    "RetrievalError",
    "RetrievalFingerprint",
    "RetrievalPlan",
    "RetrievalQuery",
    "RetrievalRejectedError",
    "RetrievalUnavailableError",
    "RetrievedKnowledgeUnit",
    "VisibilityScope",
]
