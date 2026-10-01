from __future__ import annotations

from types import SimpleNamespace
from typing import Any

import pytest

from app.bootstrap.settings import Settings
from app.modules.generation.internal.models import (
    AssistantMessage,
    ChatMessage,
    ChatSession,
    Citation,
    RetrievedChunk,
)
from app.modules.generation.internal.semantic_answer_cache import (
    SemanticAnswerCache,
    SemanticCacheCandidate,
    SemanticCacheContext,
    cleanup_expired_semantic_points,
)
from app.modules.generation.internal.service import RagChatService
from app.modules.generation.internal.sessions import InMemoryChatSessionStore
from app.modules.model.api import ModelCompletion
from app.modules.retrieval.api import QueryProfile, RetrievalFingerprint, RetrievalPlan
from app.modules.retrieval.internal.cache import retrieval_configuration_fingerprint
from app.modules.retrieval.internal.retrieval import QueryRouter


class FakeRedis:
    def __init__(self) -> None:
        self.values: dict[str, bytes] = {}
        self.ttls: dict[str, int] = {}
        self.fail_get = False
        self.fail_set = False

    async def get(self, key: str) -> bytes | None:
        if self.fail_get:
            raise ConnectionError("redis unavailable")
        return self.values.get(key)

    async def set(self, key: str, value: bytes, *, ex: int) -> None:
        if self.fail_set:
            raise ConnectionError("redis unavailable")
        self.values[key] = value
        self.ttls[key] = ex

    async def delete(self, key: str) -> None:
        self.values.pop(key, None)


class FakeRetrievalPlan:
    def __init__(self, settings: Settings) -> None:
        self._router = QueryRouter(settings)
        self._fingerprint = retrieval_configuration_fingerprint(settings)

    def plan(self, query_text: str) -> RetrievalPlan:
        return RetrievalPlan(
            RetrievalFingerprint(self._router.route(query_text), self._fingerprint)
        )


class FakeRevisionStore:
    def __init__(self, revision: int = 7) -> None:
        self.revision = revision

    async def current_revision(self, tenant_id: str, knowledge_base_id: str) -> int:
        del tenant_id, knowledge_base_id
        return self.revision

    async def increment(self, tenant_id: str, knowledge_base_id: str) -> int:
        del tenant_id, knowledge_base_id
        self.revision += 1
        return self.revision


class FakeQdrant:
    def __init__(self, *, dimension: int = 3) -> None:
        self.exists = False
        self.dimension = dimension
        self.points: dict[str, Any] = {}
        self.results: list[Any] = []
        self.indexes: list[tuple[str, Any]] = []
        self.query_limits: list[int] = []
        self.fail_queries = False

    async def collection_exists(self, collection_name: str) -> bool:
        del collection_name
        return self.exists

    async def create_collection(self, *, collection_name: str, vectors_config: Any) -> None:
        del collection_name
        self.exists = True
        vector = next(iter(vectors_config.values()))
        self.dimension = int(vector.size)

    async def create_payload_index(
        self,
        *,
        collection_name: str,
        field_name: str,
        field_schema: Any,
        wait: bool,
    ) -> None:
        del collection_name, wait
        self.indexes.append((field_name, field_schema))

    async def get_collection(self, collection_name: str) -> Any:
        del collection_name
        return SimpleNamespace(
            config=SimpleNamespace(
                params=SimpleNamespace(vectors={"query_v1": SimpleNamespace(size=self.dimension)})
            )
        )

    async def upsert(self, *, collection_name: str, points: list[Any], wait: bool) -> None:
        del collection_name, wait
        for point in points:
            self.points[str(point.id)] = point

    async def query_points(self, *, limit: int, **kwargs: Any) -> Any:
        del kwargs
        if self.fail_queries:
            raise ConnectionError("qdrant unavailable")
        self.query_limits.append(limit)
        return SimpleNamespace(points=self.results[:limit])

    async def close(self) -> None:
        return None


def configured(**overrides: object) -> Settings:
    values: dict[str, object] = {
        "_env_file": (),
        "CACHE_ENABLED": True,
        "SEMANTIC_ANSWER_CACHE_MODE": "shadow",
        "TEXT_EMBEDDING_DIMENSION": 3,
        "LLM_MODEL_ID": "answer-model",
    }
    values.update(overrides)
    return Settings(**values)


def session(**overrides: object) -> ChatSession:
    values: dict[str, object] = {
        "id": "session-1",
        "tenant_id": "tenant-1",
        "user_id": "user-1",
        "chatbot_id": "bot-1",
        "knowledge_base_id": "kb-1",
        "locale": "en",
    }
    values.update(overrides)
    return ChatSession(**values)  # type: ignore[arg-type]


def grounded_message(content: str = "Returns are accepted for 7 days [S1].") -> AssistantMessage:
    return AssistantMessage(
        role="assistant",
        content=content,
        citations=[
            Citation(
                id="S1",
                document_id="doc-1",
                source_name="policy.pdf",
                page_number=1,
                chunk_index=0,
                score=0.91,
                snippet="Returns are accepted for 7 days.",
            )
        ],
    )


def make_cache(
    *,
    settings: Settings | None = None,
    redis: FakeRedis | None = None,
    qdrant: FakeQdrant | None = None,
    revision: FakeRevisionStore | None = None,
) -> tuple[SemanticAnswerCache, FakeRedis, FakeQdrant, FakeRevisionStore]:
    redis = redis or FakeRedis()
    qdrant = qdrant or FakeQdrant()
    revision = revision or FakeRevisionStore()
    settings = settings or configured()
    cache = SemanticAnswerCache(
        settings,
        redis_client=redis,  # type: ignore[arg-type]
        retrieval=FakeRetrievalPlan(settings),  # type: ignore[arg-type]
        qdrant_client=qdrant,  # type: ignore[arg-type]
        revision_store=revision,
        now=lambda: 1_000.0,
    )
    return cache, redis, qdrant, revision


async def context(
    cache: SemanticAnswerCache,
    query: str,
    *,
    current_session: ChatSession | None = None,
    history: list[ChatMessage] | None = None,
    visible: list[str] | None = None,
) -> SemanticCacheContext:
    result = await cache.prepare_context(
        session=current_session or session(),
        query=query,
        prior_history=history or [],
        visible_document_ids=visible,
    )
    assert result is not None
    return result


@pytest.mark.asyncio
async def test_exact_write_uses_fixed_ttl_and_privacy_safe_qdrant_payload() -> None:
    cache, redis, qdrant, _ = make_cache()
    cache_context = await context(cache, "What is the return period?")

    assert await cache.write(
        context=cache_context,
        query_vector=[1.0, 0.0, 0.0],
        message=grounded_message(),
        completion=ModelCompletion("ignored", input_tokens=120, output_tokens=18),
    )

    assert redis.ttls[cache_context.redis_key] == 3600
    candidate = await cache.lookup_exact(cache_context)
    assert candidate is not None
    assert candidate.message == grounded_message()
    assert candidate.input_tokens == 120
    assert candidate.output_tokens == 18
    point = qdrant.points[cache_context.point_id]
    assert set(point.payload) == {
        "scope_hash",
        "guard_hash",
        "query_hash",
        "expires_at",
        "redis_identity",
    }
    serialized = str(point.payload)
    for raw_value in (
        "tenant-1",
        "What is the return period?",
        "Returns are accepted",
        "policy.pdf",
        "answer-model",
    ):
        assert raw_value not in serialized
    assert {name for name, _ in qdrant.indexes} == {
        "scope_hash",
        "guard_hash",
        "expires_at",
    }


@pytest.mark.asyncio
async def test_semantic_paraphrase_hit_and_below_threshold_miss() -> None:
    cache, _, qdrant, _ = make_cache()
    stored_context = await context(cache, "What is the return period?")
    lookup_context = await context(cache, "How long can I return a product?")
    await cache.write(
        context=stored_context,
        query_vector=[1.0, 0.0, 0.0],
        message=grounded_message(),
        completion=ModelCompletion("ignored"),
    )
    point = qdrant.points[stored_context.point_id]
    qdrant.results = [SimpleNamespace(id=point.id, score=0.981, payload=point.payload)]

    candidate = await cache.lookup_semantic(lookup_context, [1.0, 0.0, 0.0])
    assert candidate is not None
    assert candidate.tier == "semantic"
    assert candidate.similarity == 0.981

    qdrant.results = [SimpleNamespace(id=point.id, score=0.969, payload=point.payload)]
    assert await cache.lookup_semantic(lookup_context, [1.0, 0.0, 0.0]) is None


@pytest.mark.asyncio
async def test_semantic_candidate_limit_and_deterministic_tie_order() -> None:
    cache, _, qdrant, _ = make_cache()
    lookup_context = await context(cache, "How long can products be returned?")
    missing: list[Any] = []
    for index in range(5):
        candidate_context = await context(cache, f"What is the return window wording {index}?")
        missing.append(
            SimpleNamespace(
                id=candidate_context.point_id,
                score=0.999 - index * 0.001,
                payload={
                    "scope_hash": candidate_context.scope_hash,
                    "guard_hash": candidate_context.guard_hash,
                    "query_hash": candidate_context.query_hash,
                    "expires_at": 4_600,
                    "redis_identity": candidate_context.redis_key,
                },
            )
        )
    valid_context = await context(cache, "Tell me the product return window?")
    await cache.write(
        context=valid_context,
        query_vector=[1.0, 0.0, 0.0],
        message=grounded_message(),
        completion=ModelCompletion("ignored"),
    )
    valid_point = qdrant.points[valid_context.point_id]
    qdrant.results = [
        *missing,
        SimpleNamespace(id=valid_point.id, score=0.98, payload=valid_point.payload),
    ]
    assert await cache.lookup_semantic(lookup_context, [1.0, 0.0, 0.0]) is None
    assert qdrant.query_limits[-1] == 5

    first_context = await context(cache, "Explain the return window alpha?")
    second_context = await context(cache, "Explain the return window beta?")
    await cache.write(
        context=first_context,
        query_vector=[1.0, 0.0, 0.0],
        message=grounded_message("Alpha answer [S1]."),
        completion=ModelCompletion("ignored"),
    )
    await cache.write(
        context=second_context,
        query_vector=[1.0, 0.0, 0.0],
        message=grounded_message("Beta answer [S1]."),
        completion=ModelCompletion("ignored"),
    )
    points = [qdrant.points[first_context.point_id], qdrant.points[second_context.point_id]]
    qdrant.results = [
        SimpleNamespace(id=point.id, score=0.99, payload=point.payload)
        for point in reversed(points)
    ]
    tied = await cache.lookup_semantic(lookup_context, [1.0, 0.0, 0.0])
    assert tied is not None
    expected_id = min(str(point.id) for point in points)
    expected = "Alpha" if expected_id == first_context.point_id else "Beta"
    assert tied.message.content.startswith(expected)


@pytest.mark.asyncio
async def test_scope_history_revision_visibility_and_configuration_isolation() -> None:
    cache, _, _, revision = make_cache()
    base = await context(cache, "How does this policy work?")
    paraphrase = await context(cache, "Can you explain this policy?")
    assert base.scope_hash == paraphrase.scope_hash
    assert base.query_hash != paraphrase.query_hash

    variants = [
        await context(cache, "How does this policy work?", current_session=session(tenant_id="t2")),
        await context(
            cache, "How does this policy work?", current_session=session(chatbot_id="b2")
        ),
        await context(
            cache,
            "How does this policy work?",
            current_session=session(knowledge_base_id="kb-2"),
        ),
        await context(cache, "How does this policy work?", current_session=session(locale="vi")),
        await context(
            cache,
            "How does this policy work?",
            visible=["doc-1"],
        ),
    ]
    assert all(item.scope_hash != base.scope_hash for item in variants)

    scoped = await context(
        cache,
        "First paraphrase?",
        visible=["doc-1"],
    )
    changed_visibility = await context(
        cache,
        "First paraphrase?",
        visible=["doc-2"],
    )
    assert changed_visibility.scope_hash != scoped.scope_hash

    revision.revision += 1
    changed_revision = await context(cache, "How does this policy work?")
    assert changed_revision.scope_hash != base.scope_hash

    changed_model_cache, _, _, _ = make_cache(settings=configured(LLM_MODEL_ID="other-model"))
    changed_embedding_cache, _, _, _ = make_cache(
        settings=configured(TEXT_EMBEDDING_MODEL_ID="other-embedding")
    )
    changed_retrieval_cache, _, _, _ = make_cache(settings=configured(FINAL_CONTEXT_TOP_K=9))
    assert (
        await context(changed_model_cache, "How does this policy work?")
    ).scope_hash != base.scope_hash
    assert (
        await context(changed_embedding_cache, "How does this policy work?")
    ).scope_hash != base.scope_hash
    assert (
        await context(changed_retrieval_cache, "How does this policy work?")
    ).scope_hash != base.scope_hash


@pytest.mark.asyncio
async def test_qwen_semantic_cache_identity_uses_exact_model_id() -> None:
    model_id = "mlx-community/Qwen2.5-14B-Instruct-4bit"
    first, _, _, _ = make_cache(
        settings=configured(LLM_PROVIDER="qwen", LLM_MODEL_ID=model_id)
    )
    same, _, _, _ = make_cache(
        settings=configured(LLM_PROVIDER="qwen", LLM_MODEL_ID=model_id)
    )
    changed, _, _, _ = make_cache(
        settings=configured(
            LLM_PROVIDER="qwen",
            LLM_MODEL_ID="mlx-community/Qwen2.5-14B-Instruct-8bit",
        )
    )

    first_context = await context(first, "How does this policy work?")
    same_context = await context(same, "How does this policy work?")
    changed_context = await context(changed, "How does this policy work?")

    assert same_context.scope_hash == first_context.scope_hash
    assert changed_context.scope_hash != first_context.scope_hash


@pytest.mark.parametrize(
    ("left", "right"),
    [
        ("Include archived plans", "Do not include archived plans"),
        ("What happens after 7 days?", "What happens after 14 days?"),
        ("Policy on 2026-07-19?", "Policy on 2026-07-20?"),
        ("Is the fee USD 20?", "Is the fee USD 30?"),
        ("Status of order AB-1234?", "Status of order AB-5678?"),
        ("Explain the refund policy", "Total refund amount"),
    ],
)
@pytest.mark.asyncio
async def test_guard_rejects_negation_literals_identifiers_and_profile_changes(
    left: str, right: str
) -> None:
    cache, _, _, _ = make_cache()
    left_context = await context(cache, left)
    if "Total" in right:
        assert cache.accepts_query(right) is False
        return
    right_context = await context(cache, right)
    assert left_context.guard_hash != right_context.guard_hash


def test_calculations_and_explicit_action_requests_are_ineligible() -> None:
    cache, _, _, _ = make_cache()
    for query in (
        "Calculate the total revenue",
        "Please create a support ticket for me",
        "Hãy tạo một phiếu yêu cầu hỗ trợ",
    ):
        assert cache.accepts_query(query) is False
    assert cache.is_response_eligible(AssistantMessage(role="assistant", content="")) is False
    assert (
        cache.is_response_eligible(
            AssistantMessage(
                role="assistant",
                content="I do not know [S1].",
                citations=grounded_message().citations,
            )
        )
        is False
    )
    assert (
        cache.is_response_eligible(
            AssistantMessage(
                role="assistant", content="Grounded answer.", citations=grounded_message().citations
            )
        )
        is False
    )


@pytest.mark.asyncio
async def test_corrupt_redis_qdrant_and_collection_mismatch_fail_open() -> None:
    cache, redis, qdrant, _ = make_cache()
    cache_context = await context(cache, "What is the return period?")
    redis.values[cache_context.redis_key] = b"not-json"
    assert await cache.lookup_exact(cache_context) is None
    assert cache_context.redis_key not in redis.values

    qdrant.exists = True
    qdrant.fail_queries = True
    assert await cache.lookup_semantic(cache_context, [1.0, 0.0, 0.0]) is None

    mismatch = FakeQdrant(dimension=4)
    mismatch.exists = True
    mismatch_cache, mismatch_redis, _, _ = make_cache(qdrant=mismatch)
    mismatch_context = await context(mismatch_cache, "What is the return period?")
    assert await mismatch_cache.write(
        context=mismatch_context,
        query_vector=[1.0, 0.0, 0.0],
        message=grounded_message(),
        completion=ModelCompletion("ignored"),
    )
    assert mismatch_context.redis_key in mismatch_redis.values
    assert await mismatch_cache.lookup_exact(mismatch_context) is not None


class CleanupQdrant:
    def __init__(self, pages: list[list[str]]) -> None:
        self.pages = pages
        self.deleted: list[list[str]] = []
        self.calls = 0

    async def collection_exists(self, collection_name: str) -> bool:
        return collection_name == "semantic_answer_cache_v1"

    async def scroll(self, **kwargs: Any) -> tuple[list[Any], str | None]:
        del kwargs
        page = self.pages[self.calls] if self.calls < len(self.pages) else []
        self.calls += 1
        next_offset = str(self.calls) if self.calls < len(self.pages) else None
        return [SimpleNamespace(id=value) for value in page], next_offset

    async def delete(self, *, points_selector: Any, **kwargs: Any) -> None:
        del kwargs
        self.deleted.append([str(value) for value in points_selector.points])


@pytest.mark.asyncio
async def test_expired_cleanup_is_bounded_dry_run_and_requires_apply() -> None:
    dry_client = CleanupQdrant([["a", "b"], ["c"]])
    dry = await cleanup_expired_semantic_points(
        dry_client,  # type: ignore[arg-type]
        collection="semantic_answer_cache_v1",
        batch_size=2,
        max_batches=10,
        apply=False,
        now=1_000,
    )
    assert dry == {"scanned": 3, "expired": 3, "deleted": 0, "batches": 2, "apply": False}
    assert dry_client.deleted == []

    apply_client = CleanupQdrant([["a"], ["b"], ["c"]])
    applied = await cleanup_expired_semantic_points(
        apply_client,  # type: ignore[arg-type]
        collection="semantic_answer_cache_v1",
        batch_size=1,
        max_batches=2,
        apply=True,
        now=1_000,
    )
    assert applied["batches"] == 2
    assert applied["deleted"] == 2
    assert apply_client.deleted == [["a"], ["b"]]


class RecordingSessionStore(InMemoryChatSessionStore):
    pass


class RecordingEmbedder:
    def __init__(self) -> None:
        self.calls: list[str] = []

    async def embed_query(self, text: str) -> list[float]:
        self.calls.append(text)
        return [1.0, 0.0, 0.0]


class RecordingRetriever:
    def __init__(self) -> None:
        self.calls = 0

    async def retrieve(self, **kwargs: Any) -> list[RetrievedChunk]:
        del kwargs
        self.calls += 1
        return [
            RetrievedChunk(
                document_id="doc-1",
                source_name="policy.pdf",
                page_number=1,
                chunk_index=0,
                text="Returns are accepted for 7 days.",
                score=0.9,
            )
        ]


class RecordingModel:
    provider = "test"
    model = "test"

    def __init__(self) -> None:
        self.calls = 0
        self.last_messages: Any = None

    async def complete_with_usage(self, messages: Any) -> ModelCompletion:
        self.calls += 1
        self.last_messages = messages
        return ModelCompletion(
            "Returns are accepted for 7 days [S1].", input_tokens=100, output_tokens=10
        )

    async def complete(self, messages: Any) -> str:
        return (await self.complete_with_usage(messages)).content


def dummy_context() -> SemanticCacheContext:
    return SemanticCacheContext(
        scope_hash="a" * 64,
        guard_hash="b" * 64,
        query_hash="c" * 64,
        redis_key="ccn:v1:semantic-answer:a:query:c",
        point_id="point-1",
        revision=1,
        visible_document_hash="all",
        visible_document_ids=None,
        query_profile=QueryProfile.SEMANTIC,
    )


class StubSemanticCache:
    def __init__(
        self,
        mode: str,
        *,
        exact: SemanticCacheCandidate | None = None,
        semantic: SemanticCacheCandidate | None = None,
    ) -> None:
        self.mode = mode
        self.exact = exact
        self.semantic = semantic
        self.semantic_calls = 0
        self.writes = 0
        self.comparisons = 0
        self.served = 0
        self.prepared_kwargs: dict[str, Any] | None = None

    def accepts_query(self, query: str) -> bool:
        del query
        return True

    async def prepare_context(self, **kwargs: Any) -> SemanticCacheContext:
        self.prepared_kwargs = kwargs
        return dummy_context()

    async def lookup_exact(self, context: SemanticCacheContext) -> SemanticCacheCandidate | None:
        del context
        return self.exact

    async def lookup_semantic(
        self, context: SemanticCacheContext, query_vector: list[float]
    ) -> SemanticCacheCandidate | None:
        del context, query_vector
        self.semantic_calls += 1
        return self.semantic

    def record_served(self, candidate: SemanticCacheCandidate) -> None:
        del candidate
        self.served += 1

    def is_response_eligible(self, message: AssistantMessage) -> bool:
        return bool(message.citations)

    async def compare_shadow(self, **kwargs: Any) -> None:
        del kwargs
        self.comparisons += 1

    async def write(self, **kwargs: Any) -> bool:
        del kwargs
        self.writes += 1
        return True


def cache_candidate(tier: str) -> SemanticCacheCandidate:
    return SemanticCacheCandidate(
        tier=tier,
        message=grounded_message("Cached answer [S1]."),
        input_tokens=80,
        output_tokens=12,
        similarity=0.99 if tier == "semantic" else None,
    )


def service_with_stub(
    stub: StubSemanticCache,
) -> tuple[
    RagChatService,
    RecordingSessionStore,
    RecordingEmbedder,
    RecordingRetriever,
    RecordingModel,
    str,
]:
    store = RecordingSessionStore()
    embedder = RecordingEmbedder()
    retriever = RecordingRetriever()
    model = RecordingModel()
    service = RagChatService(
        settings=configured(SEMANTIC_ANSWER_CACHE_MODE=stub.mode),
        sessions=store,
        embedder=embedder,
        retriever=retriever,
        chat_model=model,
        semantic_answer_cache=stub,  # type: ignore[arg-type]
    )
    created = service.create_session(
        tenant_id="tenant-1",
        user_id="user-1",
        chatbot_id="bot-1",
        knowledge_base_id="kb-1",
        locale="en",
    )
    return service, store, embedder, retriever, model, created.id


@pytest.mark.asyncio
async def test_serve_exact_hit_skips_rag_but_consumes_quota_and_persists_messages() -> None:
    stub = StubSemanticCache("serve", exact=cache_candidate("exact"))
    service, store, embedder, retriever, model, session_id = service_with_stub(stub)

    result = await service.submit_message(
        tenant_id="tenant-1", session_id=session_id, content="Return policy?", user_id="user-1"
    )

    assert result.content == "Cached answer [S1]."
    assert [
        message.role for message in store.list_messages(session_id=session_id, tenant_id="tenant-1")
    ] == ["user", "assistant"]
    assert embedder.calls == []
    assert retriever.calls == 0
    assert model.calls == 0
    assert stub.served == 1


@pytest.mark.asyncio
async def test_serve_semantic_hit_embeds_query_but_skips_retrieval_and_llm() -> None:
    stub = StubSemanticCache("serve", semantic=cache_candidate("semantic"))
    service, store, embedder, retriever, model, session_id = service_with_stub(stub)

    result = await service.submit_message(
        tenant_id="tenant-1", session_id=session_id, content="Return duration?", user_id="user-1"
    )

    assert result.content == "Cached answer [S1]."
    assert embedder.calls == ["Return duration?"]
    assert retriever.calls == 0
    assert model.calls == 0
    assert stub.semantic_calls == 1


@pytest.mark.asyncio
async def test_shadow_never_serves_and_compares_then_writes_fresh_answer() -> None:
    stub = StubSemanticCache("shadow", exact=cache_candidate("exact"))
    service, store, embedder, retriever, model, session_id = service_with_stub(stub)

    result = await service.submit_message(
        tenant_id="tenant-1", session_id=session_id, content="Return policy?", user_id="user-1"
    )

    assert result.content == "Returns are accepted for 7 days [S1]."
    assert embedder.calls[0] == "Return policy?"
    assert retriever.calls == 1
    assert model.calls == 1
    assert stub.semantic_calls == 1
    assert stub.comparisons == 1
    assert stub.writes == 1
    assert stub.served == 0
