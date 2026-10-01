from __future__ import annotations

from dataclasses import dataclass, field

from app.modules.generation.api import Citation
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


@dataclass(frozen=True, slots=True)
class ChatMessage:
    role: str
    content: str
    citations: list[Citation] = field(default_factory=list)
    sequence_number: int | None = None
