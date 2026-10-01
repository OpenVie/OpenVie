from __future__ import annotations

import os
from uuid import uuid4

import aio_pika
import pytest

from app.bootstrap.configuration import ingestion_transport_config
from app.bootstrap.settings import Settings
from app.modules.ingestion.transport import rabbitmq


class UnusedPipeline:
    async def process(self, command: object) -> object:
        raise AssertionError(command)


@pytest.mark.asyncio
@pytest.mark.skipif(
    not os.getenv("RABBITMQ_TEST_URL"), reason="RABBITMQ_TEST_URL is not set"
)
async def test_document_worker_declares_durable_confirmed_topology(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    suffix = uuid4().hex
    names = {
        "INGESTION_EXCHANGE": f"test.cacanode.ingestion.{suffix}",
        "DEAD_LETTER_EXCHANGE": f"test.cacanode.dlx.{suffix}",
        "INGESTION_QUEUE": f"test.cacanode.document.ingestion.{suffix}",
        "INGESTION_DLQ": f"test.cacanode.document.ingestion.dlq.{suffix}",
    }
    for name, value in names.items():
        monkeypatch.setattr(rabbitmq, name, value)
    connection = await aio_pika.connect_robust(os.environ["RABBITMQ_TEST_URL"])
    worker = rabbitmq.DocumentWorker(
        ingestion_transport_config(Settings(_env_file=())),
        UnusedPipeline(),  # type: ignore[arg-type]
        connection=connection,
    )
    try:
        await worker.start()
        channel = await connection.channel(publisher_confirms=True)
        assert await channel.get_queue(names["INGESTION_QUEUE"], ensure=True)
        assert await channel.get_queue(names["INGESTION_DLQ"], ensure=True)
        await channel.close()
    finally:
        await worker.stop()
        channel = await connection.channel()
        for queue_name in (names["INGESTION_QUEUE"], names["INGESTION_DLQ"]):
            queue = await channel.get_queue(queue_name, ensure=False)
            await queue.delete(if_unused=False, if_empty=False)
        for exchange_name in (names["INGESTION_EXCHANGE"], names["DEAD_LETTER_EXCHANGE"]):
            exchange = await channel.get_exchange(exchange_name, ensure=False)
            await exchange.delete(if_unused=False)
        await channel.close()
        await connection.close()
