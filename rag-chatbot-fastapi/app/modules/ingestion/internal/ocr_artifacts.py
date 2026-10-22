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

"""Cached OCR results stored next to the raw document in object storage.

Artifacts live under ``<document>/ocr/<fingerprint>.json`` where the fingerprint
binds the recognized text to the raw bytes and the OCR configuration (provider,
languages, DPI). A hit lets reindexing skip recognition entirely; a miss (or a
corrupted artifact) falls back to a full parse. Artifact failures never block
ingestion — they are logged and swallowed.
"""

from __future__ import annotations

import json
import logging
from typing import Any

from app.common.errors import StorageNotFoundError
from app.common.storage import ObjectStorage
from app.common.storage_keys import (
    document_media_key,
    ocr_artifact_key,
    ocr_artifact_prefix,
)
from app.modules.ingestion.internal.extraction import ImageAsset, KnowledgeBlock, ParsedDocument

logger = logging.getLogger(__name__)

_OCR_ARTIFACT_SCHEMA = "ocr-artifact-v1"
_OCR_ARTIFACT_CONTENT_TYPE = "application/json"
_KNOWN_BLOCK_TYPES = {
    "heading",
    "paragraph",
    "list",
    "code",
    "quote",
    "table",
    "page",
    "sheet",
    "row",
}


async def load_ocr_artifact(
    store: ObjectStorage,
    *,
    tenant_id: str,
    knowledge_base_id: str,
    document_id: str,
    fingerprint: str,
) -> ParsedDocument | None:
    """Return the cached parse for ``fingerprint`` or None (miss/corruption)."""
    key = ocr_artifact_key(tenant_id, knowledge_base_id, document_id, fingerprint)
    try:
        payload = await store.download(key)
    except StorageNotFoundError:
        return None
    except Exception as exc:  # noqa: BLE001 - any failure falls back to a full parse
        logger.warning("Unable to read OCR artifact %s; reparsing document: %s", key, exc)
        return None
    return _document_from_json(payload, fingerprint, key)


async def save_ocr_artifact(
    store: ObjectStorage,
    *,
    tenant_id: str,
    knowledge_base_id: str,
    document_id: str,
    fingerprint: str,
    parsed: ParsedDocument,
    media_blobs: dict[str, bytes] | None = None,
) -> None:
    """Best-effort upload of the OCR-derived parse; never raises.

    Citable image assets are persisted before the artifact JSON is written so
    a cached artifact always implies its media objects exist in storage
    (artifact ⇒ assets); a media-upload failure therefore skips the artifact
    and the next ingest re-parses instead of referencing dead keys.
    """
    blob_by_name = media_blobs or {}
    for asset in parsed.media:
        blob = blob_by_name.get(asset.name)
        if not blob:
            continue
        try:
            await store.upload(
                document_media_key(tenant_id, knowledge_base_id, document_id, asset.name),
                blob,
                content_type=asset.media_type or "application/octet-stream",
            )
        except Exception as exc:  # noqa: BLE001 - caching must never block ingestion
            logger.warning(
                "Unable to cache image asset %s for %s; skipping OCR artifact: %s",
                asset.name,
                document_id,
                exc,
            )
            return
    payload: dict[str, Any] = {
        "schema_version": _OCR_ARTIFACT_SCHEMA,
        "fingerprint": fingerprint,
        "modality": parsed.modality,
        "text_source": parsed.text_source,
        "page_count": parsed.page_count,
        "blocks": [_block_to_dict(block) for block in parsed.blocks],
        "media": [_media_to_dict(asset) for asset in parsed.media],
    }
    data = json.dumps(payload, ensure_ascii=False, default=str).encode("utf-8")
    key = ocr_artifact_key(tenant_id, knowledge_base_id, document_id, fingerprint)
    try:
        await store.upload(key, data, content_type=_OCR_ARTIFACT_CONTENT_TYPE)
    except Exception as exc:  # noqa: BLE001 - caching must never block ingestion
        logger.warning("Unable to cache OCR artifact %s; continuing: %s", key, exc)


async def delete_ocr_artifacts(
    store: ObjectStorage,
    *,
    tenant_id: str,
    knowledge_base_id: str,
    document_id: str,
) -> None:
    await store.delete_prefix(ocr_artifact_prefix(tenant_id, knowledge_base_id, document_id))


def _document_from_json(payload: bytes, fingerprint: str, key: str) -> ParsedDocument | None:
    try:
        document = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        logger.warning("Ignoring unreadable OCR artifact %s: %s", key, exc)
        return None
    if not isinstance(document, dict):
        logger.warning("Ignoring malformed OCR artifact %s", key)
        return None
    if (
        document.get("schema_version") != _OCR_ARTIFACT_SCHEMA
        or document.get("fingerprint") != fingerprint
    ):
        logger.warning("Ignoring OCR artifact %s with mismatched fingerprint", key)
        return None
    raw_blocks = document.get("blocks")
    modality = document.get("modality")
    text_source = document.get("text_source")
    page_count = document.get("page_count")
    if (
        modality not in {"document", "spreadsheet"}
        or text_source not in {"text", "ocr", "mixed"}
        or not isinstance(raw_blocks, list)
        or not raw_blocks
    ):
        logger.warning("Ignoring malformed OCR artifact %s", key)
        return None
    try:
        blocks = tuple(_block_from_dict(item) for item in raw_blocks)
    except (KeyError, TypeError, ValueError) as exc:
        logger.warning("Ignoring OCR artifact %s with malformed blocks: %s", key, exc)
        return None
    raw_media = document.get("media")
    try:
        media = tuple(
            _media_from_dict(item) for item in (raw_media if isinstance(raw_media, list) else [])
        )
    except (KeyError, TypeError, ValueError) as exc:
        logger.warning("Ignoring OCR artifact %s with malformed media: %s", key, exc)
        return None
    return ParsedDocument(
        modality,
        blocks,
        text_source=text_source,
        page_count=page_count if isinstance(page_count, int) else None,
        media=media,
    )


def _media_to_dict(asset: ImageAsset) -> dict[str, Any]:
    """Descriptors only — asset bytes never enter the artifact JSON."""
    return {
        "name": asset.name,
        "media_type": asset.media_type,
        "width": asset.width,
        "height": asset.height,
    }


def _media_from_dict(item: Any) -> ImageAsset:
    if not isinstance(item, dict) or not isinstance(item.get("name"), str) or not item["name"]:
        raise TypeError("media entry is missing a name")
    media_type = item.get("media_type")
    width = item.get("width")
    height = item.get("height")
    return ImageAsset(
        name=item["name"],
        media_type=str(media_type) if isinstance(media_type, str) else "",
        width=int(width) if isinstance(width, int) and not isinstance(width, bool) else None,
        height=int(height) if isinstance(height, int) and not isinstance(height, bool) else None,
    )


def _block_to_dict(block: KnowledgeBlock) -> dict[str, Any]:
    return {
        "unit_id": block.unit_id,
        "block_type": block.block_type,
        "text": block.text,
        "section_path": list(block.section_path),
        "heading_context": block.heading_context,
        "page_number": block.page_number,
        "sheet_name": block.sheet_name,
        "cell_range": block.cell_range,
        "table_id": block.table_id,
        "source_start": block.source_start,
        "source_end": block.source_end,
        "content_hash": block.content_hash,
        "parser_version": block.parser_version,
        "metadata": block.metadata,
    }


def _block_from_dict(item: Any) -> KnowledgeBlock:
    if not isinstance(item, dict) or item.get("block_type") not in _KNOWN_BLOCK_TYPES:
        raise TypeError("block_type is missing or unknown")
    return KnowledgeBlock(
        unit_id=str(item["unit_id"]),
        block_type=item["block_type"],
        text=str(item["text"]),
        section_path=tuple(str(value) for value in item.get("section_path") or ()),
        heading_context=_optional(item.get("heading_context")),
        page_number=_optional(item.get("page_number")),
        sheet_name=_optional(item.get("sheet_name")),
        cell_range=_optional(item.get("cell_range")),
        table_id=_optional(item.get("table_id")),
        source_start=_optional(item.get("source_start")),
        source_end=_optional(item.get("source_end")),
        content_hash=str(item.get("content_hash") or ""),
        parser_version=str(item.get("parser_version") or "ocr-v1"),
        metadata=dict(item.get("metadata") or {}),
    )


def _optional(value: Any) -> Any:
    return None if value is None else value
