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
