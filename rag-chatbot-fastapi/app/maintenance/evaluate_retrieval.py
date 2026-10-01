from __future__ import annotations

import argparse
import json
import math
from collections import Counter
from collections.abc import Sequence
from pathlib import Path
from statistics import mean
from typing import Any


def evaluate(dataset: list[dict[str, Any]], results: dict[str, Any]) -> dict[str, Any]:
    recalls_at_5: list[float] = []
    recalls_at_10: list[float] = []
    reciprocal_ranks: list[float] = []
    ndcgs: list[float] = []
    no_answer_predictions = 0
    correct_no_answer_predictions = 0
    channels: Counter[str] = Counter()
    latencies: list[float] = []
    for example in dataset:
        query_id = str(example["id"])
        relevant = set(str(item) for item in example.get("relevant_unit_ids", []))
        response = results.get(query_id, {})
        ranking = response.get("unit_ids", response) if isinstance(response, dict) else response
        ranking = [str(item) for item in ranking]
        if isinstance(response, dict):
            channels.update(str(item) for item in response.get("channels", []))
            if response.get("latency_ms") is not None:
                latencies.append(float(response["latency_ms"]))
        if not relevant:
            predicted_no_answer = not ranking
            no_answer_predictions += int(predicted_no_answer)
            correct_no_answer_predictions += int(predicted_no_answer)
            continue
        no_answer_predictions += int(not ranking)
        recalls_at_5.append(len(relevant.intersection(ranking[:5])) / len(relevant))
        recalls_at_10.append(len(relevant.intersection(ranking[:10])) / len(relevant))
        first_rank = next(
            (index for index, unit_id in enumerate(ranking, start=1) if unit_id in relevant),
            None,
        )
        reciprocal_ranks.append(1.0 / first_rank if first_rank else 0.0)
        dcg = sum(
            (1.0 / math.log2(index + 1))
            for index, unit_id in enumerate(ranking[:10], start=1)
            if unit_id in relevant
        )
        ideal = sum(1.0 / math.log2(index + 1) for index in range(1, min(len(relevant), 10) + 1))
        ndcgs.append(dcg / ideal if ideal else 0.0)
    return {
        "recall_at_5": mean(recalls_at_5) if recalls_at_5 else 0.0,
        "recall_at_10": mean(recalls_at_10) if recalls_at_10 else 0.0,
        "mrr": mean(reciprocal_ranks) if reciprocal_ranks else 0.0,
        "ndcg_at_10": mean(ndcgs) if ndcgs else 0.0,
        "no_answer_precision": (
            correct_no_answer_predictions / no_answer_predictions if no_answer_predictions else 0.0
        ),
        "channel_contribution": dict(sorted(channels.items())),
        "p95_latency_ms": _percentile(latencies, 0.95),
        "query_count": len(dataset),
    }


def _percentile(values: list[float], quantile: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, math.ceil(len(ordered) * quantile) - 1)
    return ordered[index]


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Score recorded retrieval rankings")
    parser.add_argument("--dataset", required=True)
    parser.add_argument("--results", required=True)
    parser.add_argument("--label", default="full-pipeline")
    args = parser.parse_args(argv)
    dataset = json.loads(Path(args.dataset).read_text(encoding="utf-8"))
    results = json.loads(Path(args.results).read_text(encoding="utf-8"))
    print(json.dumps({"label": args.label, **evaluate(dataset, results)}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
