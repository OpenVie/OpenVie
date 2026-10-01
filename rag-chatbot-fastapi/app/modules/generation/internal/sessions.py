from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any, Protocol
from uuid import uuid4

from app.modules.generation.internal.models import (
    AssistantMessage,
    ChatMessage,
    ChatSession,
    Citation,
)


class ChatSessionStore(Protocol):
    def create(self, **kwargs: Any) -> ChatSession: ...

    def get_for_tenant(self, session_id: str, tenant_id: str) -> ChatSession | None: ...

    def add_user_message(self, session_id: str, content: str) -> None: ...

    def add_assistant_message(self, session_id: str, message: AssistantMessage) -> None: ...

    def list_messages(
        self,
        *,
        session_id: str,
        tenant_id: str,
        limit: int = 50,
        after: int | None = None,
    ) -> list[ChatMessage]: ...

    def close_for_tenant(self, session_id: str, tenant_id: str) -> bool: ...

    def list_playground_sessions(self, **kwargs: Any) -> list[dict[str, Any]]: ...

    def hide_playground_session(self, **kwargs: Any) -> bool: ...


@dataclass(slots=True)
class StoredMessage:
    role: str
    content: str
    citations: list[Citation] = field(default_factory=list)


@dataclass(slots=True)
class StoredSession:
    session: ChatSession
    messages: list[StoredMessage] = field(default_factory=list)


class InMemoryChatSessionStore:
    """Test-only session store."""

    def __init__(self) -> None:
        self._sessions: dict[str, StoredSession] = {}
        self._hidden_sessions: set[str] = set()

    def create(self, **kwargs: Any) -> ChatSession:
        tenant_id = str(kwargs["tenant_id"])
        session = ChatSession(
            id=str(uuid4()),
            tenant_id=tenant_id,
            user_id=kwargs.get("user_id"),
            chatbot_id=str(kwargs["chatbot_id"]),
            knowledge_base_id=str(kwargs["knowledge_base_id"]),
            locale=str(kwargs["locale"]),
            channel=str(kwargs.get("channel", "EMPLOYEE_PLAYGROUND")),
        )
        self._sessions[session.id] = StoredSession(session)
        return session

    def get_for_tenant(self, session_id: str, tenant_id: str) -> ChatSession | None:
        stored = self._sessions.get(session_id)
        if (
            stored is None
            or stored.session.tenant_id != tenant_id
            or session_id in self._hidden_sessions
        ):
            return None
        return stored.session

    def add_user_message(self, session_id: str, content: str) -> None:
        self._sessions[session_id].messages.append(StoredMessage("user", content))

    def add_assistant_message(self, session_id: str, message: AssistantMessage) -> None:
        self._sessions[session_id].messages.append(
            StoredMessage(message.role, message.content, message.citations)
        )

    def list_messages(
        self,
        *,
        session_id: str,
        tenant_id: str,
        limit: int = 50,
        after: int | None = None,
    ) -> list[ChatMessage]:
        stored = self._sessions.get(session_id)
        if stored is None or stored.session.tenant_id != tenant_id:
            return []
        return [
            ChatMessage(
                role=item.role,
                content=item.content,
                citations=item.citations,
                sequence_number=index,
            )
            for index, item in enumerate(stored.messages, start=1)
            if index > (after or 0)
        ][:limit]

    def close_for_tenant(self, session_id: str, tenant_id: str) -> bool:
        stored = self._sessions.get(session_id)
        if stored is None or stored.session.tenant_id != tenant_id:
            return False
        del self._sessions[session_id]
        return True

    def list_playground_sessions(self, **kwargs: Any) -> list[dict[str, Any]]:
        tenant_id = str(kwargs["tenant_id"])
        user_id = str(kwargs["user_id"])
        rows = []
        for stored in self._sessions.values():
            session = stored.session
            if (
                session.tenant_id == tenant_id
                and session.user_id == user_id
                and session.channel == "EMPLOYEE_PLAYGROUND"
                and session.id not in self._hidden_sessions
            ):
                first = next(
                    (item.content for item in stored.messages if item.role == "user"), ""
                )
                rows.append(
                    {
                        "id": session.id,
                        "title": first[:60] or datetime.now(UTC).date().isoformat(),
                        "message_count": len(stored.messages),
                        "status": "OPEN",
                        "created_at": datetime.now(UTC),
                        "last_activity_at": datetime.now(UTC),
                    }
                )
        offset = int(kwargs.get("offset", 0))
        limit = int(kwargs.get("limit", 50))
        return rows[offset : offset + limit]

    def hide_playground_session(self, **kwargs: Any) -> bool:
        session_id = str(kwargs["session_id"])
        stored = self._sessions.get(session_id)
        if (
            stored is None
            or stored.session.tenant_id != str(kwargs["tenant_id"])
            or stored.session.user_id != str(kwargs["user_id"])
        ):
            return False
        self._hidden_sessions.add(session_id)
        return True
