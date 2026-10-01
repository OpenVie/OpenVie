from __future__ import annotations

import hashlib
import re
import unicodedata

from app.modules.ingestion.api import (
    ContentExtractionCommand,
    ExtractedContent,
    PermanentIngestionFailure,
    SourceSegment,
)
from app.modules.ingestion.internal.extraction import DocumentTextExtractor


class DigitalContentExtractionAdapter:
    """Extraction-only adapter; it deliberately does not call ingestion or indexing."""

    def __init__(
        self,
        *,
        max_characters: int = 50_000,
        max_segments: int = 250,
        extractor: DocumentTextExtractor | None = None,
    ) -> None:
        self._max_characters = max_characters
        self._max_segments = max_segments
        self._extractor = extractor or DocumentTextExtractor()

    async def extract(self, command: ContentExtractionCommand) -> ExtractedContent:
        parsed = self._extractor.parse(
            command.file_bytes,
            content_type=command.content_type,
            file_name=command.file_name,
        )
        segments: list[SourceSegment] = []
        used = 0
        pages: set[int] = set()
        for block in parsed.blocks:
            for source_text in _split_source_text(block.text):
                if len(segments) >= self._max_segments or used >= self._max_characters:
                    break
                ordinal = len(segments) + 1
                text = source_text[: self._max_characters - used]
                location = _location(block.page_number, block.section_path, ordinal)
                identity = hashlib.sha256(
                    f"{ordinal}|{location}|{text}".encode()
                ).hexdigest()[:24]
                segments.append(SourceSegment(identity, text, location))
                used += len(text)
                if block.page_number is not None:
                    pages.add(block.page_number)
            if len(segments) >= self._max_segments or used >= self._max_characters:
                break
        if not segments:
            raise PermanentIngestionFailure("Document contains no extractable text")
        normalized = "\n\n".join(segment.text for segment in segments)
        return ExtractedContent(
            normalized_text=normalized,
            detected_content_type=command.content_type,
            character_count=len(normalized),
            page_count=max(pages) if pages else None,
            source_segments=tuple(segments),
        )


def _normalize(value: str) -> str:
    normalized = unicodedata.normalize(
        "NFC", value.replace("\r\n", "\n").replace("\r", "\n")
    )
    return "\n".join(_collapse_character_spacing(line) for line in normalized.splitlines()).strip()


def _collapse_character_spacing(line: str) -> str:
    chunks = re.split(r"[ \t]{2,}", line.strip())
    tokenized = [re.split(r"[ \t]", chunk) for chunk in chunks]
    character_chunks = [
        tokens
        for tokens in tokenized
        if len(tokens) >= 2 and all(len(token) == 1 for token in tokens)
    ]
    if not character_chunks or max(len(tokens) for tokens in character_chunks) < 4:
        return line
    repaired = [
        "".join(tokens) if len(tokens) >= 2 and all(len(token) == 1 for token in tokens) else chunk
        for chunk, tokens in zip(chunks, tokenized, strict=True)
    ]
    return " ".join(repaired)


def _split_source_text(value: str) -> tuple[str, ...]:
    lines = [line.strip() for line in _normalize(value).splitlines() if line.strip()]
    segments: list[str] = []
    current: list[str] = []
    for line in lines:
        if _looks_like_heading(line):
            if current:
                segments.append(_join_wrapped_lines(current))
                current = []
            segments.append(line)
            continue
        current.append(line)
        combined = _join_wrapped_lines(current)
        if line.endswith((".", "!", "?", ":")) or len(combined) >= 800:
            segments.append(combined)
            current = []
    if current:
        segments.append(_join_wrapped_lines(current))
    return tuple(segments)


def _looks_like_heading(line: str) -> bool:
    letters = [character for character in line if character.isalpha()]
    return (
        (len(letters) >= 3 and all(character.isupper() for character in letters))
        or "|" in line
        or re.match(r"^[^:]{1,40}:", line) is not None
    )


def _join_wrapped_lines(lines: list[str]) -> str:
    result = ""
    for line in lines:
        if not result:
            result = line
        elif result.endswith("-"):
            result += line
        else:
            result += f" {line}"
    return result


def _location(page: int | None, section_path: tuple[str, ...], ordinal: int) -> str:
    parts: list[str] = []
    if page is not None:
        parts.append(f"page {page}")
    if section_path:
        parts.append(" / ".join(section_path)[-80:])
    parts.append(f"segment {ordinal}")
    return ", ".join(parts)[:120]
