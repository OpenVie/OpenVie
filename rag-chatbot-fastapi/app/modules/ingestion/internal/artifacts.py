from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from io import BytesIO
from typing import Any

from app.common.storage import ObjectStorageWriter
from app.common.storage_keys import spreadsheet_table_key, spreadsheet_table_prefix
from app.modules.ingestion.api import PermanentIngestionFailure, TransientIngestionFailure
from app.modules.ingestion.internal.extraction import NormalizedTable, ParsedDocument

PARQUET_CONTENT_TYPE = "application/vnd.apache.parquet"


@dataclass(frozen=True, slots=True)
class SpreadsheetArtifact:
    storage_key: str
    data: bytes
    content_type: str = PARQUET_CONTENT_TYPE


def prepare_spreadsheet_artifacts(
    document: ParsedDocument,
    *,
    tenant_id: str,
    knowledge_base_id: str,
    document_id: str,
) -> tuple[SpreadsheetArtifact, ...]:
    return tuple(
        SpreadsheetArtifact(
            storage_key=spreadsheet_table_key(
                tenant_id,
                knowledge_base_id,
                document_id,
                table.table_id,
            ),
            data=_parquet_bytes(table),
        )
        for table in document.tables
    )


async def replace_spreadsheet_artifacts(
    store: ObjectStorageWriter,
    *,
    tenant_id: str,
    knowledge_base_id: str,
    document_id: str,
    artifacts: tuple[SpreadsheetArtifact, ...],
) -> None:
    prefix = spreadsheet_table_prefix(tenant_id, knowledge_base_id, document_id)
    await store.delete_prefix(prefix)
    for artifact in artifacts:
        await store.upload(
            artifact.storage_key,
            artifact.data,
            content_type=artifact.content_type,
        )


def _parquet_bytes(table: NormalizedTable) -> bytes:
    try:
        import polars as pl
    except ImportError as exc:
        raise TransientIngestionFailure(
            "Spreadsheet artifact dependency polars is unavailable"
        ) from exc
    try:
        frame = pl.DataFrame(
            [_safe_values(row.values) for row in table.rows],
            strict=False,
        )
        output = BytesIO()
        frame.write_parquet(output)
        return output.getvalue()
    except Exception as exc:
        raise PermanentIngestionFailure(
            f"Unable to serialize spreadsheet table {table.table_id}"
        ) from exc


def _safe_values(values: dict[str, Any]) -> dict[str, Any]:
    return {
        name: float(value) if isinstance(value, Decimal) else value
        for name, value in values.items()
    }
