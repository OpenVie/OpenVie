from __future__ import annotations

import os
from urllib.parse import urlsplit, urlunsplit
from uuid import uuid4

import pytest
import redis.asyncio as redis
from qdrant_client import AsyncQdrantClient

from app.bootstrap.settings import Settings
from app.modules.generation.internal.models import AssistantMessage, ChatSession, Citation
from app.modules.generation.internal.semantic_answer_cache import SemanticAnswerCache
from app.modules.model.api import ModelCompletion
from app.modules.retrieval.api import RetrievalFingerprint, RetrievalPlan
from app.modules.retrieval.internal.cache import retrieval_configuration_fingerprint
from app.modules.retrieval.internal.retrieval import QueryRouter


def _database_15_url(url: str) -> str:
    parts = urlsplit(url)
    return urlunsplit((parts.scheme, parts.netloc, "/15", parts.query, parts.fragment))


class Revision:
    async def current_revision(self, tenant_id: str, knowledge_base_id: str) -> int:
        del tenant_id, knowledge_base_id
        return 1


class RetrievalPlanStub:
    def __init__(self, settings: Settings) -> None:
        self._router = QueryRouter(settings)
        self._fingerprint = retrieval_configuration_fingerprint(settings)

    def plan(self, query_text: str) -> RetrievalPlan:
        return RetrievalPlan(
            RetrievalFingerprint(self._router.route(query_text), self._fingerprint)
        )

    async def increment(self, tenant_id: str, knowledge_base_id: str) -> int:
        del tenant_id, knowledge_base_id
        return 2


@pytest.mark.asyncio
@pytest.mark.skipif(
    not os.getenv("REDIS_TEST_URL") or not os.getenv("QDRANT_TEST_URL"),
    reason="REDIS_TEST_URL and QDRANT_TEST_URL are required",
)
async def test_semantic_answer_cache_against_real_redis_15_and_local_qdrant() -> None:
    prefix = f"ccn:test:{uuid4().hex}"
    collection = f"semantic_answer_cache_test_{uuid4().hex}"
    settings = Settings(
        _env_file=(),
        CACHE_ENABLED=True,
        CACHE_KEY_PREFIX=prefix,
        SEMANTIC_ANSWER_CACHE_MODE="serve",
        SEMANTIC_ANSWER_CACHE_TTL_SECONDS=30,
        SEMANTIC_ANSWER_CACHE_COLLECTION=collection,
        TEXT_EMBEDDING_DIMENSION=3,
        LLM_MODEL_ID="test-model",
    )
    redis_client = redis.from_url(
        _database_15_url(os.environ["REDIS_TEST_URL"]),
        decode_responses=False,
        socket_connect_timeout=1,
        socket_timeout=1,
    )
    qdrant_client = AsyncQdrantClient(url=os.environ["QDRANT_TEST_URL"], check_compatibility=False)
    cache = SemanticAnswerCache(
        settings,
        redis_client=redis_client,
        retrieval=RetrievalPlanStub(settings),  # type: ignore[arg-type]
        qdrant_client=qdrant_client,
        revision_store=Revision(),
    )
    chat_session = ChatSession(
        id=str(uuid4()),
        tenant_id=str(uuid4()),
        user_id=str(uuid4()),
        chatbot_id=str(uuid4()),
        knowledge_base_id=str(uuid4()),
        locale="en",
    )
    stored = await cache.prepare_context(
        session=chat_session,
        query="What is the return period?",
        prior_history=[],
        visible_document_ids=None,
    )
    paraphrase = await cache.prepare_context(
        session=chat_session,
        query="How long can a product be returned?",
        prior_history=[],
        visible_document_ids=None,
    )
    assert stored is not None and paraphrase is not None
    message = AssistantMessage(
        role="assistant",
        content="Products can be returned for 7 days [S1].",
        citations=[
            Citation(
                id="S1",
                document_id=str(uuid4()),
                source_name="policy.pdf",
                page_number=1,
                chunk_index=0,
                score=0.9,
                snippet="Products can be returned for 7 days.",
            )
        ],
    )
    try:
        assert await cache.write(
            context=stored,
            query_vector=[1.0, 0.0, 0.0],
            message=message,
            completion=ModelCompletion("ignored", input_tokens=50, output_tokens=8),
        )
        assert 0 < await redis_client.ttl(stored.redis_key) <= 30
        assert (await cache.lookup_exact(stored)) is not None

        semantic = await cache.lookup_semantic(paraphrase, [1.0, 0.0, 0.0])
        assert semantic is not None
        assert semantic.message == message

        points, _ = await qdrant_client.scroll(
            collection_name=collection, limit=10, with_payload=True, with_vectors=False
        )
        assert len(points) == 1
        assert set(points[0].payload or {}) == {
            "scope_hash",
            "guard_hash",
            "query_hash",
            "expires_at",
            "redis_identity",
        }

        await redis_client.delete(stored.redis_key)
        assert await cache.lookup_semantic(paraphrase, [1.0, 0.0, 0.0]) is None
    finally:
        await redis_client.delete(stored.redis_key)
        if await qdrant_client.collection_exists(collection):
            await qdrant_client.delete_collection(collection)
        await qdrant_client.close()
        await redis_client.aclose()
