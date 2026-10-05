from __future__ import annotations

from collections.abc import Sequence
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
from app.modules.generation.internal.models import ChatSession, RetrievedChunk
from app.modules.generation.internal.sensitive_policy import (
    has_authorized_citation,
    is_sensitive_query,
)
from app.modules.generation.internal.service import NO_INFORMATION_RESPONSE, RagChatService
from app.modules.generation.internal.sessions import InMemoryChatSessionStore
from app.modules.index.api import KnowledgeIndexQuery
from app.modules.index.internal.qdrant_search import QdrantKnowledgeIndexQuery
from app.modules.model.api import ModelUnavailableError
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
