from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True, slots=True)
class IngestionDiagnostic:
    incomplete_checkpoint_count: int
    truncated: bool


class IngestionDiagnostics(Protocol):
    async def inspect(self, *, scan_limit: int) -> IngestionDiagnostic: ...
