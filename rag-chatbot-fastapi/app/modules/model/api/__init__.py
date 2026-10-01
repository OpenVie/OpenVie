from __future__ import annotations

import unicodedata
from collections.abc import Sequence
from dataclasses import dataclass
from enum import StrEnum
from typing import Protocol

EMBEDDING_NORMALIZATION_VERSION = 1


def normalize_embedding_text(text: str) -> str:
    return unicodedata.normalize("NFC", text.replace("\r\n", "\n").replace("\r", "\n"))


class ModelRole(StrEnum):
    SYSTEM = "system"
    USER = "user"
    ASSISTANT = "assistant"


@dataclass(frozen=True, slots=True)
class ModelMessage:
    role: ModelRole
    content: str


@dataclass(frozen=True, slots=True)
class ModelCompletion:
    content: str
    input_tokens: int | None = None
    output_tokens: int | None = None


@dataclass(frozen=True, slots=True)
class SparseEmbedding:
    indices: tuple[int, ...]
    values: tuple[float, ...]


@dataclass(frozen=True, slots=True)
class EmbeddingNormalization:
    version: str
    normalized_text: str


class ModelError(Exception):
    """Base error owned by the model capability."""


class ModelTimeoutError(ModelError):
    pass


class ModelRejectedError(ModelError):
    pass


class ModelUnavailableError(ModelError):
    pass


class ChatModelApi(Protocol):
    provider: str
    model: str

    async def complete(self, messages: Sequence[dict[str, object]]) -> str: ...

    async def complete_with_usage(
        self, messages: Sequence[dict[str, object]]
    ) -> ModelCompletion: ...


class TextEmbeddingApi(Protocol):
    async def embed_documents(self, texts: Sequence[str]) -> list[list[float]]: ...

    async def embed_query(self, text: str) -> list[float]: ...


class SparseEmbeddingApi(Protocol):
    async def embed_documents(self, texts: Sequence[str]) -> list[SparseEmbedding]: ...

    async def embed_query(self, text: str) -> SparseEmbedding: ...


__all__ = [
    "ChatModelApi",
    "EmbeddingNormalization",
    "EMBEDDING_NORMALIZATION_VERSION",
    "ModelCompletion",
    "ModelError",
    "ModelMessage",
    "ModelRejectedError",
    "ModelRole",
    "ModelTimeoutError",
    "ModelUnavailableError",
    "SparseEmbedding",
    "SparseEmbeddingApi",
    "TextEmbeddingApi",
    "normalize_embedding_text",
]
