from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import math
import re
import time
from collections.abc import Callable, Sequence
from dataclasses import asdict, dataclass
from typing import Any, Protocol
from uuid import NAMESPACE_URL, uuid5

from qdrant_client import AsyncQdrantClient, models
from redis.asyncio import Redis

from app.common.metrics import (
    observe_semantic_answer_cache_lookup,
    observe_semantic_answer_shadow,
    observe_semantic_answer_similarity,
    record_redis_operation,
    record_semantic_answer_avoided,
    record_semantic_answer_cache_operation,
)
from app.modules.generation.api import GenerationCacheCleanupResult
from app.modules.generation.internal.config import GenerationConfig
from app.modules.generation.internal.models import (
    AssistantMessage,
    ChatMessage,
    ChatSession,
    Citation,
)
from app.modules.model.api import ModelCompletion, normalize_embedding_text
from app.modules.retrieval.api import QueryProfile, RetrievalApi

logger = logging.getLogger(__name__)

SEMANTIC_ANSWER_CACHE_SCHEMA_VERSION = 1
SEMANTIC_ANSWER_SCOPE_VERSION = 1
CHAT_PROMPT_SCHEMA_VERSION = "chat-prompts-v3"
MAX_CACHED_CITATIONS = 32

_NEGATIONS = (
    "not",
    "no",
    "never",
    "without",
    "exclude",
    "except",
    "don't",
    "do not",
    "isn't",
    "is not",
    "không",
    "chẳng",
    "chưa",
    "đừng",
    "ngoại trừ",
    "không phải",
)
_NUMBER = re.compile(r"(?<![\w])[-+]?\d+(?:[.,]\d+)*(?![\w])", re.UNICODE)
_DATE = re.compile(
    r"(?:\b\d{4}[-/.]\d{1,2}[-/.]\d{1,2}\b|"
    r"\b\d{1,2}[-/.]\d{1,2}(?:[-/.]\d{2,4})?\b|"
    r"\b(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|"
    r"jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|"
    r"dec(?:ember)?)\s+\d{1,2}(?:,\s*\d{4})?\b)",
    re.IGNORECASE,
)
_CURRENCY = re.compile(
    r"(?:[$€£¥₫]\s*\d[\d.,]*|\d[\d.,]*\s*(?:usd|eur|gbp|jpy|vnd|đ|₫|"
    r"dollars?|euros?|pounds?|yen|đồng|triệu|nghìn|ngàn))",
    re.IGNORECASE,
)
_IDENTIFIER = re.compile(
    r"(?:\b[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}\b|"
    r"\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\b|"
    r"\b(?:SKU|CODE|ID|ORDER|INVOICE|MÃ|MST|SĐT|PHONE|EMAIL)\s*[:#-]?\s*"
    r"[A-Za-z0-9@._+/-]+|\b(?=[A-Za-z0-9_-]*[A-Za-z])(?=[A-Za-z0-9_-]*\d)"
    r"[A-Za-z0-9][A-Za-z0-9_-]{3,}\b)",
    re.IGNORECASE,
)
_ACTION_INTENT = re.compile(
    r"(?:\b(?:create|open|submit|file|raise|start|make)\b.{0,30}"
    r"\b(?:support\s+)?(?:ticket|case|request)\b|"
    r"\b(?:ticket|case|support\s+request)\b.{0,30}\b(?:create|open|submit|file|raise)\b|"
    r"(?:tạo|mở|gửi|lập|nộp).{0,30}(?:ticket|vé|phiếu|yêu cầu hỗ trợ)|"
    r"(?:ticket|vé|phiếu|yêu cầu hỗ trợ).{0,30}(?:tạo|mở|gửi|lập|nộp)|"
    r"(?:liên hệ|chuyển|gặp).{0,20}(?:nhân viên hỗ trợ|tổng đài|support agent))",
    re.IGNORECASE | re.DOTALL,
)
_NO_INFORMATION = re.compile(
    r"(?:không (?:tìm thấy|có|đủ) thông tin|không biết|không thể trả lời|"
    r"not (?:find|enough|available)|do not know|don't know|cannot answer|"
    r"can't answer|sources? (?:are|is) insufficient|no relevant (?:source|information))",
    re.IGNORECASE,
)


class AnswerEmbedder(Protocol):
    async def embed_query(self, text: str) -> list[float]: ...


@dataclass(frozen=True, slots=True)
class SemanticCacheContext:
    scope_hash: str
    guard_hash: str
    query_hash: str
    redis_key: str
    point_id: str
    revision: int
    visible_document_hash: str
    visible_document_ids: tuple[str, ...] | None
    query_profile: QueryProfile


@dataclass(frozen=True, slots=True)
class SemanticCacheCandidate:
    tier: str
    message: AssistantMessage
    input_tokens: int | None
    output_tokens: int | None
    similarity: float | None = None


def _canonical_hash(value: Any) -> str:
    encoded = json.dumps(
        value,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
        allow_nan=False,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _text_hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _normalized_literal(value: str) -> str:
    return " ".join(value.casefold().split())


def _guard_hash(query: str, profile: QueryProfile) -> str:
    normalized = normalize_embedding_text(query).casefold()
    negations = sorted(
        marker
        for marker in _NEGATIONS
        if re.search(rf"(?<!\w){re.escape(marker)}(?!\w)", normalized)
    )
    identifiers = sorted(
        {
            _text_hash(_normalized_literal(match.group(0)))
            for match in _IDENTIFIER.finditer(normalized)
        }
    )
    return _canonical_hash(
        {
            "profile": profile.value,
            "negations": negations,
            "numbers": sorted({_normalized_literal(item) for item in _NUMBER.findall(normalized)}),
            "dates": sorted({_normalized_literal(item) for item in _DATE.findall(normalized)}),
            "currencies": sorted(
                {_normalized_literal(item) for item in _CURRENCY.findall(normalized)}
            ),
            "identifiers": identifiers,
        }
    )


def _cosine(left: Sequence[float], right: Sequence[float]) -> float:
    if len(left) != len(right) or not left:
        raise ValueError("Embedding dimensions do not match")
    dot = sum(a * b for a, b in zip(left, right, strict=True))
    left_norm = math.sqrt(sum(value * value for value in left))
    right_norm = math.sqrt(sum(value * value for value in right))
    if left_norm == 0 or right_norm == 0:
        raise ValueError("Embedding norm must be positive")
    value = dot / (left_norm * right_norm)
    if not math.isfinite(value):
        raise ValueError("Cosine similarity must be finite")
    return max(-1.0, min(1.0, value))


class SemanticAnswerCache:
    def __init__(
        self,
        settings: GenerationConfig,
        *,
        redis_client: Redis,
        retrieval: RetrievalApi,
        revision_store: Any | None = None,
        qdrant_client: AsyncQdrantClient | None = None,
        now: Callable[[], float] = time.time,
    ) -> None:
        self._settings = settings
        self.mode = settings.SEMANTIC_ANSWER_CACHE_MODE
        self._enabled = settings.CACHE_ENABLED and self.mode in {"shadow", "serve"}
        self._redis = redis_client
        self._collection = settings.SEMANTIC_ANSWER_CACHE_COLLECTION
        self._vector_name = settings.SEMANTIC_ANSWER_CACHE_VECTOR_NAME
        self._dimension = settings.TEXT_EMBEDDING_DIMENSION
        self._ttl_seconds = settings.SEMANTIC_ANSWER_CACHE_TTL_SECONDS
        self._threshold = settings.SEMANTIC_ANSWER_CACHE_SIMILARITY_THRESHOLD
        self._candidate_limit = settings.SEMANTIC_ANSWER_CACHE_CANDIDATE_LIMIT
        self._prefix = settings.CACHE_KEY_PREFIX.rstrip(":")
        self._retrieval = retrieval
        self._revision_store = revision_store
        self._now = now
        self._owns_qdrant = qdrant_client is None
        self._qdrant = qdrant_client or AsyncQdrantClient(
            url=settings.QDRANT_URL,
            api_key=settings.QDRANT_API_KEY or None,
            check_compatibility=False,
        )

    @property
    def active(self) -> bool:
        return self._enabled

    def accepts_query(self, query: str) -> bool:
        if not self._enabled or not query.strip() or _ACTION_INTENT.search(query):
            return False
        return self._retrieval.plan(query).fingerprint.profile is not QueryProfile.CALCULATION

    async def prepare_context(
        self,
        *,
        session: ChatSession,
        query: str,
        prior_history: Sequence[ChatMessage],
        visible_document_ids: Sequence[str] | None,
    ) -> SemanticCacheContext | None:
        if not self.accepts_query(query):
            return None
        try:
            revision = (
                await self._revision_store.current_revision(
                    session.tenant_id, session.knowledge_base_id
                )
                if self._revision_store is not None
                else session.authoritative_revision
            )
            if revision < 0:
                raise ValueError("Knowledge-base revision must not be negative")
            visible_ids = (
                None
                if visible_document_ids is None
                else tuple(sorted({str(value) for value in visible_document_ids}))
            )
            visible_hash = "all" if visible_ids is None else _canonical_hash(visible_ids)
            del prior_history
            model_id = self._settings.LLM_MODEL_ID
            scope_hash = _canonical_hash(
                {
                    "scope_version": SEMANTIC_ANSWER_SCOPE_VERSION,
                    "tenant_id": session.tenant_id,
                    "chatbot_id": session.chatbot_id,
                    "knowledge_base_id": session.knowledge_base_id,
                    "knowledge_base_revision": revision,
                    "channel": session.channel,
                    "locale": session.locale,
                    "visible_document_hash": visible_hash,
                    "prompt_schema_version": CHAT_PROMPT_SCHEMA_VERSION,
                    "llm": {
                        "provider": self._settings.LLM_PROVIDER,
                        "model": model_id,
                        "adapter": self._settings.LLM_ADAPTER_ID,
                        "temperature": self._settings.LLM_TEMPERATURE,
                        "max_output_tokens": self._settings.LLM_MAX_OUTPUT_TOKENS,
                        "disable_thinking": self._settings.LLM_DISABLE_THINKING,
                    },
                    "embedding": {
                        "base_url": self._settings.TEXT_EMBEDDING_BASE_URL,
                        "model": self._settings.TEXT_EMBEDDING_MODEL_ID,
                        "dimension": self._settings.TEXT_EMBEDDING_DIMENSION,
                        "normalization_version": 1,
                    },
                    "retrieval_configuration": (
                        self._retrieval.plan(query).fingerprint.configuration
                    ),
                }
            )
            query_hash = _text_hash(normalize_embedding_text(query))
            profile = self._retrieval.plan(query).fingerprint.profile
            guard_hash = _guard_hash(query, profile)
            redis_key = self._redis_key(scope_hash, query_hash)
            return SemanticCacheContext(
                scope_hash=scope_hash,
                guard_hash=guard_hash,
                query_hash=query_hash,
                redis_key=redis_key,
                point_id=self.point_id(scope_hash, query_hash),
                revision=revision,
                visible_document_hash=visible_hash,
                visible_document_ids=visible_ids,
                query_profile=profile,
            )
        except Exception:
            record_semantic_answer_cache_operation(self.mode, "exact", "scope_error")
            return None

    async def lookup_exact(self, context: SemanticCacheContext) -> SemanticCacheCandidate | None:
        return await self._lookup_redis(
            context=context,
            query_hash=context.query_hash,
            redis_key=context.redis_key,
            tier="exact",
            similarity=None,
        )

    async def lookup_semantic(
        self,
        context: SemanticCacheContext,
        query_vector: Sequence[float],
    ) -> SemanticCacheCandidate | None:
        started = time.perf_counter()
        outcome = "miss"
        try:
            if len(query_vector) != self._dimension or not all(
                math.isfinite(value) for value in query_vector
            ):
                record_semantic_answer_cache_operation(self.mode, "semantic", "invalid_vector")
                outcome = "error"
                return None
            if not await self._qdrant.collection_exists(self._collection):
                record_semantic_answer_cache_operation(self.mode, "semantic", "miss")
                return None
            response = await self._qdrant.query_points(
                collection_name=self._collection,
                query=list(query_vector),
                using=self._vector_name,
                query_filter=models.Filter(
                    must=[
                        models.FieldCondition(
                            key="scope_hash",
                            match=models.MatchValue(value=context.scope_hash),
                        ),
                        models.FieldCondition(
                            key="guard_hash",
                            match=models.MatchValue(value=context.guard_hash),
                        ),
                        models.FieldCondition(
                            key="expires_at",
                            range=models.Range(gt=int(self._now())),
                        ),
                    ]
                ),
                limit=self._candidate_limit,
                with_payload=True,
                with_vectors=False,
            )
            points = list(getattr(response, "points", response))
            points.sort(key=lambda point: (-float(point.score), str(point.id)))
            for point in points[: self._candidate_limit]:
                score = float(point.score)
                if not math.isfinite(score):
                    record_semantic_answer_cache_operation(self.mode, "semantic", "rejected")
                    continue
                record_semantic_answer_cache_operation(self.mode, "semantic", "candidate")
                observe_semantic_answer_similarity(self.mode, "semantic", score)
                if score < self._threshold:
                    record_semantic_answer_cache_operation(self.mode, "semantic", "rejected")
                    continue
                try:
                    payload = self._validate_qdrant_payload(point.payload, context)
                except ValueError:
                    record_semantic_answer_cache_operation(self.mode, "semantic", "payload_error")
                    continue
                candidate = await self._lookup_redis(
                    context=context,
                    query_hash=payload["query_hash"],
                    redis_key=payload["redis_identity"],
                    tier="semantic",
                    similarity=score,
                    nested=True,
                )
                if candidate is not None:
                    outcome = "hit"
                    return candidate
            record_semantic_answer_cache_operation(self.mode, "semantic", "miss")
            return None
        except Exception:
            outcome = "error"
            record_semantic_answer_cache_operation(self.mode, "semantic", "error")
            return None
        finally:
            observe_semantic_answer_cache_lookup(
                self.mode, "semantic", outcome, time.perf_counter() - started
            )

    async def write(
        self,
        *,
        context: SemanticCacheContext,
        query_vector: Sequence[float],
        message: AssistantMessage,
        completion: ModelCompletion,
    ) -> bool:
        if not self.is_response_eligible(message):
            record_semantic_answer_cache_operation(self.mode, "write", "ineligible")
            return False
        if len(query_vector) != self._dimension or not all(
            math.isfinite(value) for value in query_vector
        ):
            record_semantic_answer_cache_operation(self.mode, "write", "invalid_vector")
            return False
        expires_at = int(self._now()) + self._ttl_seconds
        payload = self._encode_payload(context, message, completion, expires_at)
        try:
            await self._redis.set(context.redis_key, payload, ex=self._ttl_seconds)
            record_redis_operation("semantic-answer", "set", "success")
            record_semantic_answer_cache_operation(self.mode, "write", "redis_write")
        except Exception:
            record_redis_operation("semantic-answer", "set", "error")
            record_semantic_answer_cache_operation(self.mode, "write", "error")
            return False
        try:
            await self._ensure_collection()
            await self._qdrant.upsert(
                collection_name=self._collection,
                points=[
                    models.PointStruct(
                        id=context.point_id,
                        vector={self._vector_name: list(query_vector)},
                        payload={
                            "scope_hash": context.scope_hash,
                            "guard_hash": context.guard_hash,
                            "query_hash": context.query_hash,
                            "expires_at": expires_at,
                            "redis_identity": context.redis_key,
                        },
                    )
                ],
                wait=True,
            )
            record_semantic_answer_cache_operation(self.mode, "write", "qdrant_write")
        except Exception:
            record_semantic_answer_cache_operation(self.mode, "write", "qdrant_error")
        return True

    async def compare_shadow(
        self,
        *,
        candidate: SemanticCacheCandidate,
        fresh: AssistantMessage,
        embedder: AnswerEmbedder,
    ) -> None:
        if self.mode != "shadow" or not self.is_response_eligible(fresh):
            return
        try:
            candidate_ids = {
                (citation.document_id, citation.unit_id or str(citation.chunk_index))
                for citation in candidate.message.citations
            }
            fresh_ids = {
                (citation.document_id, citation.unit_id or str(citation.chunk_index))
                for citation in fresh.citations
            }
            union = candidate_ids | fresh_ids
            overlap = len(candidate_ids & fresh_ids) / len(union) if union else 1.0
            candidate_vector, fresh_vector = await asyncio.gather(
                embedder.embed_query(candidate.message.content),
                embedder.embed_query(fresh.content),
            )
            answer_similarity = _cosine(candidate_vector, fresh_vector)
            observe_semantic_answer_shadow(
                self.mode,
                candidate.tier,
                citation_overlap=overlap,
                answer_similarity=answer_similarity,
            )
            record_semantic_answer_cache_operation(self.mode, candidate.tier, "shadow_compared")
        except Exception:
            record_semantic_answer_cache_operation(
                self.mode, candidate.tier, "shadow_compare_error"
            )

    def record_served(self, candidate: SemanticCacheCandidate) -> None:
        record_semantic_answer_avoided(
            self.mode,
            candidate.tier,
            input_tokens=candidate.input_tokens,
            output_tokens=candidate.output_tokens,
        )

    def is_response_eligible(self, message: AssistantMessage) -> bool:
        if not message.content.strip() or not message.citations:
            return False
        if _NO_INFORMATION.search(message.content):
            return False
        citation_markers = {f"[{citation.id}]" for citation in message.citations}
        return any(marker in message.content for marker in citation_markers)

    async def close(self) -> None:
        if self._owns_qdrant:
            await self._qdrant.close()

    async def _lookup_redis(
        self,
        *,
        context: SemanticCacheContext,
        query_hash: str,
        redis_key: str,
        tier: str,
        similarity: float | None,
        nested: bool = False,
    ) -> SemanticCacheCandidate | None:
        started = time.perf_counter()
        outcome = "miss"
        try:
            value = await self._redis.get(redis_key)
            record_redis_operation("semantic-answer", "get", "success")
            if value is None:
                record_semantic_answer_cache_operation(
                    self.mode, tier, "missing_payload" if nested else "miss"
                )
                return None
            try:
                message, input_tokens, output_tokens = self._decode_payload(
                    bytes(value), context=context, query_hash=query_hash
                )
            except ValueError:
                record_semantic_answer_cache_operation(self.mode, tier, "payload_error")
                try:
                    await self._redis.delete(redis_key)
                    record_redis_operation("semantic-answer", "delete", "success")
                except Exception:
                    record_redis_operation("semantic-answer", "delete", "error")
                outcome = "payload_error"
                return None
            if not nested:
                record_semantic_answer_cache_operation(self.mode, tier, "candidate")
            record_semantic_answer_cache_operation(self.mode, tier, "hit")
            outcome = "hit"
            return SemanticCacheCandidate(
                tier=tier,
                message=message,
                input_tokens=input_tokens,
                output_tokens=output_tokens,
                similarity=similarity,
            )
        except Exception:
            record_redis_operation("semantic-answer", "get", "error")
            record_semantic_answer_cache_operation(self.mode, tier, "error")
            outcome = "error"
            return None
        finally:
            if not nested:
                observe_semantic_answer_cache_lookup(
                    self.mode, tier, outcome, time.perf_counter() - started
                )

    def _encode_payload(
        self,
        context: SemanticCacheContext,
        message: AssistantMessage,
        completion: ModelCompletion,
        expires_at: int,
    ) -> bytes:
        return json.dumps(
            {
                "schema_version": SEMANTIC_ANSWER_CACHE_SCHEMA_VERSION,
                "scope_hash": context.scope_hash,
                "guard_hash": context.guard_hash,
                "query_hash": context.query_hash,
                "revision": context.revision,
                "visible_document_hash": context.visible_document_hash,
                "answer": message.content,
                "citations": [asdict(citation) for citation in message.citations],
                "input_tokens": completion.input_tokens,
                "output_tokens": completion.output_tokens,
                "expires_at": expires_at,
            },
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
            allow_nan=False,
        ).encode("utf-8")

    def _decode_payload(
        self,
        payload: bytes,
        *,
        context: SemanticCacheContext,
        query_hash: str,
    ) -> tuple[AssistantMessage, int | None, int | None]:
        try:
            raw = json.loads(payload.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ValueError("Semantic answer payload is not valid JSON") from exc
        expected = {
            "schema_version",
            "scope_hash",
            "guard_hash",
            "query_hash",
            "revision",
            "visible_document_hash",
            "answer",
            "citations",
            "input_tokens",
            "output_tokens",
            "expires_at",
        }
        if not isinstance(raw, dict) or set(raw) != expected:
            raise ValueError("Semantic answer payload is malformed")
        if raw["schema_version"] != SEMANTIC_ANSWER_CACHE_SCHEMA_VERSION:
            raise ValueError("Semantic answer payload schema is unsupported")
        if (
            raw["scope_hash"] != context.scope_hash
            or raw["guard_hash"] != context.guard_hash
            or raw["query_hash"] != query_hash
            or raw["revision"] != context.revision
            or raw["visible_document_hash"] != context.visible_document_hash
        ):
            raise ValueError("Semantic answer payload scope does not match")
        expires_at = raw["expires_at"]
        if isinstance(expires_at, bool) or not isinstance(expires_at, int):
            raise ValueError("Semantic answer expiry is malformed")
        if expires_at <= int(self._now()):
            raise ValueError("Semantic answer payload is expired")
        answer = raw["answer"]
        if not isinstance(answer, str) or not answer.strip() or _NO_INFORMATION.search(answer):
            raise ValueError("Semantic answer content is ineligible")
        citations_raw = raw["citations"]
        if (
            not isinstance(citations_raw, list)
            or not citations_raw
            or len(citations_raw) > MAX_CACHED_CITATIONS
        ):
            raise ValueError("Semantic answer citations are malformed")
        citations = [
            self._decode_citation(item, context.visible_document_ids) for item in citations_raw
        ]
        message = AssistantMessage(role="assistant", content=answer, citations=citations)
        if not self.is_response_eligible(message):
            raise ValueError("Semantic answer is not grounded")
        return (
            message,
            self._optional_token_count(raw["input_tokens"]),
            self._optional_token_count(raw["output_tokens"]),
        )

    def _decode_citation(self, item: Any, visible_document_ids: tuple[str, ...] | None) -> Citation:
        fields = {
            "id",
            "document_id",
            "source_name",
            "page_number",
            "chunk_index",
            "score",
            "snippet",
            "unit_id",
            "modality",
            "section_path",
            "block_type",
            "sheet_name",
            "cell_range",
            "table_id",
        }
        if not isinstance(item, dict) or set(item) != fields:
            raise ValueError("Semantic answer citation is malformed")
        required_strings = ("id", "document_id", "source_name", "snippet")
        if any(not isinstance(item[field], str) or not item[field] for field in required_strings):
            raise ValueError("Semantic answer citation string is malformed")
        if not re.fullmatch(r"S[1-9]\d*", item["id"]):
            raise ValueError("Semantic answer citation identifier is malformed")
        document_id = item["document_id"]
        if visible_document_ids is not None and document_id not in set(visible_document_ids):
            raise ValueError("Semantic answer citation is outside the visible document scope")
        page_number = item["page_number"]
        if page_number is not None and (
            isinstance(page_number, bool) or not isinstance(page_number, int) or page_number < 1
        ):
            raise ValueError("Semantic answer citation page is malformed")
        chunk_index = item["chunk_index"]
        if isinstance(chunk_index, bool) or not isinstance(chunk_index, int) or chunk_index < 0:
            raise ValueError("Semantic answer citation chunk is malformed")
        score = item["score"]
        if isinstance(score, bool) or not isinstance(score, int | float):
            raise ValueError("Semantic answer citation score is malformed")
        score_value = float(score)
        if not math.isfinite(score_value):
            raise ValueError("Semantic answer citation score is not finite")
        optional_strings = (
            "unit_id",
            "modality",
            "block_type",
            "sheet_name",
            "cell_range",
            "table_id",
        )
        if any(
            item[field] is not None and not isinstance(item[field], str)
            for field in optional_strings
        ):
            raise ValueError("Semantic answer optional citation field is malformed")
        section_path = item["section_path"]
        if not isinstance(section_path, list) or not all(
            isinstance(value, str) for value in section_path
        ):
            raise ValueError("Semantic answer citation section path is malformed")
        return Citation(
            id=item["id"],
            document_id=document_id,
            source_name=item["source_name"],
            page_number=page_number,
            chunk_index=chunk_index,
            score=score_value,
            snippet=item["snippet"],
            unit_id=item["unit_id"],
            modality=item["modality"],
            section_path=tuple(section_path),
            block_type=item["block_type"],
            sheet_name=item["sheet_name"],
            cell_range=item["cell_range"],
            table_id=item["table_id"],
        )

    def _validate_qdrant_payload(
        self, payload: Any, context: SemanticCacheContext
    ) -> dict[str, Any]:
        fields = {
            "scope_hash",
            "guard_hash",
            "query_hash",
            "expires_at",
            "redis_identity",
        }
        if not isinstance(payload, dict) or set(payload) != fields:
            raise ValueError("Semantic point payload is malformed")
        if payload["scope_hash"] != context.scope_hash:
            raise ValueError("Semantic point scope does not match")
        if payload["guard_hash"] != context.guard_hash:
            raise ValueError("Semantic point guard does not match")
        query_hash = payload["query_hash"]
        if not isinstance(query_hash, str) or not re.fullmatch(r"[0-9a-f]{64}", query_hash):
            raise ValueError("Semantic point query hash is malformed")
        expires_at = payload["expires_at"]
        if (
            isinstance(expires_at, bool)
            or not isinstance(expires_at, int)
            or expires_at <= int(self._now())
        ):
            raise ValueError("Semantic point is expired")
        expected_key = self._redis_key(context.scope_hash, query_hash)
        if payload["redis_identity"] != expected_key:
            raise ValueError("Semantic point Redis identity does not match")
        return payload

    async def _ensure_collection(self) -> None:
        if not await self._qdrant.collection_exists(self._collection):
            await self._qdrant.create_collection(
                collection_name=self._collection,
                vectors_config={
                    self._vector_name: models.VectorParams(
                        size=self._dimension, distance=models.Distance.COSINE
                    )
                },
            )
            for field_name, schema in (
                ("scope_hash", models.PayloadSchemaType.KEYWORD),
                ("guard_hash", models.PayloadSchemaType.KEYWORD),
                ("expires_at", models.PayloadSchemaType.INTEGER),
            ):
                await self._qdrant.create_payload_index(
                    collection_name=self._collection,
                    field_name=field_name,
                    field_schema=schema,
                    wait=True,
                )
            return
        info = await self._qdrant.get_collection(self._collection)
        vectors = info.config.params.vectors
        if not isinstance(vectors, dict):
            raise ValueError("Semantic answer collection must use named vectors")
        vector = vectors.get(self._vector_name)
        if vector is None or int(vector.size) != self._dimension:
            raise ValueError("Semantic answer collection vector configuration does not match")

    def _redis_key(self, scope_hash: str, query_hash: str) -> str:
        return f"{self._prefix}:semantic-answer:{scope_hash}:query:{query_hash}"

    @staticmethod
    def point_id(scope_hash: str, query_hash: str) -> str:
        return str(uuid5(NAMESPACE_URL, f"semantic-answer:{scope_hash}:{query_hash}"))

    @staticmethod
    def _optional_token_count(value: Any) -> int | None:
        if value is None:
            return None
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            raise ValueError("Semantic answer token count is malformed")
        return value


async def cleanup_expired_semantic_points(
    client: AsyncQdrantClient,
    *,
    collection: str,
    batch_size: int,
    max_batches: int = 10,
    apply: bool,
    now: int | None = None,
) -> dict[str, int | bool]:
    if batch_size <= 0 or max_batches <= 0:
        raise ValueError("Cleanup batch and run limits must be positive")
    if not await client.collection_exists(collection):
        return {"scanned": 0, "expired": 0, "deleted": 0, "batches": 0, "apply": apply}
    current_time = int(time.time()) if now is None else now
    offset: Any = None
    scanned = expired = deleted = batches = 0
    for _ in range(max_batches):
        response = await client.scroll(
            collection_name=collection,
            scroll_filter=models.Filter(
                must=[models.FieldCondition(key="expires_at", range=models.Range(lte=current_time))]
            ),
            limit=batch_size,
            offset=offset,
            with_payload=False,
            with_vectors=False,
        )
        if isinstance(response, tuple):
            points, next_offset = response
        else:
            points = response.points
            next_offset = getattr(response, "next_page_offset", None)
        points = list(points)
        if not points:
            break
        batches += 1
        scanned += len(points)
        expired += len(points)
        if apply:
            await client.delete(
                collection_name=collection,
                points_selector=models.PointIdsList(points=[point.id for point in points]),
                wait=True,
            )
            deleted += len(points)
        if next_offset is None or len(points) < batch_size:
            break
        offset = next_offset
    return {
        "scanned": scanned,
        "expired": expired,
        "deleted": deleted,
        "batches": batches,
        "apply": apply,
    }


class SemanticAnswerCacheMaintenance:
    def __init__(self, settings: GenerationConfig) -> None:
        self._settings = settings

    async def cleanup_expired(
        self, *, max_batches: int, apply: bool
    ) -> GenerationCacheCleanupResult:
        client = AsyncQdrantClient(
            url=self._settings.QDRANT_URL,
            api_key=self._settings.QDRANT_API_KEY or None,
            check_compatibility=False,
        )
        try:
            result = await cleanup_expired_semantic_points(
                client,
                collection=self._settings.SEMANTIC_ANSWER_CACHE_COLLECTION,
                batch_size=self._settings.SEMANTIC_ANSWER_CACHE_CLEANUP_BATCH_SIZE,
                max_batches=min(max(max_batches, 1), 10),
                apply=apply,
            )
            return GenerationCacheCleanupResult(
                scanned=int(result["scanned"]),
                expired=int(result["expired"]),
                deleted=int(result["deleted"]),
                batches=int(result["batches"]),
                apply=bool(result["apply"]),
            )
        finally:
            await client.close()
