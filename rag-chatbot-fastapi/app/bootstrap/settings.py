from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

PROJECT_ROOT = Path(__file__).resolve().parents[3]
SERVICE_ROOT = Path(__file__).resolve().parents[2]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=(PROJECT_ROOT / ".env", SERVICE_ROOT / ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    APP_ENV: Literal["development", "test", "staging", "production"] = "development"
    APP_HOST: str = "0.0.0.0"
    APP_PORT: int = 8000
    LOG_LEVEL: str = "INFO"
    CORS_ORIGINS: str = "http://localhost:3000"
    DEFAULT_LOCALE: str = "vi-VN"
    READINESS_REQUIRE_MODELS: bool = False
    READINESS_DIAGNOSTICS_TIMEOUT_SECONDS: float = 0.5
    READINESS_INGESTION_SCAN_LIMIT: int = 200

    REDIS_URL: str = "redis://localhost:16379/0"
    REDIS_CONNECT_TIMEOUT_SECONDS: float = 1.0
    REDIS_OPERATION_TIMEOUT_SECONDS: float = 1.0
    RABBITMQ_URL: str = "amqp://rag_user:rag_password@localhost:15673/"
    INGESTION_CHECKPOINT_RETENTION_SECONDS: int = 30 * 24 * 60 * 60
    INGESTION_LEASE_SECONDS: int = 300
    INGESTION_HEARTBEAT_SECONDS: int = 30
    INGESTION_WORKER_CONCURRENCY: int = 4

    CACHE_ENABLED: bool = False
    CACHE_KEY_PREFIX: str = "ccn:v1"
    CACHE_TTL_JITTER_PERCENT: int = 10
    EMBEDDING_CACHE_ENABLED: bool = False
    EMBEDDING_CACHE_TTL_SECONDS: int = 86400
    RETRIEVAL_CACHE_ENABLED: bool = False
    RETRIEVAL_CACHE_TTL_SECONDS: int = 120
    SEMANTIC_ANSWER_CACHE_MODE: Literal["off", "shadow", "serve"] = "off"
    SEMANTIC_ANSWER_CACHE_TTL_SECONDS: int = 3600
    SEMANTIC_ANSWER_CACHE_SIMILARITY_THRESHOLD: float = 0.97
    SEMANTIC_ANSWER_CACHE_COLLECTION: str = "semantic_answer_cache_v1"
    SEMANTIC_ANSWER_CACHE_VECTOR_NAME: str = "query_v1"
    SEMANTIC_ANSWER_CACHE_CANDIDATE_LIMIT: int = 5
    SEMANTIC_ANSWER_CACHE_CLEANUP_BATCH_SIZE: int = 1000
    GENERATION_RESULT_CACHE_TTL_SECONDS: int = 600

    GRPC_HOST: str = "0.0.0.0"
    GRPC_PORT: int = 50051
    GRPC_PLAINTEXT: bool = True
    GRPC_SERVER_CERTIFICATE: str = ""
    GRPC_SERVER_KEY: str = ""
    GRPC_CLIENT_CA_CERTIFICATE: str = ""
    GRPC_MAX_MESSAGE_BYTES: int = 16 * 1024 * 1024

    SEAWEEDFS_MASTER_URL: str = "http://localhost:19334"
    SEAWEEDFS_FILER_URL: str = "http://localhost:18888"
    SEAWEEDFS_S3_ENDPOINT: str = "http://localhost:18333"
    SEAWEEDFS_ACCESS_KEY: str = ""
    SEAWEEDFS_SECRET_KEY: str = ""
    SEAWEEDFS_BUCKET: str = "cacanode"
    SEAWEEDFS_CONNECT_TIMEOUT_SECONDS: float = 3.0
    SEAWEEDFS_READ_TIMEOUT_SECONDS: float = 30.0
    SEAWEEDFS_MAX_ATTEMPTS: int = 3

    QDRANT_URL: str = "http://localhost:16333"
    QDRANT_API_KEY: str = ""
    QDRANT_COLLECTION: str = "knowledge_units_v2"
    QDRANT_DENSE_VECTOR_NAME: str = "text_bge_m3_v1"
    QDRANT_SPARSE_VECTOR_NAME: str = "text_bm25_v1"
    QDRANT_TENANT_FIELD: str = "tenant_id"
    QDRANT_KNOWLEDGE_BASE_FIELD: str = "knowledge_base_id"
    KUZU_DATABASE_PATH: str = "./data/kuzu/cacanode.kuzu"
    GRAPH_SERVICE_URL: str = "http://localhost:8010"
    GRAPH_INTERNAL_TOKEN: str = "development-graph-token"
    GRAPH_TIMEOUT_SECONDS: float = 30.0
    GRAPH_EXTRACTION_BATCH_SIZE: int = 4
    GRAPH_EXTRACTION_MAX_OUTPUT_TOKENS: int = 512
    GRAPH_EXTRACTION_MAX_ENTITIES_PER_UNIT: int = 2
    GRAPH_EXTRACTION_MAX_RELATIONS_PER_UNIT: int = 2
    GRAPH_EXTRACTION_REASONING_EFFORT: Literal["low", "medium", "high"] = "low"
    PARSER_VERSION: str = "digital-v1"
    CHUNKER_VERSION: str = "structural-v2"
    SPREADSHEET_MAX_ROWS: int = 250_000
    SPREADSHEET_MAX_COLUMNS: int = 2_000

    # Local generation/embedding by default. Providers target configured internal
    # endpoints only; there is no hosted-provider fallback.
    LLM_PROVIDER: Literal["ollama", "qwen"] = "ollama"
    LLM_BASE_URL: str = "http://localhost:11434/v1"
    LLM_MODEL_ID: str = "vylinh"
    LLM_ADAPTER_ID: str = ""
    LLM_TEMPERATURE: float = 0.2
    LLM_MAX_OUTPUT_TOKENS: int = 512
    LLM_TIMEOUT_SECONDS: float = 90.0
    LLM_USE_OLLAMA_NATIVE_CHAT: bool = True
    LLM_DISABLE_THINKING: bool = True

    TEXT_EMBEDDING_BASE_URL: str = "http://localhost:11434"
    TEXT_EMBEDDING_MODEL_ID: str = "bge-m3"
    TEXT_EMBEDDING_DIMENSION: int = 1024
    TEXT_EMBEDDING_BATCH_SIZE: int = 32
    TEXT_EMBEDDING_TIMEOUT_SECONDS: float = 120.0
    SPARSE_MODEL_ID: str = "Qdrant/bm25"
    SPARSE_MODEL_CACHE_DIR: str = "./data/models/fastembed"

    WORKER_MODE: Literal["embedded", "dedicated", "disabled"] = "embedded"
    WORKER_KINDS: str = "document"
    WORKER_POLL_INTERVAL_SECONDS: float = 2.0

    GRAPH_MAX_HOPS: int = 3
    DENSE_CANDIDATE_COUNT: int = 40
    SPARSE_CANDIDATE_COUNT: int = 40
    GRAPH_CANDIDATE_COUNT: int = 20
    FUSION_CANDIDATE_COUNT: int = 30
    RRF_K: int = 30
    SEMANTIC_DENSE_WEIGHT: float = 0.55
    SEMANTIC_SPARSE_WEIGHT: float = 0.30
    SEMANTIC_GRAPH_WEIGHT: float = 0.15
    EXACT_DENSE_WEIGHT: float = 0.25
    EXACT_SPARSE_WEIGHT: float = 0.60
    EXACT_GRAPH_WEIGHT: float = 0.15
    RELATIONAL_DENSE_WEIGHT: float = 0.30
    RELATIONAL_SPARSE_WEIGHT: float = 0.15
    RELATIONAL_GRAPH_WEIGHT: float = 0.55
    CALCULATION_DENSE_WEIGHT: float = 0.35
    CALCULATION_SPARSE_WEIGHT: float = 0.50
    CALCULATION_GRAPH_WEIGHT: float = 0.15
    PRIMARY_CONTEXT_TOP_K: int = 5
    FINAL_CONTEXT_TOP_K: int = 8
    CONTEXT_DOCUMENT_SOFT_LIMIT: int = 2
    NEIGHBOR_EXPANSION_LIMIT: int = 3
    RERANKER_ENABLED: bool = False
    RERANKER_URL: str = "http://localhost:8082"
    RERANKER_MODEL_ID: str = "Alibaba-NLP/gte-multilingual-reranker-base"
    RERANKER_TIMEOUT_SECONDS: float = 10.0

    OTEL_EXPORTER_OTLP_ENDPOINT: str = ""
    DISABLE_EXTERNAL_TELEMETRY: bool = True

    @property
    def cors_origins(self) -> list[str]:
        return [origin.strip() for origin in self.CORS_ORIGINS.split(",") if origin.strip()]

    @property
    def worker_kinds(self) -> tuple[str, ...]:
        return tuple(kind.strip() for kind in self.WORKER_KINDS.split(",") if kind.strip())

    @property
    def model_configured(self) -> bool:
        return bool(self.LLM_MODEL_ID and self.LLM_BASE_URL)

    @model_validator(mode="after")
    def validate_settings(self) -> "Settings":
        retrieval_counts = (
            self.DENSE_CANDIDATE_COUNT,
            self.SPARSE_CANDIDATE_COUNT,
            self.GRAPH_CANDIDATE_COUNT,
            self.FUSION_CANDIDATE_COUNT,
            self.RRF_K,
            self.PRIMARY_CONTEXT_TOP_K,
            self.FINAL_CONTEXT_TOP_K,
        )
        if any(value <= 0 for value in retrieval_counts):
            raise ValueError("Retrieval candidate and context limits must be positive")
        if not 0 <= self.GRAPH_MAX_HOPS <= 3:
            raise ValueError("GRAPH_MAX_HOPS must be between 0 and 3")
        if self.PRIMARY_CONTEXT_TOP_K > self.FINAL_CONTEXT_TOP_K:
            raise ValueError("PRIMARY_CONTEXT_TOP_K cannot exceed FINAL_CONTEXT_TOP_K")
        if self.RERANKER_TIMEOUT_SECONDS <= 0:
            raise ValueError("RERANKER_TIMEOUT_SECONDS must be positive")
        if self.REDIS_CONNECT_TIMEOUT_SECONDS <= 0 or self.REDIS_OPERATION_TIMEOUT_SECONDS <= 0:
            raise ValueError("Redis timeouts must be positive")
        if not 0 < self.READINESS_DIAGNOSTICS_TIMEOUT_SECONDS <= 2:
            raise ValueError("Readiness diagnostics timeout must be between 0 and 2 seconds")
        if not 1 <= self.READINESS_INGESTION_SCAN_LIMIT <= 1000:
            raise ValueError("Readiness ingestion scan limit must be between 1 and 1000")
        if (
            min(
                self.INGESTION_CHECKPOINT_RETENTION_SECONDS,
                self.INGESTION_LEASE_SECONDS,
                self.INGESTION_HEARTBEAT_SECONDS,
            )
            <= 0
        ):
            raise ValueError("Ingestion checkpoint and lease durations must be positive")
        if self.INGESTION_HEARTBEAT_SECONDS >= self.INGESTION_LEASE_SECONDS:
            raise ValueError("Ingestion heartbeat must be shorter than the lease")
        if not 1 <= self.INGESTION_WORKER_CONCURRENCY <= 64:
            raise ValueError("INGESTION_WORKER_CONCURRENCY must be between 1 and 64")
        if not 1 <= self.GRAPH_EXTRACTION_BATCH_SIZE <= 64:
            raise ValueError("GRAPH_EXTRACTION_BATCH_SIZE must be between 1 and 64")
        if self.GRAPH_EXTRACTION_MAX_OUTPUT_TOKENS < 64:
            raise ValueError("GRAPH_EXTRACTION_MAX_OUTPUT_TOKENS must be at least 64")
        if self.LLM_MAX_OUTPUT_TOKENS < 64:
            raise ValueError("LLM_MAX_OUTPUT_TOKENS must be at least 64")
        if (
            min(
                self.GRAPH_EXTRACTION_MAX_ENTITIES_PER_UNIT,
                self.GRAPH_EXTRACTION_MAX_RELATIONS_PER_UNIT,
            )
            < 1
        ):
            raise ValueError("Graph extraction per-unit limits must be at least 1")
        if not 0 <= self.CACHE_TTL_JITTER_PERCENT <= 100:
            raise ValueError("CACHE_TTL_JITTER_PERCENT must be between 0 and 100")
        if self.EMBEDDING_CACHE_TTL_SECONDS <= 0:
            raise ValueError("EMBEDDING_CACHE_TTL_SECONDS must be positive")
        if self.RETRIEVAL_CACHE_TTL_SECONDS <= 0:
            raise ValueError("RETRIEVAL_CACHE_TTL_SECONDS must be positive")
        if self.SEMANTIC_ANSWER_CACHE_TTL_SECONDS <= 0:
            raise ValueError("SEMANTIC_ANSWER_CACHE_TTL_SECONDS must be positive")
        if not 0 < self.SEMANTIC_ANSWER_CACHE_SIMILARITY_THRESHOLD <= 1:
            raise ValueError("SEMANTIC_ANSWER_CACHE_SIMILARITY_THRESHOLD must be in (0, 1]")
        if self.SEMANTIC_ANSWER_CACHE_CANDIDATE_LIMIT <= 0:
            raise ValueError("SEMANTIC_ANSWER_CACHE_CANDIDATE_LIMIT must be positive")
        if self.SEMANTIC_ANSWER_CACHE_CLEANUP_BATCH_SIZE <= 0:
            raise ValueError("SEMANTIC_ANSWER_CACHE_CLEANUP_BATCH_SIZE must be positive")
        if not self.SEMANTIC_ANSWER_CACHE_COLLECTION.strip():
            raise ValueError("SEMANTIC_ANSWER_CACHE_COLLECTION must not be blank")
        if not self.SEMANTIC_ANSWER_CACHE_VECTOR_NAME.strip():
            raise ValueError("SEMANTIC_ANSWER_CACHE_VECTOR_NAME must not be blank")
        if self.GENERATION_RESULT_CACHE_TTL_SECONDS <= 0:
            raise ValueError("GENERATION_RESULT_CACHE_TTL_SECONDS must be positive")
        if self.GRPC_PORT <= 0 or self.GRPC_MAX_MESSAGE_BYTES <= 0:
            raise ValueError("gRPC port and message size must be positive")
        if not self.GRPC_PLAINTEXT and not all(
            value.strip()
            for value in (
                self.GRPC_SERVER_CERTIFICATE,
                self.GRPC_SERVER_KEY,
                self.GRPC_CLIENT_CA_CERTIFICATE,
            )
        ):
            raise ValueError("Production gRPC mTLS material is incomplete")
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()


settings = get_settings()
