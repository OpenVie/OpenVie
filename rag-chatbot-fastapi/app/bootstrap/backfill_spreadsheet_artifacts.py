from __future__ import annotations

import argparse
import asyncio
import logging
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import PurePosixPath

from app.bootstrap.configuration import storage_config
from app.bootstrap.settings import Settings
from app.common.storage import SeaweedS3DocumentStore
from app.modules.ingestion.internal.artifacts import (
    prepare_spreadsheet_artifacts,
    replace_spreadsheet_artifacts,
)
from app.modules.ingestion.internal.extraction import DocumentTextExtractor

LOGGER = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class SourceIdentity:
    tenant_id: str
    knowledge_base_id: str
    document_id: str
    file_name: str


async def run(settings: Settings, storage_keys: Sequence[str]) -> int:
    store = SeaweedS3DocumentStore(storage_config(settings))
    extractor = DocumentTextExtractor()
    artifact_count = 0
    for storage_key in storage_keys:
        identity = _source_identity(storage_key)
        data = await store.download(storage_key)
        parsed = extractor.parse(
            data,
            content_type=_spreadsheet_content_type(identity.file_name),
            file_name=identity.file_name,
        )
        if parsed.modality != "spreadsheet":
            raise ValueError(f"Source is not a spreadsheet: {storage_key}")
        artifacts = prepare_spreadsheet_artifacts(
            parsed,
            tenant_id=identity.tenant_id,
            knowledge_base_id=identity.knowledge_base_id,
            document_id=identity.document_id,
        )
        await replace_spreadsheet_artifacts(
            store,
            tenant_id=identity.tenant_id,
            knowledge_base_id=identity.knowledge_base_id,
            document_id=identity.document_id,
            artifacts=artifacts,
        )
        artifact_count += len(artifacts)
        LOGGER.info(
            "Backfilled spreadsheet artifacts document_id=%s count=%s",
            identity.document_id,
            len(artifacts),
        )
    return artifact_count


def _source_identity(storage_key: str) -> SourceIdentity:
    parts = PurePosixPath(storage_key).parts
    if (
        len(parts) != 7
        or parts[0] != "tenants"
        or parts[2] != "knowledge-bases"
        or parts[4] != "documents"
    ):
        raise ValueError(f"Storage key does not match the document layout: {storage_key}")
    return SourceIdentity(
        tenant_id=parts[1],
        knowledge_base_id=parts[3],
        document_id=parts[5],
        file_name=parts[6],
    )


def _spreadsheet_content_type(file_name: str) -> str:
    suffix = PurePosixPath(file_name).suffix.casefold()
    if suffix == ".csv":
        return "text/csv"
    if suffix == ".xlsx":
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    raise ValueError(f"Unsupported spreadsheet file extension: {file_name}")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Backfill deterministic Parquet artifacts for existing spreadsheets"
    )
    parser.add_argument(
        "--storage-key",
        action="append",
        required=True,
        help="Existing CSV/XLSX source key; repeat for multiple documents",
    )
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO)
    count = asyncio.run(run(Settings(), args.storage_key))
    LOGGER.info("Backfill complete artifact_count=%s", count)


if __name__ == "__main__":
    main()
