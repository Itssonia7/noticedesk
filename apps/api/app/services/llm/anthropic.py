"""Anthropic Claude provider (primary LLM)."""

from __future__ import annotations

import asyncio
from typing import Any

from app.services.llm.base import (
    LLMError,
    LLMProvider,
    LLMResponse,
    LLMTransientError,
)


class AnthropicProvider(LLMProvider):
    """Calls Claude via the official ``anthropic`` async SDK.

    The SDK is imported lazily so tenants who only use the stub or OpenAI
    providers don't have to install it.
    """

    name = "anthropic"

    def __init__(
        self,
        *,
        api_key: str,
        model: str = "claude-opus-4-7",
        workspace_id: str | None = None,
        client: Any | None = None,
        timeout_seconds: float = 180.0,
    ) -> None:
        self._api_key = api_key
        self._workspace_id = workspace_id
        self.model = model
        self._client = client
        self._timeout = timeout_seconds

    def _ensure_client(self) -> Any:
        if self._client is not None:
            return self._client
        try:
            from anthropic import AsyncAnthropic  # type: ignore[import-not-found]
        except ImportError as e:
            raise LLMError(
                "anthropic SDK not installed; install it or pick a different provider"
            ) from e
        
        kwargs = {"api_key": self._api_key}
        if self._workspace_id:
            kwargs["default_headers"] = {"anthropic-workspace-id": self._workspace_id}
            
        self._client = AsyncAnthropic(**kwargs)
        return self._client

    async def generate_text(
        self,
        *,
        system: str,
        user: str,
        max_output_tokens: int = 4096,
        temperature: float = 0.0,
    ) -> LLMResponse:
        client = self._ensure_client()

        async def _call() -> LLMResponse:
            # Newer Claude models (Opus 4.7 and later) deprecate `temperature` —
            # passing it returns 400 'temperature is deprecated for this model'.
            # We only forward the kwarg when callers explicitly ask for non-zero
            # variability AND the model accepts it. Default deterministic-zero
            # callers (every agent today) get the model's native behavior.
            kwargs: dict[str, Any] = {
                "model": self.model,
                "max_tokens": max_output_tokens,
                "system": [
                    {
                        "type": "text",
                        "text": system,
                        "cache_control": {"type": "ephemeral"},
                    }
                ],
                "messages": [{"role": "user", "content": user}],
            }
            if temperature > 0.0 and not _model_deprecates_temperature(self.model):
                kwargs["temperature"] = temperature

            try:
                msg = await client.messages.create(**kwargs)
            except Exception as e:  # noqa: BLE001
                # Anthropic exceptions are mostly transient (rate limit, 5xx,
                # overloaded). Treat as transient by default so the workflow's
                # retry layer gets a chance.
                raise LLMTransientError(f"anthropic call failed: {e}") from e

            text_chunks = [
                block.text for block in msg.content if getattr(block, "type", None) == "text"
            ]
            content = "".join(text_chunks)
            usage = getattr(msg, "usage", None)
            return LLMResponse(
                content=content,
                model=self.model,
                provider_name=self.name,
                input_tokens=getattr(usage, "input_tokens", None) if usage else None,
                output_tokens=getattr(usage, "output_tokens", None) if usage else None,
                raw={"id": getattr(msg, "id", None)},
            )

        try:
            return await asyncio.wait_for(_call(), timeout=self._timeout)
        except TimeoutError as e:
            raise LLMTransientError(f"anthropic timed out after {self._timeout}s") from e


def _model_deprecates_temperature(model: str) -> bool:
    """Return True for models that 400 when `temperature` is passed.

    Anthropic deprecated the parameter starting with Opus 4.x. Conservative
    check: any model name containing 'opus-4' or 'sonnet-4' or 'haiku-4'.
    """
    lowered = model.lower()
    return any(tag in lowered for tag in ("opus-4", "sonnet-4", "haiku-4"))
