from __future__ import annotations

import logging
from pathlib import Path

import grpc
from redis.asyncio import Redis

from app.bootstrap.configuration import graph_config, index_config, storage_config
from app.bootstrap.generation import RagRuntime
from app.bootstrap.settings import Settings
from app.common.storage import SeaweedS3DocumentStore
from app.generated import cacanode_ai_v1_pb2 as pb
from app.generated import cacanode_ai_v1_pb2_grpc as pb_grpc
from app.modules.generation.transport.grpc import GenerationGrpcHandler
from app.modules.generation.transport.result_cache import ProtobufGenerationResultCache
from app.modules.graph.internal.service import GraphServiceClient
from app.modules.index.internal.qdrant_commands import QdrantKnowledgeIndex
from app.modules.index.internal.qdrant_search import QdrantKnowledgeIndexQuery
from app.modules.index.transport.grpc import IndexGrpcHandler
from app.modules.ingestion.internal.pipeline import DocumentIndexLifecycleService
from app.modules.ingestion.transport.grpc import IngestionGrpcHandler

logger = logging.getLogger(__name__)


class InferenceGrpcService(pb_grpc.InferenceServiceServicer):
    def __init__(
        self,
        generation: GenerationGrpcHandler,
        index: IndexGrpcHandler,
        ingestion: IngestionGrpcHandler,
    ) -> None:
        self._generation = generation
        self._index = index
        self._ingestion = ingestion

    async def GenerateAnswer(
        self, request: pb.GenerateAnswerRequest, context: grpc.aio.ServicerContext
    ) -> pb.GenerateAnswerResponse:
        return await self._generation.generate(request, context)

    async def ListDocumentUnits(
        self, request: pb.ListDocumentUnitsRequest, context: grpc.aio.ServicerContext
    ) -> pb.ListDocumentUnitsResponse:
        return await self._index.list_document_units(request, context)

    async def DeleteDocumentIndex(
        self, request: pb.DeleteDocumentIndexRequest, context: grpc.aio.ServicerContext
    ) -> pb.DeleteDocumentIndexResponse:
        return await self._ingestion.delete_document_index(request, context)


def create_grpc_service(
    settings: Settings, runtime: RagRuntime, redis_client: Redis
) -> InferenceGrpcService:
    index_commands = QdrantKnowledgeIndex(index_config(settings))
    index_queries = QdrantKnowledgeIndexQuery(index_config(settings))
    graph = GraphServiceClient(graph_config(settings))
    return InferenceGrpcService(
        GenerationGrpcHandler(
            runtime.generation,
            ProtobufGenerationResultCache(
                redis_client,
                prefix=settings.CACHE_KEY_PREFIX,
                ttl_seconds=settings.GENERATION_RESULT_CACHE_TTL_SECONDS,
            ),
        ),
        IndexGrpcHandler(index_queries),
        IngestionGrpcHandler(
            DocumentIndexLifecycleService(
                index_commands,
                graph,
                SeaweedS3DocumentStore(storage_config(settings)),
            )
        ),
    )


async def start_grpc_server(
    settings: Settings, runtime: RagRuntime, redis_client: Redis
) -> grpc.aio.Server:
    server = grpc.aio.server(
        options=(
            ("grpc.max_receive_message_length", settings.GRPC_MAX_MESSAGE_BYTES),
            ("grpc.max_send_message_length", settings.GRPC_MAX_MESSAGE_BYTES),
        )
    )
    pb_grpc.add_InferenceServiceServicer_to_server(
        create_grpc_service(settings, runtime, redis_client), server
    )
    address = f"{settings.GRPC_HOST}:{settings.GRPC_PORT}"
    if settings.GRPC_PLAINTEXT:
        server.add_insecure_port(address)
    else:
        private_key = Path(settings.GRPC_SERVER_KEY).read_bytes()
        certificate = Path(settings.GRPC_SERVER_CERTIFICATE).read_bytes()
        client_ca = Path(settings.GRPC_CLIENT_CA_CERTIFICATE).read_bytes()
        credentials = grpc.ssl_server_credentials(
            ((private_key, certificate),),
            root_certificates=client_ca,
            require_client_auth=True,
        )
        server.add_secure_port(address, credentials)
    await server.start()
    logger.info("gRPC inference service listening on %s", address)
    return server
