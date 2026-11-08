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

import grpc
import pytest

from app.generated import openvie_ai_v1_pb2 as pb
from app.modules.generation.transport.grpc import (
    GenerationGrpcHandler,
    _parse_json_schema,
)
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


class RecordingModel:
    """Duck-typed chat model recording complete_text kwargs."""

    def __init__(self) -> None:
        self.calls: list[dict[str, object]] = []
        self.payload: str = '{"fields": {"customerName": "Nguyen Van A"}}'
        self.fail = False

    async def complete_text(
        self,
        messages,
        *,
        max_output_tokens,
        temperature,
        format=None,
        model_id=None,
        timeout_seconds=None,
    ):
        self.calls.append(
            {
                "messages": messages,
                "max_output_tokens": max_output_tokens,
                "temperature": temperature,
                "format": format,
                "model_id": model_id,
                "timeout_seconds": timeout_seconds,
            }
        )
        if self.fail:
            raise RuntimeError("boom")
        return self.payload


class GenerationWithModel:
    """Exposes .model so the handler's reflection chain finds it."""

    def __init__(self, model: RecordingModel) -> None:
        self.model = model


def handler(generation: object) -> GenerationGrpcHandler:
    return GenerationGrpcHandler(
        generation, ProtobufGenerationResultCache(Redis(), prefix="ccn:v1", ttl_seconds=600)
    )


def structured_request(schema: str) -> pb.CompleteJsonRequest:
    return pb.CompleteJsonRequest(
        prompt="Extract slots",
        system_prompt="You are a slot extractor",
        json_schema=schema,
        model_id="vylinh",
        temperature=0.1,
        max_tokens=256,
    )


SLOT_SCHEMA = '{"type":"object","required":["fields"],"properties":{"fields":{"type":"object"}}}'


def test_parse_json_schema_accepts_schema_content_and_rejects_file_names() -> None:
    parsed = _parse_json_schema(SLOT_SCHEMA)
    assert parsed == {
        "type": "object", "required": ["fields"],
        "properties": {"fields": {"type": "object"}},
    }

    assert _parse_json_schema("bizapp_intent.schema.json") is None  # legacy file-name style
    assert _parse_json_schema("") is None
    assert _parse_json_schema("{not json") is None


@pytest.mark.asyncio
async def test_complete_json_forwards_inline_schema_as_provider_format() -> None:
    model = RecordingModel()
    generation = GenerationWithModel(model)

    response = await handler(generation).complete_json(structured_request(SLOT_SCHEMA), Context())

    assert response.json_output == '{"fields": {"customerName": "Nguyen Van A"}}'
    call = model.calls[0]
    assert call["format"] == {
        "type": "object", "required": ["fields"],
        "properties": {"fields": {"type": "object"}},
    }
    assert call["max_output_tokens"] == 256
    assert call["temperature"] == pytest.approx(0.1, rel=1e-3)
    assert call["model_id"] == "vylinh"
    assert call["timeout_seconds"] == 300.0


@pytest.mark.asyncio
async def test_complete_json_legacy_schema_name_stays_plain_completion() -> None:
    model = RecordingModel()
    generation = GenerationWithModel(model)

    await handler(generation).complete_json(
        structured_request("bizapp_intent.schema.json"), Context())

    assert model.calls[0]["format"] is None


@pytest.mark.asyncio
async def test_complete_json_model_failure_returns_empty_object() -> None:
    model = RecordingModel()
    model.fail = True
    generation = GenerationWithModel(model)

    response = await handler(generation).complete_json(structured_request(SLOT_SCHEMA), Context())

    assert response.json_output == "{}"
