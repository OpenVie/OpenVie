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
