from __future__ import annotations

import argparse
import asyncio
import json
import math
import os
import time
from collections import Counter
from dataclasses import dataclass
from typing import Any

import httpx
from redis.asyncio import Redis
from redis.exceptions import RedisError

METRIC_PREFIXES = (
    "cacanode_ai_embedding_seconds",
    "cacanode_ai_embedding_requests_total",
    "cacanode_ai_retrieval_seconds",
    "cacanode_ai_retrieval_channel_seconds",
    "cacanode_ai_reranker_seconds",
    "cacanode_ai_rag_answer_seconds",
    "cacanode_cache_",
    "cacanode_redis_",
    "cacanode_semantic_answer_cache_",
)


@dataclass(frozen=True)
class Probe:
    name: str
    method: str
    url: str
    headers: dict[str, str]
    params: dict[str, str] | None = None
    json_body: dict[str, Any] | None = None


def _required_env(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise SystemExit(f"{name} must be set")
    return value


def _percentile(sorted_values: list[float], percentile: float) -> float:
    if not sorted_values:
        return 0.0
    index = max(0, math.ceil(percentile * len(sorted_values)) - 1)
    return sorted_values[index]


def summarize(samples: list[tuple[str, float]]) -> dict[str, Any]:
    latencies = sorted(latency for _, latency in samples)
    statuses = Counter(status for status, _ in samples)
    return {
        "status_counts": dict(sorted(statuses.items())),
        "latency_ms": {
            "min": round(latencies[0], 3) if latencies else 0.0,
            "p50": round(_percentile(latencies, 0.50), 3),
            "p95": round(_percentile(latencies, 0.95), 3),
            "max": round(latencies[-1], 3) if latencies else 0.0,
        },
    }


async def _request(
    client: httpx.AsyncClient, probe: Probe
) -> tuple[str, float, httpx.Response | None]:
    started = time.perf_counter()
    try:
        response = await client.request(
            probe.method,
            probe.url,
            headers=probe.headers,
            params=probe.params,
            json=probe.json_body,
        )
        return str(response.status_code), (time.perf_counter() - started) * 1000, response
    except httpx.HTTPError:
        return "network_error", (time.perf_counter() - started) * 1000, None


async def _run_probe(
    client: httpx.AsyncClient,
    probe: Probe,
    *,
    warmups: int,
    samples: int,
) -> dict[str, Any]:
    for _ in range(warmups):
        await _request(client, probe)
    measured = []
    for _ in range(samples):
        status, latency, _ = await _request(client, probe)
        measured.append((status, latency))
    return summarize(measured)


async def _run_external_auth_probe(
    client: httpx.AsyncClient,
    probe: Probe,
    *,
    warmups: int,
    samples: int,
) -> dict[str, Any]:
    measured: list[tuple[str, float]] = []
    created_sessions: list[str] = []
    try:
        for index in range(warmups + samples):
            status, latency, response = await _request(client, probe)
            if index >= warmups:
                measured.append((status, latency))
            if response is not None and response.is_success:
                try:
                    session_id = str(response.json()["id"])
                    created_sessions.append(session_id)
                except (KeyError, TypeError, ValueError):
                    pass
    finally:
        for session_id in created_sessions:
            await _request(
                client,
                Probe(
                    "integration_session_cleanup",
                    "DELETE",
                    f"{probe.url}/{session_id}",
                    probe.headers,
                ),
            )
    return summarize(measured)


async def _run_optional_rag(
    client: httpx.AsyncClient,
    *,
    ai_base_url: str,
    headers: dict[str, str],
    chatbot_id: str,
    knowledge_base_id: str,
    query: str,
    warmups: int,
    samples: int,
) -> dict[str, Any]:
    create_probe = Probe(
        "rag_session_create",
        "POST",
        f"{ai_base_url}/api/v1/chat/sessions",
        headers,
        json_body={
            "chatbot_id": chatbot_id,
            "knowledge_base_id": knowledge_base_id,
            "locale": "vi-VN",
        },
    )
    create_status, create_latency, response = await _request(client, create_probe)
    result: dict[str, Any] = {
        "create": summarize([(create_status, create_latency)]),
        "repeated_query": summarize([]),
    }
    if response is None or not response.is_success:
        return result
    try:
        session_id = str(response.json()["id"])
    except (KeyError, TypeError, ValueError):
        return result
    try:
        submit_probe = Probe(
            "rag_repeated_query",
            "POST",
            f"{ai_base_url}/api/v1/chat/sessions/{session_id}/messages",
            headers,
            json_body={"content": query},
        )
        measured: list[tuple[str, float]] = []
        for index in range(warmups + samples):
            submit_status, submit_latency, _ = await _request(client, submit_probe)
            if index >= warmups:
                measured.append((submit_status, submit_latency))
        result["repeated_query"] = summarize(measured)
    finally:
        await _request(
            client,
            Probe(
                "rag_session_cleanup",
                "DELETE",
                f"{ai_base_url}/api/v1/chat/playground/sessions/{session_id}",
                headers,
            ),
        )
    return result


async def _scrape_metrics(
    client: httpx.AsyncClient,
    url: str,
    headers: dict[str, str] | None = None,
) -> dict[str, float]:
    try:
        response = await client.get(url, headers=headers, follow_redirects=True)
        response.raise_for_status()
    except httpx.HTTPError:
        return {}
    snapshot: dict[str, float] = {}
    for line in response.text.splitlines():
        if line.startswith("#") or not line.startswith(METRIC_PREFIXES):
            continue
        try:
            sample, value = line.rsplit(" ", 1)
            snapshot[sample] = float(value)
        except ValueError:
            continue
    return dict(sorted(snapshot.items()))


async def _redis_snapshot() -> dict[str, int] | None:
    redis_url = os.getenv("BASELINE_REDIS_URL", "").strip()
    if not redis_url:
        return None
    client = Redis.from_url(
        redis_url,
        decode_responses=False,
        socket_connect_timeout=1,
        socket_timeout=1,
    )
    try:
        memory = await client.info("memory")
        stats = await client.info("stats")
        return {
            "used_memory_bytes": int(memory.get("used_memory", 0)),
            "key_count": int(await client.dbsize()),
            "evicted_keys": int(stats.get("evicted_keys", 0)),
            "keyspace_hits": int(stats.get("keyspace_hits", 0)),
            "keyspace_misses": int(stats.get("keyspace_misses", 0)),
        }
    except (RedisError, OSError, ValueError, TypeError):
        return None
    finally:
        await client.aclose()


async def run(args: argparse.Namespace) -> dict[str, Any]:
    access_token = _required_env("BASELINE_ACCESS_TOKEN")
    integration_token = _required_env("BASELINE_INTEGRATION_TOKEN")
    knowledge_base_id = _required_env("BASELINE_KNOWLEDGE_BASE_ID")
    business_base_url = os.getenv("BASELINE_BUSINESS_API_BASE_URL", "http://localhost:8080").rstrip(
        "/"
    )
    ai_base_url = business_base_url
    inference_metrics_url = os.getenv(
        "BASELINE_INFERENCE_METRICS_URL", "http://localhost:18000/metrics/"
    )
    access_headers = {"Authorization": f"Bearer {access_token}"}
    integration_headers = {"Authorization": f"Bearer {integration_token}"}
    probes = [
        Probe(
            "widget_configuration",
            "GET",
            f"{business_base_url}/api/v1/tenants/me/integrations/widget",
            access_headers,
        ),
        Probe(
            "customer_answer_prompt",
            "GET",
            f"{business_base_url}/api/v1/tenants/me/customer-answer-prompt",
            access_headers,
        ),
        Probe(
            "billing_account",
            "GET",
            f"{business_base_url}/api/v1/billing/account",
            access_headers,
        ),
        Probe(
            "dashboard_summary",
            "GET",
            f"{business_base_url}/api/v1/dashboard/summary",
            access_headers,
        ),
        Probe(
            "analytics",
            "GET",
            f"{business_base_url}/api/v1/analytics",
            access_headers,
            params={"scope": "CUSTOMER", "days": "30"},
        ),
        Probe(
            "tenant_workspace",
            "GET",
            f"{business_base_url}/api/v1/tenants/me/workspace",
            access_headers,
        ),
        Probe(
            "user_directory",
            "GET",
            f"{business_base_url}/api/v1/users/directory",
            access_headers,
        ),
        Probe(
            "document_list",
            "GET",
            f"{business_base_url}/api/v1/documents",
            access_headers,
            params={"knowledgeBaseId": knowledge_base_id},
        ),
    ]
    timeout = httpx.Timeout(args.timeout_seconds)
    async with httpx.AsyncClient(timeout=timeout) as client:
        metrics_before = await _scrape_metrics(client, inference_metrics_url)
        spring_metrics_before = await _scrape_metrics(
            client,
            f"{business_base_url}/actuator/prometheus",
            access_headers,
        )
        redis_before = await _redis_snapshot()
        results = {
            probe.name: await _run_probe(client, probe, warmups=args.warmups, samples=args.samples)
            for probe in probes
        }
        spring_metrics_after = await _scrape_metrics(
            client,
            f"{business_base_url}/actuator/prometheus",
            access_headers,
        )
        redis_after = await _redis_snapshot()
        external_probe = Probe(
            "integration_authentication",
            "POST",
            f"{ai_base_url}/api/v1/external/chat/sessions",
            integration_headers,
            json_body={"locale": "vi-VN", "metadata": {}},
        )
        integration_metrics_before = await _scrape_metrics(client, inference_metrics_url)
        results[external_probe.name] = await _run_external_auth_probe(
            client,
            external_probe,
            warmups=args.warmups,
            samples=args.samples,
        )
        integration_metrics_after = await _scrape_metrics(client, inference_metrics_url)
        optional_rag: dict[str, Any] | None = None
        embedding_cache_probe: dict[str, Any] | None = None
        rag_query = os.getenv("BASELINE_RAG_QUERY", "").strip()
        chatbot_id = os.getenv("BASELINE_PLAYGROUND_CHATBOT_ID", "").strip()
        if rag_query and chatbot_id:
            embedding_metrics_before = await _scrape_metrics(client, inference_metrics_url)
            embedding_redis_before = await _redis_snapshot()
            optional_rag = await _run_optional_rag(
                client,
                ai_base_url=ai_base_url,
                headers=access_headers,
                chatbot_id=chatbot_id,
                knowledge_base_id=knowledge_base_id,
                query=rag_query,
                warmups=args.warmups,
                samples=args.samples,
            )
            embedding_metrics_after = await _scrape_metrics(client, inference_metrics_url)
            embedding_redis_after = await _redis_snapshot()
            embedding_cache_probe = {
                "metrics": {
                    "before": embedding_metrics_before,
                    "after": embedding_metrics_after,
                },
                "redis": {
                    "before": embedding_redis_before,
                    "after": embedding_redis_after,
                },
            }
        metrics_after = await _scrape_metrics(client, inference_metrics_url)
    return {
        "cache_enabled": os.getenv("BASELINE_CACHE_ENABLED", "false").lower() == "true",
        "warmups": args.warmups,
        "samples": args.samples,
        "endpoints": results,
        "optional_rag": optional_rag,
        "embedding_cache_probe": embedding_cache_probe,
        "ai_metrics": {"before": metrics_before, "after": metrics_after},
        "spring_metrics": {"before": spring_metrics_before, "after": spring_metrics_after},
        "redis": {"before": redis_before, "after": redis_after},
        "integration_auth_metrics": {
            "before": integration_metrics_before,
            "after": integration_metrics_after,
        },
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Capture a local cache comparison sample")
    parser.add_argument("--warmups", type=int, default=3)
    parser.add_argument("--samples", type=int, default=30)
    parser.add_argument("--timeout-seconds", type=float, default=120.0)
    args = parser.parse_args()
    if args.warmups < 0 or args.samples <= 0 or args.timeout_seconds <= 0:
        parser.error("warmups must be non-negative; samples and timeout must be positive")
    return args


def main() -> None:
    print(json.dumps(asyncio.run(run(parse_args())), sort_keys=True, separators=(",", ":")))


if __name__ == "__main__":
    main()
