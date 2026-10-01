from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass
from io import BytesIO
from typing import Any

from app.common.errors import StorageNotFoundError
from app.common.storage import ObjectStorageReader
from app.common.storage_keys import spreadsheet_table_key
from app.modules.generation.internal.spreadsheets import (
    CalculationCommand,
    CalculationError,
    PolarsCalculationAdapter,
)
from app.modules.retrieval.api import RetrievedKnowledgeUnit as RetrievedChunk


@dataclass(frozen=True, slots=True)
class CalculationContext:
    text: str | None = None
    clarification: str | None = None


LOGGER = logging.getLogger(__name__)
MISSING_TABLE_CLARIFICATION = (
    "The spreadsheet calculation data is unavailable. Please reprocess the workbook and try again."
)


class SpreadsheetCalculationCoordinator:
    def __init__(self, store: ObjectStorageReader, model: Any):
        self._store = store
        self._model = model
        self._adapter = PolarsCalculationAdapter()

    async def prepare(
        self,
        *,
        tenant_id: str,
        knowledge_base_id: str,
        question: str,
        chunks: list[RetrievedChunk],
    ) -> CalculationContext | None:
        if not _looks_like_calculation(question):
            return None
        candidates = {
            (chunk.document_id, chunk.table_id)
            for chunk in chunks
            if chunk.modality == "spreadsheet" and chunk.table_id
        }
        if not candidates:
            return None
        if len(candidates) != 1:
            return CalculationContext(
                clarification="Please specify one workbook or logical table for this calculation."
            )
        document_id, table_id = next(iter(candidates))
        assert table_id is not None
        key = spreadsheet_table_key(
            tenant_id,
            knowledge_base_id,
            document_id,
            table_id,
        )
        try:
            data = await self._store.download(key)
        except StorageNotFoundError:
            LOGGER.warning(
                "Spreadsheet calculation artifact is missing document_id=%s table_id=%s",
                document_id,
                table_id,
            )
            return CalculationContext(clarification=MISSING_TABLE_CLARIFICATION)
        try:
            import polars as pl

            schema = pl.read_parquet_schema(BytesIO(data))
        except Exception as exc:
            LOGGER.warning(
                "Spreadsheet calculation artifact is unreadable document_id=%s table_id=%s: %s",
                document_id,
                table_id,
                exc,
            )
            return CalculationContext(clarification=MISSING_TABLE_CLARIFICATION)
        raw = await self._model.complete(
            [
                {"role": "system", "content": _PLANNER_PROMPT},
                {
                    "role": "user",
                    "content": json.dumps(
                        {
                            "question": question,
                            "table_id": table_id,
                            "columns": {name: str(dtype) for name, dtype in schema.items()},
                        },
                        ensure_ascii=False,
                    ),
                },
            ]
        )
        try:
            payload = json.loads(re.sub(r"^```(?:json)?\s*|\s*```$", "", raw.strip()))
            command = CalculationCommand.model_validate(payload)
            result = self._adapter.execute_parquet(data, command)
        except (ValueError, TypeError, json.JSONDecodeError, CalculationError):
            return CalculationContext(
                clarification="Please clarify the table column and calculation you want to use."
            )
        return CalculationContext(
            text=(
                "VERIFIED SPREADSHEET CALCULATION (do not recompute): "
                f"operation={result.operation}; row_count={result.row_count}; "
                f"result={json.dumps(result.value, default=str, ensure_ascii=False)}"
            )
        )


def _looks_like_calculation(question: str) -> bool:
    return bool(
        re.search(
            r"\b(count|sum|total|average|avg|minimum|maximum|min|max|top|bottom|sort|group)\b",
            question.casefold(),
        )
    )


_PLANNER_PROMPT = """Plan one safe spreadsheet calculation. Return only JSON matching:
{"table_id":"","operation":"count|sum|average|minimum|maximum|sort|top|bottom",
"column":null,"group_by":null,"filters":[{"column":"","operator":"eq|ne|gt|gte|lt|lte|between",
"value":"","end_value":null}],"limit":10}. Use only supplied columns. Never emit code, SQL,
Python, formulas, or expressions. A count may omit column; other operations require one column."""
