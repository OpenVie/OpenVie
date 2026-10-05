import asyncio
import time
from collections.abc import AsyncIterator, Sequence
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

    async def complete(self, messages: Sequence[dict[str, Any]]) -> str:
        return (await self.complete_with_usage(messages)).content

    async def complete_with_usage(self, messages: Sequence[dict[str, Any]]) -> ModelCompletion:
        started_at = time.perf_counter()
        outcome = "success"
        try:
            return await asyncio.wait_for(
                self._complete_ollama_native(messages),
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

    async def _complete_ollama_native(self, messages: Sequence[dict[str, Any]]) -> ModelCompletion:
        payload: dict[str, Any] = {
            "model": self.model,
            "messages": [
                {"role": message.get("role", "user"), "content": str(message.get("content", ""))}
                for message in messages
            ],
            "stream": False,
            "options": {
                "temperature": self._settings.LLM_TEMPERATURE,
                "num_predict": self._settings.LLM_MAX_OUTPUT_TOKENS,
            },
        }
        if self._settings.LLM_DISABLE_THINKING:
            payload["think"] = False

        async with httpx.AsyncClient(timeout=self._timeout_seconds) as client:
            response = await client.post(self._ollama_chat_url(), json=payload)
            response.raise_for_status()
            data = response.json()
        if data.get("done_reason") == "length":
            raise ModelUnavailableError(
                "Ollama provider reached the output limit (finish_reason=length)"
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

    async def stream(self, messages: Sequence[dict[str, Any]]) -> AsyncIterator[str]:
        yield await self.complete(messages)


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

    async def complete(self, messages: Sequence[dict[str, Any]]) -> str:
        return (await self.complete_with_usage(messages)).content

    async def complete_with_usage(self, messages: Sequence[dict[str, Any]]) -> ModelCompletion:
        started_at = time.perf_counter()
        outcome = "success"
        try:
            return await asyncio.wait_for(
                self._complete_qwen(messages),
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

    async def _complete_qwen(self, messages: Sequence[dict[str, Any]]) -> ModelCompletion:
        payload = {
            "model": self.model,
            "messages": [
                {
                    "role": message.get("role", "user"),
                    "content": str(message.get("content", "")),
                }
                for message in messages
            ],
            "temperature": self._settings.LLM_TEMPERATURE,
            "max_tokens": self._settings.LLM_MAX_OUTPUT_TOKENS,
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
            raise ModelUnavailableError(
                "Qwen provider reached the output limit (finish_reason=length)"
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

    async def stream(self, messages: Sequence[dict[str, Any]]) -> AsyncIterator[str]:
        yield await self.complete(messages)


def create_chat_model(
    settings: ModelConfig,
    *,
    reasoning_effort: str | None = None,
    enforce_reasoning_minimum: bool = True,
) -> OllamaChatModel | QwenChatModel:
    if settings.LLM_PROVIDER == "qwen":
        return QwenChatModel(settings)
    return OllamaChatModel(settings)
