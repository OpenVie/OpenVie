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
"""Add or verify Apache-2.0 license headers on OpenVie source files.

Usage:
    python3 scripts/license_headers.py --add     # prepend missing headers
    python3 scripts/license_headers.py --check   # CI gate: fail when missing

The canonical header is the standard ASF notice from the Apache-2.0 appendix,
adapted per language (``//`` for Java/TypeScript/protobuf, ``#`` for Python and
shell). Generated artifacts (``app/generated/**``, ``next-env.d.ts``) are
excluded on purpose: they are outputs, not authored source.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]

_BODY = (
    "Copyright 2026 LinkedNodeDigital",
    "",
    "Licensed under the Apache License, Version 2.0 (the \"License\");",
    "you may not use this file except in compliance with the License.",
    "You may obtain a copy of the License at",
    "",
    "    http://www.apache.org/licenses/LICENSE-2.0",
    "",
    "Unless required by applicable law or agreed to in writing, software",
    "distributed under the License is distributed on an \"AS IS\" BASIS,",
    "WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.",
    "See the License for the specific language governing permissions and",
    "limitations under the License.",
)

# Glob patterns (relative to the repo root) that select authored source files.
_TARGET_GLOBS: tuple[str, ...] = (
    "api/src/**/*.java",
    "rag-chatbot-fastapi/app/**/*.py",
    "rag-chatbot-fastapi/tests/**/*.py",
    "frontend/src/**/*.ts",
    "frontend/src/**/*.tsx",
    "frontend/e2e/**/*.ts",
    "frontend/*.config.ts",
    "frontend/scripts/**/*.ts",
    "proto/*.proto",
    "scripts/**/*.sh",
)

# Substrings that skip a matched path: generated artifacts and scratch output.
_EXCLUDED_PIECES: tuple[str, ...] = (
    "app/generated/",
    "__pycache__",
    "node_modules/",
    "playwright-report/",
    "test-results/",
)

# Generated-on-the-fly files must never gain a header.
_EXCLUDED_PATHS: frozenset[str] = frozenset({"frontend/next-env.d.ts"})


def header_lines(prefix: str) -> list[str]:
    """Header lines for a language; a blank body comment line spans a lone prefix."""
    return [f"{prefix} {line}" if line else prefix for line in _BODY]


def _source_files() -> list[Path]:
    collected: set[Path] = set()
    for pattern in _TARGET_GLOBS:
        for candidate in sorted(ROOT.glob(pattern)):
            if not candidate.is_file():
                continue
            rel = candidate.relative_to(ROOT).as_posix()
            if rel in _EXCLUDED_PATHS:
                continue
            if any(piece in rel + "/" for piece in _EXCLUDED_PIECES):
                continue
            collected.add(candidate)
    return sorted(collected)


def _eol_of(text: str) -> str:
    return "\r\n" if "\r\n" in text[:2048] else "\n"


def _has_header(text: str, prefix: str) -> bool:
    expected = "\n".join(header_lines(prefix))
    normalized = text.replace("\r\n", "\n")
    if normalized[: len(expected)] == expected:
        return True
    return "Licensed under the Apache License, Version 2.0 (the \"License\");" in normalized[:1600]


def _header_prefix(path: Path) -> str:
    rel = str(path.relative_to(ROOT))
    if rel.endswith((".java", ".ts", ".tsx", ".proto")):
        return "//"
    if rel.endswith((".py", ".sh")):
        return "#"
    raise AssertionError(f"no header comment syntax for {rel}")


def _with_header(path: Path, text: str) -> str:
    prefix = _header_prefix(path)
    eol = _eol_of(text)
    header = eol.join(header_lines(prefix))
    body = text
    if body.startswith("#!"):  # keep the shebang as the very first line
        newline_index = body.find("\n")
        if newline_index != -1:
            shebang = body[: newline_index + 1]
            return shebang + header + eol + eol + body[newline_index + 1 :]
    return header + eol + eol + body


def main() -> int:
    parser = argparse.ArgumentParser(description="Apply/verify the ASF Apache-2.0 header.")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--add", action="store_true", help="prepend headers where missing")
    mode.add_argument("--check", action="store_true", help="fail when a header is missing")
    args = parser.parse_args()

    files = _source_files()
    missing: list[Path] = []
    wrote = 0
    for path in files:
        prefix = _header_prefix(path)
        try:
            text: Any = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            print(f"SKIP (not utf-8): {path}", file=sys.stderr)
            continue
        if _has_header(text, prefix):
            continue
        missing.append(path)
        if not args.add:
            continue
        path.write_text(_with_header(path, text), encoding="utf-8")
        wrote += 1

    if args.add:
        print(f"license headers: added to {wrote} file(s); {len(files) - wrote} already carried one")
    elif missing:
        print(f"license headers: MISSING on {len(missing)} of {len(files)} file(s)")
        for path in missing[:25]:
            print(f"  MISSING: {path.relative_to(ROOT)}")
        if len(missing) > 25:
            print(f"  ... and {len(missing) - 25} more")
    return 1 if (args.check and missing) else 0


if __name__ == "__main__":
    raise SystemExit(main())
