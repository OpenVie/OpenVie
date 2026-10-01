from __future__ import annotations

import aio_pika

from app.modules.ingestion.internal.checkpoints import RedisIngestionCheckpointStore
from app.modules.ingestion.transport.rabbitmq import INGEST_REQUESTED, INGESTION_EXCHANGE


class RabbitCheckpointRepublisher:
    def __init__(
        self,
        checkpoints: RedisIngestionCheckpointStore,
        *,
        rabbitmq_url: str,
    ) -> None:
        self._checkpoints = checkpoints
        self._rabbitmq_url = rabbitmq_url

    async def republish_incomplete(self, *, limit: int) -> int:
        if not 1 <= limit <= 1000:
            raise ValueError("Recovery limit must be between 1 and 1000")
        payloads = await self._checkpoints.incomplete_requests(limit=limit)
        connection = await aio_pika.connect_robust(self._rabbitmq_url)
        try:
            channel = await connection.channel(publisher_confirms=True)
            exchange = await channel.declare_exchange(
                INGESTION_EXCHANGE, aio_pika.ExchangeType.TOPIC, durable=True
            )
            for payload in payloads:
                await exchange.publish(
                    aio_pika.Message(
                        payload,
                        content_type="application/json",
                        content_encoding="utf-8",
                        delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
                    ),
                    routing_key=INGEST_REQUESTED,
                    mandatory=True,
                )
            return len(payloads)
        finally:
            await connection.close()
