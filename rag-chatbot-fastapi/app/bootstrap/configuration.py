from app.bootstrap.settings import Settings
from app.common.config import StorageConfig
from app.modules.generation.internal.config import GenerationConfig
from app.modules.graph.internal.config import GraphConfig
from app.modules.index.internal.config import IndexConfig
from app.modules.ingestion.internal.config import IngestionTransportConfig
from app.modules.model.internal.config import ModelConfig
from app.modules.retrieval.internal.config import RetrievalConfig


def storage_config(settings: Settings) -> StorageConfig:
    return StorageConfig.from_settings(settings)


def model_config(settings: Settings) -> ModelConfig:
    return ModelConfig.from_settings(settings)


def index_config(settings: Settings) -> IndexConfig:
    return IndexConfig.from_settings(settings)


def graph_config(settings: Settings) -> GraphConfig:
    return GraphConfig.from_settings(settings)


def retrieval_config(settings: Settings) -> RetrievalConfig:
    return RetrievalConfig.from_settings(settings)


def generation_config(settings: Settings) -> GenerationConfig:
    return GenerationConfig.from_settings(settings)


def ingestion_transport_config(settings: Settings) -> IngestionTransportConfig:
    return IngestionTransportConfig.from_settings(settings)
