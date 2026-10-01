from dataclasses import dataclass

from app.common.config import FrozenModuleConfig


@dataclass(frozen=True, slots=True)
class IngestionTransportConfig(FrozenModuleConfig):
    FIELDS = frozenset({"INGESTION_HEARTBEAT_SECONDS", "RABBITMQ_URL"})
