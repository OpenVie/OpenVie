"""Non-retrieval intent gate for chit-chat and nonsense input.

A follow-up such as "hi abcd" carries no document lookup intent, so running
it through embedding, hybrid retrieval, and grounded generation only produces
a misleading answer with a random source attached. This module is a
deliberately narrow, deterministic filter kept next to the query planner:

* it never blocks sensitive vocabulary — the caller checks
  ``is_sensitive_query`` first and sensitive queries always retrieve;
* when in doubt it allows retrieval, so a real question is never dropped;
* only high-confidence chit-chat (greetings, thanks, acknowledgements,
  identity questions, punctuation-only input, or greeting plus a gibberish
  tail) skips retrieval;
* the reply is a fixed bilingual template, never LLM output, so it cannot
  leak filenames, invent citations, or need a source panel.

The caller returns the reply with ``citations=[]``; the frontend already
hides the SOURCES section when there are no citations.
"""

from __future__ import annotations

import re
import unicodedata
from collections.abc import Sequence

_GREETING_WORD = (
    r"(?:hi+|hello+|hey+|yo|alo+|chao|xin chao|"
    r"good (?:morning|afternoon|evening)|bye+|tam biet|hen gap lai)"
)
_SOCIAL_WORD = (
    r"(?:cam on|thanks?|thank you|ok(?:ay)?|oke|vang|da|uhm*|u+|bye+)"
)
_IDENTITY_WORD = (
    r"(?:who are you|ban la ai|may la ai|how are you|"
    r"(?:ban\s+)?khoe\s+khong)"
)
_SOCIAL_ONLY = re.compile(
    rf"^\s*(?:{_GREETING_WORD}|{_SOCIAL_WORD}|{_IDENTITY_WORD})"
    r"\s*[.?!…~]*\s*$"
)
_GREETING_WITH_TAIL = re.compile(
    rf"^\s*(?:{_GREETING_WORD})\s+([a-z0-9]{{1,12}})\s*[.?!…~]*\s*$"
)
_GIBBERISH_FULL = re.compile(
    r"^\s*(?:abcd(?:e(?:f(?:g)?)?)?|abc|ab|asdf(?:gh)?|qwer(?:ty)?|"
    r"zxcv|xyz|test(?:ing)?|hahaha+|hehe+|hihi+|kkk+|xxx+|zzz+|"
    r"haha|hehe|hihi)\s*[.?!…~]*\s*$"
)
_REPEATED = re.compile(r"(.)\1{2,}")

_QUESTION_WORD = re.compile(
    r"(?:\bai\b|\bgi\b|\bnao\b|\bbao\b|\bthe nao\b|\btai sao\b|"
    r"\blam sao\b|\bo dau\b|\bkhi nao\b|\bbao nhieu\b|"
    r"\bco\b.{0,12}\bkhong\b|\bhow\b|\bwhat\b|\bwhen\b|\bwhere\b|"
    r"\bwhy\b|\bwhich\b|\bwho\b|\bwhom\b|\bwhose\b|\bcan\b|\bcould\b|"
    r"\bwould\b|\bshould\b|\bdo\b|\bdoes\b|\bis\b|\bare\b|\bwas\b|"
    r"\bwere\b)"
)
_DOC_SIGNAL = re.compile(
    r"(?:chinh sach|quy dinh|tai lieu|van ban|hop dong|dieu|khoan|"
    r"policy|policies|document|contract|regulation|rule|guideline|"
    r"return|refund|price|warranty|bao hanh|doi tra|hoan tien|"
    r"thanh toan|payment|invoice|hoa don|luong|salary|nghi phep|"
    r"leave|tuyen dung|recruit|dao tao|training|huong dan|manual|"
    r"thu tuc|procedure|bieu mau|danh sach|bao cao|report)"
)
_ELABORATION = re.compile(
    r"(?:cu the|chi tiet|ro hon|ro rang|giai thich|mo rong|vi du|"
    r"them\b|further|more|detail|details|specific|elaborat|explain|"
    r"clarif|example)"
)
_QUOTED = re.compile(r"[\"“”'‘’][^\"“”'‘’]{2,}[\"“”'‘’]")
_DIGIT = re.compile(r"\d")

_VI_DIACRITIC = re.compile(
    r"[àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõ"
    r"ôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ]",
    re.IGNORECASE,
)
_VI_KEYWORDS = re.compile(
    r"\b(?:chao|xin chao|cam on|chinh sach|quy dinh|tai lieu|khong|"
    r"gi|nao|ban|minh|toi|duoc|cu the|chi tiet|doi tra|hoan|"
    r"bao hanh|lam sao|the nao|tai sao|o dau|khi nao|bao nhieu|"
    r"co the|vui long|cho hoi|cho minh hoi|khoe)\b"
)


def _fold(text: str) -> str:
    folded = unicodedata.normalize("NFKD", text.casefold()).replace("đ", "d")
    return "".join(char for char in folded if not unicodedata.combining(char))


def _looks_like_gibberish(token: str) -> bool:
    lowered = token.lower()
    if len(lowered) <= 1:
        return True
    if _REPEATED.search(lowered):
        return True
    return bool(
        re.fullmatch(
            r"(?:abcd(?:e(?:f(?:g)?)?)?|abc|ab|asdf(?:gh)?|qwer(?:ty)?|"
            r"zxcv|xyz|test(?:ing)?|hahaha+|hehe+|hihi+|kkk+|xxx+|zzz+|"
            r"haha|hehe|hihi)",
            lowered,
        )
    )


def _has_retrieval_signal(folded: str, raw: str) -> bool:
    if _QUOTED.search(raw):
        return True
    if _DIGIT.search(folded):
        return True
    if _QUESTION_WORD.search(folded):
        return True
    if _DOC_SIGNAL.search(folded):
        return True
    if _ELABORATION.search(folded):
        return True
    return "?" in raw


def is_non_retrieval_question(
    question: object, prior_messages: Sequence[object] = ()
) -> bool:
    """True only for high-confidence chit-chat; otherwise allow retrieval.

    The caller must check ``is_sensitive_query`` first: sensitive vocabulary
    always takes the retrieval path and never reaches this gate as a block.
    ``prior_messages`` is accepted for signature symmetry with the query
    planner but does not affect the verdict — history only shapes the reply.
    """
    del prior_messages
    text = question if isinstance(question, str) else str(question or "")
    if not text.strip():
        return True
    folded = _fold(text)
    stripped = " ".join(re.sub(r"[^\w\s]", " ", folded).split())
    if not stripped:
        return True
    if _SOCIAL_ONLY.match(folded):
        return True
    tail = _GREETING_WITH_TAIL.match(folded)
    if tail and _looks_like_gibberish(tail.group(1)):
        return True
    if _GIBBERISH_FULL.match(folded):
        return True
    if _has_retrieval_signal(folded, text):
        return False
    tokens = stripped.split()
    return (
        len(tokens) == 1
        and len(tokens[0]) <= 8
        and _looks_like_gibberish(tokens[0])
    )


def _reply_language(question: object) -> str:
    text = question if isinstance(question, str) else str(question or "")
    if _VI_DIACRITIC.search(text):
        return "vi"
    if _VI_KEYWORDS.search(_fold(text)):
        return "vi"
    return "en"


def _last_meaningful_user_topic(
    prior_messages: Sequence[object],
) -> str:
    for message in reversed(list(prior_messages or ())):
        if str(getattr(message, "role", "")) != "user":
            continue
        content = " ".join(str(getattr(message, "content", "")).split())
        if not content:
            continue
        if is_non_retrieval_question(content):
            continue
        return content
    return ""


def build_non_retrieval_reply(
    question: object, prior_messages: Sequence[object] = ()
) -> str:
    """Polite history-aware reply with no citations and no source names."""
    lang = _reply_language(question)
    topic = _last_meaningful_user_topic(prior_messages)
    short = " ".join(topic.split())
    if len(short) > 80:
        short = short[:77].rstrip() + "…"
    if lang == "vi":
        if short:
            return (
                "Mình chưa thấy ý định tra cứu tài liệu trong tin nhắn vừa rồi. "
                f"Lần trước bạn đang hỏi về “{short}” — bạn muốn tiếp tục "
                "với chủ đề đó, hay hỏi một nội dung khác trong tài liệu?"
            )
        return (
            "Mình là trợ lý tài liệu của workspace này — mình chỉ trả lời "
            "từ tài liệu đã tải lên. Bạn muốn tìm thông tin gì trong tài liệu? "
            "Ví dụ: chính sách, quy định, hoặc một mã/tên cụ thể."
        )
    if short:
        return (
            "I didn't see a document lookup intent in your last message. "
            f"You were asking about “{short}” — do you want to continue "
            "with that, or ask about something else in the documents?"
        )
    return (
        "I'm the document assistant for this workspace — I answer only from "
        "uploaded documents. What would you like to look up? For example: "
        "a policy, a regulation, or a specific code or name."
    )
