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

from dataclasses import dataclass, field

from app.modules.generation.api import Citation, TokenUsage
from app.modules.retrieval.api import RetrievedKnowledgeUnit

RetrievedChunk = RetrievedKnowledgeUnit


@dataclass(frozen=True, slots=True)
class ChatSession:
    id: str
    tenant_id: str
    user_id: str | None
    chatbot_id: str
    knowledge_base_id: str
    locale: str
    channel: str = "EMPLOYEE_PLAYGROUND"
    authoritative_revision: int = 0


@dataclass(frozen=True, slots=True)
class AssistantMessage:
    role: str
    content: str
    citations: list[Citation] = field(default_factory=list)
    token_usage: TokenUsage = TokenUsage()
    # Short follow-up questions the client may render as clickable chips.
    suggested_questions: list[str] = field(default_factory=list)


@dataclass(frozen=True, slots=True)
class ChatMessage:
    role: str
    content: str
    citations: list[Citation] = field(default_factory=list)
    sequence_number: int | None = None
