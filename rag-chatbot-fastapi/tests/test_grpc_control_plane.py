from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable

import grpc
import pytest

from app.bootstrap.grpc import InferenceGrpcService
from app.generated import cacanode_ai_v1_pb2 as pb
from app.modules.generation.api import (
    Citation,
    GenerationContext,
    GenerationResult,
    GenerationTimeoutError,
    GenerationUnavailableError,
    TokenUsage,
)
from app.modules.generation.transport.grpc import GenerationGrpcHandler
from app.modules.generation.transport.result_cache import ProtobufGenerationResultCache


class Redis:
    def __init__(self) -> None:
        self.values: dict[str, bytes] = {}

    async def get(self, key: str) -> bytes | None:
        return self.values.get(key)

    async def setex(self, key: str, ttl: int, value: bytes) -> None:
        assert ttl == 600
        self.values[key] = value


class Context:
    def cancelled(self) -> bool:
        return False

    async def abort(self, code: grpc.StatusCode, details: str) -> None:
        raise RuntimeError(f"{code.name}:{details}")


class Generation:
    def __init__(self) -> None:
        self.calls = 0
        self.context: GenerationContext | None = None

    async def generate(
        self,
        context: GenerationContext,
        *,
        on_content: Callable[[str], Awaitable[None]] | None = None,
    ) -> GenerationResult:
        self.calls += 1
        self.context = context
        if on_content is not None:
            await on_content("Grounded")
            await on_content("Grounded answer")
        return GenerationResult(
            generation_id=context.generation_id,
            authoritative_revision=context.authoritative_revision,
            answer="Grounded answer [S1].",
            token_usage=TokenUsage(input_tokens=31, output_tokens=7),
            citations=(
                Citation(
                    id="S1",
                    document_id="doc-1",
                    source_name="source.txt",
                    page_number=1,
                    chunk_index=0,
                    score=0.9,
                    snippet="Grounded source",
                ),
            ),
        )


class Unused:
    async def list_document_units(self, request: object, context: object) -> object:
        raise AssertionError

    async def delete_document_index(self, request: object, context: object) -> object:
        raise AssertionError


def service(generation: Generation, redis: Redis) -> InferenceGrpcService:
    return InferenceGrpcService(
        GenerationGrpcHandler(
            generation,
            ProtobufGenerationResultCache(
                redis, prefix="ccn:v1", ttl_seconds=600  # type: ignore[arg-type]
            ),
        ),
        Unused(),  # type: ignore[arg-type]
        Unused(),  # type: ignore[arg-type]
    )


def request() -> pb.GenerateAnswerRequest:
    return pb.GenerateAnswerRequest(
        generation_id="generation-1",
        turn_id="turn-1",
        tenant_id="tenant-1",
        chatbot_id="bot-1",
        knowledge_base_id="kb-1",
        authoritative_revision=7,
        channel="EMPLOYEE_PLAYGROUND",
        locale="vi-VN",
        question="Question",
        prior_messages=[pb.PriorMessage(role="user", content="Earlier")],
        tenant_name="Tenant",
        prompt_schema_version="chat-prompts-v4",
    )


@pytest.mark.asyncio
async def test_generation_context_is_supplied_and_result_is_deduplicated() -> None:
    generation = Generation()
    redis = Redis()
    grpc_service = service(generation, redis)

    first_events = [
        event async for event in grpc_service.GenerateAnswer(request(), Context())
    ]
    second_events = [
        event async for event in grpc_service.GenerateAnswer(request(), Context())
    ]
    assert [event.WhichOneof("payload") for event in first_events] == [
        "content", "content", "completed",
    ]
    assert [event.content for event in first_events[:-1]] == ["Grounded", "Grounded answer"]
    assert [event.WhichOneof("payload") for event in second_events] == ["completed"]
    first = first_events[-1].completed
    second = second_events[-1].completed

    assert first.authoritative_revision == 7
    assert first.citations[0].document_id == "doc-1"
    assert generation.calls == 1
    assert second.cache_tier == "generation_id"
    assert first.input_tokens == 31
    assert second.avoided_input_tokens == 31
    assert second.avoided_output_tokens == 7
    assert generation.context is not None
    assert generation.context.authoritative_revision == 7
    assert [item.content for item in generation.context.prior_messages] == ["Earlier"]


class ControlledGeneration(Generation):
    def __init__(self, *, error: Exception | None = None) -> None:
        super().__init__()
        self.error = error
        self.release = asyncio.Event()
        self.closed = False

    async def generate(
        self,
        context: GenerationContext,
        *,
        on_content: Callable[[str], Awaitable[None]] | None = None,
    ) -> GenerationResult:
        assert on_content is not None
        try:
            await on_content("Early preview")
            await self.release.wait()
            if self.error is not None:
                raise self.error
            return await super().generate(context)
        finally:
            self.closed = True


@pytest.mark.asyncio
async def test_grpc_preview_is_early_and_close_cancels_producer_without_caching() -> None:
    generation = ControlledGeneration()
    redis = Redis()
    stream = service(generation, redis).GenerateAnswer(request(), Context())
    event = await anext(stream)
    assert event.content == "Early preview"
    assert not generation.closed
    assert redis.values == {}
    await stream.aclose()
    assert generation.closed
    assert redis.values == {}


@pytest.mark.asyncio
async def test_grpc_task_cancellation_awaits_producer_cleanup() -> None:
    generation = ControlledGeneration()
    redis = Redis()
    stream = service(generation, redis).GenerateAnswer(request(), Context())
    assert (await anext(stream)).content == "Early preview"
    pending = asyncio.create_task(anext(stream))
    await asyncio.sleep(0)
    pending.cancel()
    with pytest.raises(asyncio.CancelledError):
        await pending
    assert generation.closed
    assert redis.values == {}


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("error", "status"),
    [
        (GenerationTimeoutError("deadline"), "DEADLINE_EXCEEDED"),
        (GenerationUnavailableError("Model provider failed"), "INTERNAL"),
        (ValueError("unexpected private provider details"), "INTERNAL"),
    ],
)
async def test_grpc_failure_after_preview_never_completes_or_caches(
    error: Exception, status: str
) -> None:
    generation = ControlledGeneration(error=error)
    redis = Redis()
    stream = service(generation, redis).GenerateAnswer(request(), Context())
    assert (await anext(stream)).content == "Early preview"
    generation.release.set()
    with pytest.raises(RuntimeError, match=status):
        await anext(stream)
    assert generation.closed
    assert redis.values == {}


class SaturatingGeneration(Generation):
    def __init__(self) -> None:
        super().__init__()
        self.callbacks = 0
        self.closed = False

    async def generate(
        self,
        context: GenerationContext,
        *,
        on_content: Callable[[str], Awaitable[None]] | None = None,
    ) -> GenerationResult:
        assert on_content is not None
        try:
            for index in range(100):
                self.callbacks += 1
                await on_content(str(index))
            return await super().generate(context)
        finally:
            self.closed = True


@pytest.mark.asyncio
async def test_grpc_backpressure_is_bounded_and_cancel_does_not_block_on_full_queue() -> None:
    generation = SaturatingGeneration()
    redis = Redis()
    stream = service(generation, redis).GenerateAnswer(request(), Context())
    assert (await anext(stream)).content == "0"
    assert generation.callbacks <= 6
    await stream.aclose()
    assert generation.closed
    assert redis.values == {}
