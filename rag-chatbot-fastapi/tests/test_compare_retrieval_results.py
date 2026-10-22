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

from app.maintenance.compare_retrieval_results import compare


def test_comparison_requires_identical_rankings() -> None:
    dataset = [{"id": "q1", "relevant_unit_ids": ["u1"]}]

    result = compare(
        dataset,
        {"q1": {"unit_ids": ["u1", "u2"]}},
        {"q1": {"unit_ids": ["u2", "u1"]}},
    )

    assert result["passed"] is False
    assert result["ranking_mismatches"] == ["q1"]


def test_comparison_accepts_identical_rankings_without_quality_regression() -> None:
    dataset = [{"id": "q1", "relevant_unit_ids": ["u1"]}]
    results = {"q1": {"unit_ids": ["u1"], "latency_ms": 10}}

    result = compare(dataset, results, results)

    assert result["passed"] is True
    assert result["quality_regressions"] == {}
