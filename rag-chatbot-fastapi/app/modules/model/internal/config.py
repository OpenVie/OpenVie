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
