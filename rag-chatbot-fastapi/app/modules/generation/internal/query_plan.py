"""Contextual query planning for follow-up turns.

A follow-up question such as "can you be more specific?" carries no retrievable
terms on its own. This module rewrites it into one standalone search query using
the recent conversation, and is deliberately bounded:

* it runs only when the conversation already contains a user turn, so a first
  question costs nothing and cannot be distorted;
* it caps generation at ``MAX_FOLLOW_UP_OUTPUT_TOKENS`` so a small model cannot
  monopolize the turn budget;
* every failure (timeout, provider error, empty or over-long output) falls back
  to the original question, so retrieval never depends on the rewrite.
"""

from __future__ import annotations

import logging
import re
import time
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Any, Protocol

from app.common.metrics import AI_QUERY_PLAN_REWRITES_TOTAL, AI_QUERY_PLAN_SECONDS

logger = logging.getLogger(__name__)

MAX_HISTORY_MESSAGES = 6
MAX_HISTORY_CHARS = 400
MAX_FOLLOW_UP_OUTPUT_TOKENS = 64
MAX_QUERY_CHARS = 300

_FENCE = re.compile(r"^```(?:json)?\s*|\s*```$")
_LABEL = re.compile(
    r"^\s*(?:truy vấn|truy van|query|câu hỏi|soru|search query)\s*[:：]\s*",
    re.IGNORECASE,
)

_SYSTEM_PROMPT = (
    "You rewrite one follow-up chat question into a single standalone search query for a document "
    "retrieval system. Resolve pronouns and elliptical references from the conversation, and keep "
    "concrete names, numbers, and acronyms. Keep the language of the follow-up. Output only the "
    "search query on one line: no quotes, no labels, no explanation. If the follow-up already "
    "stands alone, repeat it unchanged."
)


class QueryPlannerModel(Protocol):
    async def complete(self, messages: Sequence[dict[str, Any]]) -> str: ...


@dataclass(frozen=True, slots=True)
class QueryPlan:
    """Retrieval query derived from the current question and prior turns."""

    query: str
    replaced: bool = False
    outcome: str = "unchanged"


class ContextualQueryPlanner:
    def __init__(self, model: QueryPlannerModel, *, enabled: bool = True) -> None:
        self._model = model
        self._enabled = enabled

    async def plan(
        self, *, question: str, prior_messages: Sequence[Any]
    ) -> QueryPlan:
        turns = _conversation(prior_messages)
        if not self._enabled or not any(role == "user" for role, _ in turns):
            return QueryPlan(query=question, outcome="skipped")

        started_at = time.perf_counter()
        outcome = "unchanged"
        try:
            raw = await self._complete(question, turns)
            rewritten = _clean_query(raw)
            if not rewritten or rewritten == question.strip():
                return QueryPlan(query=question, outcome="unchanged")
            outcome = "rewritten"
            return QueryPlan(query=rewritten, replaced=True, outcome=outcome)
        except Exception:
            outcome = "fallback"
            logger.warning(
                "query_plan_failed question_chars=%s", len(question), exc_info=True
            )
            return QueryPlan(query=question, outcome=outcome)
        finally:
            AI_QUERY_PLAN_SECONDS.labels(outcome=outcome).observe(
                time.perf_counter() - started_at
            )
            AI_QUERY_PLAN_REWRITES_TOTAL.labels(outcome=outcome).inc()

    async def _complete(self, question: str, turns: Sequence[tuple[str, str]]) -> str:
        conversation = "\n".join(f"{role}: {content}" for role, content in turns)
        messages = [
            {"role": "system", "content": _SYSTEM_PROMPT},
            {
                "role": "user",
                "content": f"Conversation:\n{conversation}\nFollow-up: {question}\nSearch query:",
            },
        ]
        bounded = getattr(self._model, "complete_text", None)
        if callable(bounded):
            return str(
                await bounded(messages, max_output_tokens=MAX_FOLLOW_UP_OUTPUT_TOKENS)
            )
        return str(await self._model.complete(messages))


def _conversation(prior_messages: Sequence[Any]) -> list[tuple[str, str]]:
    turns: list[tuple[str, str]] = []
    for message in prior_messages:
        role = str(getattr(message, "role", ""))
        if role not in {"user", "assistant"}:
            continue
        content = _condense(str(getattr(message, "content", "")))
        if content:
            turns.append((role, content))
    return turns[-MAX_HISTORY_MESSAGES:]


def _condense(text: str) -> str:
    normalized = " ".join(text.split())
    if len(normalized) <= MAX_HISTORY_CHARS:
        return normalized
    return normalized[:MAX_HISTORY_CHARS].rstrip()


def _clean_query(raw: str) -> str:
    text = _FENCE.sub("", raw.strip())
    line = next((candidate for candidate in text.splitlines() if candidate.strip()), "")
    line = _LABEL.sub("", line.strip()).strip().strip("\"'“”‘’`").strip()
    normalized = " ".join(line.split())
    if len(normalized) > MAX_QUERY_CHARS:
        return ""
    return normalized