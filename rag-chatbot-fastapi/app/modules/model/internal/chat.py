import asyncio
import json
import time
from collections.abc import AsyncGenerator, Sequence
from contextlib import aclosing
from typing import Any

import httpx

from app.common.metrics import AI_CHAT_MODEL_SECONDS, AI_CHAT_MODEL_TIMEOUTS_TOTAL
from app.modules.model.api import ModelCompletion, ModelTimeoutError, ModelUnavailableError
from app.modules.model.internal.config import ModelConfig


def _non_negative_token_count(value: Any) -> int | None:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        return None
    return value


class OllamaChatModel:
    """Adapter for Ollama's native /api/chat endpoint."""

    provider = "ollama"

    def __init__(self, settings: ModelConfig):
        if not settings.LLM_MODEL_ID:
            raise RuntimeError("LLM_MODEL_ID is not configured")
        self._settings = settings
        self._base_url = settings.LLM_BASE_URL.rstrip("/")
        self.model = settings.LLM_MODEL_ID
        self._timeout_seconds = settings.LLM_TIMEOUT_SECONDS

    async def complete(
        self, messages: Sequence[dict[str, Any]], *, allow_truncated: bool = False
    ) -> str:
        return (await self.complete_with_usage(messages, allow_truncated=allow_truncated)).content

    async def complete_text(
        self,
        messages: Sequence[dict[str, Any]],
        *,
        max_output_tokens: int,
        temperature: float | None = None,
    ) -> str:
        """Generate with a bounded output budget for auxiliary, non-answer calls."""
        return (
            await self._complete_with_limits(
                messages,
                max_output_tokens=max_output_tokens,
                temperature=temperature,
            )
        ).content

    async def complete_with_usage(
        self, messages: Sequence[dict[str, Any]], *, allow_truncated: bool = False
    ) -> ModelCompletion:
        return await self._complete_with_limits(
            messages, allow_truncated=allow_truncated
        )

    async def _complete_with_limits(
        self,
        messages: Sequence[dict[str, Any]],
        *,
        max_output_tokens: int | None = None,
        temperature: float | None = None,
        allow_truncated: bool = False,
    ) -> ModelCompletion:
        started_at = time.perf_counter()
        outcome = "success"
        try:
            return await asyncio.wait_for(
                self._complete_ollama_native(
                    messages,
                    max_output_tokens=max_output_tokens,
                    temperature=temperature,
                    allow_truncated=allow_truncated,
                ),
                timeout=self._timeout_seconds,
            )
        except TimeoutError as exc:
            outcome = "timeout"
            AI_CHAT_MODEL_TIMEOUTS_TOTAL.labels(
                provider=self.provider,
                model=self.model,
            ).inc()
            raise ModelTimeoutError("Model generation timed out") from exc
        except httpx.TimeoutException as exc:
            outcome = "timeout"
            AI_CHAT_MODEL_TIMEOUTS_TOTAL.labels(
                provider=self.provider,
                model=self.model,
            ).inc()
            raise ModelTimeoutError("Model generation timed out") from exc
        except ModelUnavailableError:
            outcome = "error"
            raise
        except Exception as exc:
            outcome = "error"
            raise ModelUnavailableError("Model provider request failed") from exc
        finally:
            AI_CHAT_MODEL_SECONDS.labels(
                provider=self.provider,
                model=self.model,
                outcome=outcome,
            ).observe(time.perf_counter() - started_at)

    async def _complete_ollama_native(
        self,
        messages: Sequence[dict[str, Any]],
        *,
        max_output_tokens: int | None = None,
        temperature: float | None = None,
        allow_truncated: bool = False,
    ) -> ModelCompletion:
        payload: dict[str, Any] = {
            "model": self.model,
            "messages": [
                {"role": message.get("role", "user"), "content": str(message.get("content", ""))}
                for message in messages
            ],
            "stream": False,
            "options": {
                "temperature": (
                    self._settings.LLM_TEMPERATURE if temperature is None else temperature
                ),
                "num_predict": (
                    self._settings.LLM_MAX_OUTPUT_TOKENS
                    if max_output_tokens is None
                    else max_output_tokens
                ),
            },
        }
        if self._settings.LLM_DISABLE_THINKING:
            payload["think"] = False

        async with httpx.AsyncClient(timeout=self._timeout_seconds) as client:
            response = await client.post(self._ollama_chat_url(), json=payload)
            response.raise_for_status()
            data = response.json()
        if data.get("done_reason") == "length":
            # Extraction subdivides batches on this signal, so it always treats a
            # length stop as a failure; only an explicit opt-in receives the
            # partial content instead.
            message = data.get("message")
            content = str(message.get("content", "")) if isinstance(message, dict) else ""
            if not (allow_truncated and content.strip()):
                raise ModelUnavailableError(
                    "Ollama provider reached the output limit (finish_reason=length)"
                )
            return ModelCompletion(
                content=content,
                input_tokens=_non_negative_token_count(data.get("prompt_eval_count")),
                output_tokens=_non_negative_token_count(data.get("eval_count")),
                truncated=True,
            )
        message = data.get("message")
        if isinstance(message, dict):
            return ModelCompletion(
                content=str(message.get("content", "")),
                input_tokens=_non_negative_token_count(data.get("prompt_eval_count")),
                output_tokens=_non_negative_token_count(data.get("eval_count")),
            )
        return ModelCompletion(
            content="",
            input_tokens=_non_negative_token_count(data.get("prompt_eval_count")),
            output_tokens=_non_negative_token_count(data.get("eval_count")),
        )

    def _ollama_chat_url(self) -> str:
        base_url = self._base_url
        if base_url.endswith("/v1"):
            base_url = base_url[: -len("/v1")]
        return f"{base_url}/api/chat"

    async def stream_with_usage(
        self, messages: Sequence[dict[str, Any]], *, allow_truncated: bool = False
    ) -> AsyncGenerator[str | ModelCompletion, None]:
        """Yield provider content deltas, then exactly one authoritative completion."""
        payload: dict[str, Any] = {
            "model": self.model,
            "messages": [
                {"role": message.get("role", "user"), "content": str(message.get("content", ""))}
                for message in messages
            ],
            "stream": True,
            "options": {
                "temperature": self._settings.LLM_TEMPERATURE,
                "num_predict": self._settings.LLM_MAX_OUTPUT_TOKENS,
            },
        }
        if self._settings.LLM_DISABLE_THINKING:
            payload["think"] = False
        stream = _stream_provider(
            self, self._ollama_chat_url(), payload, allow_truncated=allow_truncated
        )
        try:
            async for event in stream:
                yield event
        finally:
            await stream.aclose()


class QwenChatModel:
    """Adapter for an externally managed MLX OpenAI-compatible chat endpoint."""

    provider = "qwen"

    def __init__(self, settings: ModelConfig):
        if not settings.LLM_MODEL_ID:
            raise RuntimeError("LLM_MODEL_ID is not configured")
        if not settings.LLM_BASE_URL:
            raise RuntimeError("LLM_BASE_URL is not configured")
        self._settings = settings
        self._base_url = settings.LLM_BASE_URL.rstrip("/")
        self.model = settings.LLM_MODEL_ID
        self._timeout_seconds = settings.LLM_TIMEOUT_SECONDS

    async def complete(
        self, messages: Sequence[dict[str, Any]], *, allow_truncated: bool = False
    ) -> str:
        return (await self.complete_with_usage(messages, allow_truncated=allow_truncated)).content

    async def complete_text(
        self,
        messages: Sequence[dict[str, Any]],
        *,
        max_output_tokens: int,
        temperature: float | None = None,
    ) -> str:
        """Generate with a bounded output budget for auxiliary, non-answer calls."""
        return (
            await self._complete_with_limits(
                messages,
                max_output_tokens=max_output_tokens,
                temperature=temperature,
            )
        ).content

    async def complete_with_usage(
        self, messages: Sequence[dict[str, Any]], *, allow_truncated: bool = False
    ) -> ModelCompletion:
        return await self._complete_with_limits(messages, allow_truncated=allow_truncated)

    async def _complete_with_limits(
        self,
        messages: Sequence[dict[str, Any]],
        *,
        max_output_tokens: int | None = None,
        temperature: float | None = None,
        allow_truncated: bool = False,
    ) -> ModelCompletion:
        started_at = time.perf_counter()
        outcome = "success"
        try:
            return await asyncio.wait_for(
                self._complete_qwen(
                    messages,
                    max_output_tokens=max_output_tokens,
                    temperature=temperature,
                    allow_truncated=allow_truncated,
                ),
                timeout=self._timeout_seconds,
            )
        except (TimeoutError, httpx.TimeoutException) as exc:
            outcome = "timeout"
            AI_CHAT_MODEL_TIMEOUTS_TOTAL.labels(
                provider=self.provider,
                model=self.model,
            ).inc()
            raise ModelTimeoutError("Model generation timed out") from exc
        except ModelUnavailableError:
            outcome = "error"
            raise
        except Exception as exc:
            outcome = "error"
            raise ModelUnavailableError("Model provider request failed") from exc
        finally:
            AI_CHAT_MODEL_SECONDS.labels(
                provider=self.provider,
                model=self.model,
                outcome=outcome,
            ).observe(time.perf_counter() - started_at)

    async def _complete_qwen(
        self,
        messages: Sequence[dict[str, Any]],
        *,
        max_output_tokens: int | None = None,
        temperature: float | None = None,
        allow_truncated: bool = False,
    ) -> ModelCompletion:
        payload = {
            "model": self.model,
            "messages": [
                {
                    "role": message.get("role", "user"),
                    "content": str(message.get("content", "")),
                }
                for message in messages
            ],
            "temperature": (
                self._settings.LLM_TEMPERATURE if temperature is None else temperature
            ),
            "max_tokens": (
                self._settings.LLM_MAX_OUTPUT_TOKENS
                if max_output_tokens is None
                else max_output_tokens
            ),
            "stream": False,
        }
        async with httpx.AsyncClient(timeout=self._timeout_seconds) as client:
            response = await client.post(self._qwen_chat_url(), json=payload)
            response.raise_for_status()
            data = response.json()
        if not isinstance(data, dict):
            raise ModelUnavailableError("Qwen provider returned a malformed response")
        choices = data.get("choices")
        if not isinstance(choices, list) or not choices or not isinstance(choices[0], dict):
            raise ModelUnavailableError("Qwen provider returned a malformed response")
        finish_reason = choices[0].get("finish_reason")
        if finish_reason == "length":
            # Extraction subdivides batches on this signal, so it always treats a
            # length stop as a failure; only an explicit opt-in receives the
            # partial content instead.
            message = choices[0].get("message")
            content = str(message.get("content", "")) if isinstance(message, dict) else ""
            if not (allow_truncated and content.strip()):
                raise ModelUnavailableError(
                    "Qwen provider reached the output limit (finish_reason=length)"
                )
            usage = data.get("usage")
            token_usage = usage if isinstance(usage, dict) else {}
            return ModelCompletion(
                content=content,
                input_tokens=_non_negative_token_count(token_usage.get("prompt_tokens")),
                output_tokens=_non_negative_token_count(token_usage.get("completion_tokens")),
                truncated=True,
            )
        message = choices[0].get("message")
        if not isinstance(message, dict) or not isinstance(message.get("content"), str):
            raise ModelUnavailableError("Qwen provider returned a malformed response")
        content = message["content"].strip()
        if not content:
            raise ModelUnavailableError("Qwen provider returned an empty response")
        usage = data.get("usage")
        token_usage = usage if isinstance(usage, dict) else {}
        return ModelCompletion(
            content=content,
            input_tokens=_non_negative_token_count(token_usage.get("prompt_tokens")),
            output_tokens=_non_negative_token_count(token_usage.get("completion_tokens")),
        )

    def _qwen_chat_url(self) -> str:
        return f"{self._base_url}/chat/completions"

    async def stream_with_usage(
        self, messages: Sequence[dict[str, Any]], *, allow_truncated: bool = False
    ) -> AsyncGenerator[str | ModelCompletion, None]:
        """Yield provider content deltas, then exactly one authoritative completion."""
        payload = {
            "model": self.model,
            "messages": [
                {"role": message.get("role", "user"), "content": str(message.get("content", ""))}
                for message in messages
            ],
            "temperature": self._settings.LLM_TEMPERATURE,
            "max_tokens": self._settings.LLM_MAX_OUTPUT_TOKENS,
            "stream": True,
            "stream_options": {"include_usage": True},
        }
        stream = _stream_provider(
            self, self._qwen_chat_url(), payload, allow_truncated=allow_truncated
        )
        try:
            async for event in stream:
                yield event
        finally:
            await stream.aclose()


def create_chat_model(
    settings: ModelConfig,
    *,
    reasoning_effort: str | None = None,
    enforce_reasoning_minimum: bool = True,
) -> OllamaChatModel | QwenChatModel:
    if settings.LLM_PROVIDER == "qwen":
        return QwenChatModel(settings)
    return OllamaChatModel(settings)


async def _stream_provider(
    model: OllamaChatModel | QwenChatModel,
    url: str,
    payload: dict[str, Any],
    *,
    allow_truncated: bool,
) -> AsyncGenerator[str | ModelCompletion, None]:
    started_at = time.perf_counter()
    outcome = "success"
    content = ""
    input_tokens: int | None = None
    output_tokens: int | None = None
    finished = False
    truncated = False
    try:
        deadline = started_at + model._timeout_seconds
        async with httpx.AsyncClient(timeout=model._timeout_seconds) as client:
            response = await asyncio.wait_for(
                client.send(client.build_request("POST", url, json=payload), stream=True),
                timeout=max(0, deadline - time.perf_counter()),
            )
            async with aclosing(response):
                response.raise_for_status()
                frames = _provider_frames(response, sse=model.provider == "qwen")
                try:
                    while True:
                        try:
                            frame = await asyncio.wait_for(
                                anext(frames), timeout=max(0, deadline - time.perf_counter())
                            )
                        except StopAsyncIteration:
                            raise ModelUnavailableError(
                                "Model stream ended before completion"
                            ) from None
                        if frame == "[DONE]":
                            if model.provider != "qwen" or not finished:
                                raise ModelUnavailableError("Model stream ended before completion")
                            break
                        data = json.loads(frame)
                        if not isinstance(data, dict) or data.get("error"):
                            raise ModelUnavailableError(
                                "Model provider returned a malformed stream"
                            )
                        delta = ""
                        if model.provider == "ollama":
                            message = data.get("message")
                            if not isinstance(message, dict):
                                raise ModelUnavailableError(
                                    "Ollama provider returned a malformed stream"
                                )
                            delta = message.get("content", "")
                            finished = data.get("done") is True
                            if finished:
                                truncated = data.get("done_reason") == "length"
                                input_tokens = _non_negative_token_count(
                                    data.get("prompt_eval_count")
                                )
                                output_tokens = _non_negative_token_count(data.get("eval_count"))
                        else:
                            usage = data.get("usage")
                            if isinstance(usage, dict):
                                input_tokens = _non_negative_token_count(usage.get("prompt_tokens"))
                                output_tokens = _non_negative_token_count(
                                    usage.get("completion_tokens")
                                )
                            choices = data.get("choices")
                            if not isinstance(choices, list):
                                raise ModelUnavailableError(
                                    "Qwen provider returned a malformed stream"
                                )
                            if choices:
                                choice = choices[0]
                                if not isinstance(choice, dict) or finished:
                                    raise ModelUnavailableError(
                                        "Qwen provider returned a malformed stream"
                                    )
                                delta_data = choice.get("delta")
                                if not isinstance(delta_data, dict):
                                    raise ModelUnavailableError(
                                        "Qwen provider returned a malformed stream"
                                    )
                                delta = delta_data.get("content") or ""
                                finish_reason = choice.get("finish_reason")
                                if finish_reason is not None:
                                    finished = True
                                    truncated = finish_reason == "length"
                        if not isinstance(delta, str):
                            raise ModelUnavailableError(
                                "Model provider returned a malformed stream"
                            )
                        if delta:
                            content += delta
                            yield delta
                        if model.provider == "ollama" and finished:
                            break
                finally:
                    await frames.aclose()
        if not finished or not content.strip():
            raise ModelUnavailableError("Model provider returned an empty or incomplete stream")
        if truncated and not allow_truncated:
            raise ModelUnavailableError(
                f"{model.provider} provider reached the output limit (finish_reason=length)"
            )
        yield ModelCompletion(
            content=content.strip(),
            input_tokens=input_tokens,
            output_tokens=output_tokens,
            truncated=truncated,
        )
    except (TimeoutError, httpx.TimeoutException) as exc:
        outcome = "timeout"
        AI_CHAT_MODEL_TIMEOUTS_TOTAL.labels(provider=model.provider, model=model.model).inc()
        raise ModelTimeoutError("Model generation timed out") from exc
    except ModelUnavailableError:
        outcome = "error"
        raise
    except Exception as exc:
        outcome = "error"
        raise ModelUnavailableError("Model provider request failed") from exc
    finally:
        AI_CHAT_MODEL_SECONDS.labels(
            provider=model.provider, model=model.model, outcome=outcome
        ).observe(time.perf_counter() - started_at)


async def _provider_frames(
    response: httpx.Response, *, sse: bool
) -> AsyncGenerator[str, None]:
    """httpx reassembles UTF-8 and line fragments; SSE data may span several lines."""
    data: list[str] = []
    async for line in response.aiter_lines():
        if not sse:
            if line.strip():
                yield line
        elif not line:
            if data:
                yield "\n".join(data)
                data.clear()
        elif line.startswith("data:"):
            data.append(line[5:].removeprefix(" "))
        elif line.startswith((":", "event:", "id:", "retry:")):
            continue
        else:
            raise ModelUnavailableError("Qwen provider returned a malformed stream")
    if data:
        raise ModelUnavailableError("Model stream ended mid-event")
