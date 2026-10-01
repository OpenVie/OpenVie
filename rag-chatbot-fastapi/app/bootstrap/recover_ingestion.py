from __future__ import annotations

import argparse
import asyncio

import redis.asyncio as redis

from app.bootstrap.settings import settings
from app.maintenance.recover_ingestion import run
from app.modules.ingestion.internal.checkpoints import RedisIngestionCheckpointStore
from app.modules.ingestion.transport.recovery import RabbitCheckpointRepublisher


async def recover(limit: int) -> int:
    client = redis.from_url(
        settings.REDIS_URL,
        decode_responses=False,
        socket_connect_timeout=settings.REDIS_CONNECT_TIMEOUT_SECONDS,
        socket_timeout=settings.REDIS_OPERATION_TIMEOUT_SECONDS,
    )
    try:
        checkpoints = RedisIngestionCheckpointStore(
            client,
            prefix=settings.CACHE_KEY_PREFIX,
            retention_seconds=settings.INGESTION_CHECKPOINT_RETENTION_SECONDS,
            lease_seconds=settings.INGESTION_LEASE_SECONDS,
        )
        return await run(
            RabbitCheckpointRepublisher(
                checkpoints, rabbitmq_url=settings.RABBITMQ_URL
            ),
            limit=limit,
        )
    finally:
        await client.aclose()


def main() -> None:
    parser = argparse.ArgumentParser(description="Republish incomplete ingestion checkpoints")
    parser.add_argument("--limit", type=int, default=100)
    args = parser.parse_args()
    print(asyncio.run(recover(args.limit)))


if __name__ == "__main__":
    main()
