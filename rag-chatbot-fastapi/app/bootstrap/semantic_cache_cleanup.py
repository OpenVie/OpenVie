from __future__ import annotations

import argparse
import asyncio
import json
from collections.abc import Sequence

from app.bootstrap.configuration import generation_config
from app.bootstrap.settings import settings
from app.maintenance.cleanup_semantic_answer_cache import run
from app.modules.generation.internal.semantic_answer_cache import (
    SemanticAnswerCacheMaintenance,
)


def main(argv: Sequence[str] | None = None) -> None:
    parser = argparse.ArgumentParser(
        description="Bounded cleanup for expired semantic answer-cache Qdrant points."
    )
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--max-batches", type=int, default=10)
    args = parser.parse_args(argv)
    result = asyncio.run(
        run(
            SemanticAnswerCacheMaintenance(generation_config(settings)),
            apply=bool(args.apply),
            max_batches=int(args.max_batches),
        )
    )
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
