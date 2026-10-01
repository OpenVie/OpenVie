from __future__ import annotations

import time
from collections.abc import Sequence
from dataclasses import replace
from typing import Any, Protocol

import httpx

from app.common.metrics import AI_RERANKER_SECONDS
from app.modules.retrieval.api import RetrievedKnowledgeUnit as RetrievedChunk
from app.modules.retrieval.internal.config import RetrievalConfig


class Reranker(Protocol):
    async def rerank(
        self, query_text: str, candidates: Sequence[RetrievedChunk]
    ) -> list[RetrievedChunk]: ...


class TeiReranker:
    def __init__(self, settings: RetrievalConfig):
        self._url = settings.RERANKER_URL.rstrip("/")
        self._model = settings.RERANKER_MODEL_ID
        self._timeout = settings.RERANKER_TIMEOUT_SECONDS

    async def rerank(
        self, query_text: str, candidates: Sequence[RetrievedChunk]
    ) -> list[RetrievedChunk]:
        started_at = time.perf_counter()
        outcome = "success"
        try:
            async with httpx.AsyncClient(timeout=self._timeout) as client:
                response = await client.post(
                    f"{self._url}/rerank",
                    json={
                        "query": query_text,
                        "texts": [candidate.text for candidate in candidates],
                        "model": self._model,
                        "truncate": True,
                    },
                )
                response.raise_for_status()
                payload: Any = response.json()
            rows = payload.get("results", []) if isinstance(payload, dict) else payload
            scores = {
                int(row["index"]): float(row.get("score", row.get("relevance_score", 0.0)))
                for row in rows
            }
            return [
                replace(candidates[index], score=scores.get(index, candidates[index].score))
                for index in sorted(
                    range(len(candidates)),
                    key=lambda index: (-scores.get(index, float("-inf")), index),
                )
            ]
        except Exception:
            outcome = "error"
            raise
        finally:
            AI_RERANKER_SECONDS.labels(outcome=outcome).observe(time.perf_counter() - started_at)
