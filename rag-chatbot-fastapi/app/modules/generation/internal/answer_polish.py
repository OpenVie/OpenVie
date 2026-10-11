"""Deterministic output polishing for tenant answers.

The workspace ingest copies tenant templates verbatim, so knowledge units can
carry bracketed placeholders such as `[Tên Trường]` or `[đường dẫn truy cập kho
mã nguồn ...]`. A 3B instruction-following model copies them into answers even
when told not to (measured 4/5 leaks on one question). Prompt rules alone cannot
guarantee this, so the answer is cleaned after generation:

* bracketed placeholders become grounded phrasing ("the registering
  organization"), never dropped silently;
* a source-reference mention (document name, chunk number, file extension)
  inside the answer body is removed; the citation panel already carries that
  provenance.

The cleaner is pure text transformation: it never invents facts, and it is
applied before the sensitive citation check so markers stay intact.
"""

from __future__ import annotations

import re

# A bracketed template placeholder: short-ish bracketed text with no sentence
# punctuation inside, e.g. `[Tên Trường]`, `[đường dẫn truy cập kho mã nguồn ...]`.
# Citation markers (`[S1]`) are excluded so evidence markers survive.
_PLACEHOLDER = re.compile(r"\[(?![Ss]\d+\])([^\[\]{}]{2,120})\]")

# A source reference echo: `tên-file.docx`, `chunk 32`, `[S3] as a file pointer`.
_FILE_REFERENCE = re.compile(
    r"(?:tài liệu\s*)?[a-z0-9_./\-]*_[a-z0-9_./\-]{8,}"
    r"\.(?:docx|doc|pdf|xlsx|pptx|txt|md|csv)\b",
    re.IGNORECASE,
)
_CHUNK_REFERENCE = re.compile(
    r"\s*(?:,|từ|trong|của)?\s*(?:tài liệu\s*)?chunk\s+\d+", re.IGNORECASE
)
# Trailing separator left behind after removing an echo at the end of a line.
_TRAILING_SEPARATORS = r"\s*[,;:\-–—]\s*$"


def _grounded_placeholder(placeholder: str) -> str:
    lowered = placeholder.strip().casefold()
    if "trường" in lowered:
        return "trường/thành viên đăng ký"
    if "đường dẫn" in lowered or "link" in lowered:
        return "đường dẫn do ban tổ chức cung cấp"
    if "tên" in lowered:
        return "đơn vị đăng ký"
    return "phần do ban tổ chức cung cấp"


def polish_answer(answer: str) -> str:
    """Replace template placeholders and strip source-reference echoes."""
    polished = _PLACEHOLDER.sub(
        lambda match: _grounded_placeholder(match.group(1)), answer
    )
    polished = _FILE_REFERENCE.sub("", polished)
    polished = _CHUNK_REFERENCE.sub("", polished)
    polished = re.sub(_TRAILING_SEPARATORS, "", polished, flags=re.MULTILINE)
    return " ".join(polished.split())


def polish_preview(answer: str) -> str:
    """Withhold a short tail and incomplete tokens before applying the final cleaner.

    Never expose an open placeholder or a split file/chunk reference. Whole
    whitespace-delimited tokens stay buffered even when a filename is long.
    """
    candidate = answer[:-48]
    boundary = max((match.start() for match in re.finditer(r"\s", candidate)), default=0)
    candidate = candidate[:boundary]
    open_bracket = candidate.rfind("[")
    if open_bracket > candidate.rfind("]"):
        candidate = candidate[:open_bracket]
    candidate = re.sub(r"\bchunk\s*$", "", candidate, flags=re.IGNORECASE)
    return polish_answer(candidate)


def contains_source_echo(answer: str) -> bool:
    """True when the answer still mentions document names or chunk numbers."""
    return bool(_FILE_REFERENCE.search(answer) or _CHUNK_REFERENCE.search(answer))