"""LLM factory keyed off ``LLM_PROVIDER_PRIMARY`` / ``LLM_PROVIDER_SECONDARY``.

Each agent picks its provider via :func:`get_llm_for_agent` so the cost-tier
split (Opus for drafting, Sonnet for parsing + triage) can be applied
without every agent knowing the model name. The legacy
:func:`get_primary_llm` / :func:`get_secondary_llm` helpers stay for any
caller that doesn't have an agent identity.
"""

from __future__ import annotations

from functools import lru_cache

from app.core.config import get_settings
from app.services.llm.anthropic import AnthropicProvider
from app.services.llm.base import LLMError, LLMProvider
from app.services.llm.openai import OpenAIProvider
from app.services.llm.stub import StubLLMProvider
from app.services.llm.stub_canned import lookup_canned_response

KNOWN_LLM_PROVIDERS: frozenset[str] = frozenset({"anthropic", "openai", "stub"})


def _build_anthropic(model: str) -> LLMProvider:
    s = get_settings()
    if not s.anthropic_api_key:
        raise LLMError("ANTHROPIC_API_KEY must be set for anthropic provider")
    return AnthropicProvider(
        api_key=s.anthropic_api_key,
        model=model,
        workspace_id=s.anthropic_workspace_id,
        timeout_seconds=s.anthropic_timeout_seconds,
    )


def _build_openai(model: str) -> LLMProvider:
    s = get_settings()
    if not s.openai_api_key:
        raise LLMError("OPENAI_API_KEY must be set for openai provider")
    return OpenAIProvider(
        api_key=s.openai_api_key,
        model=model,
        timeout_seconds=s.openai_timeout_seconds,
        base_url=s.openai_base_url or None,
    )


def _build_stub(model: str) -> LLMProvider:  # noqa: ARG001 — stub ignores model
    def _factory(system: str, user: str) -> str:
        canned = lookup_canned_response(user)
        return canned if canned is not None else "{}"

    return StubLLMProvider(response_factory=_factory)


_BUILDERS = {
    "anthropic": _build_anthropic,
    "openai": _build_openai,
    "stub": _build_stub,
}


@lru_cache(maxsize=16)
def _build_cached(name: str, model: str) -> LLMProvider:
    if name not in _BUILDERS:
        raise LLMError(f"unknown LLM provider: {name!r} (valid: {sorted(_BUILDERS)})")
    return _BUILDERS[name](model)


# ---- Agent-aware helpers (preferred for new callers) --------------------


def get_llm_for_agent(agent: str) -> LLMProvider:
    """Return the primary LLM for an agent ('drafting' | 'triage' | 'parsing').

    Routes to the right model tier — Opus for drafting, Sonnet for the
    extraction agents — so cost stays predictable. Non-Anthropic providers
    fall through to their own native default.
    """
    s = get_settings()
    name = s.llm_provider_primary
    model = s.model_for_agent(agent, provider=name)
    return _build_cached(name, model)


def get_secondary_llm_for_agent(agent: str) -> LLMProvider | None:
    s = get_settings()
    secondary = s.llm_provider_secondary
    if not secondary or secondary == s.llm_provider_primary:
        return None
    model = s.model_for_agent(agent, provider=secondary)
    return _build_cached(secondary, model)


# ---- Legacy helpers (kept for back-compat) ------------------------------


def get_llm_provider(name: str) -> LLMProvider:
    """Build a provider with its native default model. Legacy: prefer
    :func:`get_llm_for_agent` so per-agent cost tiers are honoured."""
    s = get_settings()
    if name == "anthropic":
        return _build_cached(name, s.anthropic_model)
    if name == "openai":
        return _build_cached(name, s.openai_model)
    return _build_cached(name, "")


def get_primary_llm() -> LLMProvider:
    return get_llm_provider(get_settings().llm_provider_primary)


def get_secondary_llm() -> LLMProvider | None:
    name = get_settings().llm_provider_secondary
    if not name or name == get_settings().llm_provider_primary:
        return None
    return get_llm_provider(name)
