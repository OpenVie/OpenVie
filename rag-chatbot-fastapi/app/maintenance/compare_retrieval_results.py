from __future__ import annotations

import argparse
import json
from collections.abc import Sequence
from pathlib import Path
from typing import Any

from app.maintenance.evaluate_retrieval import evaluate

QUALITY_METRICS = (
    "recall_at_5",
    "recall_at_10",
    "mrr",
    "ndcg_at_10",
    "no_answer_precision",
)


def _ranking(result: Any) -> list[str]:
    value = result.get("unit_ids", result) if isinstance(result, dict) else result
    return [str(item) for item in value]


def compare(
    dataset: list[dict[str, Any]],
    disabled: dict[str, Any],
    enabled: dict[str, Any],
    *,
    tolerance: float = 0.001,
) -> dict[str, Any]:
    ranking_mismatches = [
        str(example["id"])
        for example in dataset
        if _ranking(disabled.get(str(example["id"]), []))
        != _ranking(enabled.get(str(example["id"]), []))
    ]
    disabled_metrics = evaluate(dataset, disabled)
    enabled_metrics = evaluate(dataset, enabled)
    regressions = {
        metric: disabled_metrics[metric] - enabled_metrics[metric]
        for metric in QUALITY_METRICS
        if disabled_metrics[metric] - enabled_metrics[metric] > tolerance
    }
    return {
        "passed": not ranking_mismatches and not regressions,
        "tolerance": tolerance,
        "ranking_mismatches": ranking_mismatches,
        "quality_regressions": regressions,
        "cache_disabled": disabled_metrics,
        "cache_enabled": enabled_metrics,
    }


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Compare cache-disabled and cache-enabled retrieval rankings"
    )
    parser.add_argument("--dataset", required=True)
    parser.add_argument("--cache-disabled-results", required=True)
    parser.add_argument("--cache-enabled-results", required=True)
    parser.add_argument("--tolerance", type=float, default=0.001)
    args = parser.parse_args(argv)
    if args.tolerance < 0:
        parser.error("tolerance must be non-negative")
    dataset = json.loads(Path(args.dataset).read_text(encoding="utf-8"))
    disabled = json.loads(Path(args.cache_disabled_results).read_text(encoding="utf-8"))
    enabled = json.loads(Path(args.cache_enabled_results).read_text(encoding="utf-8"))
    comparison = compare(dataset, disabled, enabled, tolerance=args.tolerance)
    print(json.dumps(comparison, sort_keys=True))
    return 0 if comparison["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
