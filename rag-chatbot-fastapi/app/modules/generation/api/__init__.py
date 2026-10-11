from __future__ import annotations

from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from enum import StrEnum
from typing import Protocol


class GenerationCacheTier(StrEnum):
    NONE = "none"
    GENERATION_ID = "generation_id"
    SEMANTIC_EXACT = "semantic_exact"
    SEMANTIC_VECTOR = "semantic_vector"


@dataclass(frozen=True, slots=True)
class PriorMessage:
    role: str
    content: str


@dataclass(frozen=True, slots=True)
class GenerationContext:
    generation_id: str
    turn_id: str
    tenant_id: str
    chatbot_id: str
    knowledge_base_id: str
    authoritative_revision: int
    channel: str
    locale: str
    question: str
    prior_messages: tuple[PriorMessage, ...]
    tenant_name: str
    prompt_schema_version: str


@dataclass(frozen=True, slots=True)
class Citation:
    id: str
    document_id: str
    source_name: str
    page_number: int | None
    chunk_index: int
    score: float
    snippet: str
    unit_id: str | None = None
    modality: str | None = None
    section_path: tuple[str, ...] = ()
    block_type: str | None = None
    sheet_name: str | None = None
    cell_range: str | None = None
    table_id: str | None = None


@dataclass(frozen=True, slots=True)
class TokenUsage:
    input_tokens: int | None = None
    output_tokens: int | None = None
    avoided_input_tokens: int | None = None
    avoided_output_tokens: int | None = None


@dataclass(frozen=True, slots=True)
class GenerationResult:
    generation_id: str
    authoritative_revision: int
    answer: str
    citations: tuple[Citation, ...] = ()
    token_usage: TokenUsage = TokenUsage()
    cache_tier: GenerationCacheTier = GenerationCacheTier.NONE


@dataclass(frozen=True, slots=True)
class GenerationCacheCleanupResult:
    scanned: int
    expired: int
    deleted: int
    batches: int
    apply: bool


class GenerationError(Exception):
    pass


class GenerationRejectedError(GenerationError):
    pass


class GenerationTimeoutError(GenerationError):
    pass


class GenerationUnavailableError(GenerationError):
    pass


class GenerationApi(Protocol):
    async def generate(
        self,
        context: GenerationContext,
        *,
        on_content: Callable[[str], Awaitable[None]] | None = None,
    ) -> GenerationResult: ...


class GenerationCacheMaintenanceApi(Protocol):
    async def cleanup_expired(
        self, *, max_batches: int, apply: bool
    ) -> GenerationCacheCleanupResult: ...


__all__ = [
    "Citation",
    "GenerationApi",
    "GenerationCacheMaintenanceApi",
    "GenerationCacheCleanupResult",
    "GenerationCacheTier",
    "GenerationContext",
    "GenerationError",
    "GenerationRejectedError",
    "GenerationResult",
    "GenerationTimeoutError",
    "GenerationUnavailableError",
    "PriorMessage",
    "TokenUsage",
]
