from dataclasses import dataclass

from app.common.config import FrozenModuleConfig


@dataclass(frozen=True, slots=True)
class GenerationConfig(FrozenModuleConfig):
    FIELDS = frozenset(
        {
            "CACHE_ENABLED",
            "CACHE_KEY_PREFIX",
            "FINAL_CONTEXT_TOP_K",
            "LLM_ADAPTER_ID",
            "LLM_DISABLE_THINKING",
            "LLM_MAX_OUTPUT_TOKENS",
            "LLM_MODEL_ID",
            "LLM_PROVIDER",
            "LLM_TEMPERATURE",
            "QDRANT_API_KEY",
            "QDRANT_URL",
            "SEMANTIC_ANSWER_CACHE_CANDIDATE_LIMIT",
            "SEMANTIC_ANSWER_CACHE_CLEANUP_BATCH_SIZE",
            "SEMANTIC_ANSWER_CACHE_COLLECTION",
            "SEMANTIC_ANSWER_CACHE_MODE",
            "SEMANTIC_ANSWER_CACHE_SIMILARITY_THRESHOLD",
            "SEMANTIC_ANSWER_CACHE_TTL_SECONDS",
            "SEMANTIC_ANSWER_CACHE_VECTOR_NAME",
            "TEXT_EMBEDDING_BASE_URL",
            "TEXT_EMBEDDING_DIMENSION",
            "TEXT_EMBEDDING_MODEL_ID",
        }
    )
