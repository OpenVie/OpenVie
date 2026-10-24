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

from collections.abc import Sequence

import pytest

from app.modules.generation.internal.errors import ChatModelTimeoutError
from app.modules.generation.internal.suggestions import (
    MAX_SUGGESTED_QUESTIONS,
    _clean_questions,
    suggest_next_questions,
)

_TRIPLET = (
    "Chính sách đổi trả hàng có áp dụng cho sản phẩm khuyến mãi không?",
    "mất bao lâu?",
    "Thủ tục hoàn tiền sau khi đổi trả diễn ra thế nào?",
)


class EchoModel:
    async def complete(self, messages: Sequence[dict[str, object]]) -> str:
        self.messages = messages  # type: ignore[attr-defined]
        return "\n".join(_TRIPLET)


class TimingOutModel:
    async def complete(self, messages: Sequence[dict[str, object]]) -> str:
        del messages
        raise ChatModelTimeoutError("Model generation timed out")


def test_clean_questions_strips_markers_labels_and_quotes() -> None:
    raw = "\n".join(
        [
            "1. Chính sách đổi trả hàng có áp dụng cho sản phẩm khuyến mãi không?",
            "- mất bao lâu?",
            "• Thủ tục hoàn tiền sau khi đổi trả diễn ra thế nào?",
        ]
    )
    assert _clean_questions(raw) == _TRIPLET

    raw = "\n".join(
        [
            "Câu hỏi: Cân nặng tối đa là bao nhiêu?",
            "\"Có cần hóa đơn để đổi trả?\"",
        ]
    )
    assert _clean_questions(raw) == ("Cân nặng tối đa là bao nhiêu?", "Có cần hóa đơn để đổi trả?")


def test_clean_questions_dedupes_case_insensitively_and_caps_three() -> None:
    raw = "\n".join([_TRIPLET[0].upper(), _TRIPLET[0], *_TRIPLET[1:], *_TRIPLET[2:]])
    assert len(_clean_questions(raw)) == MAX_SUGGESTED_QUESTIONS
    assert _clean_questions(raw)[0] == _TRIPLET[0].upper()


def test_clean_questions_drops_unusable_lines() -> None:
    raw = "\n".join(
        [
            "không phải câu hỏi",  # no question mark, too short
            "?",  # too short
            "Đây là một câu hỏi cực kỳ dài " * 8 + "?",  # over the length cap
            "Hợp lệ là bao nhiêu ngày?",
        ]
    )
    assert _clean_questions(raw) == ("Hợp lệ là bao nhiêu ngày?",)


@pytest.mark.asyncio
async def test_suggest_next_questions_returns_cleaned_triplet() -> None:
    model = EchoModel()
    result = await suggest_next_questions(
        model, question="Chinh sach doi tra?", answer="Sản phẩm được đổi trong 7 ngày [S1]."
    )
    assert result == _TRIPLET
    prompt = str(model.messages[0]["content"])  # type: ignore[attr-defined]
    assert "follow-up questions" in prompt
    body = str(model.messages[1]["content"])  # type: ignore[attr-defined]
    assert "Chinh sach doi tra?" in body
    assert "[S1]." in body


@pytest.mark.asyncio
async def test_suggest_next_questions_attaches_language_hint_for_known_locale() -> None:
    model = EchoModel()
    await suggest_next_questions(
        model,
        question="Question",
        answer="Answer.",
        locale="vi-VN",
    )
    prompt = str(model.messages[0]["content"])  # type: ignore[attr-defined]
    assert "The answer language is Vietnamese." in prompt


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("model", "answer"),
    [
        (TimingOutModel(), "Sản phẩm được đổi trong 7 ngày [S1]."),
        (EchoModel(), "",),
        (EchoModel(), "   "),
    ],
)
async def test_suggest_next_questions_falls_back_to_empty_result(
    model: EchoModel | TimingOutModel, answer: str
) -> None:
    result = await suggest_next_questions(
        model, question="Chinh sach doi tra?", answer=answer
    )
    assert result == ()


@pytest.mark.asyncio
async def test_bounded_suggestion_call_reuses_complete_text_budget() -> None:
    class BoundedModel:
        def __init__(self) -> None:
            self.budget: int | None = None

        async def complete_text(
            self, messages: Sequence[dict[str, object]], *, max_output_tokens: int
        ) -> str:
            self.budget = max_output_tokens
            return "\n".join(_TRIPLET)

    model = BoundedModel()
    result = await suggest_next_questions(
        model, question="Question", answer="Answer.", conversation=[]
    )
    assert result == _TRIPLET
    assert model.budget is not None and model.budget > 0


@pytest.mark.asyncio
async def test_conversation_block_bounds_recent_turns_in_prompt() -> None:
    class Turn:
        role = "user"

        def __init__(self, content: str) -> None:
            self.content = content

    model = EchoModel()
    await suggest_next_questions(
        model,
        question="Question",
        answer="Answer.",
        conversation=[Turn("Dòng lịch sử")],
        locale="vi",
    )
    body = str(model.messages[1]["content"])  # type: ignore[attr-defined]
    assert "Recent conversation:" in body
    assert "Dòng lịch sử" in body
    assert "The answer language is Vietnamese." in str(model.messages[0]["content"])  # type: ignore[attr-defined]
