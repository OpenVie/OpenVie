from __future__ import annotations

import logging
import re
import time
from collections.abc import AsyncGenerator, Awaitable, Callable, Sequence
from typing import Any, Protocol

from app.common.metrics import AI_RAG_ANSWER_SECONDS
from app.modules.generation.api import TokenUsage
from app.modules.generation.internal.answer_polish import polish_answer, polish_preview
from app.modules.generation.internal.calculation import SpreadsheetCalculationCoordinator
from app.modules.generation.internal.config import GenerationConfig
from app.modules.generation.internal.errors import ChatModelTimeoutError, ChatSessionNotFoundError
from app.modules.generation.internal.intent import (
    build_non_retrieval_reply,
    is_non_retrieval_question,
)
from app.modules.generation.internal.models import (
    AssistantMessage,
    ChatMessage,
    ChatSession,
    Citation,
    RetrievedChunk,
)
from app.modules.generation.internal.query_plan import ContextualQueryPlanner
from app.modules.generation.internal.semantic_answer_cache import (
    SemanticAnswerCache,
    SemanticCacheCandidate,
    SemanticCacheContext,
)
from app.modules.generation.internal.sensitive_policy import (
    has_authorized_citation,
    is_sensitive_query,
)
from app.modules.generation.internal.sessions import ChatSessionStore
from app.modules.model.api import ModelCompletion, ModelTimeoutError, ModelUnavailableError

logger = logging.getLogger(__name__)

NO_INFORMATION_RESPONSE = (
    "Mình không tìm thấy thông tin phù hợp trong tài liệu đã tải lên để trả lời câu hỏi này."
)

_SYSTEM_PROMPT = (
    "You are an internal assistant for one organization's workspace. "
    "You answer only from the supplied sources. "
    "If the sources do not contain the answer, say you do not know. "
    "Cite factual claims with [S1], [S2], etc. "
    "When the sources enumerate items (numbered layers, tiers, steps, rows, options, or fields), "
    "answer with a list that reproduces every item exactly as the sources present it: keep each "
    "item's number or label, its name, and the values associated with it, and never drop, "
    "renumber, merge, or abbreviate entries. Keep prose answers short. "
    "When the user asks to elaborate, be more specific, or give more detail, expand the previous "
    "answer with the complete enumerated content from the sources instead of repeating a summary. "
    "Write for the user, in a helpful and natural tone: do not copy source labels into the answer. "
    "Never mention document names, file names, chunk numbers, or source IDs in the answer body, "
    "and never repeat literal placeholder text such as [Tên Trường]; rephrase what the source "
    "means instead. "
    "Respond in the same language as the question. "
)

_CITATION = re.compile(r"\[\s*S\s*([0-9]+)\s*\]", re.IGNORECASE)

_CONVERSATION_MESSAGES = 6
_CONVERSATION_CHARS = 400


def _render_chunk_text(chunk: RetrievedChunk) -> str:
    """Render structural tables as markdown rows.

    Structural chunking stores each table line as `header | column | column`, so
    a whole row reads like one sentence. A small model asked to enumerate rows
    then lists the header cells instead of the rows. Rendering the pipe table
    back to markdown makes the row boundary explicit for any model.
    """
    text = chunk.text
    if chunk.block_type != "table" or "|" not in text or "\n" not in text:
        return text
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    rendered: list[str] = []
    for index, line in enumerate(lines):
        cells = [cell.strip() for cell in line.split("|")]
        if len(cells) < 2:
            rendered.append(line)
            continue
        if index == 0:
            rendered.append("| " + " | ".join(cells) + " |")
            rendered.append("| " + " | ".join("---" for _ in cells) + " |")
        else:
            rendered.append("| " + " | ".join(cells) + " |")
    return "\n".join(rendered)


def _conversation_block(conversation: Sequence[ChatMessage]) -> str:
    """Render bounded recent turns so follow-up questions keep their referents."""
    turns: list[str] = []
    for message in conversation:
        if message.role not in {"user", "assistant"}:
            continue
        content = " ".join(message.content.split())
        if not content:
            continue
        turns.append(f"{message.role}: {content[:_CONVERSATION_CHARS]}")
    if not turns:
        return ""
    return "Recent conversation:\n" + "\n".join(turns[-_CONVERSATION_MESSAGES:]) + "\n\n"


class QueryEmbedder(Protocol):
    async def embed_query(self, text: str) -> list[float]: ...


class VectorRetriever(Protocol):
    async def retrieve(
        self,
        *,
        tenant_id: str,
        knowledge_base_id: str,
        query_text: str,
        query_vector: Sequence[float],
        authoritative_revision: int = 0,
        document_ids: Sequence[str] | None = None,
    ) -> list[RetrievedChunk]: ...


class ChatModel(Protocol):
    async def complete(self, messages: Sequence[dict[str, object]]) -> str: ...

    def stream_with_usage(
        self, messages: Sequence[dict[str, object]], *, allow_truncated: bool = False
    ) -> AsyncGenerator[str | ModelCompletion, None]: ...


class RagChatService:
    def __init__(
        self,
        *,
        settings: GenerationConfig,
        sessions: ChatSessionStore | None = None,
        embedder: QueryEmbedder,
        retriever: VectorRetriever,
        chat_model: ChatModel,
        calculations: SpreadsheetCalculationCoordinator | None = None,
        semantic_answer_cache: SemanticAnswerCache | None = None,
        query_planner: ContextualQueryPlanner | None = None,
    ):
        self._settings = settings
        self._sessions = sessions
        self._embedder = embedder
        self._retriever = retriever
        self._chat_model = chat_model
        self._calculations = calculations
        self._semantic_answer_cache = semantic_answer_cache
        self._query_planner = query_planner

    def create_session(self, **kwargs: Any) -> ChatSession:
        if self._sessions is None:
            raise RuntimeError("Session creation is owned by the Java control plane")
        return self._sessions.create(**kwargs)

    def list_messages(self, **kwargs: Any) -> list[ChatMessage]:
        if self._sessions is None:
            raise RuntimeError("Conversation history is owned by the Java control plane")
        session = self._sessions.get_for_tenant(
            str(kwargs["session_id"]), str(kwargs["tenant_id"])
        )
        user_id = kwargs.get("user_id")
        if session is None or (
            user_id is not None
            and (session.channel != "EMPLOYEE_PLAYGROUND" or session.user_id != user_id)
        ):
            raise ChatSessionNotFoundError(str(kwargs["session_id"]))
        return self._sessions.list_messages(
            session_id=session.id,
            tenant_id=session.tenant_id,
            limit=int(kwargs.get("limit", 50)),
            after=kwargs.get("after"),
        )

    def close_session(self, **kwargs: Any) -> None:
        if self._sessions is None:
            raise RuntimeError("Session closure is owned by the Java control plane")
        session_id = str(kwargs["session_id"])
        tenant_id = str(kwargs["tenant_id"])
        session = self._sessions.get_for_tenant(session_id, tenant_id)
        user_id = kwargs.get("user_id")
        if session is None or (
            user_id is not None
            and session.channel == "EMPLOYEE_PLAYGROUND"
            and session.user_id != user_id
        ):
            raise ChatSessionNotFoundError(session_id)
        if not self._sessions.close_for_tenant(session_id, tenant_id):
            raise ChatSessionNotFoundError(session_id)

    def hide_playground_session(self, **kwargs: Any) -> None:
        if self._sessions is None or not self._sessions.hide_playground_session(**kwargs):
            raise ChatSessionNotFoundError(str(kwargs.get("session_id", "")))

    def list_playground_sessions(self, **kwargs: Any) -> list[dict[str, Any]]:
        if self._sessions is None:
            return []
        return self._sessions.list_playground_sessions(**kwargs)

    async def submit_message(
        self,
        *,
        content: str,
        session: ChatSession | None = None,
        prior_messages: Sequence[ChatMessage] = (),
        tenant_id: str | None = None,
        session_id: str | None = None,
        user_id: str | None = None,
        on_content: Callable[[str], Awaitable[None]] | None = None,
    ) -> AssistantMessage:
        if session is not None:
            return await self._generate(
                session=session,
                content=content,
                prior_messages=prior_messages,
                on_content=on_content,
            )
        if self._sessions is None or tenant_id is None or session_id is None:
            raise ChatSessionNotFoundError(session_id or "")
        stored = self._sessions.get_for_tenant(session_id, tenant_id)
        if stored is None or (
            user_id is not None
            and (stored.channel != "EMPLOYEE_PLAYGROUND" or stored.user_id != user_id)
        ):
            raise ChatSessionNotFoundError(session_id)
        history = self._sessions.list_messages(
            session_id=session_id, tenant_id=tenant_id, limit=20
        )
        self._sessions.add_user_message(session_id, content)
        message = await self._generate(
            session=stored,
            content=content,
            prior_messages=history,
            on_content=on_content,
        )
        self._sessions.add_assistant_message(session_id, message)
        return message

    async def _generate(
        self,
        *,
        session: ChatSession,
        content: str,
        prior_messages: Sequence[ChatMessage] = (),
        on_content: Callable[[str], Awaitable[None]] | None = None,
    ) -> AssistantMessage:
        tenant_id = session.tenant_id
        session_id = session.id
        total_started_at = time.perf_counter()
        outcome = "success"
        embedding_seconds = 0.0
        retrieval_seconds = 0.0
        llm_seconds = 0.0
        chunk_count = 0
        completion = ModelCompletion(content="")
        truncated_answer = False
        llm_provider = str(getattr(self._chat_model, "provider", self._settings.LLM_PROVIDER))
        llm_model = str(getattr(self._chat_model, "model", self._settings.LLM_MODEL_ID))

        try:
            sensitive_query = is_sensitive_query(content)
            if not sensitive_query and is_non_retrieval_question(content, prior_messages):
                # Chit-chat or nonsense: skip embedding, retrieval, and the LLM
                # entirely. Fixed template reply, never cached, never cited.
                return AssistantMessage(
                    role="assistant",
                    content=build_non_retrieval_reply(content, prior_messages),
                )
            cache_context: SemanticCacheContext | None = None
            shadow_candidate: SemanticCacheCandidate | None = None
            semantic_cache = self._semantic_answer_cache
            if (
                not sensitive_query
                and semantic_cache is not None
                and semantic_cache.accepts_query(content)
            ):
                cache_context = await semantic_cache.prepare_context(
                    session=session,
                    query=content,
                    prior_history=[],
                    visible_document_ids=None,
                )

            if cache_context is not None and semantic_cache is not None:
                exact_candidate = await semantic_cache.lookup_exact(cache_context)
                if semantic_cache.mode == "serve" and exact_candidate is not None:
                    semantic_cache.record_served(exact_candidate)
                    return exact_candidate.message
                if semantic_cache.mode == "shadow":
                    shadow_candidate = exact_candidate

            embedding_started_at = time.perf_counter()
            embedding_outcome = "success"
            try:
                query_vector = await self._embedder.embed_query(content)
            except Exception:
                embedding_outcome = "error"
                outcome = "error"
                raise
            finally:
                embedding_seconds = time.perf_counter() - embedding_started_at
                AI_RAG_ANSWER_SECONDS.labels(
                    stage="embedding",
                    provider=llm_provider,
                    outcome=embedding_outcome,
                ).observe(embedding_seconds)

            if cache_context is not None and semantic_cache is not None:
                semantic_candidate = await semantic_cache.lookup_semantic(
                    cache_context, query_vector
                )
                if semantic_cache.mode == "serve" and semantic_candidate is not None:
                    semantic_cache.record_served(semantic_candidate)
                    return semantic_candidate.message
                if semantic_cache.mode == "shadow" and shadow_candidate is None:
                    shadow_candidate = semantic_candidate

            retrieval_query = content
            if self._query_planner is not None:
                plan = await self._query_planner.plan(
                    question=content, prior_messages=prior_messages
                )
                if plan.replaced:
                    retrieval_query = plan.query
                    embedding_started_at = time.perf_counter()
                    embedding_outcome = "success"
                    try:
                        query_vector = await self._embedder.embed_query(retrieval_query)
                    except Exception:
                        embedding_outcome = "error"
                        outcome = "error"
                        raise
                    finally:
                        embedding_seconds += time.perf_counter() - embedding_started_at
                        AI_RAG_ANSWER_SECONDS.labels(
                            stage="embedding",
                            provider=llm_provider,
                            outcome=embedding_outcome,
                        ).observe(embedding_seconds)

            retrieval_started_at = time.perf_counter()
            retrieval_outcome = "success"
            try:
                chunks = await self._retrieve(
                    tenant_id=tenant_id,
                    knowledge_base_id=session.knowledge_base_id,
                    query_text=retrieval_query,
                    query_vector=query_vector,
                    authoritative_revision=session.authoritative_revision,
                )
            except Exception:
                retrieval_outcome = "error"
                outcome = "error"
                raise
            finally:
                retrieval_seconds = time.perf_counter() - retrieval_started_at
                AI_RAG_ANSWER_SECONDS.labels(
                    stage="retrieval",
                    provider=llm_provider,
                    outcome=retrieval_outcome,
                ).observe(retrieval_seconds)

            selected = chunks[: self._settings.FINAL_CONTEXT_TOP_K]
            chunk_count = len(selected)
            if sensitive_query and not selected:
                return AssistantMessage(role="assistant", content=NO_INFORMATION_RESPONSE)
            calculation_text: str | None = None
            calculation_used = False
            if self._calculations is not None and not sensitive_query:
                calculation = await self._calculations.prepare(
                    tenant_id=tenant_id,
                    knowledge_base_id=session.knowledge_base_id,
                    question=content,
                    chunks=chunks,
                )
                if calculation is not None:
                    calculation_used = True
                    if calculation.clarification:
                        message = AssistantMessage(
                            role="assistant", content=calculation.clarification
                        )
                        return message
                    calculation_text = calculation.text
            if not selected:
                message = AssistantMessage(role="assistant", content=NO_INFORMATION_RESPONSE)
                return message

            citations = self._citations(selected)
            sensitive_instruction = (
                "This is a sensitive legal, government, compliance, health, or financial "
                "question. Do not give personalized legal, medical, or financial advice. "
                "Answer only with supplied tenant evidence, cite each factual claim using "
                "the provided source IDs, and say you lack information if evidence is "
                "insufficient. Never invent citations. "
                if sensitive_query
                else ""
            )
            llm_started_at = time.perf_counter()
            llm_outcome = "success"
            try:
                completion = await self._complete_with_usage(
                    self._prompt_messages(
                        question=content,
                        chunks=selected,
                        citations=citations,
                        calculation_context=calculation_text,
                        sensitive_instruction=sensitive_instruction,
                        conversation=prior_messages,
                    ),
                    allow_truncated=True,
                    on_content=on_content,
                    withhold_preview=sensitive_query,
                )
                raw_answer = completion.content.strip()
                truncated_answer = completion.truncated
            except (ModelTimeoutError, ChatModelTimeoutError):
                llm_outcome = "timeout"
                outcome = "timeout"
                raise
            except Exception:
                llm_outcome = "error"
                outcome = "error"
                raise
            finally:
                llm_seconds = time.perf_counter() - llm_started_at
                AI_RAG_ANSWER_SECONDS.labels(
                    stage="llm",
                    provider=llm_provider,
                    outcome=llm_outcome,
                ).observe(llm_seconds)

            answer = polish_answer(raw_answer)
            if on_content is not None and not answer:
                raise ModelUnavailableError("Model provider returned an empty answer")
            if truncated_answer and sensitive_query:
                # A cut-off enumeration must not be presented as complete, and a
                # partial sensitive answer cannot be re-grounded safely.
                raise ChatModelTimeoutError("Model generation truncated")
            if sensitive_query and not has_authorized_citation(
                answer, {citation.id for citation in citations}
            ):
                return AssistantMessage(
                    role="assistant",
                    content=NO_INFORMATION_RESPONSE,
                    token_usage=TokenUsage(
                        input_tokens=completion.input_tokens,
                        output_tokens=completion.output_tokens,
                    ),
                )
            message = AssistantMessage(
                role="assistant",
                content=answer,
                citations=self._cited_citations(answer, citations),
                token_usage=TokenUsage(
                    input_tokens=completion.input_tokens,
                    output_tokens=completion.output_tokens,
                ),
            )
            if (
                cache_context is not None
                and semantic_cache is not None
                and not calculation_used
                and semantic_cache.is_response_eligible(message)
            ):
                if shadow_candidate is not None:
                    await semantic_cache.compare_shadow(
                        candidate=shadow_candidate,
                        fresh=message,
                        embedder=self._embedder,
                    )
                await semantic_cache.write(
                    context=cache_context,
                    query_vector=query_vector,
                    message=message,
                    completion=completion,
                )
            return message
        except (ModelTimeoutError, ChatModelTimeoutError):
            outcome = "timeout"
            raise
        except Exception:
            if outcome == "success":
                outcome = "error"
            raise
        finally:
            total_seconds = time.perf_counter() - total_started_at
            AI_RAG_ANSWER_SECONDS.labels(
                stage="total",
                provider=llm_provider,
                outcome=outcome,
            ).observe(total_seconds)
            logger.info(
                "rag_chat_request tenant_id=%s session_id=%s embedding_ms=%.2f "
                "retrieval_ms=%.2f llm_ms=%.2f total_ms=%.2f llm_provider=%s "
                "llm_model=%s chunk_count=%s outcome=%s",
                tenant_id,
                session_id,
                embedding_seconds * 1000,
                retrieval_seconds * 1000,
                llm_seconds * 1000,
                total_seconds * 1000,
                llm_provider,
                llm_model,
                chunk_count,
                outcome,
                extra={
                    "tenant_id": tenant_id,
                    "session_id": session_id,
                    "embedding_ms": embedding_seconds * 1000,
                    "retrieval_ms": retrieval_seconds * 1000,
                    "llm_ms": llm_seconds * 1000,
                    "total_ms": total_seconds * 1000,
                    "llm_provider": llm_provider,
                    "llm_model": llm_model,
                    "chunk_count": chunk_count,
                    "outcome": outcome,
                },
            )

    async def _complete_with_usage(
        self,
        messages: Sequence[dict[str, object]],
        *,
        allow_truncated: bool = False,
        on_content: Callable[[str], Awaitable[None]] | None = None,
        withhold_preview: bool = False,
    ) -> ModelCompletion:
        if on_content is not None:
            stream = self._chat_model.stream_with_usage(messages, allow_truncated=allow_truncated)
            draft = ""
            preview = ""
            completion: ModelCompletion | None = None
            try:
                async for event in stream:
                    if isinstance(event, ModelCompletion):
                        if completion is not None:
                            raise TypeError("Model stream returned multiple completions")
                        completion = event
                    elif isinstance(event, str) and completion is None:
                        draft += event
                        polished = polish_preview(draft)
                        if not withhold_preview and polished and polished != preview:
                            preview = polished
                            await on_content(preview)
                    else:
                        raise TypeError("Model stream returned an invalid event")
            finally:
                await stream.aclose()
            if completion is None or not completion.content.strip():
                raise TypeError("Model stream ended without a completion")
            return completion
        method = getattr(self._chat_model, "complete_with_usage", None)
        if callable(method):
            try:
                result = await method(messages, allow_truncated=allow_truncated)
            except TypeError as exc:
                if "allow_truncated" not in str(exc):
                    raise
                result = await method(messages)
            if isinstance(result, ModelCompletion):
                return result
            raise TypeError("complete_with_usage must return ModelCompletion")
        content = await self._chat_model.complete(messages)
        return ModelCompletion(content=content)

    async def _retrieve(self, **kwargs: Any) -> list[RetrievedChunk]:
        try:
            return await self._retriever.retrieve(**kwargs)
        except TypeError as exc:
            if "authoritative_revision" not in str(exc):
                raise
            kwargs.pop("authoritative_revision", None)
            return await self._retriever.retrieve(**kwargs)

    def _prompt_messages(
        self,
        *,
        question: str,
        chunks: list[RetrievedChunk],
        citations: list[Citation],
        calculation_context: str | None = None,
        sensitive_instruction: str = "",
        conversation: Sequence[ChatMessage] = (),
    ) -> list[dict[str, object]]:
        sources = "\n\n".join(
            f"[{citation.id}] {chunk.source_name}"
            f"{', page ' + str(chunk.page_number) if chunk.page_number is not None else ''}, "
            f"chunk {chunk.chunk_index}\n{_render_chunk_text(chunk)}"
            for chunk, citation in zip(chunks, citations, strict=True)
        )
        transcript = _conversation_block(conversation)
        return [
            {
                "role": "system",
                "content": f"{_SYSTEM_PROMPT}{sensitive_instruction}",
            },
            {
                "role": "user",
                "content": (
                    f"{transcript}"
                    f"Sources:\n{sources}\n\n"
                    f"{calculation_context + chr(10) + chr(10) if calculation_context else ''}"
                    f"Question:\n{question}"
                ),
            },
        ]

    def _cited_citations(
        self, answer: str, citations: list[Citation]
    ) -> list[Citation]:
        """Keep the sources the answer actually cites, in offered order.

        Uncited answers keep every offered source: the panel is labelled as the
        retrieved evidence, and dropping it would hide the evidence used.
        """
        mentioned = {f"S{number}".upper() for number in _CITATION.findall(answer)}
        cited = [citation for citation in citations if citation.id.upper() in mentioned]
        return cited or citations

    def _citations(self, chunks: list[RetrievedChunk]) -> list[Citation]:
        return [
            Citation(
                id=f"S{index}",
                document_id=chunk.document_id,
                source_name=chunk.source_name,
                page_number=chunk.page_number,
                chunk_index=chunk.chunk_index,
                score=chunk.score,
                snippet=self._snippet(chunk.text),
                unit_id=chunk.unit_id,
                modality=chunk.modality,
                section_path=chunk.section_path,
                block_type=chunk.block_type,
                sheet_name=chunk.sheet_name,
                cell_range=chunk.cell_range,
                table_id=chunk.table_id,
            )
            for index, chunk in enumerate(chunks, start=1)
        ]

    def _snippet(self, text: str) -> str:
        normalized = " ".join(text.split())
        if len(normalized) <= 220:
            return normalized
        cut = normalized[:217].rsplit(" ", 1)[0].rstrip(",.;:")
        return f"{cut}..."
