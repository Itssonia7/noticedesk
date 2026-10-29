"""Application settings loaded from environment variables."""

from __future__ import annotations

from functools import lru_cache
from typing import Literal

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

Environment = Literal["development", "staging", "production"]
AuthProvider = Literal["clerk", "dev"]
StorageBackend = Literal["s3", "local"]
WorkflowBackend = Literal["temporal", "inline"]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    environment: Environment = "development"
    log_level: str = "INFO"

    # Non-superuser role so RLS actually filters. The 'noticedesk' superuser
    # is reserved for migrations and schema tests — superusers bypass RLS
    # even with FORCE.
    database_url: str = "postgresql+asyncpg://noticedesk_app:noticedesk_app@localhost:5432/noticedesk_dev"

    # The async engine needs the asyncpg driver. Tooling (Makefile, psql,
    # libpq) commonly hands out the bare ``postgres://`` / ``postgresql://``
    # form, which SQLAlchemy can't load as an async dialect — coerce it here
    # so an exported DATABASE_URL doesn't 500 every request.
    @field_validator("database_url")
    @classmethod
    def _ensure_async_driver(cls, v: str) -> str:
        if v.startswith("postgres://"):
            return "postgresql+asyncpg://" + v[len("postgres://") :]
        if v.startswith("postgresql://"):
            return "postgresql+asyncpg://" + v[len("postgresql://") :]
        return v

    auth_provider: AuthProvider = "dev"
    clerk_jwks_url: str | None = None
    clerk_issuer: str | None = None

    sentry_dsn: str | None = None

    # Stored as a plain string in env so Pydantic-Settings doesn't try to
    # JSON-decode it (a list-typed env var would force JSON, which breaks
    # the natural ``ALLOWED_ORIGINS=http://localhost:3000`` form). Parsed
    # into a list via :meth:`allowed_origins`.
    allowed_origins_raw: str = Field(
        default="http://localhost:3000",
        validation_alias="ALLOWED_ORIGINS",
    )

    @property
    def allowed_origins(self) -> list[str]:
        s = (self.allowed_origins_raw or "").strip()
        if not s:
            return []
        if s.startswith("["):
            import json

            try:
                parsed = json.loads(s)
            except json.JSONDecodeError:
                parsed = None
            if isinstance(parsed, list):
                return [str(x) for x in parsed]
        return [item.strip() for item in s.split(",") if item.strip()]

    # ---- Sprint 2: storage --------------------------------------------------
    storage_backend: StorageBackend = "local"
    aws_region: str = "ap-south-1"
    s3_documents_bucket: str | None = None
    local_storage_dir: str = "/tmp/noticedesk-storage"
    max_upload_bytes: int = 50 * 1024 * 1024  # 50 MB

    # ---- Sprint 2: OCR ------------------------------------------------------
    ocr_provider_primary: str = "stub"
    ocr_provider_fallback: str | None = None

    google_doc_ai_project_id: str | None = None
    google_doc_ai_location: str | None = None
    google_doc_ai_processor_id: str | None = None

    azure_doc_intel_endpoint: str | None = None
    azure_doc_intel_api_key: str | None = None

    # ---- Sprint 2: workflows ------------------------------------------------
    workflow_backend: WorkflowBackend = "inline"
    temporal_host: str = "localhost:7233"
    temporal_namespace: str = "default"
    temporal_task_queue: str = "noticedesk-ocr"

    # ---- Sprint 2: email inbound -------------------------------------------
    email_inbound_webhook_secret: str | None = None
    email_inbound_domain: str = "noticedesk.in"

    # ---- Outbound email ----------------------------------------------------
    # 'smtp' is the universal choice (Gmail / Mailtrap / SES-SMTP / Postmark
    # all work). 'stub' writes to EMAIL_STUB_DIR — useful for tests and
    # offline preview.
    email_provider: str = "stub"
    email_from_address: str = "noreply@noticedesk.in"
    email_from_name: str = "NoticeDesk"
    email_stub_dir: str = "/tmp/noticedesk-emails"
    # SMTP — only used when email_provider == 'smtp'. Gmail: smtp.gmail.com
    # port 587 with an app password. Mailtrap / Postmark / SES SMTP use
    # similar STARTTLS-on-587 pattern.
    smtp_host: str | None = None
    smtp_port: int = 587
    smtp_username: str | None = None
    smtp_password: str | None = None
    smtp_use_tls: bool = True

    # ---- Sprint 3: LLM providers -------------------------------------------
    llm_provider_primary: str = "stub"
    llm_provider_secondary: str | None = None

    anthropic_api_key: str | None = None
    anthropic_workspace_id: str | None = None
    anthropic_model: str = "claude-opus-4-7"
    # 15-section drafts of large notices commonly take 90-150s on Opus 4.7
    # because the model is generating ~6-8K output tokens. The OCR pipeline
    # is fast (single page, few seconds); this timeout only governs the
    # drafting LLM call. Raise it again if partners report timeouts on the
    # densest matters; lower it once we move to a streaming endpoint.
    anthropic_timeout_seconds: float = 180.0
    openai_api_key: str | None = None
    openai_model: str = "gpt-4o"
    openai_timeout_seconds: float = 180.0
    # Override to talk to an OpenAI-compatible endpoint instead of
    # api.openai.com — Groq, Cerebras, Together, Fireworks, OpenRouter,
    # Ollama all work. Leave empty for OpenAI itself.
    openai_base_url: str = ""

    # ---- Per-agent model overrides -----------------------------------------
    # Drafting needs Opus's writing quality (it produces 6-12K output tokens
    # of structured legal prose). Parsing + triage are extraction tasks —
    # Sonnet 4.6 is ~5-10x cheaper and indistinguishable in quality on those
    # workloads. The split saves roughly 50% of per-notice LLM cost.
    #
    # Empty string → fall back to the per-provider default (anthropic_model
    # / openai_model) so single-tier configs (cheap-dev, OpenAI-only) keep
    # working without any new env vars.
    llm_model_drafting: str = ""
    llm_model_triage: str = "claude-sonnet-4-6"
    llm_model_parsing: str = "claude-sonnet-4-6"

    # Agents
    document_parsing_prompt_version: str = "v1"
    document_parsing_max_tokens: int = 4096
    document_parsing_temperature: float = 0.0

    def model_for_agent(self, agent: str, *, provider: str) -> str:
        """Resolve the model name for a (provider, agent) combination.

        Anthropic-specific overrides are honoured for the Anthropic provider;
        every other provider falls through to its own native default so
        accidentally pointing ``LLM_MODEL_TRIAGE=claude-...`` at an OpenAI
        deployment doesn't 400.
        """
        if provider != "anthropic":
            return self.openai_model if provider == "openai" else ""
        per_agent = {
            "drafting": self.llm_model_drafting,
            "triage": self.llm_model_triage,
            "parsing": self.llm_model_parsing,
        }.get(agent, "")
        return per_agent or self.anthropic_model

    @property
    def is_dev_auth_allowed(self) -> bool:
        return self.environment == "development" and self.auth_provider == "dev"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
