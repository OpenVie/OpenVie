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
class IndexConfig(FrozenModuleConfig):
    FIELDS = frozenset(
        {
            "QDRANT_API_KEY",
            "QDRANT_COLLECTION",
            "QDRANT_DENSE_VECTOR_NAME",
            "QDRANT_KNOWLEDGE_BASE_FIELD",
            "QDRANT_SPARSE_VECTOR_NAME",
            "QDRANT_TENANT_FIELD",
            "QDRANT_URL",
        }
    )
