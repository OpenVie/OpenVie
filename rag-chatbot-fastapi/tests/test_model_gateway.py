from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from prometheus_client import REGISTRY

from app.bootstrap.settings import Settings
from app.modules.model.api import ModelCompletion
from app.modules.model.api import ModelTimeoutError as ChatModelTimeoutError
from app.modules.model.api import ModelUnavailableError as ChatModelProviderError
from app.modules.model.internal.chat import (
    OllamaChatModel,
    QwenChatModel,
    create_chat_model,
)


def metric_value(name: str, labels: dict[str, str]) -> float:
    return REGISTRY.get_sample_value(name, labels) or 0.0


class FakeOllamaResponse:
    def raise_for_status(self) -> None:
        return None

    def json(self) -> dict[str, object]:
        return {"message": {"role": "assistant", "content": "The answer is four."}}


class UsageOllamaResponse(FakeOllamaResponse):
    def json(self) -> dict[str, object]:
        return {
            "message": {"role": "assistant", "content": "The answer is four."},
            "prompt_eval_count": 31,
            "eval_count": 7,
        }


class FakeOllamaClient:
    last_url = ""
    last_json: dict[str, Any] = {}

    def __init__(self, timeout: float):
        self.timeout = timeout

    async def __aenter__(self) -> FakeOllamaClient:
        return self

    async def __aexit__(self, *args: object) -> None:
        return None

    async def post(self, url: str, json: dict[str, object]) -> FakeOllamaResponse:
        FakeOllamaClient.last_url = url
        FakeOllamaClient.last_json = dict(json)
        return FakeOllamaResponse()


class LengthLimitedOllamaResponse(FakeOllamaResponse):
    def json(self) -> dict[str, object]:
        return {
            "message": {"role": "assistant", "content": '{"entities":['},
            "done_reason": "length",
            "eval_count": 4096,
        }


class LengthLimitedOllamaClient(FakeOllamaClient):
    async def post(self, url: str, json: dict[str, object]) -> FakeOllamaResponse:
        FakeOllamaClient.last_url = url
        FakeOllamaClient.last_json = dict(json)
        return LengthLimitedOllamaResponse()


class SlowOllamaClient(FakeOllamaClient):
    async def post(self, url: str, json: dict[str, object]) -> FakeOllamaResponse:
        del url, json
        await asyncio.sleep(1)
        return FakeOllamaResponse()


class UsageOllamaClient(FakeOllamaClient):
    async def post(self, url: str, json: dict[str, object]) -> UsageOllamaResponse:
        FakeOllamaClient.last_url = url
        FakeOllamaClient.last_json = dict(json)
        return UsageOllamaResponse()


class FakeQwenResponse:
    def raise_for_status(self) -> None:
        return None

    def json(self) -> object:
        return {
            "choices": [{"message": {"role": "assistant", "content": " Qwen answer. "}}],
            "usage": {"prompt_tokens": 53, "completion_tokens": 11},
        }


class EmptyQwenResponse(FakeQwenResponse):
    def json(self) -> object:
        return {"choices": [{"message": {"role": "assistant", "content": "  "}}]}


class LengthLimitedQwenResponse(FakeQwenResponse):
    def json(self) -> object:
        return {
            "choices": [
                {
                    "finish_reason": "length",
                    "message": {"role": "assistant", "content": '{"entities":['},
                }
            ]
        }


class MalformedQwenResponse(FakeQwenResponse):
    def json(self) -> object:
        return {"choices": []}


class InvalidJsonQwenResponse(FakeQwenResponse):
    def json(self) -> object:
        raise ValueError("invalid JSON")


class FailedQwenResponse(FakeQwenResponse):
    def raise_for_status(self) -> None:
        request = httpx.Request("POST", "http://localhost:8080/v1/chat/completions")
        response = httpx.Response(503, request=request)
        raise httpx.HTTPStatusError("service unavailable", request=request, response=response)


class FakeQwenClient:
    response_type: type[FakeQwenResponse] = FakeQwenResponse
    last_url = ""
    last_json: dict[str, object] = {}

    def __init__(self, timeout: float):
        self.timeout = timeout

    async def __aenter__(self) -> FakeQwenClient:
        return self

    async def __aexit__(self, *args: object) -> None:
        return None

    async def post(self, url: str, json: dict[str, object]) -> FakeQwenResponse:
        FakeQwenClient.last_url = url
        FakeQwenClient.last_json = dict(json)
        return self.response_type()


class EmptyQwenClient(FakeQwenClient):
    response_type = EmptyQwenResponse


class LengthLimitedQwenClient(FakeQwenClient):
    response_type = LengthLimitedQwenResponse


class MalformedQwenClient(FakeQwenClient):
    response_type = MalformedQwenResponse


class InvalidJsonQwenClient(FakeQwenClient):
    response_type = InvalidJsonQwenResponse


class FailedQwenClient(FakeQwenClient):
    response_type = FailedQwenResponse


class SlowQwenClient(FakeQwenClient):
    async def post(self, url: str, json: dict[str, object]) -> FakeQwenResponse:
        del url, json
        await asyncio.sleep(1)
        return FakeQwenResponse()


def settings(**overrides: object) -> Settings:
    values: dict[str, object] = {
        "_env_file": (),
        "LLM_BASE_URL": "http://localhost:11434/v1",
        "LLM_MODEL_ID": "vylinh",
        "LLM_MAX_OUTPUT_TOKENS": 64,
        "LLM_TEMPERATURE": 0,
        "LLM_TIMEOUT_SECONDS": 1,
        "LLM_DISABLE_THINKING": True,
    }
    values.update(overrides)
    return Settings(**values)


def test_create_chat_model_defaults_to_ollama() -> None:
    model = create_chat_model(settings(LLM_PROVIDER="ollama"))

    assert isinstance(model, OllamaChatModel)


def test_create_chat_model_selects_qwen() -> None:
    model = create_chat_model(
        settings(
            LLM_PROVIDER="qwen",
            LLM_BASE_URL="http://127.0.0.1:8080/v1",
            LLM_MODEL_ID="mlx-community/Qwen2.5-14B-Instruct-4bit",
        )
    )

    assert isinstance(model, QwenChatModel)


@pytest.mark.asyncio
async def test_ollama_native_chat_disables_thinking(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("app.modules.model.internal.chat.httpx.AsyncClient", FakeOllamaClient)
    gateway = OllamaChatModel(settings())

    response = await gateway.complete([{"role": "user", "content": "what is 2+2?"}])

    assert response == "The answer is four."
    assert FakeOllamaClient.last_url == "http://localhost:11434/api/chat"
    assert FakeOllamaClient.last_json["think"] is False
    assert FakeOllamaClient.last_json["stream"] is False
    assert FakeOllamaClient.last_json["options"] == {"temperature": 0.0, "num_predict": 64}


@pytest.mark.asyncio
async def test_ollama_preserves_composed_message_content(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("app.modules.model.internal.chat.httpx.AsyncClient", FakeOllamaClient)
    gateway = OllamaChatModel(settings())
    messages = [
        {"role": "system", "content": "Platform rules\nTenant instructions\nPlatform priority"},
        {"role": "user", "content": "Question with sources"},
    ]

    await gateway.complete(messages)

    assert FakeOllamaClient.last_json["messages"] == messages


@pytest.mark.asyncio
async def test_ollama_complete_with_usage_extracts_native_token_counts(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr("app.modules.model.internal.chat.httpx.AsyncClient", UsageOllamaClient)
    gateway = OllamaChatModel(settings())

    completion = await gateway.complete_with_usage([{"role": "user", "content": "what is 2+2?"}])

    assert completion.content == "The answer is four."
    assert completion.input_tokens == 31
    assert completion.output_tokens == 7


@pytest.mark.asyncio
async def test_qwen_chat_uses_openai_compatible_payload_and_parses_usage(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr("app.modules.model.internal.chat.httpx.AsyncClient", FakeQwenClient)
    gateway = QwenChatModel(
        settings(
            LLM_PROVIDER="qwen",
            LLM_BASE_URL="http://127.0.0.1:8080/v1/",
            LLM_MODEL_ID="mlx-community/Qwen2.5-14B-Instruct-4bit",
            LLM_TEMPERATURE=0.3,
            LLM_MAX_OUTPUT_TOKENS=256,
        )
    )
    messages = [
        {"role": "system", "content": "Follow the policy."},
        {"role": "user", "content": "Question"},
        {"role": "assistant", "content": "Prior answer"},
    ]

    completion = await gateway.complete_with_usage(messages)

    assert completion.content == "Qwen answer."
    assert completion.input_tokens == 53
    assert completion.output_tokens == 11
    assert FakeQwenClient.last_url == "http://127.0.0.1:8080/v1/chat/completions"
    assert FakeQwenClient.last_json == {
        "model": "mlx-community/Qwen2.5-14B-Instruct-4bit",
        "messages": messages,
        "temperature": 0.3,
        "max_tokens": 256,
        "stream": False,
    }


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "client_type",
    [EmptyQwenClient, MalformedQwenClient, InvalidJsonQwenClient],
)
async def test_qwen_empty_and_malformed_responses_are_provider_errors(
    monkeypatch: pytest.MonkeyPatch,
    client_type: type[FakeQwenClient],
) -> None:
    monkeypatch.setattr("app.modules.model.internal.chat.httpx.AsyncClient", client_type)
    gateway = QwenChatModel(settings(LLM_PROVIDER="qwen"))

    with pytest.raises(ChatModelProviderError):
        await gateway.complete([{"role": "user", "content": "hello"}])


@pytest.mark.asyncio
async def test_qwen_output_limit_reports_finish_reason(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        "app.modules.model.internal.chat.httpx.AsyncClient", LengthLimitedQwenClient
    )
    gateway = QwenChatModel(settings(LLM_PROVIDER="qwen"))

    with pytest.raises(ChatModelProviderError, match="finish_reason=length"):
        await gateway.complete_text([{"role": "user", "content": "hello"}], max_output_tokens=16)

    with pytest.raises(ChatModelProviderError, match="finish_reason=length"):
        await gateway.complete([{"role": "user", "content": "hello"}])


@pytest.mark.asyncio
async def test_ollama_output_limit_reports_finish_reason(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The extraction path still receives a raisable signal, with content kept for answers."""
    monkeypatch.setattr(
        "app.modules.model.internal.chat.httpx.AsyncClient", LengthLimitedOllamaClient
    )
    gateway = OllamaChatModel(settings())

    with pytest.raises(ChatModelProviderError, match="finish_reason=length"):
        await gateway.complete_text([{"role": "user", "content": "hello"}], max_output_tokens=16)

    with pytest.raises(ChatModelProviderError, match="finish_reason=length"):
        await gateway.complete([{"role": "user", "content": "hello"}])


@pytest.mark.asyncio
async def test_qwen_http_failures_are_wrapped(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("app.modules.model.internal.chat.httpx.AsyncClient", FailedQwenClient)
    model_id = "mlx-community/Qwen2.5-14B-Instruct-4bit"
    labels = {"provider": "qwen", "model": model_id, "outcome": "error"}
    before = metric_value("cacanode_ai_chat_model_seconds_count", labels)
    gateway = QwenChatModel(settings(LLM_PROVIDER="qwen", LLM_MODEL_ID=model_id))

    with pytest.raises(ChatModelProviderError):
        await gateway.complete([{"role": "user", "content": "hello"}])

    assert metric_value("cacanode_ai_chat_model_seconds_count", labels) == before + 1


@pytest.mark.asyncio
async def test_qwen_timeout_records_qwen_metrics(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("app.modules.model.internal.chat.httpx.AsyncClient", SlowQwenClient)
    model_id = "mlx-community/Qwen2.5-14B-Instruct-4bit"
    labels = {"provider": "qwen", "model": model_id}
    before = metric_value("cacanode_ai_chat_model_timeouts_total", labels)
    gateway = QwenChatModel(
        settings(
            LLM_PROVIDER="qwen",
            LLM_MODEL_ID=model_id,
            LLM_TIMEOUT_SECONDS=0.001,
        )
    )

    with pytest.raises(ChatModelTimeoutError):
        await gateway.complete([{"role": "user", "content": "slow"}])

    assert metric_value("cacanode_ai_chat_model_timeouts_total", labels) == before + 1
    assert (
        metric_value(
            "cacanode_ai_chat_model_seconds_count",
            {"provider": "qwen", "model": model_id, "outcome": "timeout"},
        )
        >= 1
    )


@pytest.mark.asyncio
async def test_ollama_timeout_records_counter_and_raises_model_timeout(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr("app.modules.model.internal.chat.httpx.AsyncClient", SlowOllamaClient)
    labels = {"provider": "ollama", "model": "timeout-model"}
    before = metric_value("cacanode_ai_chat_model_timeouts_total", labels)
    gateway = OllamaChatModel(
        settings(
            LLM_MODEL_ID="timeout-model",
            LLM_TIMEOUT_SECONDS=0.001,
        )
    )

    with pytest.raises(ChatModelTimeoutError):
        await gateway.complete([{"role": "user", "content": "slow"}])

    after = metric_value("cacanode_ai_chat_model_timeouts_total", labels)
    assert after == before + 1
    assert (
        metric_value(
            "cacanode_ai_chat_model_seconds_count",
            {"provider": "ollama", "model": "timeout-model", "outcome": "timeout"},
        )
        >= 1
    )


class FragmentedProviderStream(httpx.AsyncByteStream):
    def __init__(self, frames: list[bytes], *, gate_after: int | None = None) -> None:
        self.frames = frames
        self.gate_after = gate_after
        self.release = asyncio.Event()
        self.closed = False
        self.finished = False

    async def __aiter__(self) -> AsyncIterator[bytes]:
        for index, frame in enumerate(self.frames):
            if index == self.gate_after:
                await self.release.wait()
            # Split bytes within JSON, lines, and Vietnamese UTF-8 codepoints.
            for start in range(0, len(frame), 3):
                yield frame[start:start + 3]
        self.finished = True

    async def aclose(self) -> None:
        self.closed = True


def provider_frame(provider: str, data: dict[str, object]) -> bytes:
    encoded = json.dumps(data, ensure_ascii=False).encode()
    return encoded + b"\n" if provider == "ollama" else b"data: " + encoded + b"\n\n"


def install_provider_stream(
    monkeypatch: pytest.MonkeyPatch, stream: FragmentedProviderStream
) -> list[dict[str, Any]]:
    requests: list[dict[str, Any]] = []
    client_type = httpx.AsyncClient

    async def respond(request: httpx.Request) -> httpx.Response:
        requests.append(json.loads(request.content))
        return httpx.Response(200, stream=stream)

    monkeypatch.setattr(
        "app.modules.model.internal.chat.httpx.AsyncClient",
        lambda **kwargs: client_type(transport=httpx.MockTransport(respond), **kwargs),
    )
    return requests


def streaming_frames(provider: str, *, truncated: bool = False) -> list[bytes]:
    if provider == "ollama":
        return [
            provider_frame(provider, {"message": {"content": "Thông tin "}, "done": False}),
            provider_frame(provider, {"message": {"content": "được cung cấp."}, "done": False}),
            provider_frame(provider, {
                "message": {"content": ""},
                "done": True,
                "done_reason": "length" if truncated else "stop",
                "prompt_eval_count": 31,
                "eval_count": 7,
            }),
        ]
    return [
        provider_frame(provider, {"choices": [{"delta": {"content": "Thông tin "}}]}),
        provider_frame(provider, {"choices": [{"delta": {"content": "được cung cấp."}}]}),
        provider_frame(provider, {"choices": [{
            "delta": {}, "finish_reason": "length" if truncated else "stop",
        }]}),
        provider_frame(provider, {
            "choices": [], "usage": {"prompt_tokens": 31, "completion_tokens": 7},
        }),
        b"data: [DONE]\n\n",
    ]


@pytest.mark.asyncio
@pytest.mark.parametrize("provider", ["ollama", "qwen"])
async def test_provider_stream_delivers_before_finish_and_preserves_usage(
    monkeypatch: pytest.MonkeyPatch, provider: str
) -> None:
    wire = FragmentedProviderStream(streaming_frames(provider), gate_after=2)
    requests = install_provider_stream(monkeypatch, wire)
    model = create_chat_model(settings(LLM_PROVIDER=provider))
    stream = model.stream_with_usage([{"role": "user", "content": "Question"}])
    try:
        assert await anext(stream) == "Thông tin "
        assert await anext(stream) == "được cung cấp."
        assert not wire.finished
        wire.release.set()
        final = await anext(stream)
        assert isinstance(final, ModelCompletion)
        assert final.content == "Thông tin được cung cấp."
        assert (final.input_tokens, final.output_tokens) == (31, 7)
        assert not final.truncated
        with pytest.raises(StopAsyncIteration):
            await anext(stream)
    finally:
        wire.release.set()
        await stream.aclose()
    assert requests[0]["stream"] is True
    if provider == "ollama":
        assert requests[0]["think"] is False
        assert requests[0]["options"] == {"temperature": 0.0, "num_predict": 64}
    else:
        assert requests[0]["max_tokens"] == 64
        assert requests[0]["stream_options"] == {"include_usage": True}
    assert wire.closed


@pytest.mark.asyncio
@pytest.mark.parametrize("provider", ["ollama", "qwen"])
@pytest.mark.parametrize("allow_truncated", [False, True])
async def test_provider_stream_length_is_explicit(
    monkeypatch: pytest.MonkeyPatch, provider: str, allow_truncated: bool
) -> None:
    wire = FragmentedProviderStream(streaming_frames(provider, truncated=True))
    install_provider_stream(monkeypatch, wire)
    model = create_chat_model(settings(LLM_PROVIDER=provider))
    stream = model.stream_with_usage(
        [{"role": "user", "content": "Question"}], allow_truncated=allow_truncated
    )
    if allow_truncated:
        events = [event async for event in stream]
        assert isinstance(events[-1], ModelCompletion)
        assert events[-1].truncated
    else:
        with pytest.raises(ChatModelProviderError, match="finish_reason=length"):
            _ = [event async for event in stream]
    assert wire.closed


@pytest.mark.asyncio
@pytest.mark.parametrize("provider", ["ollama", "qwen"])
@pytest.mark.parametrize("failure", ["premature", "malformed", "blank"])
async def test_provider_stream_does_not_complete_invalid_wire_response(
    monkeypatch: pytest.MonkeyPatch, provider: str, failure: str
) -> None:
    frames = streaming_frames(provider)[:1] if failure == "premature" else []
    if failure == "malformed":
        frames = [b"not json\n" if provider == "ollama" else b"data: not json\n\n"]
    wire = FragmentedProviderStream(frames)
    install_provider_stream(monkeypatch, wire)
    model = create_chat_model(settings(LLM_PROVIDER=provider))
    events: list[str | ModelCompletion] = []
    with pytest.raises(ChatModelProviderError):
        async for event in model.stream_with_usage([{"role": "user", "content": "Question"}]):
            events.append(event)
    assert not any(isinstance(event, ModelCompletion) for event in events)
    assert wire.closed


@pytest.mark.asyncio
@pytest.mark.parametrize("provider", ["ollama", "qwen"])
async def test_closing_provider_consumer_closes_http_stream(
    monkeypatch: pytest.MonkeyPatch, provider: str
) -> None:
    wire = FragmentedProviderStream(streaming_frames(provider), gate_after=1)
    install_provider_stream(monkeypatch, wire)
    model = create_chat_model(settings(LLM_PROVIDER=provider))
    stream = model.stream_with_usage([{"role": "user", "content": "Question"}])
    assert await anext(stream) == "Thông tin "
    await stream.aclose()
    assert wire.closed
    assert not wire.finished


@pytest.mark.asyncio
@pytest.mark.parametrize("provider", ["ollama", "qwen"])
async def test_cancelling_pending_provider_read_closes_http_stream(
    monkeypatch: pytest.MonkeyPatch, provider: str
) -> None:
    wire = FragmentedProviderStream(streaming_frames(provider), gate_after=1)
    install_provider_stream(monkeypatch, wire)
    model = create_chat_model(settings(LLM_PROVIDER=provider))
    stream = model.stream_with_usage([{"role": "user", "content": "Question"}])
    assert await anext(stream) == "Thông tin "
    pending = asyncio.create_task(anext(stream))
    await asyncio.sleep(0)
    pending.cancel()
    with pytest.raises(asyncio.CancelledError):
        await pending
    assert wire.closed
    assert not wire.finished


@pytest.mark.asyncio
@pytest.mark.parametrize("provider", ["ollama", "qwen"])
async def test_provider_stream_deadline_closes_http_without_completion(
    monkeypatch: pytest.MonkeyPatch, provider: str
) -> None:
    wire = FragmentedProviderStream(streaming_frames(provider), gate_after=1)
    install_provider_stream(monkeypatch, wire)
    clock = [0.0]
    monkeypatch.setattr(
        "app.modules.model.internal.chat.time",
        SimpleNamespace(perf_counter=lambda: clock[0]),
    )
    model = create_chat_model(settings(LLM_PROVIDER=provider))
    stream = model.stream_with_usage([{"role": "user", "content": "Question"}])
    assert await anext(stream) == "Thông tin "
    clock[0] = 2.0
    with pytest.raises(ChatModelTimeoutError):
        await anext(stream)
    assert wire.closed
