.PHONY: db-test db-reset api-test web-test ci-local fmt lint typecheck demo-prep demo-anthropic demo-stub

DATABASE_URL ?= postgres://noticedesk:noticedesk@localhost:5432/noticedesk_test
# Dev DB (the API connects here). For local Mac dev created via
# `createdb -O noticedesk noticedesk_dev`.
DEV_DATABASE_URL ?= postgres://noticedesk:noticedesk@localhost:5432/noticedesk_dev

db-test:
	DATABASE_URL=$(DATABASE_URL) bash packages/db/tests/run_all.sh

# Wipe + re-apply migrations + reload the demo seed for the API's dev DB.
# Use this before a partner demo so the dataset is in a known state.
db-reset:
	@echo "==> Resetting $(DEV_DATABASE_URL)"
	DATABASE_URL=$(DEV_DATABASE_URL) bash packages/db/tests/run_all.sh
	@echo "==> Reloading demo seed"
	psql "$(DEV_DATABASE_URL)" -f packages/db/seeds/phase1_demo.sql >/dev/null
	@echo "==> Done. Active dataset:"
	@psql "$(DEV_DATABASE_URL)" -c "SELECT (SELECT count(*) FROM clients WHERE deleted_at IS NULL) AS clients, (SELECT count(*) FROM notices) AS notices, (SELECT count(*) FROM matters) AS matters;"

api-test:
	cd apps/api && pytest

web-test:
	cd apps/web && npm run typecheck && npm run lint && npm run build

ci-local: db-test api-test web-test

fmt:
	cd apps/api && ruff format . || true
	cd infra && terraform fmt -recursive || true

lint:
	cd apps/api && ruff check .











































































































	
	cd apps/web && npm run lint

typecheck:
	cd apps/api && mypy app
	cd apps/web && npm run typecheck
	cd packages/shared && npm run typecheck

# ---- Demo-mode targets ---------------------------------------------------
# These help you spin up a partner demo quickly. Real keys live in your
# shell environment; never check them into .env.

# Install the Anthropic SDK for real-LLM drafting + parsing.
demo-prep:
	cd apps/api && .venv/bin/pip install -e ".[anthropic]" python-docx
	cd apps/api && .venv/bin/python -m app.agents.evals.drafting.eval_runner
	$(MAKE) db-reset
	@echo
	@echo "==> Demo prep complete. To run with real Claude, export:"
	@echo "    export ANTHROPIC_API_KEY=sk-ant-..."
	@echo "    export LLM_PROVIDER_PRIMARY=anthropic"
	@echo "    export CITATION_PROVIDER=indiankanoon   # optional, only if you have a token"
	@echo "Then start uvicorn + npm run dev."

# Print the active provider config so you know what's serving a request.
demo-status:
	@echo "LLM_PROVIDER_PRIMARY = $${LLM_PROVIDER_PRIMARY:-stub}"
	@echo "OCR_PROVIDER_PRIMARY = $${OCR_PROVIDER_PRIMARY:-stub}"
	@echo "CITATION_PROVIDER    = $${CITATION_PROVIDER:-stub}"
	@echo "ANTHROPIC_API_KEY    = $${ANTHROPIC_API_KEY:+set}"
	@echo "INDIANKANOON_API_TOKEN = $${INDIANKANOON_API_TOKEN:+set}"

# Pre-generate a draft on the demo seed's overdue Acme notice so the
# partner demo opens on a populated Draft tab instead of waiting 60s live.
# Picks up whichever LLM_PROVIDER_PRIMARY is in the shell.
demo-preseed-draft:
	cd apps/api && .venv/bin/python -m scripts.preseed_demo_draft

# Run the full e2e flow against the running stack: upload a PDF (defaults
# to an embedded 1-page test file), poll for OCR + parse + route, then
# generate a draft. Requires uvicorn already running on :8000.
#
# For a partner demo flow with any real PDF:
#   STUB_DEFAULT_CANNED=acme_mh_asmt10 make demo-walk PDF=path/to/notice.pdf
demo-walk:
	cd apps/api && .venv/bin/python -m scripts.demo_e2e $(PDF)

# Install the real-LLM + real-OCR SDKs in one go. Both providers are
# optional extras in pyproject.toml so the default install stays slim.
demo-live-install:
	cd apps/api && .venv/bin/pip install -e ".[live]"

# Preflight: call Claude + Google Doc AI once each with the current env.
# Exits non-zero if either fails — use this 5 minutes before the partner.
demo-preflight:
	cd apps/api && .venv/bin/python -m scripts.check_live_providers
