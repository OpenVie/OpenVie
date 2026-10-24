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

import asyncio
import json
import logging
from typing import Any

import aio_pika
from aio_pika.abc import AbstractIncomingMessage

from app.modules.lakehouse.api import LakehouseDataset
from app.modules.lakehouse.internal.sink import LakehouseArchiveSink

logger = logging.getLogger(__name__)

LAKEHOUSE_EXCHANGE = "openvie.ingestion.v1"
LAKEHOUSE_CDC_QUEUE = "openvie.lakehouse.cdc.v1"
CDC_ROUTING_KEY_PATTERN = "cdc.events.*"


class LakehouseCdcWorker:
    """RabbitMQ consumer that streams CDC events and batches them to Parquet lakehouse storage."""

    def __init__(
        self,
        rabbitmq_url: str,
        sink: LakehouseArchiveSink,
        *,
        batch_size: int = 50,
        flush_interval_seconds: float = 10.0,
    ):
        self._url = rabbitmq_url
        self._sink = sink
        self._batch_size = batch_size
        self._flush_interval = flush_interval_seconds
        self._buffer: dict[tuple[str, str], list[dict[str, Any]]] = {}
        self._running = False
        self._task: asyncio.Task[None] | None = None
        self._flush_task: asyncio.Task[None] | None = None
        self._connection: Any | None = None
        self._channel: Any | None = None

    async def start(self) -> None:
        """Connects to RabbitMQ and starts consumer and periodic flush tasks."""
        if self._running:
            return
        self._running = True
        self._task = asyncio.create_task(self._consume_loop(), name="lakehouse-cdc-consumer")
        self._flush_task = asyncio.create_task(
            self._flush_timer_loop(), name="lakehouse-cdc-flusher"
        )
        logger.info("Lakehouse CDC worker started on queue %s", LAKEHOUSE_CDC_QUEUE)

    async def stop(self) -> None:
        """Flushes remaining buffered records and closes RabbitMQ resources."""
        self._running = False
        if self._flush_task:
            self._flush_task.cancel()
        if self._task:
            self._task.cancel()
        await self.flush_all()
        if self._connection and not self._connection.is_closed:
            await self._connection.close()
        logger.info("Lakehouse CDC worker stopped")

    async def record_event(
        self,
        tenant_id: str,
        dataset: str | LakehouseDataset,
        data: dict[str, Any],
    ) -> None:
        """Directly queues a CDC event into the buffer."""
        dataset_name = dataset.value if isinstance(dataset, LakehouseDataset) else str(dataset)
        key = (tenant_id, dataset_name)
        if key not in self._buffer:
            self._buffer[key] = []
        self._buffer[key].append(data)

        if len(self._buffer[key]) >= self._batch_size:
            await self._flush_key(key)

    async def flush_all(self) -> None:
        """Flushes all current tenant/dataset buffers to storage."""
        keys = list(self._buffer.keys())
        for key in keys:
            await self._flush_key(key)

    async def _flush_key(self, key: tuple[str, str]) -> None:
        records = self._buffer.pop(key, None)
        if not records:
            return
        tenant_id, dataset = key
        try:
            await self._sink.write_partition(tenant_id, dataset, records)
        except Exception as exc:
            logger.error(
                "Failed to flush lakehouse batch for tenant %s dataset %s: %s",
                tenant_id,
                dataset,
                exc,
            )
            if key not in self._buffer:
                self._buffer[key] = []
            self._buffer[key].extend(records)

    async def _flush_timer_loop(self) -> None:
        while self._running:
            try:
                await asyncio.sleep(self._flush_interval)
                await self.flush_all()
            except asyncio.CancelledError:
                break
            except Exception as exc:
                logger.warning("Error during periodic lakehouse flush: %s", exc)

    async def _consume_loop(self) -> None:
        while self._running:
            try:
                connection = await aio_pika.connect_robust(self._url)
                self._connection = connection
                channel = await connection.channel()
                self._channel = channel
                await channel.set_qos(prefetch_count=self._batch_size)

                exchange = await channel.declare_exchange(
                    LAKEHOUSE_EXCHANGE,
                    aio_pika.ExchangeType.TOPIC,
                    durable=True,
                )
                queue = await channel.declare_queue(LAKEHOUSE_CDC_QUEUE, durable=True)
                await queue.bind(exchange, routing_key=CDC_ROUTING_KEY_PATTERN)
                await queue.bind(exchange, routing_key="document.ingest.completed")

                async with queue.iterator() as queue_iter:
                    async for message in queue_iter:
                        if not self._running:
                            break
                        await self._process_message(message)
            except asyncio.CancelledError:
                break
            except Exception as exc:
                if self._running:
                    logger.warning(
                        "Lakehouse CDC consumer connection failed: %s. Retrying in 5s...", exc
                    )
                    await asyncio.sleep(5.0)

    async def _process_message(self, message: AbstractIncomingMessage) -> None:
        async with message.process():
            try:
                payload = json.loads(message.body.decode("utf-8"))
                routing_key = message.routing_key or ""

                if routing_key == "document.ingest.completed":
                    tenant_id = str(payload.get("tenant_id", ""))
                    if tenant_id:
                        await self.record_event(
                            tenant_id=tenant_id,
                            dataset=LakehouseDataset.DOCUMENT_LIFECYCLE,
                            data={
                                "document_id": str(payload.get("document_id", "")),
                                "knowledge_base_id": str(payload.get("knowledge_base_id", "")),
                                "file_name": str(payload.get("file_name", "unknown")),
                                "content_type": str(
                                    payload.get("content_type", "application/octet-stream")
                                ),
                                "file_size_bytes": int(payload.get("file_size_bytes", 0)),
                                "chunk_count": int(payload.get("chunk_count", 1)),
                                "entity_count": int(payload.get("entity_count", 0)),
                                "relation_count": int(payload.get("relation_count", 0)),
                                "ingestion_status": "COMPLETED",
                                "duration_seconds": float(payload.get("duration_seconds", 1.0)),
                            },
                        )
                elif "tenant_id" in payload and "dataset" in payload:
                    await self.record_event(
                        tenant_id=str(payload["tenant_id"]),
                        dataset=payload["dataset"],
                        data=payload.get("data", payload),
                    )
            except Exception as exc:
                logger.error("Failed to parse and record CDC message: %s", exc)
