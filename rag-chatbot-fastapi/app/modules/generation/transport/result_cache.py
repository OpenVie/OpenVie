from __future__ import annotations

import logging

from redis.asyncio import Redis

from app.generated import cacanode_ai_v1_pb2 as pb

logger = logging.getLogger(__name__)


class ProtobufGenerationResultCache:
    def __init__(self, redis_client: Redis, *, prefix: str, ttl_seconds: int) -> None:
        self._redis = redis_client
        self._prefix = prefix.rstrip(":")
        self._ttl = ttl_seconds

    async def get(self, generation_id: str) -> pb.GenerateAnswerResponse | None:
        try:
            value = await self._redis.get(self._key(generation_id))
            return None if value is None else pb.GenerateAnswerResponse.FromString(value)
        except Exception:
            logger.warning("Generation result-cache read failed open", exc_info=True)
            return None

    async def put(self, generation_id: str, response: pb.GenerateAnswerResponse) -> None:
        try:
            await self._redis.setex(
                self._key(generation_id), self._ttl, response.SerializeToString()
            )
        except Exception:
            logger.warning("Generation result-cache write failed open", exc_info=True)

    def _key(self, generation_id: str) -> str:
        return f"{self._prefix}:generation-result:{generation_id}"

