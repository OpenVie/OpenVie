from prometheus_client import Counter, Gauge, Histogram

AI_EMBEDDING_SECONDS = Histogram(
    "cacanode_ai_embedding_seconds",
    "Time spent generating embeddings.",
    ["operation", "provider", "outcome"],
)

AI_EMBEDDING_REQUESTS_TOTAL = Counter(
    "cacanode_ai_embedding_requests_total",
    "Embedding provider HTTP requests.",
    ["operation", "provider", "outcome"],
)

AI_RETRIEVAL_SECONDS = Histogram(
    "cacanode_ai_retrieval_seconds",
    "Time spent retrieving chunks from vector storage.",
    ["provider", "outcome"],
)

AI_CHAT_MODEL_SECONDS = Histogram(
    "cacanode_ai_chat_model_seconds",
    "Time spent generating chat model answers.",
    ["provider", "model", "outcome"],
)

AI_RAG_ANSWER_SECONDS = Histogram(
    "cacanode_ai_rag_answer_seconds",
    "Time spent in RAG answer stages.",
    ["stage", "provider", "outcome"],
)

AI_CHAT_MODEL_TIMEOUTS_TOTAL = Counter(
    "cacanode_ai_chat_model_timeouts_total",
    "Total chat model generation timeouts.",
    ["provider", "model"],
)

AI_ROUTER_PROFILES_TOTAL = Counter(
    "cacanode_ai_router_profiles_total",
    "Retrieval queries routed to each deterministic profile.",
    ["profile"],
)

AI_RETRIEVAL_CHANNEL_SECONDS = Histogram(
    "cacanode_ai_retrieval_channel_seconds",
    "Time spent in each retrieval channel.",
    ["channel", "outcome"],
)

AI_RETRIEVAL_CHANNEL_RESULTS = Histogram(
    "cacanode_ai_retrieval_channel_results",
    "Candidate count returned by each retrieval channel.",
    ["channel"],
)

AI_FUSION_CANDIDATES = Histogram(
    "cacanode_ai_fusion_candidates",
    "Candidate count retained after weighted reciprocal-rank fusion.",
)

AI_RERANKER_SECONDS = Histogram(
    "cacanode_ai_reranker_seconds",
    "Time spent reranking fused retrieval candidates.",
    ["outcome"],
)

AI_RETRIEVAL_FALLBACKS_TOTAL = Counter(
    "cacanode_ai_retrieval_fallbacks_total",
    "Fail-open retrieval fallbacks by component.",
    ["component"],
)

AI_CONTEXT_UNITS = Histogram(
    "cacanode_ai_context_units",
    "Final cited context unit count.",
)

CACHE_OPERATIONS_TOTAL = Counter(
    "cacanode_cache_operations_total",
    "Cache operations by controlled cache and outcome.",
    ["service", "cache", "outcome"],
)

CACHE_OPERATION_SECONDS = Histogram(
    "cacanode_cache_operation_seconds",
    "Cache operation latency.",
    ["service", "cache", "operation"],
)

CACHE_PAYLOAD_BYTES = Histogram(
    "cacanode_cache_payload_bytes",
    "Cache payload size in bytes.",
    ["service", "cache"],
)

REDIS_OPERATIONS_TOTAL = Counter(
    "cacanode_redis_operations_total",
    "Raw Redis operations by controlled component and outcome.",
    ["service", "component", "operation", "outcome"],
)

CACHE_AUTHORITATIVE_SECONDS = Histogram(
    "cacanode_cache_authoritative_seconds",
    "Authoritative backend call count and latency for cache-aside loads.",
    ["service", "cache", "outcome"],
)

CACHE_AUTHORITATIVE_LOADS_IN_FLIGHT = Gauge(
    "cacanode_cache_authoritative_loads_in_flight",
    "Enabled cache misses or errors currently invoking an authoritative backend.",
    ["service", "cache"],
)

CACHE_SAME_KEY_OVERLAPS_TOTAL = Counter(
    "cacanode_cache_same_key_overlaps_total",
    "Authoritative loads that began while the same cache key was already loading.",
    ["service", "cache"],
)

CACHE_SAME_KEY_CONCURRENCY = Histogram(
    "cacanode_cache_same_key_concurrency",
    "Observed process-local authoritative concurrency for a hashed cache key.",
    ["service", "cache"],
    buckets=(1, 2, 3, 5, 10, 20, 50, 100),
)

SEMANTIC_ANSWER_CACHE_OPERATIONS_TOTAL = Counter(
    "cacanode_semantic_answer_cache_operations_total",
    "Semantic answer cache operations and controlled outcomes.",
    ["service", "mode", "tier", "outcome"],
)

SEMANTIC_ANSWER_CACHE_LOOKUP_SECONDS = Histogram(
    "cacanode_semantic_answer_cache_lookup_seconds",
    "Semantic answer cache lookup latency.",
    ["service", "mode", "tier", "outcome"],
)

SEMANTIC_ANSWER_CACHE_SIMILARITY = Histogram(
    "cacanode_semantic_answer_cache_similarity",
    "Accepted or rejected semantic candidate cosine similarity.",
    ["service", "mode", "tier"],
)

SEMANTIC_ANSWER_CACHE_SHADOW_CITATION_OVERLAP = Histogram(
    "cacanode_semantic_answer_cache_shadow_citation_overlap",
    "Citation-set overlap between a shadow candidate and a fresh answer.",
    ["service", "mode", "tier"],
)

SEMANTIC_ANSWER_CACHE_SHADOW_ANSWER_SIMILARITY = Histogram(
    "cacanode_semantic_answer_cache_shadow_answer_similarity",
    "Answer-embedding cosine similarity for shadow comparisons.",
    ["service", "mode", "tier"],
)

SEMANTIC_ANSWER_CACHE_AVOIDED_LLM_REQUESTS_TOTAL = Counter(
    "cacanode_semantic_answer_cache_avoided_llm_requests_total",
    "LLM requests avoided by served answer-cache hits.",
    ["service", "mode", "tier"],
)

SEMANTIC_ANSWER_CACHE_AVOIDED_TOKENS_TOTAL = Counter(
    "cacanode_semantic_answer_cache_avoided_tokens_total",
    "Source-model tokens avoided by served answer-cache hits.",
    ["service", "mode", "tier", "token_type"],
)

_SERVICE = "fastapi"
_CACHE_NAMES = {
    "foundation",
    "integration-token",
    "business-read",
    "embedding",
    "retrieval",
    "semantic-answer",
}
_REDIS_COMPONENTS = {"cache", "integration-rate-limit", "semantic-answer"}
_SEMANTIC_MODES = {"shadow", "serve"}
_SEMANTIC_TIERS = {"exact", "semantic", "write", "cleanup"}


def record_cache_operation(cache_name: str, outcome: str) -> None:
    controlled_name = cache_name if cache_name in _CACHE_NAMES else "unknown"
    CACHE_OPERATIONS_TOTAL.labels(_SERVICE, controlled_name, outcome).inc()


def observe_cache_duration(cache_name: str, operation: str, seconds: float) -> None:
    controlled_name = cache_name if cache_name in _CACHE_NAMES else "unknown"
    CACHE_OPERATION_SECONDS.labels(_SERVICE, controlled_name, operation).observe(seconds)


def observe_cache_payload(cache_name: str, size: int) -> None:
    controlled_name = cache_name if cache_name in _CACHE_NAMES else "unknown"
    CACHE_PAYLOAD_BYTES.labels(_SERVICE, controlled_name).observe(size)


def record_redis_operation(component: str, operation: str, outcome: str) -> None:
    controlled_component = component if component in _REDIS_COMPONENTS else "unknown"
    REDIS_OPERATIONS_TOTAL.labels(_SERVICE, controlled_component, operation, outcome).inc()


def observe_authoritative_duration(cache_name: str, outcome: str, seconds: float) -> None:
    controlled_name = cache_name if cache_name in _CACHE_NAMES else "unknown"
    controlled_outcome = outcome if outcome in {"success", "not_found", "error"} else "error"
    CACHE_AUTHORITATIVE_SECONDS.labels(_SERVICE, controlled_name, controlled_outcome).observe(
        seconds
    )


def record_authoritative_load_started(cache_name: str, same_key_concurrency: int) -> None:
    controlled_name = cache_name if cache_name in _CACHE_NAMES else "unknown"
    CACHE_AUTHORITATIVE_LOADS_IN_FLIGHT.labels(_SERVICE, controlled_name).inc()
    CACHE_SAME_KEY_CONCURRENCY.labels(_SERVICE, controlled_name).observe(same_key_concurrency)
    if same_key_concurrency > 1:
        CACHE_SAME_KEY_OVERLAPS_TOTAL.labels(_SERVICE, controlled_name).inc()


def record_authoritative_load_finished(cache_name: str) -> None:
    controlled_name = cache_name if cache_name in _CACHE_NAMES else "unknown"
    CACHE_AUTHORITATIVE_LOADS_IN_FLIGHT.labels(_SERVICE, controlled_name).dec()


def record_semantic_answer_cache_operation(mode: str, tier: str, outcome: str) -> None:
    controlled_mode = mode if mode in _SEMANTIC_MODES else "off"
    controlled_tier = tier if tier in _SEMANTIC_TIERS else "semantic"
    SEMANTIC_ANSWER_CACHE_OPERATIONS_TOTAL.labels(
        _SERVICE, controlled_mode, controlled_tier, outcome
    ).inc()


def observe_semantic_answer_cache_lookup(
    mode: str, tier: str, outcome: str, seconds: float
) -> None:
    controlled_mode = mode if mode in _SEMANTIC_MODES else "off"
    controlled_tier = tier if tier in _SEMANTIC_TIERS else "semantic"
    SEMANTIC_ANSWER_CACHE_LOOKUP_SECONDS.labels(
        _SERVICE, controlled_mode, controlled_tier, outcome
    ).observe(seconds)


def observe_semantic_answer_similarity(mode: str, tier: str, similarity: float) -> None:
    controlled_mode = mode if mode in _SEMANTIC_MODES else "off"
    controlled_tier = tier if tier in _SEMANTIC_TIERS else "semantic"
    SEMANTIC_ANSWER_CACHE_SIMILARITY.labels(_SERVICE, controlled_mode, controlled_tier).observe(
        similarity
    )


def observe_semantic_answer_shadow(
    mode: str,
    tier: str,
    *,
    citation_overlap: float,
    answer_similarity: float,
) -> None:
    controlled_mode = mode if mode in _SEMANTIC_MODES else "off"
    controlled_tier = tier if tier in _SEMANTIC_TIERS else "semantic"
    labels = (_SERVICE, controlled_mode, controlled_tier)
    SEMANTIC_ANSWER_CACHE_SHADOW_CITATION_OVERLAP.labels(*labels).observe(citation_overlap)
    SEMANTIC_ANSWER_CACHE_SHADOW_ANSWER_SIMILARITY.labels(*labels).observe(answer_similarity)


def record_semantic_answer_avoided(
    mode: str,
    tier: str,
    *,
    input_tokens: int | None,
    output_tokens: int | None,
) -> None:
    controlled_mode = mode if mode in _SEMANTIC_MODES else "off"
    controlled_tier = tier if tier in _SEMANTIC_TIERS else "semantic"
    labels = (_SERVICE, controlled_mode, controlled_tier)
    SEMANTIC_ANSWER_CACHE_AVOIDED_LLM_REQUESTS_TOTAL.labels(*labels).inc()
    for token_type, value in (("input", input_tokens), ("output", output_tokens)):
        if value is not None and value >= 0:
            SEMANTIC_ANSWER_CACHE_AVOIDED_TOKENS_TOTAL.labels(*labels, token_type).inc(value)
