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
