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
import re
from pathlib import PurePosixPath


def spreadsheet_table_prefix(
    tenant_id: str, knowledge_base_id: str, document_id: str
) -> str:
    tenant = _component("tenant_id", tenant_id)
    knowledge_base = _component("knowledge_base_id", knowledge_base_id)
    document = _component("document_id", document_id)
    return (
        f"tenants/{tenant}/knowledge-bases/{knowledge_base}/documents/{document}/tables/"
    )


def spreadsheet_table_key(
    tenant_id: str,
    knowledge_base_id: str,
    document_id: str,
    table_id: str,
) -> str:
    table = _component("table_id", table_id)
    return f"{spreadsheet_table_prefix(tenant_id, knowledge_base_id, document_id)}{table}.parquet"


def ocr_artifact_prefix(tenant_id: str, knowledge_base_id: str, document_id: str) -> str:
    tenant = _component("tenant_id", tenant_id)
    knowledge_base = _component("knowledge_base_id", knowledge_base_id)
    document = _component("document_id", document_id)
    return f"tenants/{tenant}/knowledge-bases/{knowledge_base}/documents/{document}/ocr/"


def ocr_artifact_key(
    tenant_id: str,
    knowledge_base_id: str,
    document_id: str,
    fingerprint: str,
) -> str:
    prefix = ocr_artifact_prefix(tenant_id, knowledge_base_id, document_id)
    return f"{prefix}{fingerprint}.json"


def document_media_prefix(tenant_id: str, knowledge_base_id: str, document_id: str) -> str:
    tenant = _component("tenant_id", tenant_id)
    knowledge_base = _component("knowledge_base_id", knowledge_base_id)
    document = _component("document_id", document_id)
    return f"tenants/{tenant}/knowledge-bases/{knowledge_base}/documents/{document}/media/"


def document_media_key(
    tenant_id: str,
    knowledge_base_id: str,
    document_id: str,
    image_name: str,
) -> str:
    """Deterministic object key for a document's citable image asset.

    Image names can contain package paths (``word/media/image1.png``) or be
    synthesized by pypdf, so the key hashes the name and keeps a sanitized
    extension only. Deriving keys from (tenant, knowledge base, document,
    name) makes the mapping stable across OCR-cache hits, which carry
    descriptors but not bytes.
    """
    digest = hashlib.sha256(image_name.encode("utf-8")).hexdigest()[:16]
    suffix = PurePosixPath(image_name).suffix
    ext = re.sub(r"[^0-9A-Za-z]", "", suffix)[:8].lower()
    base = f"{document_media_prefix(tenant_id, knowledge_base_id, document_id)}{digest}"
    return f"{base}.{ext}" if ext else base


def _component(label: str, value: str) -> str:
    normalized = str(value).strip()
    if not normalized or "/" in normalized or normalized in {".", ".."}:
        raise ValueError(f"{label} is not a valid storage-key component")
    return normalized
