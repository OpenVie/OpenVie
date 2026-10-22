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
