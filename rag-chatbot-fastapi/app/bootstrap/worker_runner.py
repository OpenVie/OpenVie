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

import argparse
import asyncio
import signal

import redis.asyncio as redis

from app.bootstrap.configuration import model_config
from app.bootstrap.settings import settings
from app.bootstrap.workers import SUPPORTED_WORKERS, WorkerManager
from app.common.cache import RedisCacheStore, TtlJitter
from app.modules.model.internal.embedding import create_embedding_client


async def run(kind: str) -> None:
    redis_client = redis.from_url(
        settings.REDIS_URL,
        decode_responses=False,
        socket_connect_timeout=settings.REDIS_CONNECT_TIMEOUT_SECONDS,
        socket_timeout=settings.REDIS_OPERATION_TIMEOUT_SECONDS,
    )
    cache_store = RedisCacheStore(
        redis_client,
        enabled=settings.CACHE_ENABLED,
        ttl_jitter=TtlJitter(settings.CACHE_TTL_JITTER_PERCENT),
    )
    manager = WorkerManager(
        settings,
        kinds=(kind,),
        embedder=create_embedding_client(model_config(settings), cache_store),
        redis_client=redis_client,
    )
    stopped = asyncio.Event()
    loop = asyncio.get_running_loop()
    for signum in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(signum, stopped.set)
        except NotImplementedError:
            pass
    try:
        await manager.start()
        await stopped.wait()
    finally:
        await manager.stop()
        await redis_client.aclose()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("kind", choices=SUPPORTED_WORKERS)
    args = parser.parse_args()
    asyncio.run(run(args.kind))


if __name__ == "__main__":
    main()
