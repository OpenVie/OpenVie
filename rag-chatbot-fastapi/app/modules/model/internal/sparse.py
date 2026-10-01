from __future__ import annotations

import asyncio
from collections.abc import Sequence
from typing import Any

from app.modules.model.api import ModelUnavailableError, SparseEmbedding
from app.modules.model.internal.config import ModelConfig


class FastEmbedSparseEncoder:
    """Lazy FastEmbed adapter for Qdrant's BM25 sparse model."""

    def __init__(self, settings: ModelConfig, model: Any | None = None):
        self._model_id = settings.SPARSE_MODEL_ID
        self._cache_dir = settings.SPARSE_MODEL_CACHE_DIR
        self._model = model

    async def embed_documents(self, texts: Sequence[str]) -> list[SparseEmbedding]:
        return await asyncio.to_thread(self._embed, list(texts))

    async def embed_query(self, text: str) -> SparseEmbedding:
        results = await self.embed_documents([text])
        if not results:
            raise ModelUnavailableError("Sparse encoder returned no query vector")
        return results[0]

    def _embed(self, texts: list[str]) -> list[SparseEmbedding]:
        try:
            if self._model is None:
                from fastembed import SparseTextEmbedding

                self._model = SparseTextEmbedding(
                    model_name=self._model_id,
                    cache_dir=self._cache_dir,
                )
            vectors = list(self._model.embed(texts))
            return [
                SparseEmbedding(
                    indices=tuple(int(value) for value in _as_list(vector.indices)),
                    values=tuple(float(value) for value in _as_list(vector.values)),
                )
                for vector in vectors
            ]
        except Exception as exc:
            raise ModelUnavailableError(f"Unable to generate sparse embeddings: {exc}") from exc


def _as_list(values: Any) -> list[Any]:
    method = getattr(values, "tolist", None)
    return list(method() if method is not None else values)
