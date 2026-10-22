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

import io
import json
import logging
from datetime import datetime, timezone
from typing import Any, Protocol
from uuid import uuid4

import pyarrow as pa
import pyarrow.parquet as pq

from app.common.storage_keys import lakehouse_partition_key, lakehouse_partition_prefix
from app.modules.lakehouse.models import LakehouseDataset

logger = logging.getLogger(__name__)

PARQUET_CONTENT_TYPE = "application/vnd.apache.parquet"


class StorageWriter(Protocol):
    async def upload(self, storage_key: str, data: bytes, *, content_type: str) -> None: ...


class StorageReader(Protocol):
    async def download(self, storage_key: str) -> bytes: ...


class LakehouseArchiveSink:
    """Writes partitioned Parquet lakehouse archives to object storage."""

    def __init__(self, store: StorageWriter):
        self._store = store

    async def write_partition(
        self,
        tenant_id: str,
        dataset: str | LakehouseDataset,
        records: list[dict[str, Any]],
        *,
        batch_id: str | None = None,
        timestamp: datetime | None = None,
    ) -> str:
        """Serializes records to snappy-compressed Parquet and uploads to lakehouse storage."""
        if not records:
            raise ValueError("Cannot archive empty record batch to lakehouse")

        ts = timestamp or datetime.now(timezone.utc)
        bid = batch_id or str(uuid4())
        dataset_name = dataset.value if isinstance(dataset, LakehouseDataset) else str(dataset)

        # Standardize records: ensure tenant_id, timestamp, and json serialization of complex types
        normalized: list[dict[str, Any]] = []
        for r in records:
            row = dict(r)
            row.setdefault("tenant_id", tenant_id)
            if "created_at" not in row:
                row["created_at"] = ts.isoformat()
            elif isinstance(row["created_at"], datetime):
                row["created_at"] = row["created_at"].isoformat()

            # Ensure complex types (dict, list) are JSON strings for portable Arrow storage
            for k, v in list(row.items()):
                if isinstance(v, (dict, list)):
                    row[k] = json.dumps(v, ensure_ascii=False)
            normalized.append(row)

        table = pa.Table.from_pylist(normalized)
        buf = io.BytesIO()
        pq.write_table(table, buf, compression="snappy")
        parquet_bytes = buf.getvalue()

        storage_key = lakehouse_partition_key(
            tenant_id=tenant_id,
            dataset=dataset_name,
            year=ts.year,
            month=ts.month,
            batch_id=bid,
        )

        await self._store.upload(
            storage_key=storage_key,
            data=parquet_bytes,
            content_type=PARQUET_CONTENT_TYPE,
        )

        logger.info(
            "Archived %d records to lakehouse partition key=%s bytes=%d",
            len(records),
            storage_key,
            len(parquet_bytes),
        )
        return storage_key
