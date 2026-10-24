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

"""Follow-up question suggestions for answered chat turns.

After the assistant answers from the retrieved sources, a tiny LLM call
proposes short questions the user might send next (the client renders them
as clickable chips). The module is deliberately bounded:

* it only runs on the grounded-answer path, never on chit-chat or
  "no information" replies;
* it caps generation at ``MAX_SUGGESTIONS_OUTPUT_TOKENS`` so a small model
  cannot monopolize the turn budget;
* every failure (timeout, provider error, empty or unusable output) is
  swallowed and returns no suggestions, so the answer itself never depends
  on the suggestion step.
"""

from __future__ import annotations

import logging
import re
import time
from collections.abc import Sequence
from typing import Any, Protocol

logger = logging.getLogger(__name__)

MAX_SUGGESTED_QUESTIONS = 3
MAX_SUGGESTIONS_OUTPUT_TOKENS = 96
MAX_HISTORY_MESSAGES = 6
MAX_HISTORY_CHARS = 400
MAX_QUESTION_CHARS = 220
MIN_QUESTION_CHARS = 8

_LABEL = re.compile(
    r"^\s*(?:gợi ý|goi y|câu hỏi|cau hoi|question|suggestion|prompt)\s*[:：]\s*",
    re.IGNORECASE,
)
_MARKER = re.compile(r"^\s*(?:[-•*\u2013\u2014]|\d+\s*[.)\]])\s*", re.IGNORECASE)
_QUOTES = "\"'`“”‘’"
_LANGUAGE_NAMES = {
    "vi": "Vietnamese",
    "en": "English",
    "fr": "French",
    "de": "German",
    "ja": "Japanese",
    "ko": "Korean",
    "zh": "Chinese",
}

_SUGGESTION_SYSTEM_PROMPT = (
    "You propose short follow-up questions a user of a document-grounded chatbot might ask next. "
    "Use only topics actually covered by the answer, never invent facts, and keep each question "
    "self-contained for the chatbot to answer: no pronouns that depend on the previous question. "
    "Phrase every suggestion as a direct question about the content itself. Never phrase offers, "
    "offers of help, or meta questions: avoid wording like 'Do you want', 'Would you like', "
    "'Do you know', or 'Have you heard', and their equivalents in any language, such as "
    "'Bạn có muốn' or 'Bạn có biết'. "
    "Write about the content like a person, never like an indexer: do not mention documents, file "
    "names, page numbers, chunk numbers, source IDs, or the phrase 'source'. "
    "Write at most 3 questions, one per line, as plain questions ending with a question mark. "
    "No numbering, no bullets, no quotes, no labels, no explanations. "
    "Write the questions in the same language as the answer."
)


class SuggestionModel(Protocol):
    async def complete(self, messages: Sequence[dict[str, Any]]) -> str: ...


async def suggest_next_questions(
    model: Any,
    *,
    question: str,
    answer: str,
    conversation: Sequence[Any] = (),
    locale: str | None = None,
) -> tuple[str, ...]:
    """Propose clickable follow-up questions for one answered turn.

    ``model`` is the chat model behind the answer. Any failure returns an
    empty tuple; suggestions are a nice-to-have and must never break a turn.
    """
    if not answer.strip():
        return ()
    started_at = time.perf_counter()
    count = 0
    try:
        raw = await _complete(model, question, answer, conversation, locale)
        questions = _clean_questions(raw)
        count = len(questions)
        if not questions:
            logger.info("suggestion_parse_empty question_chars=%s", len(question))
        return questions
    except Exception:
        logger.warning(
            "suggestion_generation_failed question_chars=%s", len(question),
            exc_info=True,
        )
        return ()
    finally:
        elapsed = time.perf_counter() - started_at
        logger.info(
            "suggestions_generated seconds=%.2f count=%s", elapsed, count
        )


async def _complete(
    model: Any,
    question: str,
    answer: str,
    conversation: Sequence[Any],
    locale: str | None,
) -> str:
    messages = [
        {"role": "system", "content": _SUGGESTION_SYSTEM_PROMPT + _language_hint(locale)},
        {
            "role": "user",
            "content": (
                f"{_conversation_block(conversation)}"
                f"User question:\n{question}\n\n"
                f"Assistant answer:\n{answer}\n\n"
                "Next questions, one per line:"
            ),
        },
    ]
    bounded = getattr(model, "complete_text", None)
    if callable(bounded):
        return str(await bounded(messages, max_output_tokens=MAX_SUGGESTIONS_OUTPUT_TOKENS))
    return str(await model.complete(messages))


def _language_hint(locale: str | None) -> str:
    if not locale:
        return ""
    code = locale.lower().split("-")[0]
    name = _LANGUAGE_NAMES.get(code)
    if name is None:
        return ""
    return f" The answer language is {name}."


def _conversation_block(conversation: Sequence[Any]) -> str:
    turns: list[str] = []
    for message in conversation:
        role = str(getattr(message, "role", ""))
        if role not in {"user", "assistant"}:
            continue
        content = " ".join(str(getattr(message, "content", "")).split())
        if not content:
            continue
        turns.append(f"{role}: {content[:MAX_HISTORY_CHARS]}")
    if not turns:
        return ""
    recent = turns[-MAX_HISTORY_MESSAGES:]
    return "Recent conversation:\n" + "\n".join(recent) + "\n\n"


def _clean_questions(raw: str) -> tuple[str, ...]:
    questions: list[str] = []
    seen: set[str] = set()
    for line in raw.splitlines():
        text = " ".join(line.split())
        text = _LABEL.sub("", text)
        text = _MARKER.sub("", text).strip(_QUOTES + " \t")
        normalized = " ".join(text.split())
        if len(normalized) > MAX_QUESTION_CHARS or len(normalized) < MIN_QUESTION_CHARS:
            continue
        if "?" not in normalized:
            continue
        key = normalized.casefold()
        if key in seen:
            continue
        seen.add(key)
        questions.append(normalized)
        if len(questions) == MAX_SUGGESTED_QUESTIONS:
            break
    return tuple(questions)


__all__ = [
    "MAX_SUGGESTED_QUESTIONS",
    "suggest_next_questions",
]
