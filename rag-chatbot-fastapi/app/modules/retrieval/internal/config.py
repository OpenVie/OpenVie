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

from dataclasses import dataclass

from app.common.config import FrozenModuleConfig


@dataclass(frozen=True, slots=True)
class RetrievalConfig(FrozenModuleConfig):
    FIELDS = frozenset(
        {
            "CACHE_KEY_PREFIX",
            "CALCULATION_DENSE_WEIGHT",
            "CALCULATION_GRAPH_WEIGHT",
            "CALCULATION_SPARSE_WEIGHT",
            "CONTEXT_DOCUMENT_SOFT_LIMIT",
            "DENSE_CANDIDATE_COUNT",
            "EXACT_DENSE_WEIGHT",
            "EXACT_GRAPH_WEIGHT",
            "EXACT_SPARSE_WEIGHT",
            "FINAL_CONTEXT_TOP_K",
            "FUSION_CANDIDATE_COUNT",
            "GRAPH_CANDIDATE_COUNT",
            "GRAPH_MAX_HOPS",
            "GRAPH_SERVICE_URL",
            "GRAPH_TIMEOUT_SECONDS",
            "NEIGHBOR_EXPANSION_LIMIT",
            "PRIMARY_CONTEXT_TOP_K",
            "QDRANT_COLLECTION",
            "QDRANT_DENSE_VECTOR_NAME",
            "QDRANT_KNOWLEDGE_BASE_FIELD",
            "QDRANT_SPARSE_VECTOR_NAME",
            "QDRANT_TENANT_FIELD",
            "QDRANT_URL",
            "RELATIONAL_DENSE_WEIGHT",
            "RELATIONAL_GRAPH_WEIGHT",
            "RELATIONAL_SPARSE_WEIGHT",
            "RERANKER_ENABLED",
            "RERANKER_MODEL_ID",
            "RERANKER_TIMEOUT_SECONDS",
            "RERANKER_URL",
            "RRF_K",
            "SEMANTIC_DENSE_WEIGHT",
            "SEMANTIC_GRAPH_WEIGHT",
            "SEMANTIC_SPARSE_WEIGHT",
            "SPARSE_CANDIDATE_COUNT",
            "SPARSE_MODEL_ID",
            "TEXT_EMBEDDING_BASE_URL",
            "TEXT_EMBEDDING_DIMENSION",
            "TEXT_EMBEDDING_MODEL_ID",
        }
    )
