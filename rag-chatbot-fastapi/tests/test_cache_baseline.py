# Copyright 2026 LinkedNodeDigital
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import httpx
import pytest

from app.maintenance.cache_baseline import METRIC_PREFIXES, Probe, _scrape_metrics, summarize


def test_baseline_summary_reports_statuses_and_nearest_rank_percentiles() -> None:
    samples = [("200", float(value)) for value in range(1, 31)]
    samples.append(("503", 31.0))

    summary = summarize(samples)

    assert summary == {
        "status_counts": {"200": 30, "503": 1},
        "latency_ms": {"min": 1.0, "p50": 16.0, "p95": 30.0, "max": 31.0},
    }


def test_baseline_captures_cache_authoritative_and_redis_metric_families() -> None:
    assert "openvie_cache_" in METRIC_PREFIXES
    assert "openvie_redis_" in METRIC_PREFIXES
    assert "openvie_ai_embedding_requests_total" in METRIC_PREFIXES
    assert "openvie_ai_retrieval_channel_seconds" in METRIC_PREFIXES
    assert "openvie_ai_reranker_seconds" in METRIC_PREFIXES
    assert "openvie_semantic_answer_cache_" in METRIC_PREFIXES


def test_probe_can_represent_spring_business_cache_requests() -> None:
    probe = Probe(
        "analytics",
        "GET",
        "http://localhost:8080/api/v1/analytics",
        {"Authorization": "Bearer redacted"},
        params={"scope": "CUSTOMER", "days": "30"},
    )

    assert probe.params == {"scope": "CUSTOMER", "days": "30"}


@pytest.mark.asyncio
async def test_metrics_scrape_follows_fastapi_mount_redirect() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/metrics":
            return httpx.Response(307, headers={"Location": "/metrics/"})
        return httpx.Response(
            200,
            text=(
                "# HELP ignored ignored\n"
                'openvie_ai_embedding_requests_total{outcome="success"} 4\n'
                "python_gc_objects_collected_total 10\n"
            ),
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        snapshot = await _scrape_metrics(client, "http://inference.test/metrics")

    assert snapshot == {
        'openvie_ai_embedding_requests_total{outcome="success"}': 4.0,
    }
