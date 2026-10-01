from __future__ import annotations

from collections.abc import Sequence
from typing import Any

from app.modules.generation.api import (
    GenerationApi,
    GenerationContext,
    GenerationRejectedError,
    GenerationResult,
    GenerationTimeoutError,
    GenerationUnavailableError,
)
from app.modules.generation.internal.models import ChatMessage, ChatSession
from app.modules.generation.internal.service import RagChatService
from app.modules.model.api import ModelTimeoutError, ModelUnavailableError
from app.modules.retrieval.api import (
    RetrievalApi,
    RetrievalQuery,
)


class GenerationRetrievalAdapter:
    def __init__(self, retrieval: RetrievalApi) -> None:
        self._retrieval = retrieval

    async def retrieve(
        self,
        *,
        tenant_id: str,
        knowledge_base_id: str,
        query_text: str,
        query_vector: Sequence[float],
        authoritative_revision: int = 0,
        document_ids: Sequence[str] | None = None,
    ) -> list[Any]:
        return await self._retrieval.retrieve(
            RetrievalQuery(
                tenant_id=tenant_id,
                knowledge_base_id=knowledge_base_id,
                query_text=query_text,
                query_vector=tuple(float(value) for value in query_vector),
                authoritative_revision=authoritative_revision,
                document_ids=(tuple(document_ids) if document_ids is not None else None),
            )
        )


class GenerationService(GenerationApi):
    def __init__(self, delegate: RagChatService, *, default_locale: str = "vi-VN") -> None:
        self._delegate = delegate
        self._default_locale = default_locale

    async def generate(self, context: GenerationContext) -> GenerationResult:
        if not context.generation_id or not context.tenant_id or not context.knowledge_base_id:
            raise GenerationRejectedError("Generation scope is incomplete")
        if context.authoritative_revision < 0:
            raise GenerationRejectedError("Revision must not be negative")
        session = ChatSession(
            id=context.turn_id or context.generation_id,
            tenant_id=context.tenant_id,
            user_id=None,
            chatbot_id=context.chatbot_id,
            knowledge_base_id=context.knowledge_base_id,
            locale=context.locale or self._default_locale,
            channel=context.channel or "EMPLOYEE_PLAYGROUND",
            authoritative_revision=context.authoritative_revision,
        )
        history = tuple(
            ChatMessage(role=item.role, content=item.content, sequence_number=index)
            for index, item in enumerate(context.prior_messages[:20], start=1)
            if item.role in {"user", "assistant", "system"}
        )
        try:
            message = await self._delegate.submit_message(
                session=session,
                content=context.question,
                prior_messages=history,
            )
        except ModelTimeoutError as exc:
            raise GenerationTimeoutError("Model deadline exceeded") from exc
        except ModelUnavailableError as exc:
            raise GenerationUnavailableError("Model provider failed") from exc

        return GenerationResult(
            generation_id=context.generation_id,
            authoritative_revision=context.authoritative_revision,
            answer=message.content,
            citations=tuple(message.citations),
        )
