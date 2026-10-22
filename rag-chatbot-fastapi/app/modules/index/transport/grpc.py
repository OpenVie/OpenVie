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

import logging

import grpc

from app.generated import openvie_ai_v1_pb2 as pb
from app.modules.index.api import KnowledgeIndexQueryApi, KnowledgeIndexResult

logger = logging.getLogger(__name__)


class IndexGrpcHandler:
    def __init__(self, query: KnowledgeIndexQueryApi) -> None:
        self._query = query

    async def list_document_units(
        self, request: pb.ListDocumentUnitsRequest, context: grpc.aio.ServicerContext
    ) -> pb.ListDocumentUnitsResponse:
        try:
            units = await self._query.list_document_units(
                request.tenant_id, request.document_id
            )
        except Exception:
            logger.exception("gRPC document-unit read failed document_id=%s", request.document_id)
            await context.abort(grpc.StatusCode.UNAVAILABLE, "Indexed document content unavailable")
        if not units:
            await context.abort(grpc.StatusCode.NOT_FOUND, "Indexed document was not found")
        return pb.ListDocumentUnitsResponse(units=[_unit(unit) for unit in units])


def _unit(unit: KnowledgeIndexResult) -> pb.DocumentUnit:
    value = pb.DocumentUnit(
        chunk_index=int(unit.chunk_index),
        text=str(unit.text),
        section_path=list(unit.section_path),
    )
    for field in (
        "unit_id",
        "source_name",
        "modality",
        "block_type",
        "heading_context",
        "page_number",
        "sheet_name",
        "cell_range",
        "table_id",
        "source_start",
        "source_end",
    ):
        item = getattr(unit, field)
        if item is not None:
            setattr(value, field, item)
    return value
