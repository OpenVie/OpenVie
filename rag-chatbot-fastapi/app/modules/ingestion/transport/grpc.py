from __future__ import annotations

import logging

import grpc

from app.generated import cacanode_ai_v1_pb2 as pb
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
