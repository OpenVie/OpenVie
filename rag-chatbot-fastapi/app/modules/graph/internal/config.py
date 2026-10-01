from dataclasses import dataclass

from app.common.config import FrozenModuleConfig


@dataclass(frozen=True, slots=True)
class GraphConfig(FrozenModuleConfig):
    FIELDS = frozenset(
        {"GRAPH_INTERNAL_TOKEN", "GRAPH_SERVICE_URL", "GRAPH_TIMEOUT_SECONDS"}
    )
