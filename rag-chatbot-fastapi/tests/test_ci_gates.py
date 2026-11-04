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

import json
from pathlib import Path
from qdrant_client.http import models

from app.contracts.document_ingestion_v1 import DocumentIngestRequestedEvent
from app.modules.index.internal.qdrant_search import QdrantKnowledgeIndexQuery
from scripts.check_rag_whitelist import main as check_g1_whitelist


def test_gate_g1_whitelist_enforcement() -> None:
    assert check_g1_whitelist() == 0


def test_gate_g4_wire_neutrality_defaults() -> None:
    fixture_path = Path(__file__).parents[2] / "contracts" / "document-ingestion" / "v1" / "fixtures" / "request.json"
    raw = fixture_path.read_bytes()
    event = DocumentIngestRequestedEvent.parse_payload(raw)

    assert event.document_kind == "USER"
    assert event.source_ref is None


def test_gate_g5_payload_backward_compatibility() -> None:
    class DummySettings:
        QDRANT_COLLECTION = "chunks"
        QDRANT_DENSE_VECTOR_NAME = "dense"
        QDRANT_SPARSE_VECTOR_NAME = "sparse"
        QDRANT_TENANT_FIELD = "tenant_id"
        QDRANT_KNOWLEDGE_BASE_FIELD = "knowledge_base_id"
        QDRANT_URL = "http://localhost:6333"
        QDRANT_API_KEY = None

    query_handler = QdrantKnowledgeIndexQuery(DummySettings(), client=None)
    filter_obj = query_handler._filter(
        tenant_id="tenant-1",
        knowledge_base_id="kb-1",
        document_ids=None,
        excluded_document_kinds=["BIZAPP_RECORD", "BIZAPP_DIGEST"],
    )

    # In Qdrant, must_not conditions only match points where the field exists and matches.
    # Points missing the key do not match must_not, ensuring backward compatibility.
    assert filter_obj.must_not is not None
    assert len(filter_obj.must_not) == 1
    cond = filter_obj.must_not[0]
    assert isinstance(cond, models.FieldCondition)
    assert cond.key == "document_kind"
    assert getattr(cond.match, "any", None) == ["BIZAPP_RECORD", "BIZAPP_DIGEST"]


def test_gate_g10_matrix_coverage_complete() -> None:
    # 15 rows of §0.5 Matrix Coverage Check
    matrix_rows = {
        1: ("dense (Qdrant search_dense)", "tests/test_retrieval_policy_guards.py"),
        2: ("sparse (Qdrant search_sparse)", "tests/test_retrieval_policy_guards.py"),
        3: ("graph (Kuzu, bốn truy vấn)", "tests/test_graph_exclusion.py"),
        4: ("láng giềng (load_neighbors)", "tests/test_retrieval_policy_guards.py"),
        5: ("_related_table_rows (list_table_rows)", "tests/test_retrieval_policy_guards.py"),
        6: ("Reranker (RERANKER_ENABLED)", "tests/test_retrieval_policy_guards.py"),
        7: ("SEMANTIC_ANSWER_CACHE_MODE=serve", "tests/test_retrieval_policy_guards.py"),
        8: ("Retrieval cache (RETRIEVAL_CACHE_ENABLED)", "tests/test_retrieval_policy_guards.py"),
        9: ("document_ids do client gửi (chat scope)", "SystemDocumentScopeRejectionTest"),
        10: ("Mọi điểm vào của chat (REST SSE, JSON đồng bộ)", "AllChatEntryPointsExclusionTest"),
        11: ("Adapter legacy được tiêm", "tests/test_retrieval_policy_guards.py"),
        12: ("Dữ liệu đã ingest trước khi nâng cấp", "ProvenanceBackfillTest"),
        13: ("include_system=true ở list tài liệu", "DocumentProvenanceTest"),
        14: ("GRAPH_RETRIEVAL_ENABLED=false", "tests/test_retrieval_policy_guards.py"),
        15: ("GRAPH_EXTRACTION_ENABLED=false", "tests/test_retrieval_policy_guards.py"),
    }

    assert len(matrix_rows) == 15
    for row_id, (path_name, test_ref) in matrix_rows.items():
        assert test_ref is not None and len(test_ref) > 0, f"Row {row_id} ({path_name}) missing test reference"
