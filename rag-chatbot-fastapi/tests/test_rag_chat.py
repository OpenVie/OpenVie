from __future__ import annotations

from collections.abc import AsyncIterator, Sequence
from types import SimpleNamespace
from typing import Any

import pytest
from prometheus_client import REGISTRY

from app.bootstrap.settings import Settings
from app.modules.generation.internal.errors import (
    ChatModelTimeoutError,
    ChatSessionNotFoundError,
    ChatSessionStoreUnavailableError,
)
from app.modules.generation.internal.models import (
    AssistantMessage,
    ChatMessage,
    ChatSession,
    RetrievedChunk,
)
from app.modules.generation.internal.query_plan import (
    MAX_QUERY_CHARS,
    ContextualQueryPlanner,
    _clean_query,
)
from app.modules.generation.internal.sensitive_policy import (
    has_authorized_citation,
    is_sensitive_query,
)
from app.modules.generation.internal.service import NO_INFORMATION_RESPONSE, RagChatService
from app.modules.generation.internal.sessions import InMemoryChatSessionStore
from app.modules.index.api import KnowledgeIndexQuery
from app.modules.index.internal.qdrant_search import QdrantKnowledgeIndexQuery
from app.modules.model.api import ModelCompletion, ModelUnavailableError
from app.modules.model.internal.embedding import OllamaEmbeddingClient


def metric_value(name: str, labels: dict[str, str]) -> float:
    return REGISTRY.get_sample_value(name, labels) or 0.0


class FakeOllamaResponse:
    def __init__(self, payload: dict[str, object]):
        self._payload = payload

    def raise_for_status(self) -> None:
        return None

    def json(self) -> dict[str, object]:
        return self._payload


class FakeOllamaClient:
    payload: dict[str, object]

    def __init__(self, timeout: int):
        del timeout

    async def __aenter__(self) -> FakeOllamaClient:
        return self

    async def __aexit__(self, *args: object) -> None:
        return None

    async def post(self, url: str, json: dict[str, object]) -> FakeOllamaResponse:
        self.last_url = url
        self.last_json = json
        return FakeOllamaResponse(self.payload)


@pytest.mark.asyncio
async def test_embedding_adapter_embeds_queries(monkeypatch: pytest.MonkeyPatch) -> None:
    FakeOllamaClient.payload = {"embeddings": [[1, 2, 3]]}
    monkeypatch.setattr("app.modules.model.internal.embedding.httpx.AsyncClient", FakeOllamaClient)
    embedder = OllamaEmbeddingClient(Settings(TEXT_EMBEDDING_DIMENSION=3))
    labels = {"operation": "query", "provider": "ollama", "outcome": "success"}
    before = metric_value("cacanode_ai_embedding_seconds_count", labels)

    assert await embedder.embed_query("hello") == [1.0, 2.0, 3.0]
    assert metric_value("cacanode_ai_embedding_seconds_count", labels) == before + 1


@pytest.mark.asyncio
async def test_embedding_adapter_reports_query_model_errors(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    FakeOllamaClient.payload = {"error": "model not found"}
    monkeypatch.setattr("app.modules.model.internal.embedding.httpx.AsyncClient", FakeOllamaClient)
    embedder = OllamaEmbeddingClient(Settings(TEXT_EMBEDDING_DIMENSION=3))

    with pytest.raises(ModelUnavailableError, match="model not found"):
        await embedder.embed_query("hello")


class FakeQdrantClient:
    def __init__(self) -> None:
        self.kwargs: dict[str, Any] = {}

    async def query_points(self, **kwargs: Any) -> object:
        self.kwargs = kwargs
        return SimpleNamespace(
            points=[
                SimpleNamespace(
                    score=0.88,
                    payload={
                        "document_id": "doc-1",
                        "source_name": "policy.txt",
                        "page_number": 1,
                        "chunk_index": 2,
                        "text": "Policy source text.",
                    },
                )
            ]
        )


@pytest.mark.asyncio
async def test_qdrant_retriever_filters_by_tenant_and_knowledge_base() -> None:
    client = FakeQdrantClient()
    retriever = QdrantKnowledgeIndexQuery(
        Settings(
            QDRANT_COLLECTION="chunks",
            QDRANT_TENANT_FIELD="tenant_id",
            QDRANT_KNOWLEDGE_BASE_FIELD="knowledge_base_id",
        ),
        client=client,  # type: ignore[arg-type]
    )
    chunks = await retriever.search_dense(
        KnowledgeIndexQuery(
            tenant_id="tenant-1",
            knowledge_base_id="kb-1",
            query_vector=(0.1, 0.2, 0.3),
            limit=5,
        )
    )

    assert len(chunks) == 1
    assert chunks[0].document_id == "doc-1"
    query_filter = client.kwargs["query_filter"]
    conditions = {condition.key: condition.match.value for condition in query_filter.must}
    assert conditions == {"tenant_id": "tenant-1", "knowledge_base_id": "kb-1"}
    assert client.kwargs["collection_name"] == "chunks"
    assert client.kwargs["using"] == "text_bge_m3_v1"
    assert "score_threshold" not in client.kwargs


@pytest.mark.asyncio
async def test_qdrant_retriever_can_filter_to_allowed_document_ids() -> None:
    client = FakeQdrantClient()
    retriever = QdrantKnowledgeIndexQuery(Settings(), client=client)  # type: ignore[arg-type]

    await retriever.search_dense(
        KnowledgeIndexQuery(
            tenant_id="tenant-1",
            knowledge_base_id="kb-1",
            query_vector=(0.1,),
            limit=5,
            document_ids=("doc-1", "doc-2"),
        )
    )

    document_condition = next(
        condition
        for condition in client.kwargs["query_filter"].must
        if condition.key == "document_id"
    )
    assert document_condition.match.any == ["doc-1", "doc-2"]


class FakeEmbedder:
    async def embed_query(self, text: str) -> list[float]:
        self.text = text
        return [0.1, 0.2, 0.3]


class FakeRetriever:
    def __init__(self, chunks: list[RetrievedChunk]):
        self.chunks = chunks
        self.calls: list[dict[str, object]] = []

    async def retrieve(
        self,
        *,
        tenant_id: str,
        knowledge_base_id: str,
        query_text: str,
        query_vector: Sequence[float],
        document_ids: Sequence[str] | None = None,
    ) -> list[RetrievedChunk]:
        self.calls.append(
            {
                "tenant_id": tenant_id,
                "knowledge_base_id": knowledge_base_id,
                "query_text": query_text,
                "query_vector": list(query_vector),
                "document_ids": list(document_ids) if document_ids is not None else None,
            }
        )
        return self.chunks


class FakeChatModel:
    provider = "test-provider"
    model = "test-model"

    def __init__(self) -> None:
        self.calls: list[Sequence[dict[str, object]]] = []

    async def complete(self, messages: Sequence[dict[str, object]]) -> str:
        self.calls.append(messages)
        return "Sản phẩm được đổi trong 7 ngày [S1]."


class ScriptedChatModel(FakeChatModel):
    """Returns one queued answer per call, in order."""

    def __init__(self, answers: list[str]) -> None:
        super().__init__()
        self._answers = list(answers)

    async def complete(self, messages: Sequence[dict[str, object]]) -> str:
        self.calls.append(messages)
        if not self._answers:
            raise AssertionError("ScriptedChatModel ran out of queued answers")
        return self._answers.pop(0)


class PlannerTimeoutChatModel(FakeChatModel):
    """Times out only on the query-plan call; answers the real prompt normally."""

    async def complete(self, messages: Sequence[dict[str, object]]) -> str:
        self.calls.append(messages)
        if "standalone search query" in str(messages[0]["content"]):
            raise ChatModelTimeoutError("Model generation timed out")
        return "Sản phẩm được đổi trong 7 ngày [S1]."


class TimeoutChatModel(FakeChatModel):
    provider = "timeout-provider"
    model = "timeout-model"

    async def complete(self, messages: Sequence[dict[str, object]]) -> str:
        self.calls.append(messages)
        raise ChatModelTimeoutError("Model generation timed out")


class UnavailableSessionStore(InMemoryChatSessionStore):
    def get_for_tenant(self, session_id: str, tenant_id: str) -> ChatSession | None:
        del session_id, tenant_id
        raise ChatSessionStoreUnavailableError("Postgres is unavailable")


def make_service(
    *,
    chunks: list[RetrievedChunk],
    settings: Settings | None = None,
    model: FakeChatModel | None = None,
) -> tuple[RagChatService, InMemoryChatSessionStore, FakeRetriever, FakeChatModel]:
    retriever = FakeRetriever(chunks)
    model = model or FakeChatModel()
    service = RagChatService(
        settings=settings or Settings(FINAL_CONTEXT_TOP_K=5),
        sessions=InMemoryChatSessionStore(),
        embedder=FakeEmbedder(),
        retriever=retriever,
        chat_model=model,
        query_planner=ContextualQueryPlanner(model),
    )
    return service, service._sessions, retriever, model


class UnexpectedSemanticCache:
    mode = "serve"

    def accepts_query(self, query: str) -> bool:
        raise AssertionError(f"Sensitive query reached semantic cache: {query}")


@pytest.mark.asyncio
async def test_chat_service_returns_no_information_without_evidence() -> None:
    service, store, retriever, model = make_service(chunks=[])
    session = service.create_session(
        tenant_id="tenant-1",
        user_id="user-1",
        chatbot_id="bot-1",
        knowledge_base_id="kb-1",
        locale="vi-VN",
    )

    message = await service.submit_message(
        tenant_id="tenant-1",
        session_id=session.id,
        content="Khong co trong tai lieu?",
    )

    assert message.content == NO_INFORMATION_RESPONSE
    assert message.citations == []
    assert model.calls == []
    assert retriever.calls[0]["knowledge_base_id"] == "kb-1"
    assert retriever.calls[0]["query_text"] == "Khong co trong tai lieu?"
    assert store.get_for_tenant(session.id, "tenant-1") is not None

@pytest.mark.asyncio
async def test_nonsense_input_skips_retrieval_and_has_no_citations() -> None:
    service, store, retriever, model = make_service(chunks=[
        RetrievedChunk(
            document_id="doc-1",
            source_name="policy.txt",
            page_number=1,
            chunk_index=0,
            text="Policy source text.",
            score=0.9,
        )
    ])
    session = service.create_session(
        tenant_id="tenant-1",
        user_id="user-1",
        chatbot_id="bot-1",
        knowledge_base_id="kb-1",
        locale="en",
    )

    message = await service.submit_message(
        tenant_id="tenant-1",
        session_id=session.id,
        content="hi abcd",
    )

    assert "document" in message.content.lower()
    assert message.citations == []
    assert retriever.calls == []
    assert model.calls == []
    assert store.get_for_tenant(session.id, "tenant-1") is not None


@pytest.mark.asyncio
async def test_chit_chat_with_history_points_back_to_topic() -> None:
    service, store, _, _ = make_service(chunks=[])
    session = service.create_session(
        tenant_id="tenant-1",
        user_id="user-1",
        chatbot_id="bot-1",
        knowledge_base_id="kb-1",
        locale="vi-VN",
    )
    store.add_user_message(session.id, "Chính sách đổi trả thế nào?")
    store.add_assistant_message(
        session.id, AssistantMessage(role="assistant", content="Đổi trong 7 ngày.")
    )

    message = await service.submit_message(
        tenant_id="tenant-1",
        session_id=session.id,
        content="hi",
    )

    assert "Chính sách đổi trả thế nào?" in message.content
    assert message.citations == []


@pytest.mark.asyncio
async def test_real_question_still_retrieves_despite_greeting_word() -> None:
    service, _, retriever, _ = make_service(chunks=[])
    session = service.create_session(
        tenant_id="tenant-1",
        user_id="user-1",
        chatbot_id="bot-1",
        knowledge_base_id="kb-1",
        locale="en",
    )

    await service.submit_message(
        tenant_id="tenant-1",
        session_id=session.id,
        content="Hi, what is the return policy?",
    )

    assert len(retriever.calls) == 1

@pytest.mark.asyncio
async def test_employee_prompt_is_grounded_and_tenant_prompt_free() -> None:
    model = FakeChatModel()
    service, _, _, _ = make_service(
        chunks=[
            RetrievedChunk(
                document_id="doc-1",
                source_name="policy.txt",
                page_number=1,
                chunk_index=0,
                text="Policy source text.",
                score=0.9,
            )
        ],
        model=model,
    )
    session = service.create_session(
        tenant_id="tenant-1",
        user_id="user-1",
        chatbot_id="bot-1",
        knowledge_base_id="kb-1",
        locale="en",
    )

    await service.submit_message(
        tenant_id="tenant-1",
        session_id=session.id,
        content="What is the policy?",
        user_id="user-1",
    )

    system_prompt = str(model.calls[0][0]["content"])
    assert "internal assistant" in system_prompt
    assert "You answer only from the supplied sources." in system_prompt
    assert "Tenant-specific customer answer instructions" not in system_prompt


@pytest.mark.asyncio
async def test_follow_up_question_is_rewritten_for_retrieval() -> None:
    service, store, retriever, _ = make_service(
        chunks=[
            RetrievedChunk(
                document_id="doc-1",
                source_name="policy.txt",
                page_number=1,
                chunk_index=0,
                text="Lớp 1 và Lớp 2.",
                score=0.9,
            )
        ],
        model=ScriptedChatModel(["Các lớp công nghệ ở đây là gì?", "Câu trả lời [S1]."]),
    )
    session = service.create_session(
        tenant_id="tenant-1", user_id="user-1", chatbot_id="bot-1",
        knowledge_base_id="kb-1", locale="vi-VN", channel="EMPLOYEE_PLAYGROUND",
    )
    store.add_user_message(session.id, "Các lớp công nghệ ở đây là gì?")
    store.add_assistant_message(
        session.id, AssistantMessage(role="assistant", content="Lớp 1 và Lớp 2.")
    )

    answer = await service.submit_message(
        tenant_id="tenant-1", session_id=session.id, user_id="user-1",
        content="Bạn có thể cụ thể rõ ràng hơn được không?",
    )

    assert retriever.calls[0]["query_text"] == "Các lớp công nghệ ở đây là gì?"
    assert answer.content == "Câu trả lời [S1]."
    assert answer.citations


@pytest.mark.asyncio
async def test_first_question_is_not_rewritten() -> None:
    service, _, retriever, model = make_service(
        chunks=[
            RetrievedChunk(
                document_id="doc-1",
                source_name="policy.txt",
                page_number=1,
                chunk_index=0,
                text="Sản phẩm được đổi trong 7 ngày.",
                score=0.9,
            )
        ],
        model=ScriptedChatModel(["KHONG-DUOC-GOI", "Sản phẩm được đổi trong 7 ngày [S1]."]),
    )
    session = service.create_session(
        tenant_id="tenant-1", user_id="user-1", chatbot_id="bot-1",
        knowledge_base_id="kb-1", locale="vi-VN", channel="EMPLOYEE_PLAYGROUND",
    )

    await service.submit_message(
        tenant_id="tenant-1", session_id=session.id, user_id="user-1",
        content="Chinh sach doi tra?",
    )

    assert retriever.calls[0]["query_text"] == "Chinh sach doi tra?"
    assert len(model.calls) == 1


@pytest.mark.asyncio
async def test_query_planner_timeout_falls_back_to_original_question() -> None:
    service, store, retriever, _ = make_service(
        chunks=[
            RetrievedChunk(
                document_id="doc-1",
                source_name="policy.txt",
                page_number=1,
                chunk_index=0,
                text="Sản phẩm được đổi trong 7 ngày.",
                score=0.9,
            )
        ],
        model=PlannerTimeoutChatModel(),
    )
    session = service.create_session(
        tenant_id="tenant-1", user_id="user-1", chatbot_id="bot-1",
        knowledge_base_id="kb-1", locale="vi-VN", channel="EMPLOYEE_PLAYGROUND",
    )
    store.add_user_message(session.id, "Chinh sach doi tra?")

    answer = await service.submit_message(
        tenant_id="tenant-1", session_id=session.id, user_id="user-1",
        content="Cụ thể hơn?",
    )

    assert retriever.calls[0]["query_text"] == "Cụ thể hơn?"
    assert answer.content == "Sản phẩm được đổi trong 7 ngày [S1]."


@pytest.mark.asyncio
async def test_answer_citations_are_limited_to_cited_sources() -> None:
    service, _, _, _ = make_service(
        chunks=[
            sensitive_chunk("doc-1"),
            sensitive_chunk("doc-2"),
            sensitive_chunk("doc-3"),
        ],
        model=FixedAnswerChatModel("Chỉ nguồn thứ hai nói điều này [S2]."),
    )
    session = service.create_session(
        tenant_id="tenant-1", user_id="user-1", chatbot_id="bot-1",
        knowledge_base_id="kb-1", locale="en", channel="EMPLOYEE_PLAYGROUND",
    )

    answer = await service.submit_message(
        tenant_id="tenant-1", session_id=session.id, user_id="user-1",
        content="What are the opening hours?",
    )

    assert [citation.id for citation in answer.citations] == ["S2"]
    assert [citation.document_id for citation in answer.citations] == ["doc-2"]


@pytest.mark.asyncio
async def test_grounded_prompt_preserves_enumerated_items() -> None:
    service, _, _, model = make_service(
        chunks=[
            RetrievedChunk(
                document_id="doc-1",
                source_name="policy.txt",
                page_number=1,
                chunk_index=0,
                text="Lớp 1 đến Lớp 7.",
                score=0.9,
            )
        ]
    )
    session = service.create_session(
        tenant_id="tenant-1", user_id="user-1", chatbot_id="bot-1",
        knowledge_base_id="kb-1", locale="vi-VN", channel="EMPLOYEE_PLAYGROUND",
    )

    await service.submit_message(
        tenant_id="tenant-1", session_id=session.id, user_id="user-1",
        content="Các lớp công nghệ ở đây là gì?",
    )

    system_prompt = str(model.calls[0][0]["content"])
    assert "reproduces every item" in system_prompt
    assert "never drop, renumber, merge, or abbreviate" in system_prompt
    assert "three short sentences" not in system_prompt


def test_query_plan_cleans_model_output() -> None:
    assert _clean_query("```\nLớp công nghệ\n```") == "Lớp công nghệ"
    assert _clean_query("Truy vấn: Lớp công nghệ") == "Lớp công nghệ"
    assert _clean_query('"Lớp công nghệ"') == "Lớp công nghệ"
    assert _clean_query("Lớp công nghệ\n(lý do: ngắn)") == "Lớp công nghệ"
    assert _clean_query("   ") == ""
    assert _clean_query("x" * (MAX_QUERY_CHARS + 1)) == ""


class FakePlannerModel:
    async def complete(self, messages: Sequence[dict[str, object]]) -> str:
        del messages
        return "Lớp công nghệ"


@pytest.mark.asyncio
async def test_query_planner_skips_first_turn_and_reuses_standalone_question() -> None:
    planner = ContextualQueryPlanner(FakePlannerModel())
    first = await planner.plan(question="Hạn gửi bài là gì?", prior_messages=())
    assert first.query == "Hạn gửi bài là gì?"
    assert first.replaced is False
    assert first.outcome == "skipped"

    same = await planner.plan(
        question="Lớp công nghệ",
        prior_messages=[ChatMessage(role="user", content="Hỏi trước đó")],
    )
    assert same.query == "Lớp công nghệ"
    assert same.replaced is False
    assert same.outcome == "unchanged"


@pytest.mark.asyncio
async def test_unavailable_session_storage_does_not_call_model() -> None:
    model = FakeChatModel()
    service = RagChatService(
        settings=Settings(FINAL_CONTEXT_TOP_K=5),
        sessions=UnavailableSessionStore(),
        embedder=FakeEmbedder(),
        retriever=FakeRetriever([]),
        chat_model=model,
    )

    with pytest.raises(ChatSessionStoreUnavailableError):
        await service.submit_message(
            tenant_id="tenant-1",
            session_id="session-1",
            content="Question",
        )

    assert model.calls == []


def test_employee_cannot_close_another_tenants_or_employees_playground() -> None:
    service, store, _, _ = make_service(chunks=[])
    playground = service.create_session(
        tenant_id="tenant-1",
        user_id="employee-1",
        chatbot_id="bot-1",
        knowledge_base_id="kb-1",
        locale="en",
    )

    with pytest.raises(ChatSessionNotFoundError):
        service.close_session(tenant_id="tenant-2", session_id=playground.id, user_id="employee-2")
    with pytest.raises(ChatSessionNotFoundError):
        service.close_session(tenant_id="tenant-1", session_id=playground.id, user_id="employee-2")

    assert store.get_for_tenant(playground.id, "tenant-1") is not None


@pytest.mark.asyncio
async def test_chat_service_generates_grounded_answer_with_citations() -> None:
    service, _, retriever, model = make_service(
        chunks=[
            RetrievedChunk(
                document_id="doc-1",
                source_name="policy.txt",
                page_number=1,
                chunk_index=0,
                text="Sản phẩm được đổi trong 7 ngày.",
                score=0.91,
            )
        ]
    )
    session = service.create_session(
        tenant_id="tenant-1",
        user_id="user-1",
        chatbot_id="bot-1",
        knowledge_base_id="kb-1",
        locale="vi-VN",
    )

    message = await service.submit_message(
        tenant_id="tenant-1",
        session_id=session.id,
        content="Chinh sach doi tra?",
    )

    assert message.content == "Sản phẩm được đổi trong 7 ngày [S1]."
    assert message.citations[0].id == "S1"
    assert message.citations[0].document_id == "doc-1"
    assert message.citations[0].snippet == "Sản phẩm được đổi trong 7 ngày."
    assert retriever.calls[0]["tenant_id"] == "tenant-1"
    assert "Sản phẩm được đổi trong 7 ngày." in str(model.calls[0][1]["content"])


@pytest.mark.asyncio
async def test_chat_service_records_rag_timing_metrics_on_success() -> None:
    labels = {"provider": "test-provider", "outcome": "success"}
    before = {
        stage: metric_value(
            "cacanode_ai_rag_answer_seconds_count",
            {"stage": stage, **labels},
        )
        for stage in ("embedding", "retrieval", "llm", "total")
    }
    service, _, _, _ = make_service(
        chunks=[
            RetrievedChunk(
                document_id="doc-1",
                source_name="policy.txt",
                page_number=1,
                chunk_index=0,
                text="Sản phẩm được đổi trong 7 ngày.",
                score=0.91,
            )
        ]
    )
    session = service.create_session(
        tenant_id="tenant-1",
        user_id="user-1",
        chatbot_id="bot-1",
        knowledge_base_id="kb-1",
        locale="vi-VN",
    )

    await service.submit_message(
        tenant_id="tenant-1",
        session_id=session.id,
        content="Chinh sach doi tra?",
    )

    for stage, count in before.items():
        assert (
            metric_value(
                "cacanode_ai_rag_answer_seconds_count",
                {"stage": stage, **labels},
            )
            == count + 1
        )


@pytest.mark.asyncio
async def test_chat_service_records_rag_timeout_metrics() -> None:
    labels = {"provider": "timeout-provider", "outcome": "timeout"}
    before_total = metric_value(
        "cacanode_ai_rag_answer_seconds_count",
        {"stage": "total", **labels},
    )
    before_llm = metric_value(
        "cacanode_ai_rag_answer_seconds_count",
        {"stage": "llm", **labels},
    )
    service, _, _, _ = make_service(
        chunks=[
            RetrievedChunk(
                document_id="doc-1",
                source_name="policy.txt",
                page_number=1,
                chunk_index=0,
                text="Sản phẩm được đổi trong 7 ngày.",
                score=0.91,
            )
        ],
        model=TimeoutChatModel(),
    )
    session = service.create_session(
        tenant_id="tenant-1",
        user_id="user-1",
        chatbot_id="bot-1",
        knowledge_base_id="kb-1",
        locale="vi-VN",
    )

    with pytest.raises(ChatModelTimeoutError):
        await service.submit_message(
            tenant_id="tenant-1",
            session_id=session.id,
            content="Chinh sach doi tra?",
        )

    assert (
        metric_value("cacanode_ai_rag_answer_seconds_count", {"stage": "total", **labels})
        == before_total + 1
    )
    assert (
        metric_value("cacanode_ai_rag_answer_seconds_count", {"stage": "llm", **labels})
        == before_llm + 1
    )


@pytest.mark.asyncio
async def test_chat_service_enforces_session_tenant_isolation() -> None:
    service, _, _, _ = make_service(chunks=[])
    session = service.create_session(
        tenant_id="tenant-1",
        user_id="user-1",
        chatbot_id="bot-1",
        knowledge_base_id="kb-1",
        locale="vi-VN",
    )

    with pytest.raises(ChatSessionNotFoundError):
        await service.submit_message(
            tenant_id="tenant-2",
            session_id=session.id,
            content="Can I read this?",
        )


@pytest.mark.asyncio
async def test_employee_cannot_read_or_continue_another_employees_session() -> None:
    service, _, _, _ = make_service(chunks=[])
    session = service.create_session(
        tenant_id="tenant-1",
        user_id="user-1",
        chatbot_id="bot-1",
        knowledge_base_id="kb-1",
        locale="en",
    )

    with pytest.raises(ChatSessionNotFoundError):
        service.list_messages(tenant_id="tenant-1", session_id=session.id, user_id="user-2")
    with pytest.raises(ChatSessionNotFoundError):
        await service.submit_message(
            tenant_id="tenant-1",
            session_id=session.id,
            content="Not my chat",
            user_id="user-2",
        )


def test_hiding_employee_session_preserves_messages_but_prevents_reopening() -> None:
    service, store, _, _ = make_service(chunks=[])
    session = service.create_session(
        tenant_id="tenant-1",
        user_id="user-1",
        chatbot_id="bot-1",
        knowledge_base_id="kb-1",
        locale="en",
    )
    store.add_user_message(session.id, "Keep this for history")

    service.hide_playground_session(tenant_id="tenant-1", user_id="user-1", session_id=session.id)

    assert len(store._sessions[session.id].messages) == 1
    assert store.get_for_tenant(session.id, "tenant-1") is None
    assert (
        service.list_playground_sessions(tenant_id="tenant-1", user_id="user-1", limit=50, offset=0)
        == []
    )


class FixedAnswerChatModel(FakeChatModel):
    def __init__(self, answer: str):
        super().__init__()
        self.answer = answer

    async def complete(self, messages: Sequence[dict[str, object]]) -> str:
        self.calls.append(messages)
        return self.answer


def test_sensitive_policy_is_bounded_and_checks_only_source_ids() -> None:
    for query in (
        "Tôi có phải nộp thuế không?",
        "Hợp đồng này có hợp pháp không?",
        "What medication should I take?",
        "Do government regulations require this?",
        "What interest rate applies to this loan?",
    ):
        assert is_sensitive_query(query)
    for query in ("What is the product price?", "Please refund my order.", "Is this lawful-ish?"):
        assert not is_sensitive_query(query)
    assert has_authorized_citation("Claim [s 1].", {"S1"})
    assert not has_authorized_citation("", {"S1"})
    assert not has_authorized_citation("Uncited claim", {"S1"})
    assert not has_authorized_citation("Claim [S1] and [S9]", {"S1"})
    assert not has_authorized_citation("Claim [S1]", set())


def sensitive_chunk(document_id: str = "doc-1") -> RetrievedChunk:
    return RetrievedChunk(
        document_id=document_id,
        source_name="source.txt",
        page_number=1,
        chunk_index=0,
        text="The source states the relevant rule.",
        score=0.9,
    )


@pytest.mark.asyncio
async def test_sensitive_answer_uses_retrieved_evidence_and_authorized_citation() -> None:
    service, _, retriever, model = make_service(
        chunks=[sensitive_chunk()],
        model=FixedAnswerChatModel("The source states the rule [S1]."),
    )
    session = service.create_session(
        tenant_id="tenant-1", user_id="user-1", chatbot_id="bot-1",
        knowledge_base_id="kb-1", locale="en", channel="EMPLOYEE_PLAYGROUND",
    )

    answer = await service.submit_message(
        tenant_id="tenant-1", session_id=session.id, user_id="user-1",
        content="What does the law require?",
    )

    assert answer.content == "The source states the rule [S1]."
    assert [citation.document_id for citation in answer.citations] == ["doc-1"]
    assert retriever.calls[0]["tenant_id"] == "tenant-1"
    assert "Do not give personalized legal" in str(model.calls[0][0]["content"])


@pytest.mark.asyncio
async def test_sensitive_query_without_retrieved_chunks_abstains_before_model() -> None:
    service, _, _, model = make_service(chunks=[])
    session = service.create_session(
        tenant_id="tenant-1", user_id="user-1", chatbot_id="bot-1",
        knowledge_base_id="kb-1", locale="en", channel="EMPLOYEE_PLAYGROUND",
    )

    answer = await service.submit_message(
        tenant_id="tenant-1", session_id=session.id, user_id="user-1",
        content="What is the tax deadline?",
    )

    assert answer.content == NO_INFORMATION_RESPONSE
    assert answer.citations == []
    assert model.calls == []


@pytest.mark.parametrize("model_answer", (
    "The deadline is next week.",
    "The deadline is next week [S9].",
    "The deadline is next week [S1] and [S9].",
))
@pytest.mark.asyncio
async def test_sensitive_answer_with_missing_or_unknown_marker_abstains(
    model_answer: str,
) -> None:
    service, _, _, _ = make_service(
        chunks=[sensitive_chunk()], model=FixedAnswerChatModel(model_answer),
    )
    session = service.create_session(
        tenant_id="tenant-1", user_id="user-1", chatbot_id="bot-1",
        knowledge_base_id="kb-1", locale="en", channel="EMPLOYEE_PLAYGROUND",
    )

    answer = await service.submit_message(
        tenant_id="tenant-1", session_id=session.id, user_id="user-1",
        content="What does the law say?",
    )

    assert answer.content == NO_INFORMATION_RESPONSE
    assert answer.citations == []


@pytest.mark.asyncio
async def test_normal_answer_without_marker_still_uses_existing_path() -> None:
    service, _, _, _ = make_service(
        chunks=[sensitive_chunk()], model=FixedAnswerChatModel("Open from 9 to 5."),
    )
    session = service.create_session(
        tenant_id="tenant-1", user_id="user-1", chatbot_id="bot-1",
        knowledge_base_id="kb-1", locale="en", channel="EMPLOYEE_PLAYGROUND",
    )

    answer = await service.submit_message(
        tenant_id="tenant-1", session_id=session.id, user_id="user-1",
        content="What are the opening hours?",
    )

    assert answer.content == "Open from 9 to 5."
    assert [citation.id for citation in answer.citations] == ["S1"]


@pytest.mark.asyncio
async def test_sensitive_query_bypasses_semantic_cache_and_fetches_fresh_evidence() -> None:
    service, _, retriever, _ = make_service(chunks=[sensitive_chunk()])
    service._semantic_answer_cache = UnexpectedSemanticCache()  # type: ignore[assignment]
    session = service.create_session(
        tenant_id="tenant-1", user_id="user-1", chatbot_id="bot-1",
        knowledge_base_id="kb-1", locale="en", channel="EMPLOYEE_PLAYGROUND",
    )

    answer = await service.submit_message(
        tenant_id="tenant-1", session_id=session.id, user_id="user-1",
        content="Is this contract legal?",
    )

    assert [citation.id for citation in answer.citations] == ["S1"]
    assert len(retriever.calls) == 1


class TestAnswerPolish:
    def test_placeholder_is_grounded(self) -> None:
        from app.modules.generation.internal.answer_polish import polish_answer

        polished = polish_answer(
            "Đăng ký dự thi theo đúng quy định của BTC thông qua [Tên Trường]."
        )
        assert "[Tên Trường]" not in polished
        assert "trường/thành viên đăng ký" in polished

    def test_document_echo_is_removed(self) -> None:
        from app.modules.generation.internal.answer_polish import (
            contains_source_echo,
            polish_answer,
        )

        raw = (
            "Tham khảo Khung kiến trúc DX-OS từ tài liệu "
            "e___thi_pha__n_me__m_nguo__n_mo___-_OLP_2026.docx, chunk 32."
        )
        assert contains_source_echo(raw)
        polished = polish_answer(raw)
        assert not contains_source_echo(polished)
        assert ".docx" not in polished
        assert "chunk 32" not in polished
        assert "Khung kiến trúc DX-OS" in polished

    def test_knowledge_markers_are_untouched(self) -> None:
        from app.modules.generation.internal.answer_polish import polish_answer

        answer = "Sản phẩm được đổi trong 7 ngày [S1]."
        assert polish_answer(answer) == answer

    def test_prose_placeholder_becomes_grounded_phrase(self) -> None:
        from app.modules.generation.internal.answer_polish import polish_answer

        polished = polish_answer(
            "Truy cập kho mã nguồn dự thi từ "
            "[đường dẫn truy cập kho mã nguồn chứa đầy đủ nội dung kết quả dự thi]."
        )
        assert "[đường dẫn" not in polished
        assert "đường dẫn do ban tổ chức cung cấp" in polished


class StreamingAnswerModel(FakeChatModel):
    def __init__(self, fragments: list[str], *, truncated: bool = False) -> None:
        super().__init__()
        self.fragments = fragments
        self.truncated = truncated
        self.finished = False
        self.closed = False

    async def stream_with_usage(
        self, messages: Sequence[dict[str, object]], *, allow_truncated: bool = False
    ) -> AsyncIterator[str | ModelCompletion]:
        self.calls.append(messages)
        assert allow_truncated
        try:
            for fragment in self.fragments:
                yield fragment
            self.finished = True
            yield ModelCompletion(
                content="".join(self.fragments),
                input_tokens=42,
                output_tokens=17,
                truncated=self.truncated,
            )
        finally:
            self.closed = True


@pytest.mark.asyncio
async def test_rag_stream_polishes_fragmented_echoes_and_delivers_multiple_early_snapshots(
) -> None:
    fragments = [
        "The return policy explains the process in detail for all registered customers. ",
        "Submit the form for [Tên",
        " Trường] together with the listed materials. ",
        "See tài liệu tenant_very_long_document_name_",
        "0123456789.doc",
        "x, chunk ",
        "32 for the supporting evidence. ",
        "The process is confirmed by the supplied source [S1].",
    ]
    model = StreamingAnswerModel(fragments)
    service, _, retriever, _ = make_service(chunks=[sensitive_chunk()], model=model)
    session = service.create_session(
        tenant_id="tenant-1", user_id="user-1", chatbot_id="bot-1",
        knowledge_base_id="kb-1", locale="en",
    )
    previews: list[str] = []

    async def preview(content: str) -> None:
        assert not model.finished
        previews.append(content)

    answer = await service.submit_message(
        session=session, content="What is the return policy?", on_content=preview
    )
    assert len(previews) >= 2
    assert len(retriever.calls) == 1
    assert model.closed
    assert (answer.token_usage.input_tokens, answer.token_usage.output_tokens) == (42, 17)
    assert answer.content != previews[-1]
    assert answer.content.endswith("[S1].")
    assert "trường/thành viên đăng ký" in answer.content
    for content in [*previews, answer.content]:
        assert "[Tên" not in content
        assert "tenant_" not in content
        assert ".doc" not in content
        assert "chunk" not in content


@pytest.mark.asyncio
@pytest.mark.parametrize("citation", ["[S1]", "[S9]", ""])
async def test_sensitive_stream_withholds_every_preview_until_validation(citation: str) -> None:
    model = StreamingAnswerModel([
        "The supplied source describes the relevant legal requirement in detail. ",
        f"The rule applies only to the documented scenario {citation}.",
    ])
    service, _, _, _ = make_service(chunks=[sensitive_chunk()], model=model)
    session = service.create_session(
        tenant_id="tenant-1", user_id="user-1", chatbot_id="bot-1",
        knowledge_base_id="kb-1", locale="en",
    )
    previews: list[str] = []

    async def preview(content: str) -> None:
        previews.append(content)

    answer = await service.submit_message(
        session=session, content="What does the law require?", on_content=preview
    )
    assert previews == []
    assert model.closed
    assert answer.token_usage.input_tokens == 42
    if citation == "[S1]":
        assert answer.content.endswith("[S1].")
        assert [item.id for item in answer.citations] == ["S1"]
    else:
        assert answer.content == NO_INFORMATION_RESPONSE
        assert answer.citations == []


@pytest.mark.asyncio
@pytest.mark.parametrize("sensitive", [False, True])
async def test_rag_stream_truncation_preserves_existing_sensitive_policy(sensitive: bool) -> None:
    model = StreamingAnswerModel([
        "The source describes this requirement with enough detail to answer the question. ",
        "The relevant rule is stated in the uploaded evidence [S1].",
    ], truncated=True)
    service, _, _, _ = make_service(chunks=[sensitive_chunk()], model=model)
    session = service.create_session(
        tenant_id="tenant-1", user_id="user-1", chatbot_id="bot-1",
        knowledge_base_id="kb-1", locale="en",
    )
    previews: list[str] = []

    async def preview(content: str) -> None:
        previews.append(content)

    if sensitive:
        with pytest.raises(ChatModelTimeoutError, match="truncated"):
            await service.submit_message(
                session=session, content="What does the law require?", on_content=preview
            )
        assert previews == []
    else:
        answer = await service.submit_message(
            session=session, content="What is the return policy?", on_content=preview
        )
        assert answer.content.endswith("[S1].")
        assert previews
    assert model.closed


def test_preview_never_exposes_split_placeholders_or_identifiers() -> None:
    from app.modules.generation.internal.answer_polish import polish_preview

    draft = (
        "Follow the process described for [Tên Trường] using "
        "tenant_very_long_document_identifier_0123456789.docx, chunk 12345. "
        "The remaining explanation describes the full process in detail for members."
    )
    for end in range(len(draft)):
        preview = polish_preview(draft[:end])
        assert "[Tên" not in preview
        assert "tenant_" not in preview
        assert ".doc" not in preview
        assert "chunk" not in preview


@pytest.mark.asyncio
async def test_stream_answer_empty_after_polishing_is_not_a_successful_completion() -> None:
    model = StreamingAnswerModel(["tenant_0123456789.docx"])
    service, _, _, _ = make_service(chunks=[sensitive_chunk()], model=model)
    session = service.create_session(
        tenant_id="tenant-1", user_id="user-1", chatbot_id="bot-1",
        knowledge_base_id="kb-1", locale="en",
    )
    previews: list[str] = []

    async def preview(content: str) -> None:
        previews.append(content)

    with pytest.raises(ModelUnavailableError, match="empty answer"):
        await service.submit_message(
            session=session, content="What is the return policy?", on_content=preview
        )
    assert previews == []
    assert model.closed
