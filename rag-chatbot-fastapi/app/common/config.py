from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from types import MappingProxyType
from typing import Any, ClassVar, Self


@dataclass(frozen=True, slots=True)
class FrozenModuleConfig:
    _values: Mapping[str, Any]
    FIELDS: ClassVar[frozenset[str]] = frozenset()

    @classmethod
    def from_settings(cls, settings: object) -> Self:
        names = cls.FIELDS or frozenset(
            name
            for name in dir(settings)
            if name.isupper() and not name.startswith("_")
        )
        values = {
            name: getattr(settings, name)
            for name in names
        }
        return cls(MappingProxyType(values))

    def __getattr__(self, name: str) -> Any:
        try:
            return self._values[name]
        except KeyError as exc:
            raise AttributeError(name) from exc


@dataclass(frozen=True, slots=True)
class StorageConfig(FrozenModuleConfig):
    FIELDS = frozenset(
        {
            "SEAWEEDFS_ACCESS_KEY",
            "SEAWEEDFS_BUCKET",
            "SEAWEEDFS_CONNECT_TIMEOUT_SECONDS",
            "SEAWEEDFS_MAX_ATTEMPTS",
            "SEAWEEDFS_READ_TIMEOUT_SECONDS",
            "SEAWEEDFS_S3_ENDPOINT",
            "SEAWEEDFS_SECRET_KEY",
        }
    )
