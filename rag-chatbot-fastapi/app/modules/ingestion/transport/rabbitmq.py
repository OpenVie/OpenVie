from __future__ import annotations

import asyncio
import json
import logging
from typing import Any

import aio_pika
from aio_pika.abc import AbstractIncomingMessage

from app.common.errors import StorageUnavailableError
from app.contracts.document_ingestion_v1 import (
    DocumentIngestRequestedEvent,
    partial_status_ids,
)
from app.modules.graph.api import GraphRejectedError, GraphUnavailableError
from app.modules.index.api import IndexRejectedError, IndexUnavailableError
from app.modules.ingestion.api import (
    IngestDocumentCommand,
    PermanentIngestionFailure,
    TransientIngestionFailure,
)
from app.modules.ingestion.api.event import status_event
from app.modules.ingestion.internal.checkpoints import (
    ClaimStatus,
    IngestionPhase,
    RedisIngestionCheckpointStore,
)
from app.modules.ingestion.internal.config import IngestionTransportConfig
from app.modules.ingestion.internal.pipeline import DocumentIngestionPipeline
from app.modules.model.api import ModelRejectedError, ModelUnavailableError

logger = logging.getLogger(__name__)

INGESTION_EXCHANGE = "cacanode.ingestion.v1"
DEAD_LETTER_EXCHANGE = "cacanode.dlx.v1"
INGESTION_QUEUE = "cacanode.document.ingestion.v1"
INGESTION_DLQ = "cacanode.document.ingestion.dlq.v1"
INGEST_REQUESTED = "document.ingest.requested"
INGEST_PROCESSING = "document.ingest.processing"
INGEST_COMPLETED = "document.ingest.completed"
INGEST_FAILED = "document.ingest.failed"
MAX_TRANSIENT_RETRIES = 3
RETRY_HEADER = "x-retry-count"
_PHASE_ORDER = {
    IngestionPhase.CLAIMED: 0,
    IngestionPhase.PROCESSING_PUBLISHED: 1,
    IngestionPhase.INDEX_REPLACED: 2,
    IngestionPhase.GRAPH_REPLACED: 3,
    IngestionPhase.COMPLETED_PUBLISHED: 4,
    IngestionPhase.COMPLETE: 5,
    IngestionPhase.CLEANUP_PENDING: 2,
    IngestionPhase.FAILED_PUBLISHED: 4,
    IngestionPhase.FAILED: 5,
}


def _before(current: IngestionPhase, target: IngestionPhase) -> bool:
    return _PHASE_ORDER[current] < _PHASE_ORDER[target]


class DocumentWorker:
    def __init__(
        self,
        settings: IngestionTransportConfig,
        pipeline: DocumentIngestionPipeline,
        connection: Any | None = None,
        checkpoints: RedisIngestionCheckpointStore | None = None,
    ):
        self._settings = settings
        self._pipeline = pipeline
        self._connection: Any | None = connection
        self._checkpoints = checkpoints
        self._owns_connection = connection is None
        self._channel: Any | None = None
        self._exchange: Any | None = None
        self._queue: Any | None = None

    async def run(self, stop: asyncio.Event) -> None:
        await self.start()
        try:
            assert self._queue is not None
            consumer_tag = await self._queue.consume(self.handle_message, no_ack=False)
            await stop.wait()
            await self._queue.cancel(consumer_tag)
        finally:
            await self.stop()

    async def start(self) -> None:
        if self._connection is None:
            self._connection = await aio_pika.connect_robust(self._settings.RABBITMQ_URL)
        connection = self._connection
        channel = await connection.channel(publisher_confirms=True)
        self._channel = channel
        await channel.set_qos(prefetch_count=1)
        self._exchange = await channel.declare_exchange(
            INGESTION_EXCHANGE,
            aio_pika.ExchangeType.TOPIC,
            durable=True,
        )
        dead_letter_exchange = await channel.declare_exchange(
            DEAD_LETTER_EXCHANGE,
            aio_pika.ExchangeType.TOPIC,
            durable=True,
        )
        self._queue = await channel.declare_queue(
            INGESTION_QUEUE,
            durable=True,
            arguments={"x-dead-letter-exchange": DEAD_LETTER_EXCHANGE},
        )
        await self._queue.bind(self._exchange, routing_key=INGEST_REQUESTED)
        dead_letter_queue = await channel.declare_queue(INGESTION_DLQ, durable=True)
        await dead_letter_queue.bind(dead_letter_exchange, routing_key="document.ingest.*")

    async def stop(self) -> None:
        if self._channel is not None:
            await self._channel.close()
            self._channel = None
        if self._connection is not None and self._owns_connection:
            await self._connection.close()
            self._connection = None

    async def handle_message(self, message: AbstractIncomingMessage) -> None:
        event: DocumentIngestRequestedEvent | None = None
        lease_token: str | None = None
        heartbeat: asyncio.Task[None] | None = None
        phase = IngestionPhase.CLAIMED
        try:
            event = DocumentIngestRequestedEvent.parse_payload(message.body)
            command = _command(event)
            if self._checkpoints is None:
                await self.publish_status(event, "PROCESSING")
                if hasattr(self._pipeline, "process"):
                    outcome = await self._pipeline.process(command)
                    chunk_count = outcome.chunk_count
                else:
                    chunk_count = await self._pipeline.ingest(event)  # type: ignore[attr-defined]
                await self.publish_status(event, "COMPLETED", chunk_count=chunk_count)
                await message.ack()
                return
            claim = await self._checkpoints.claim(
                event_id=str(event.event_id),
                job_id=str(event.job_id),
                request_payload=message.body,
            )
            if claim.status is ClaimStatus.COMPLETE:
                await message.ack()
                return
            if claim.status is ClaimStatus.BUSY:
                await message.nack(requeue=True)
                return
            if claim.status is ClaimStatus.PAYLOAD_MISMATCH:
                raise PermanentIngestionFailure("Ingestion event payload hash mismatch")
            lease_token = claim.lease_token
            assert lease_token is not None
            phase = claim.phase
            heartbeat = asyncio.create_task(
                self._heartbeat(str(event.job_id), lease_token),
                name=f"ingestion-lease-{event.job_id}",
            )
            if _before(phase, IngestionPhase.PROCESSING_PUBLISHED):
                await self.publish_status(event, "PROCESSING")
                await self._transition(
                    event, lease_token, IngestionPhase.PROCESSING_PUBLISHED,
                    published_flag="processing_published",
                )
                phase = IngestionPhase.PROCESSING_PUBLISHED
            prepared = await self._pipeline.prepare(command)
            if _before(phase, IngestionPhase.INDEX_REPLACED):
                await self._pipeline.replace_index(prepared)
                await self._transition(event, lease_token, IngestionPhase.INDEX_REPLACED)
                phase = IngestionPhase.INDEX_REPLACED
            if _before(phase, IngestionPhase.GRAPH_REPLACED):
                await self._pipeline.replace_graph(prepared)
                await self._transition(event, lease_token, IngestionPhase.GRAPH_REPLACED)
                phase = IngestionPhase.GRAPH_REPLACED
            chunk_count = len(prepared.chunks)
            if _before(phase, IngestionPhase.COMPLETED_PUBLISHED):
                await self.publish_status(event, "COMPLETED", chunk_count=chunk_count)
                await self._transition(
                    event,
                    lease_token,
                    IngestionPhase.COMPLETED_PUBLISHED,
                    chunk_count=chunk_count,
                    published_flag="completed_published",
                )
            await self._transition(
                event, lease_token, IngestionPhase.COMPLETE, chunk_count=chunk_count
            )
            await message.ack()
        except (
            PermanentIngestionFailure,
            ModelRejectedError,
            IndexRejectedError,
            GraphRejectedError,
            ValueError,
        ) as exc:
            logger.warning("Permanent document ingestion failure: %s", exc)
            try:
                if event is not None and lease_token is not None and not _before(
                    phase, IngestionPhase.INDEX_REPLACED
                ):
                    await self._transition(
                        event,
                        lease_token,
                        IngestionPhase.CLEANUP_PENDING,
                        error=str(exc),
                    )
                    await self._pipeline.cleanup(_command(event))
                await self._publish_failed_for(message.body, event, str(exc))
                if event is not None and lease_token is not None:
                    await self._transition(
                        event,
                        lease_token,
                        IngestionPhase.FAILED_PUBLISHED,
                        error=str(exc),
                        published_flag="failed_published",
                    )
                    await self._transition(
                        event, lease_token, IngestionPhase.FAILED, error=str(exc)
                    )
            except Exception as publish_exc:
                await self._retry_or_dead_letter(message, event, str(publish_exc))
            else:
                await message.reject(requeue=False)
        except (
            TransientIngestionFailure,
            StorageUnavailableError,
            ModelUnavailableError,
            IndexUnavailableError,
            GraphUnavailableError,
        ) as exc:
            logger.warning("Transient document ingestion failure: %s", exc)
            await self._handle_transient_failure(
                message, event, str(exc), lease_token, phase
            )
        except Exception as exc:
            logger.exception("Unexpected document ingestion failure")
            await self._handle_transient_failure(
                message, event, str(exc), lease_token, phase
            )
        finally:
            if heartbeat is not None:
                heartbeat.cancel()
                await asyncio.gather(heartbeat, return_exceptions=True)
            if event is not None and lease_token is not None and self._checkpoints is not None:
                await self._checkpoints.release(str(event.job_id), lease_token)

    async def _heartbeat(self, job_id: str, lease_token: str) -> None:
        assert self._checkpoints is not None
        while True:
            await asyncio.sleep(self._settings.INGESTION_HEARTBEAT_SECONDS)
            if not await self._checkpoints.renew(job_id, lease_token):
                raise RuntimeError("Ingestion checkpoint lease was lost")

    async def _handle_transient_failure(
        self,
        message: AbstractIncomingMessage,
        event: DocumentIngestRequestedEvent | None,
        error_message: str,
        lease_token: str | None,
        phase: IngestionPhase,
    ) -> None:
        if self._retry_count(message) < MAX_TRANSIENT_RETRIES:
            await self._republish_retry(message, self._retry_count(message) + 1)
            await message.ack()
            return
        try:
            if event is not None and lease_token is not None:
                if not _before(phase, IngestionPhase.INDEX_REPLACED):
                    await self._transition(
                        event,
                        lease_token,
                        IngestionPhase.CLEANUP_PENDING,
                        error=error_message,
                    )
                    await self._pipeline.cleanup(_command(event))
                await self._publish_failed_for(message.body, event, error_message)
                await self._transition(
                    event,
                    lease_token,
                    IngestionPhase.FAILED_PUBLISHED,
                    error=error_message,
                    published_flag="failed_published",
                )
                await self._transition(
                    event, lease_token, IngestionPhase.FAILED, error=error_message
                )
            else:
                await self._publish_failed_for(message.body, event, error_message)
        finally:
            await message.reject(requeue=False)

    async def _transition(
        self,
        event: DocumentIngestRequestedEvent,
        lease_token: str,
        phase: IngestionPhase,
        *,
        chunk_count: int | None = None,
        error: str | None = None,
        published_flag: str = "",
    ) -> None:
        assert self._checkpoints is not None
        await self._checkpoints.transition(
            str(event.job_id),
            lease_token,
            phase,
            chunk_count=chunk_count,
            error=error,
            published_flag=published_flag,
        )

    async def publish_status(
        self,
        event: DocumentIngestRequestedEvent,
        status: str,
        *,
        chunk_count: int | None = None,
        error_message: str | None = None,
    ) -> None:
        payload = status_event(
            schema_version=event.schema_version,
            job_id=event.job_id,
            tenant_id=event.tenant_id,
            document_id=event.document_id,
            status=status,
            chunk_count=chunk_count,
            error_message=error_message,
        )
        routing_key = {
            "PROCESSING": INGEST_PROCESSING,
            "COMPLETED": INGEST_COMPLETED,
            "FAILED": INGEST_FAILED,
        }[status]
        await self.publish_json(routing_key, payload)

    async def publish_json(
        self,
        routing_key: str,
        payload: dict[str, Any],
        *,
        headers: dict[str, Any] | None = None,
    ) -> None:
        if self._exchange is None:
            raise TransientIngestionFailure("RabbitMQ exchange is not initialized")
        body = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8")
        await self._exchange.publish(
            aio_pika.Message(
                body,
                headers=headers,
                content_type="application/json",
                content_encoding="utf-8",
                delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
                message_id=str(payload.get("event_id") or ""),
                correlation_id=str(payload.get("job_id") or ""),
            ),
            routing_key=routing_key,
            mandatory=True,
        )

    async def _publish_failed_for(
        self,
        body: bytes,
        event: DocumentIngestRequestedEvent | None,
        error_message: str,
    ) -> None:
        if event is not None:
            await self.publish_status(event, "FAILED", error_message=error_message)
            return

        ids = partial_status_ids(body)
        if not all(ids.get(name) for name in ("job_id", "tenant_id", "document_id")):
            return
        await self.publish_json(
            INGEST_FAILED,
            status_event(
                schema_version=str(ids.get("schema_version") or "1.0"),
                job_id=ids.get("job_id"),
                tenant_id=ids.get("tenant_id"),
                document_id=ids.get("document_id"),
                status="FAILED",
                error_message=error_message,
            ),
        )

    async def _retry_or_dead_letter(
        self,
        message: AbstractIncomingMessage,
        event: DocumentIngestRequestedEvent | None,
        error_message: str,
    ) -> None:
        retry_count = self._retry_count(message)
        if retry_count < MAX_TRANSIENT_RETRIES:
            await self._republish_retry(message, retry_count + 1)
            await message.ack()
            return

        try:
            await self._publish_failed_for(message.body, event, error_message)
        finally:
            await message.reject(requeue=False)

    async def _republish_retry(self, message: AbstractIncomingMessage, retry_count: int) -> None:
        if self._exchange is None:
            raise TransientIngestionFailure("RabbitMQ exchange is not initialized")
        headers = dict(message.headers or {})
        headers[RETRY_HEADER] = retry_count
        await self._exchange.publish(
            aio_pika.Message(
                message.body,
                headers=headers,
                content_type=message.content_type or "application/json",
                content_encoding=message.content_encoding or "utf-8",
                delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
                message_id=message.message_id,
                correlation_id=message.correlation_id,
            ),
            routing_key=INGEST_REQUESTED,
            mandatory=True,
        )

    def _retry_count(self, message: AbstractIncomingMessage) -> int:
        raw = (message.headers or {}).get(RETRY_HEADER, 0)
        if isinstance(raw, bytes | bytearray):
            raw = raw.decode("utf-8")
        if not isinstance(raw, int | float | str):
            return 0
        try:
            return int(raw)
        except (TypeError, ValueError):
            return 0


def _command(event: DocumentIngestRequestedEvent) -> IngestDocumentCommand:
    return IngestDocumentCommand(
        schema_version=event.schema_version,
        event_id=str(event.event_id),
        job_id=str(event.job_id),
        tenant_id=str(event.tenant_id),
        knowledge_base_id=str(event.knowledge_base_id),
        document_id=str(event.document_id),
        uploader_id=str(event.uploader_id),
        storage_key=event.storage_key,
        file_name=event.file_name,
        content_type=event.content_type,
        file_size_bytes=event.file_size_bytes,
        occurred_at=event.occurred_at,
    )
