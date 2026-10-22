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

import hashlib
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

from app.modules.ingestion.internal.extraction import ExtractedPage, KnowledgeBlock, ParsedDocument


def _image_fields(block: KnowledgeBlock, media_object_keys: Mapping[str, str]) -> dict[str, Any]:
    """Citable image fields copied from block metadata, keyed into storage.

    Only OCR-derived blocks that reference an image name get fields; the
    object key comes from the deterministic mapping computed per document so
    cache-hit parses without asset bytes still land on the same key.
    """
    name = block.metadata.get("image_name")
    if not name:
        return {
            "image_object_key": None,
            "image_media_type": None,
            "image_width": None,
            "image_height": None,
        }
    return {
        "image_object_key": media_object_keys.get(str(name)),
        "image_media_type": block.metadata.get("image_media_type"),
        "image_width": _optional_int(block.metadata.get("image_width")),
        "image_height": _optional_int(block.metadata.get("image_height")),
    }


def _optional_int(value: Any) -> int | None:
    return int(value) if isinstance(value, int) and not isinstance(value, bool) else None


@dataclass(frozen=True, slots=True)
class TextChunk:
    page_number: int | None
    chunk_index: int
    text: str
    content_hash: str
    unit_id: str = ""
    modality: str = "document"
    section_path: tuple[str, ...] = ()
    block_type: str = "paragraph"
    heading_context: str | None = None
    sheet_name: str | None = None
    cell_range: str | None = None
    table_id: str | None = None
    source_start: int | None = None
    source_end: int | None = None
    parser_version: str = "digital-v1"
    chunker_version: str = "structural-v2"
    # Citable image asset bound to the source block (OCR-derived text only).
    # ``image_object_key`` is resolved from the source image name with the
    # document's storage context; the remaining fields mirror block metadata.
    image_object_key: str | None = None
    image_media_type: str | None = None
    image_width: int | None = None
    image_height: int | None = None


class DeterministicChunker:
    def __init__(self, chunk_size: int = 800, overlap: int = 120):
        if chunk_size <= 0:
            raise ValueError("chunk_size must be positive")
        if overlap < 0 or overlap >= chunk_size:
            raise ValueError("overlap must be non-negative and smaller than chunk_size")
        self._chunk_size = chunk_size
        self._overlap = overlap

    def chunk(
        self,
        source: ParsedDocument | list[ExtractedPage],
        *,
        media_object_keys: Mapping[str, str] | None = None,
    ) -> list[TextChunk]:
        if isinstance(source, ParsedDocument):
            return self._chunk_blocks(source, media_object_keys or {})
        blocks = tuple(
            KnowledgeBlock(
                unit_id=hashlib.sha256(f"page:{page.page_number}".encode()).hexdigest()[:32],
                block_type="page",
                text=page.text,
                page_number=page.page_number,
                content_hash=hashlib.sha256(page.text.encode()).hexdigest(),
            )
            for page in source
        )
        return self._chunk_blocks(ParsedDocument("document", blocks), {})

    def _chunk_blocks(
        self, document: ParsedDocument, media_object_keys: Mapping[str, str] | None = None
    ) -> list[TextChunk]:
        chunks: list[TextChunk] = []
        keys = media_object_keys or {}
        for block in document.blocks:
            image_fields = _image_fields(block, keys)
            pieces = self._split_block(block.text, block_type=block.block_type)
            for piece_index, text in enumerate(pieces):
                unit_id = block.unit_id if len(pieces) == 1 else f"{block.unit_id}:{piece_index}"
                chunks.append(
                    TextChunk(
                        page_number=block.page_number,
                        chunk_index=len(chunks),
                        text=text,
                        content_hash=hashlib.sha256(text.encode("utf-8")).hexdigest(),
                        unit_id=unit_id,
                        modality=document.modality,
                        section_path=block.section_path,
                        block_type=block.block_type,
                        heading_context=block.heading_context,
                        sheet_name=block.sheet_name,
                        cell_range=block.cell_range,
                        table_id=block.table_id,
                        source_start=block.source_start,
                        source_end=block.source_end,
                        parser_version=block.parser_version,
                        **image_fields,
                    )
                )
        return chunks

    def _split_block(self, text: str, *, block_type: str = "paragraph") -> list[str]:
        structural = block_type in {"heading", "list", "code", "table", "sheet", "row"}
        if structural:
            normalized = "\n".join(line.rstrip() for line in text.splitlines() if line.strip())
        else:
            normalized = " ".join(text.split())
        if not normalized:
            return []
        if len(normalized) <= self._chunk_size:
            return [normalized]
        if structural:
            return self._split_structural(normalized, repeat_table_header=block_type == "table")
        return self._split_prose(normalized)

    def _split_prose(self, normalized: str) -> list[str]:
        result: list[str] = []
        start = 0
        while start < len(normalized):
            hard_end = min(start + self._chunk_size, len(normalized))
            end = hard_end
            if hard_end < len(normalized):
                boundary = max(
                    normalized.rfind(". ", start, hard_end),
                    normalized.rfind(" ", start, hard_end + 1),
                )
                if boundary > start:
                    end = boundary + (1 if normalized[boundary] == "." else 0)
            value = normalized[start:end].strip()
            if value:
                result.append(value)
            if end >= len(normalized):
                break
            start = max(end - self._overlap, start + 1)
            while start < len(normalized) and normalized[start] == " ":
                start += 1
        return result

    def _split_structural(self, normalized: str, *, repeat_table_header: bool) -> list[str]:
        lines = normalized.splitlines()
        if repeat_table_header and lines:
            header = [lines[0]]
            if len(lines) > 1 and self._is_table_separator(lines[1]):
                header.append(lines[1])
            return self._split_table(lines, header)
        result: list[str] = []
        current: list[str] = []
        for line in lines:
            candidates = [*current, line]
            if current and len("\n".join(candidates)) > self._chunk_size:
                result.append("\n".join(current))
                current = []
            if len(line) <= self._chunk_size:
                current.append(line)
                continue
            if current:
                result.append("\n".join(current))
                current = []
            for start in range(0, len(line), self._chunk_size):
                result.append(line[start : start + self._chunk_size])
        if current:
            result.append("\n".join(current))
        return result

    def _split_table(self, lines: list[str], header: list[str]) -> list[str]:
        result: list[str] = []
        current = list(header)
        for line in lines[len(header) :]:
            if len("\n".join([*current, line])) > self._chunk_size and current != header:
                result.append("\n".join(current))
                current = list(header)
            current.append(line)
        if current:
            result.append("\n".join(current))
        return result

    @staticmethod
    def _is_table_separator(line: str) -> bool:
        compact = line.replace("|", "").replace(":", "").replace("-", "").strip()
        return not compact
