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
from app.modules.ingestion.api import DocumentIndexLifecycleApi

logger = logging.getLogger(__name__)


class IngestionGrpcHandler:
    def __init__(self, lifecycle: DocumentIndexLifecycleApi) -> None:
        self._lifecycle = lifecycle

    async def delete_document_index(
        self, request: pb.DeleteDocumentIndexRequest, context: grpc.aio.ServicerContext
    ) -> pb.DeleteDocumentIndexResponse:
        try:
            await self._lifecycle.delete_document(
                request.tenant_id, request.knowledge_base_id, request.document_id
            )
        except Exception:
            logger.exception(
                "gRPC document-index deletion failed document_id=%s", request.document_id
            )
            await context.abort(grpc.StatusCode.UNAVAILABLE, "Document index deletion failed")
        return pb.DeleteDocumentIndexResponse(deleted=True)
