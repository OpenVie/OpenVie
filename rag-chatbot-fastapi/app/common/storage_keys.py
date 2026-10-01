from __future__ import annotations


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


def _component(label: str, value: str) -> str:
    normalized = str(value).strip()
    if not normalized or "/" in normalized or normalized in {".", ".."}:
        raise ValueError(f"{label} is not a valid storage-key component")
    return normalized
