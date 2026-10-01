"""Bounded lexical routing and structural citation checks, not legal or semantic review.

The router matches whole words/phrases in the latest question only, after folding
Vietnamese accents and case. It does not understand synonyms, intent, context,
or whether a cited source supports a claim. Extend this explicit vocabulary when
real examples warrant it; unmatched queries take the ordinary RAG path.
"""

from __future__ import annotations

import re
import unicodedata
from collections.abc import Collection

POLICY_VERSION = "sensitive-keyword-v1"

# Narrow domain vocabulary; generic prices, refunds, support and customer service
# are deliberately excluded so ordinary commercial questions remain unchanged.
_SENSITIVE_TERMS = (
    "legal", "law", "lawsuit", "court", "attorney", "lawyer", "contract",
    "regulation", "regulatory", "compliance", "government", "visa", "immigration",
    "tax", "taxes", "medical", "diagnosis", "diagnose", "prescription", "medication",
    "medicine", "health", "healthcare", "finance", "financial", "investment",
    "investing", "loan", "interest rate", "insurance",
    "phap ly", "luat", "toa an", "luat su", "hop dong", "quy dinh",
    "tuan thu", "chinh phu", "co quan nha nuoc", "thi thuc", "nhap cu",
    "thue", "y te", "chan doan", "don thuoc", "thuoc", "benh",
    "suc khoe", "tai chinh", "dau tu", "vay", "lai suat", "bao hiem",
)

_TERMS = re.compile(
    r"(?<!\w)(?:"
    + "|".join(re.escape(term).replace(r"\ ", r"\s+") for term in _SENSITIVE_TERMS)
    + r")(?!\w)"
)
_CITATION = re.compile(r"\[\s*S\s*([0-9]+)\s*\]", re.IGNORECASE)


def is_sensitive_query(query: str) -> bool:
    """Route explicit legal/government/compliance/health/finance vocabulary.

    This is intentionally a lexical guardrail, not an intent or risk classifier.
    """
    folded = unicodedata.normalize("NFKD", query.casefold()).replace("đ", "d")
    unaccented = "".join(char for char in folded if not unicodedata.combining(char))
    return _TERMS.search(unaccented) is not None


def has_authorized_citation(answer: str, authorized_ids: Collection[str]) -> bool:
    """Check marker presence and membership only; never infer source support.

    Every [S<number>] marker must reference an ID offered with this answer.
    Malformed text is not counted as evidence. Citation IDs are canonicalized
    only for case and optional marker whitespace, never by numeric value.
    """
    allowed = {identifier.upper() for identifier in authorized_ids}
    markers = _CITATION.findall(answer)
    return bool(markers) and all(f"S{number}" in allowed for number in markers)
