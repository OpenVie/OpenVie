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
class ModelConfig(FrozenModuleConfig):
    FIELDS = frozenset(
        {
            "CACHE_ENABLED",
            "CACHE_KEY_PREFIX",
            "EMBEDDING_CACHE_ENABLED",
            "EMBEDDING_CACHE_TTL_SECONDS",
            "LLM_BASE_URL",
            "LLM_DISABLE_THINKING",
            "LLM_MAX_OUTPUT_TOKENS",
            "LLM_MODEL_ID",
            "LLM_PROVIDER",
            "LLM_TEMPERATURE",
            "LLM_TIMEOUT_SECONDS",
            "SPARSE_MODEL_CACHE_DIR",
            "SPARSE_MODEL_ID",
            "TEXT_EMBEDDING_BASE_URL",
            "TEXT_EMBEDDING_BATCH_SIZE",
            "TEXT_EMBEDDING_DIMENSION",
            "TEXT_EMBEDDING_MODEL_ID",
            "TEXT_EMBEDDING_TIMEOUT_SECONDS",
        }
    )
