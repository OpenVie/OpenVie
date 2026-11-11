#!/usr/bin/env python3
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

"""
CI Gate G-1 Validator:
Asserts that changes inside rag-chatbot-fastapi/app/modules/{retrieval,index,graph,generation}/**
strictly conform to the approved G-1 whitelist.
"""

from __future__ import annotations

import subprocess
import sys

APPROVED_WHITELIST = {
    "rag-chatbot-fastapi/app/modules/index/internal/qdrant_search.py",
    "rag-chatbot-fastapi/app/modules/index/internal/qdrant_commands.py",
    "rag-chatbot-fastapi/app/modules/index/api/__init__.py",
    "rag-chatbot-fastapi/app/modules/graph/internal/service.py",
    "rag-chatbot-fastapi/app/modules/graph/api/__init__.py",
    "rag-chatbot-fastapi/app/modules/graph/transport/http.py",
    "rag-chatbot-fastapi/app/modules/retrieval/api/__init__.py",
    "rag-chatbot-fastapi/app/modules/retrieval/internal/retrieval.py",
    "rag-chatbot-fastapi/app/modules/retrieval/internal/config.py",
    "rag-chatbot-fastapi/app/modules/retrieval/internal/cache.py",
    "rag-chatbot-fastapi/app/modules/generation/transport/grpc.py",
    "rag-chatbot-fastapi/app/modules/generation/api/__init__.py",
    "rag-chatbot-fastapi/app/modules/generation/internal/generator.py",
    "rag-chatbot-fastapi/app/modules/generation/internal/semantic_answer_cache.py",
}

MONITORED_PREFIXES = (
    "rag-chatbot-fastapi/app/modules/retrieval/",
    "rag-chatbot-fastapi/app/modules/index/",
    "rag-chatbot-fastapi/app/modules/graph/",
    "rag-chatbot-fastapi/app/modules/generation/",
)


def get_changed_files() -> list[str]:
    # Check unstaged and staged changes
    res = subprocess.run(["git", "status", "--porcelain"], capture_output=True, text=True, check=True)
    files = []
    for line in res.stdout.splitlines():
        if not line.strip():
            continue
        parts = line.strip().split(maxsplit=1)
        if len(parts) == 2:
            files.append(parts[1].replace("\\", "/"))
    return files


def main() -> int:
    changed = get_changed_files()
    violations: list[str] = []

    for file_path in changed:
        is_monitored = any(file_path.startswith(prefix) for prefix in MONITORED_PREFIXES)
        if is_monitored and file_path not in APPROVED_WHITELIST:
            violations.append(file_path)

    if violations:
        print("CI Gate G-1 VIOLATION: Unauthorized modifications to core RAG pipeline:")
        for v in violations:
            print(f"  - {v}")
        return 1

    print("CI Gate G-1 PASSED: All RAG module modifications match approved whitelist.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
