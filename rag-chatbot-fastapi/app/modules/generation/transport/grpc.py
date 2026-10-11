from __future__ import annotations

import asyncio
import logging
from collections.abc import AsyncGenerator
from contextlib import suppress

import grpc

from app.generated import cacanode_ai_v1_pb2 as pb
from app.modules.generation.api import (
    Citation,
    GenerationApi,
    GenerationCacheTier,
    GenerationContext,
    GenerationRejectedError,
    GenerationResult,
    GenerationTimeoutError,
    GenerationUnavailableError,
    PriorMessage,
)
from app.modules.generation.transport.result_cache import ProtobufGenerationResultCache

logger = logging.getLogger(__name__)


class GenerationGrpcHandler:
    def __init__(
        self,
        generation: GenerationApi,
        result_cache: ProtobufGenerationResultCache,
    ) -> None:
        self._generation = generation
        self._cache = result_cache

    async def generate(
        self, request: pb.GenerateAnswerRequest, context: grpc.aio.ServicerContext
    ) -> AsyncGenerator[pb.GenerateAnswerEvent, None]:
        cached = await self._cache.get(request.generation_id)
        if cached is not None:
            cached.cache_tier = GenerationCacheTier.GENERATION_ID
            if cached.HasField("input_tokens"):
                cached.avoided_input_tokens = cached.input_tokens
            if cached.HasField("output_tokens"):
                cached.avoided_output_tokens = cached.output_tokens
            yield pb.GenerateAnswerEvent(completed=cached)
            return

        queue: asyncio.Queue[str | GenerationResult | Exception] = asyncio.Queue(maxsize=4)

        async def on_content(content: str) -> None:
            await queue.put(content)

        async def produce() -> None:
            try:
                result = await self._generation.generate(_context(request), on_content=on_content)
            except Exception as exc:
                await queue.put(exc)
            else:
                await queue.put(result)

        producer = asyncio.create_task(produce())
        try:
            while True:
                event = await queue.get()
                if isinstance(event, str):
                    yield pb.GenerateAnswerEvent(content=event)
                    continue
                if isinstance(event, Exception):
                    raise event
                # Only a fully validated successful result can enter the cache.
                if context.cancelled():
                    return
                response = _response(event)
                await self._cache.put(request.generation_id, response)
                yield pb.GenerateAnswerEvent(completed=response)
                return
        except GenerationRejectedError as exc:
            await context.abort(grpc.StatusCode.INVALID_ARGUMENT, str(exc))
        except GenerationTimeoutError:
            await context.abort(grpc.StatusCode.DEADLINE_EXCEEDED, "Model deadline exceeded")
        except GenerationUnavailableError as exc:
            details = str(exc)
            if details not in {
                "Model provider failed",
                "Generated citation escaped visibility scope",
            }:
                details = "Answer generation failed"
            await context.abort(grpc.StatusCode.INTERNAL, details)
        except Exception:
            logger.exception(
                "gRPC answer generation failed generation_id=%s", request.generation_id
            )
            await context.abort(grpc.StatusCode.INTERNAL, "Answer generation failed")
        finally:
            producer.cancel()
            with suppress(asyncio.CancelledError):
                await producer


def _context(request: pb.GenerateAnswerRequest) -> GenerationContext:
    return GenerationContext(
        generation_id=request.generation_id,
        turn_id=request.turn_id,
        tenant_id=request.tenant_id,
        chatbot_id=request.chatbot_id,
        knowledge_base_id=request.knowledge_base_id,
        authoritative_revision=request.authoritative_revision,
        channel=request.channel,
        locale=request.locale,
        question=request.question,
        prior_messages=tuple(
            PriorMessage(role=item.role, content=item.content)
            for item in request.prior_messages[:20]
        ),
        tenant_name=request.tenant_name,
        prompt_schema_version=request.prompt_schema_version,
    )


def _response(result: GenerationResult) -> pb.GenerateAnswerResponse:
    response = pb.GenerateAnswerResponse(
        generation_id=result.generation_id,
        authoritative_revision=result.authoritative_revision,
        answer=result.answer,
        citations=[_citation(item) for item in result.citations],
        cache_tier=result.cache_tier,
    )
    usage = result.token_usage
    for field in (
        "input_tokens",
        "output_tokens",
        "avoided_input_tokens",
        "avoided_output_tokens",
    ):
        value = getattr(usage, field)
        if value is not None:
            setattr(response, field, value)
    return response


def _citation(citation: Citation) -> pb.Citation:
    value = pb.Citation(
        id=str(citation.id),
        document_id=str(citation.document_id),
        source_name=str(citation.source_name),
        chunk_index=int(citation.chunk_index),
        score=float(citation.score),
        snippet=str(citation.snippet),
        section_path=list(citation.section_path),
    )
    for field in (
        "page_number",
        "unit_id",
        "modality",
        "block_type",
        "sheet_name",
        "cell_range",
        "table_id",
    ):
        item = getattr(citation, field)
        if item is not None:
            setattr(value, field, item)
    return value
