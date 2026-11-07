# NoticeDesk

Litigation-first tax operating system for Indian CA firms. Sprint 1: foundation scaffold (PAN-centric data model, auth, API, frontend skeleton).

## Repository layout

```
/apps
  /web              Next.js 14 App Router frontend
  /api              FastAPI backend (Python 3.11+) — parallel rewrite, same API contract
/noticedesk-java     Spring Boot backend (Java 21) — primary backend
/packages
  /db               PostgreSQL migrations and seed data (used with apps/api)
  /shared           TypeScript types shared between web and api
  /agents           Python agent prompts and orchestration (placeholder for V2 sprints)
/infra              Terraform for AWS Mumbai
/docs               Architecture decision records
```

There are **two backend implementations** with the same API contract — pick one:

| | Python (`apps/api`) | Java (`noticedesk-java`) |
|---|---|---|
| Port | `8000` | `9090` |
| Status | Original, feature-complete | Rewrite in progress |
| Migrations | `packages/db/migrations/*.sql` | `noticedesk-java/src/main/resources/db/migration/*.sql` (Flyway) |

The frontend defaults to the Java backend. To point it at Python instead, see [Running the frontend](#3-frontend-nextjs).

## Identity model (non-negotiable)

PAN is the canonical identifier for every client. A client has exactly one IT
registration (`identifier_value = pan`) and zero-to-many GST registrations
(GSTIN where positions 3-12 = the client's PAN). Every notice, matter, draft,
and intelligence output resolves to a `(client_id, registration_id)` pair.

See `docs/adr/0001-pan-centric-identity-model.md`.

---

## Prerequisites

- **PostgreSQL 16+** running locally
- **Node.js 18+** and `npm`
- **Python 3.11+** (for the Python backend) — or **Java 21 + Maven** (for the Java backend)
- `uv` (Python package manager) — install with `pip install uv`

---

## 1. Database setup

Create the dev database and app role once:

```bash
createdb noticedesk_dev
psql noticedesk_dev -c "CREATE USER noticedesk_app WITH PASSWORD 'noticedesk_app';"
psql noticedesk_dev -c "GRANT ALL PRIVILEGES ON DATABASE noticedesk_dev TO noticedesk_app;"
psql noticedesk_dev -c "GRANT ALL ON SCHEMA public TO noticedesk_app;"
```

Apply migrations — **pick the path matching the backend you're running**:

**Python backend** (uses `packages/db/migrations/`):
```bash
make db-reset
```
This wipes, re-applies all migrations, and loads the demo seed (`packages/db/seeds/phase1_demo.sql`) in one step.

**Java backend** (Flyway migrations run automatically on startup — see step 2b). If you hit a broken migration state from a previous run, drop and recreate the DB first:
```bash
psql postgres -c "DROP DATABASE IF EXISTS noticedesk_dev;"
psql postgres -c "CREATE DATABASE noticedesk_dev;"
psql noticedesk_dev -c "GRANT ALL ON SCHEMA public TO noticedesk_app;"
```
Then load demo data manually after the app starts once (see `seed_demo.sql` at the repo root, or `packages/db/seeds/phase1_demo.sql` — both work against either backend's schema):
```bash
psql noticedesk_dev -f seed_demo.sql
```

---

## 2a. Backend — Python (FastAPI), port 8000

```bash
cd apps/api
uv sync
cp .env.example .env   # defaults work out of the box for local dev
uv run uvicorn app.main:app --reload --port 8000
```

Health check: `GET http://localhost:8000/v1/health`

Key `.env` defaults (already stub-mode, no API keys required to run):
- `LLM_PROVIDER_PRIMARY=stub` — set to `anthropic` + `ANTHROPIC_API_KEY=sk-ant-...` for real draft generation
- `OCR_PROVIDER_PRIMARY=stub` — set to `google_doc_ai` or `azure_doc_intel` for real OCR
- `CITATION_PROVIDER=stub` — set to `indiankanoon` + `INDIANKANOON_API_TOKEN=...` for real citation verification
- `AUTH_PROVIDER=dev` — dev header bypass, only works when `ENVIRONMENT=development`

Run tests:
```bash
cd apps/api && pytest
```

---

## 2b. Backend — Java (Spring Boot), port 9090

```bash
cd noticedesk-java
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS; must be Java 21, not 25
mvn spring-boot:run
```

Flyway applies all migrations automatically on startup. Health check: `GET http://localhost:9090/v1/health`

Config is in `noticedesk-java/src/main/resources/application.yml` (same env var names as the Python `.env.example` above where applicable — `DATABASE_URL`, `ANTHROPIC_API_KEY`, etc.)

---

## 3. Frontend (Next.js)

From the **repo root** (this is an npm workspace — installing here links `apps/web` and `packages/shared` together):

```bash
npm install
cd apps/web
cp .env.example .env.local
npm run dev
```

Open **http://localhost:3000**

By default the frontend proxies to the **Java backend on port 9090** (`NEXT_PUBLIC_API_BASE_URL` in `.env.local`). To point it at the **Python backend** instead, edit `apps/web/.env.local`:
```
NEXT_PUBLIC_API_BASE_URL=http://localhost:8000
```

### Logging in (dev auth)

Go to **http://localhost:3000/login** and enter any tenant/user UUIDs — this drops an httpOnly session cookie that the frontend forwards as `X-Dev-User-Id` / `X-Dev-Tenant-Id` headers on every API call.

If you loaded the demo seed, use the seed's fixed IDs:
- **Tenant ID:** `11111111-1111-1111-1111-111111111111`
- **User ID:** `22222222-2222-2222-2222-222222222222`

---

## 4. Calling the API directly (curl / Postman)

Every request (except `/v1/health`) needs both dev headers:

```bash
curl http://localhost:8000/v1/notices \
  -H "x-dev-tenant-id: 11111111-1111-1111-1111-111111111111" \
  -H "x-dev-user-id: 22222222-2222-2222-2222-222222222222"
```

(Swap port `8000` → `9090` for the Java backend.)

---

## One-shot everything (Python stack)

```bash
make db-reset      # migrations + seed data
make api-test       # backend tests
make web-test        # frontend typecheck + lint + build
```

See the `Makefile` for demo-prep targets (`demo-prep`, `demo-preflight`, `demo-walk`) that wire up real LLM/OCR providers for a partner demo.

---

## Sprint 1 scope

This sprint produces no end-user features. It produces a deployable skeleton
that subsequent sprints build on top of. Notice parsing, drafting, reminders,
WhatsApp, and any agent functionality are explicitly out of scope.

## Branch / deploy

- Feature branch: `claude/noticedesk-sprint-1-foundation-Xyr0t`
- CI: `.github/workflows/ci.yml`
