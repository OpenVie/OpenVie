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


ARTIFACT_KINDS = ("ocr", "asr")


def artifact_prefix(
    artifact_kind: str, tenant_id: str, knowledge_base_id: str, document_id: str
) -> str:
    """Derived-parse artifact namespace for one document ("<kind>/" directory).

    ``artifact_kind`` selects the folder: ``ocr`` for recognized raster text
    and ``asr`` for speech transcripts — each bound to its own fingerprint
    with no cross-reading between stages.
    """
    if artifact_kind not in ARTIFACT_KINDS:
        raise ValueError(f"Unknown artifact kind: {artifact_kind}")
    tenant = _component("tenant_id", tenant_id)
    knowledge_base = _component("knowledge_base_id", knowledge_base_id)
    document = _component("document_id", document_id)
    return (
        f"tenants/{tenant}/knowledge-bases/{knowledge_base}/documents/{document}/{artifact_kind}/"
    )


def artifact_key(
    artifact_kind: str,
    tenant_id: str,
    knowledge_base_id: str,
    document_id: str,
    fingerprint: str,
) -> str:
    prefix = artifact_prefix(artifact_kind, tenant_id, knowledge_base_id, document_id)
    return f"{prefix}{fingerprint}.json"


def ocr_artifact_prefix(tenant_id: str, knowledge_base_id: str, document_id: str) -> str:
    return artifact_prefix("ocr", tenant_id, knowledge_base_id, document_id)


def ocr_artifact_key(
    tenant_id: str,
    knowledge_base_id: str,
    document_id: str,
    fingerprint: str,
) -> str:
    return artifact_key("ocr", tenant_id, knowledge_base_id, document_id, fingerprint)


def asr_artifact_prefix(tenant_id: str, knowledge_base_id: str, document_id: str) -> str:
    return artifact_prefix("asr", tenant_id, knowledge_base_id, document_id)


def asr_artifact_key(
    tenant_id: str,
    knowledge_base_id: str,
    document_id: str,
    fingerprint: str,
) -> str:
    return artifact_key("asr", tenant_id, knowledge_base_id, document_id, fingerprint)


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


def lakehouse_dataset_prefix(tenant_id: str, dataset: str) -> str:
    tenant = _component("tenant_id", tenant_id)
    ds = _component("dataset", dataset)
    return f"lakehouse/tenants/{tenant}/{ds}/"


def lakehouse_partition_prefix(
    tenant_id: str, dataset: str, year: int, month: int
) -> str:
    prefix = lakehouse_dataset_prefix(tenant_id, dataset)
    return f"{prefix}year={year:04d}/month={month:02d}/"


def lakehouse_partition_key(
    tenant_id: str, dataset: str, year: int, month: int, batch_id: str
) -> str:
    batch = _component("batch_id", batch_id)
    return f"{lakehouse_partition_prefix(tenant_id, dataset, year, month)}{batch}.parquet"


def _component(label: str, value: str) -> str:
    normalized = str(value).strip()
    if not normalized or "/" in normalized or normalized in {".", ".."}:
        raise ValueError(f"{label} is not a valid storage-key component")
    return normalized
