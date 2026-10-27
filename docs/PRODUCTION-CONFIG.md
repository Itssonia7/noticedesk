# Production config — removing every mock

Eight surfaces are currently stubbed. This document walks each one in the
order you should tackle them. By the end you have zero mocks; the demo
banner at the top of the web app shows every provider chip in green.

For each section: **(1)** what to provision on the cloud side, **(2)** env
vars to set, **(3)** code changes if any, **(4)** how to verify.

> Quick reference — every env var that flips a mock off:
>
> | Mock today | Flip with |
> |---|---|
> | LLM stub | `LLM_PROVIDER_PRIMARY=anthropic` + `ANTHROPIC_API_KEY=…` |
> | OCR stub | `OCR_PROVIDER_PRIMARY=google_doc_ai` + the four GOOGLE_DOC_AI_* vars + `GOOGLE_APPLICATION_CREDENTIALS=/path/to/sa.json` |
> | Citation stub | `CITATION_PROVIDER=indiankanoon` + `INDIANKANOON_API_TOKEN=…` |
> | Dev auth | `AUTH_PROVIDER=clerk` + `CLERK_JWKS_URL=…` + `CLERK_ISSUER=…` **plus code change in `_verify_clerk`** |
> | Local storage | `STORAGE_BACKEND=s3` + `S3_DOCUMENTS_BUCKET=…` + AWS creds |
> | Inline workflow | `WORKFLOW_BACKEND=temporal` + Temporal cluster reachable |
> | Email stub | `EMAIL_INBOUND_WEBHOOK_SECRET=…` + SES configured |
> | Sentry off | `SENTRY_DSN=…` |

---

## 1. LLM provider (drafting + parsing) — Anthropic

Used by: `app/agents/document_parsing.py`, `app/agents/drafting.py`.

### 1.1 Provision

- Anthropic Console (`console.anthropic.com`) → API keys → create a key
  scoped to the `claude-opus-4-7` model.
- Add a monthly spend cap (Settings → Limits). Typical Phase-1 firm spend
  for ~50 drafts/day is ₹15-25K/month at Opus 4.7 list pricing.
- Set up a billing alert at 50% / 80% / 100% of the cap.

### 1.2 Env vars

```bash
LLM_PROVIDER_PRIMARY=anthropic
LLM_PROVIDER_SECONDARY=               # leave blank or 'openai' for fallback
ANTHROPIC_API_KEY=sk-ant-…
ANTHROPIC_MODEL=claude-opus-4-7        # or claude-sonnet-4-6 for cheaper
```

### 1.3 Install the SDK

```bash
cd apps/api && .venv/bin/pip install -e ".[anthropic]"
```

The optional extra is defined in `apps/api/pyproject.toml`. CI installs
without it; production explicitly adds it.

### 1.4 (Recommended) Enable prompt caching

The drafting system prompt is >1024 tokens and reused on every call.
Anthropic charges 90% less on cache reads. Apply the
[prompt-caching headers](https://docs.anthropic.com/en/docs/build-with-claude/prompt-caching)
to `AnthropicProvider.generate_text()` in
`app/services/llm/anthropic.py`. **One small code change**, ~10 lines.

### 1.5 Verify

```bash
make demo-status                  # ANTHROPIC_API_KEY = set
.venv/bin/python -m app.agents.evals.drafting.eval_runner
# Hand-grade the 10 fixture outputs against Vipul's gold-standard drafts
# for factual accuracy + hallucination rate.
```

The demo banner LLM chip flips to `Claude · claude-opus-4-7`.

---

## 2. OCR — Google Document AI primary, Azure Document Intelligence fallback

Used by: `app/agents/document_parsing.py` (downstream of OCR), the OCR
workflow.

### 2.1 Provision Google Document AI

- GCP Console → enable Document AI API in a project located in
  ap-south-1 (Mumbai) to satisfy DPDP residency.
- Document AI → Processors → **Create Processor** → Form Parser. Note
  the processor ID (looks like `e8c1a4b9d2f3e7a5`).
- IAM → create a service account `noticedesk-docai@<project>.iam` with
  role `Document AI API User`. Download the JSON key.
- Store the JSON key at a path the API process can read; never check it
  into git.

### 2.2 Env vars

```bash
OCR_PROVIDER_PRIMARY=google_doc_ai
OCR_PROVIDER_FALLBACK=                 # or 'azure_doc_intel' (see 2.4)
GOOGLE_DOC_AI_PROJECT_ID=your-gcp-project
GOOGLE_DOC_AI_LOCATION=asia-south1     # ap-south-1's GCP equivalent
GOOGLE_DOC_AI_PROCESSOR_ID=e8c1a4b9d2f3e7a5
GOOGLE_APPLICATION_CREDENTIALS=/etc/noticedesk/docai-sa.json
```

### 2.3 Install the SDK

```bash
cd apps/api && .venv/bin/pip install google-cloud-documentai
```

(Not declared as an optional extra today — runs through the lazy import
in `app/services/ocr/google_document_ai.py`. To make it a proper extra,
add a `google_doc_ai` group to `pyproject.toml` alongside the existing
`anthropic` / `openai` groups.)

### 2.4 (Optional) Provision Azure Document Intelligence as fallback

If Google Doc AI rate-limits or fails:

- Azure Portal → Document Intelligence resource in
  Central India region.
- Resource → Keys and Endpoint → copy `Endpoint` and `KEY 1`.

```bash
OCR_PROVIDER_FALLBACK=azure_doc_intel
AZURE_DOC_INTEL_ENDPOINT=https://noticedesk-docai.cognitiveservices.azure.com/
AZURE_DOC_INTEL_API_KEY=…
pip install azure-ai-documentintelligence azure-core
```

### 2.5 Verify

Upload a real notice PDF through `/inbox`. The inbox row should transition
`pending → in_progress → completed` within ~3-8 seconds. `documents_inbox.ocr_text`
contains real text, not "[stub OCR] notice text\nsize_bytes=…".

```bash
psql "$DATABASE_URL" -c "SELECT inbox_id, ocr_provider_used, LENGTH(ocr_text) FROM documents_inbox ORDER BY ocr_completed_at DESC LIMIT 1;"
```
Expected: `ocr_provider_used = google_doc_ai`, ocr_text length > 500.

Demo banner OCR chip flips to `google_doc_ai`.

---

## 2b. PDF runner on a laptop — local text, anonymisation, restored draft

Used by: `noticedesk-java` `V7PdfPipelineRunnerTest` (and `OCR_PROVIDER_PRIMARY=local` for the app's upload path).

The firm's gemini key is on the **free tier**, whose data may be used for training, so no client-identifying text
may reach gemini (or any model it does not have to). The PDF runner therefore never sends the PDF anywhere:

| Step | What runs | Where |
|---|---|---|
| 1. text | `OCR_PROVIDER=local` (default): PDFBox reads the text layer; a scanned PDF goes through the system `tesseract` (eng) page by page; without tesseract the run fails with "scanned PDF needs tesseract or a paid OCR provider; refusing to send raw bytes to a free-tier model" | laptop only, cost 0 |
| 2. anonymise | `NOTICE_ANONYMISE=on` (default): GSTINs (state code kept), PANs, TAN / CIN / IEC / Aadhaar / bank account / IFSC, phones, e-mails, PIN codes, ARN / DIN / reference numbers (same shape, so the DIN defect check still works), the taxpayer ("NOTICEE COMPANY", suffix kept), other parties ("SUPPLIER n TRADERS"), persons ("PERSON n"), street addresses ("ADDRESS n", city and State kept). Amounts, dates, periods, sections, forms, officer designations, rates, HSN and invoice numbers stay | laptop only; `$PRIVATE_SEED_DIR/results/<id>_ocr_anon.txt` + `<id>_anon_map.json` |
| 3. triage | `DocumentParsingAgent` on the anonymised text, file name `notice.pdf`; provider `LLM_PROVIDER_TRIAGE` (default `anthropic`, the primary provider; `gemini` allowed because it sees placeholders only), model `LLM_MODEL_TRIAGE` (else `LLM_MODEL_PARSING`, else the `model-parsing` default) | model call, anonymised text |
| 4. v7 pipeline | shortlist, matching, template variables, writers, citation check on the anonymised text; the learned library stores the anonymised text only | model calls, anonymised text |
| 5. outputs | restored before saving: `<id>_draft.txt`, `<id>_draft.docx`, `<id>_summary.txt`; `<id>_draft_anon.txt` keeps what the models saw; the console report lines are restored too | `$PRIVATE_SEED_DIR/results/` |

Guards: a non-local `OCR_PROVIDER` with the anonymiser on is refused (`NOTICE_ANONYMISE=off` is the only way to send
real text, and that is an explicit decision); `PRIVATE_SEED_DIR` must be set and outside any git working tree (the
mapping holds the real identifiers); the commit hook and `.gitignore` reject `*_anon_map.json`, `*_ocr_anon.txt`,
`*_draft_anon.txt` and `results/*_draft.*`. The notice id is the PDF's file name: name PDFs neutrally (`scn-001.pdf`).

### Env vars

```bash
OCR_PROVIDER=local              # local (default) | gemini (refused unless NOTICE_ANONYMISE=off)
NOTICE_ANONYMISE=on             # on (default) | off
PRIVATE_SEED_DIR=$HOME/noticedesk-private   # pdf/ in, results/ out (required)
V7_PDF_NOTICES=scn-001.pdf      # comma list under $PRIVATE_SEED_DIR/pdf, or absolute paths
LLM_PROVIDER_TRIAGE=anthropic   # anthropic (default) | gemini
LLM_MODEL_TRIAGE=               # default: LLM_MODEL_PARSING, else application.yml model-parsing
```

### Copy-paste block: one PDF end to end on a Linux laptop

```bash
# --- one time -------------------------------------------------------------------------------------
sudo apt-get install -y tesseract-ocr tesseract-ocr-eng      # only needed for scanned PDFs
export PRIVATE_SEED_DIR=$HOME/noticedesk-private              # outside the repository
mkdir -p "$PRIVATE_SEED_DIR/pdf" "$PRIVATE_SEED_DIR/results"
cp ~/Downloads/<the notice>.pdf "$PRIVATE_SEED_DIR/pdf/scn-001.pdf"   # neutral file name
# local Postgres with the dev database (README "1. Database setup") and the private catalogue loaded:
#   SPRING_PROFILES_ACTIVE=private-seed mvn -f noticedesk-java/pom.xml spring-boot:run   (once, then stop it)
cd noticedesk && git config core.hooksPath .githooks

# --- every run ------------------------------------------------------------------------------------
cd noticedesk && git fetch origin feature/v7-drafting && git checkout feature/v7-drafting && git pull --ff-only
export LIVE_EXPECTED_COMMIT=$(git rev-parse --short HEAD)     # the runner refuses stale code (paste the sha from PROGRESS.md to pin one)
cd noticedesk-java && mvn -q -DskipTests package              # first run downloads PDFBox; later runs can add -o

export ENABLE_E2E_TESTS=true
export V7_PDF_NOTICES=scn-001.pdf
export OCR_PROVIDER=local                 # default; shown here so nobody switches it on by accident
export NOTICE_ANONYMISE=on                # default

export ANTHROPIC_API_KEY=sk-ant-...
export LLM_MODEL_MATCHING=claude-sonnet-5-5           # priced in application.yml
export LLM_MODEL_PARTIAL_DRAFTING=claude-sonnet-5-5   # the Sonnet writer
export LLM_MODEL_NEW_ISSUE=claude-sonnet-5-5
export LLM_MODEL_HIGH_STAKES=claude-sonnet-5-5        # off by default (HIGH_STAKES_*), still must be set and priced
export LLM_PROVIDER_TRIAGE=anthropic                  # default; "gemini" (free tier) sees the anonymised text only
export LLM_MODEL_TRIAGE=claude-haiku-4-5-20251001
export INDIANKANOON_API_TOKEN=...                     # optional; without it every citation stays NOT_CHECKED (block)

export LIVE_MAX_RUN_COST_INR=150          # hard cap for the whole run
export LIVE_MAX_NOTICE_COST_INR=60        # hard cap per notice (OCR + triage + pipeline)
export LIVE_SMOKE=true                    # first PDF only; drop it for the whole V7_PDF_NOTICES list

mvn test -Dtest=V7PdfPipelineRunnerTest -DfailIfNoTests=false

ls -l "$PRIVATE_SEED_DIR/results/"       # scn-001_ocr_anon.txt, scn-001_anon_map.json, scn-001_draft_anon.txt,
                                         # scn-001_draft.txt, scn-001_draft.docx, scn-001_summary.txt
```

What to read in the console: `OCR: provider=local model=pdfbox pages=N chars=... cost INR 0.0000`, one
`Privacy: anonymised GSTINs a, PANs b, names c (...), addresses d, references e, other ids f` line per notice (counts
only), `Triage: document_type=...`, then the usual v7 report lines (restored) and the paths of the saved files.

---

## 3. Citation verification — IndianKanoon

Used by: `app/agents/citation_verification.py`.

### 3.1 Provision

- IndianKanoon → Account → API access → request a token. The free tier
  is rate-limited (~30 req/min); the paid tier is ~₹3K/month for
  unlimited.
- Note: the free tier returns search results but not paragraph text.
  `verified_paragraph_text` stays NULL on most rows. Tier 2 (Taxmann /
  SCC) is what gives paragraph-level verification — Phase 2 work.

### 3.2 Env vars

```bash
CITATION_PROVIDER=indiankanoon
INDIANKANOON_API_TOKEN=your-token-here
# real per-call prices from your IndianKanoon plan (INR); 0 = logged but free.
# Every v7 search / fragment call is logged in api_usage_logs (step ik_search / ik_fragment)
# at this price and counted against LLM_MAX_COST_INR_PER_NOTICE; past the cap the remaining
# citations are NOT_CHECKED (block flag). HTTP 401 / 403 / 429 from IndianKanoon stop the run.
INDIANKANOON_COST_INR_PER_SEARCH=0
INDIANKANOON_COST_INR_PER_FRAGMENT=0
# cases an AI writer may cite per issue (the writer prompts ask for at most 3). Extra cases stay in the text,
# are marked NOT_CHECKED ("beyond per-issue citation limit") and are never sent to IndianKanoon. 0 = no limit.
CITATIONS_MAX_PER_ISSUE=3
```

Citation-only live check (no model calls): `ENABLE_E2E_TESTS=true INDIANKANOON_API_TOKEN=... mvn test
-Dtest=CitationCheckRunnerTest` prints the status of five well-known citations (one invented, must be
NOT_FOUND), the IndianKanoon call count and the configured cost. `CITATION_CHECK_LIST="case|court|year|quoted text;..."`
overrides the list (court, year and quoted text optional; a quoted text costs one fragment call).

A cited name is matched against the IndianKanoon title after both are normalised (Pvt / Private, Ltd / Limited,
(P), Commr / Commissioner, CCE, "&" / "and", "M/s", "and others", bracketed parts, the judgment date and
initials such as "D.Y.Beathel"); the cited court against the docsource ("Supreme Court of India", "<place> High
Court", "CESTAT", "Appellate Tribunal").

### 3.3 Verify

Generate a draft. Citations in Section 06 should show real
`indiankanoon.org/doc/<id>/` URLs you can click.

```bash
psql "$DATABASE_URL" -c "SELECT case_name, status, source_url FROM citations ORDER BY verified_at DESC LIMIT 10;"
```
Expected: mix of VERIFIED + VERIFIED_PARTIAL with real URLs.

Demo banner Citations chip flips to `indiankanoon`.

---

## 4. Auth — Clerk

Used by: `app/core/auth.py`, every API request.

> This is the only one with non-trivial code work — `_verify_clerk` in
> `app/core/auth.py` currently raises `NotImplementedError`.

### 4.1 Provision

- Clerk Dashboard → create application in `asia` region.
- Authentication → Multi-Factor → enable TOTP (recommended for partners).
- Organizations → enable. Map one Clerk org per CA firm.
- API keys → copy the **Issuer URL** (e.g. `https://<your-app>.clerk.accounts.dev`)
  and **JWKS URL** (`<issuer>/.well-known/jwks.json`).
- Sessions → set the session token lifetime to 24h (default 7d is too
  generous for litigation data).

### 4.2 Env vars

```bash
ENVIRONMENT=production
AUTH_PROVIDER=clerk
CLERK_JWKS_URL=https://<your-app>.clerk.accounts.dev/.well-known/jwks.json
CLERK_ISSUER=https://<your-app>.clerk.accounts.dev
```

`ENVIRONMENT != development` causes `_verify_dev` to reject all
`X-Dev-*` headers (see `auth.py:48`), so the cookie path is closed
automatically.

### 4.3 Code changes needed

1. **Implement `_verify_clerk`** in `app/core/auth.py`. Use `pyjwt` +
   the JWKS URL to verify the bearer token's signature, then read `sub`
   as `user_id` and the `org_id` claim as `tenant_id`. Roughly:
   ```python
   import httpx, jwt
   from jwt.algorithms import RSAAlgorithm

   async def _verify_clerk(self, request: Request) -> AuthClaims:
       token = _extract_bearer(request)
       if not token:
           raise AuthError("missing bearer token")
       jwks = await self._fetch_jwks_cached()
       unverified_header = jwt.get_unverified_header(token)
       key_data = next(k for k in jwks["keys"] if k["kid"] == unverified_header["kid"])
       public_key = RSAAlgorithm.from_jwk(json.dumps(key_data))
       claims = jwt.decode(token, public_key, algorithms=["RS256"], issuer=self._settings.clerk_issuer)
       if "org_id" not in claims:
           raise AuthError("clerk token missing org_id claim — make sure Organizations is enabled")
       return AuthClaims(user_id=claims["sub"], tenant_id=claims["org_id"], email=claims.get("email"))
   ```
   Add a 5-minute JWKS cache so we don't hit Clerk on every request.

2. **Migration to map Clerk org IDs → tenant rows**. The seed today
   uses `tenant_id = 11111111-…`. In production, each Clerk org's
   `org_id` becomes the tenant. Two options:
   - Use Clerk's `org_id` directly as `tenant_id` (UUIDs both). Cleanest.
   - Add a `clerk_org_id TEXT UNIQUE` column on `tenants` and look up
     tenant_id by org_id in middleware.

3. **Frontend** — replace `/login` page with Clerk's `<SignIn />`
   component. `apps/web/lib/session.ts` drops the cookie reader and
   reads `auth().userId` from `@clerk/nextjs`. The BFF proxies stop
   forwarding `X-Dev-*` and forward the Clerk session JWT in
   `Authorization: Bearer <token>` instead.

This is ~1 day of work + testing. Don't skip it before signing a paid
firm.

### 4.4 Verify

```bash
curl https://api.your-domain.in/v1/dashboard/status_counts \
  -H "Authorization: Bearer <a real Clerk JWT>"
# 200 OK with the tenant's counts

curl https://api.your-domain.in/v1/dashboard/status_counts \
  -H "X-Dev-User-Id: 11111111-…" -H "X-Dev-Tenant-Id: 22222222-…"
# 401 — dev headers rejected
```

Demo banner Auth chip flips to `clerk`.

---

## 5. Storage — S3 in ap-south-1

Used by: every upload (`/v1/documents/upload`, `/v1/matters/{id}/documents`).

### 5.1 Provision

- AWS Console → S3 → **Mumbai (ap-south-1)** region only.
- Create bucket `noticedesk-documents-prod`.
  - Block all public access: yes
  - Bucket versioning: enabled
  - Default encryption: SSE-KMS with a customer-managed key
  - Object Lock: enabled (governance mode, 7-year retention default)
  - Lifecycle: transition to Glacier Deep Archive after 90 days
- Create an IAM role `noticedesk-api` with this policy on the bucket:
  ```json
  {
    "Version": "2012-10-17",
    "Statement": [
      {
        "Effect": "Allow",
        "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"],
        "Resource": "arn:aws:s3:::noticedesk-documents-prod/*"
      },
      {
        "Effect": "Allow",
        "Action": ["s3:ListBucket"],
        "Resource": "arn:aws:s3:::noticedesk-documents-prod"
      }
    ]
  }
  ```
- Attach the role to the EC2/Fargate instance running the API. Don't use
  long-lived access keys.

### 5.2 Env vars

```bash
STORAGE_BACKEND=s3
S3_DOCUMENTS_BUCKET=noticedesk-documents-prod
AWS_REGION=ap-south-1
# No AWS_ACCESS_KEY_ID — boto3 picks up the instance role.
```

### 5.3 Verify

Upload a document via the UI. Then:
```bash
aws s3 ls s3://noticedesk-documents-prod/tenants/<tenant-id>/ --recursive | head -5
```
You should see the object. Check its encryption:
```bash
aws s3api head-object --bucket noticedesk-documents-prod --key tenants/…
# Look for "ServerSideEncryption": "aws:kms"
```

Demo banner Storage chip flips to `s3`.

---

## 6. Workflow backend — Temporal

Used by: drafting, OCR pipeline, parse+route. Inline blocks the request
thread for ~60s on draft generation; Temporal moves it off-thread.

### 6.1 Provision

Two options:

**Option A — Temporal Cloud (recommended for Phase 1)**. Managed.
- Sign up at `cloud.temporal.io` → create namespace in `ap-southeast-1`
  (Singapore — closest available; Mumbai isn't yet a Temporal Cloud region).
- Generate a mTLS client cert pair.

**Option B — Self-hosted Temporal**. Run via Helm on the same Kubernetes
cluster as the API. Steeper ops burden.

### 6.2 Env vars

```bash
WORKFLOW_BACKEND=temporal
TEMPORAL_HOST=<namespace>.<region>.tmprl.cloud:7233    # Cloud
# or
TEMPORAL_HOST=temporal-frontend.temporal:7233          # self-hosted
TEMPORAL_NAMESPACE=noticedesk-prod
TEMPORAL_TASK_QUEUE=noticedesk-ocr
```

For Cloud, also set:
```bash
TEMPORAL_TLS_CERT=/etc/noticedesk/temporal.crt
TEMPORAL_TLS_KEY=/etc/noticedesk/temporal.key
```
(Wire these in `app/workflows/dispatcher.py` `TemporalDispatcher.__init__`.)

### 6.3 Code work

The `TemporalDispatcher` class exists. The `Workflow` definitions in
`app/workflows/temporal_defs.py` need to be registered with the Temporal
worker process. Run the worker in a separate container:

```bash
cd apps/api && .venv/bin/python -m app.workflows.worker
# (file doesn't exist yet — needs ~30 lines to register the workflows
#  + activities with the SDK's Worker())
```

### 6.4 Frontend change

When workflows go async, `POST /v1/notices/{id}/draft` returns immediately
with `{draft_id: null, workflow_run_id: "…"}` rather than blocking. Add:
- `GET /v1/drafts/progress?workflow_run_id=…` — polls Temporal for status
- Frontend `DraftTab` polls every 2s until `state == "completed"`

This is ~2-3 hours of work on top of having Temporal running.

### 6.5 Verify

```bash
# Trigger a draft
curl -X POST .../v1/notices/<id>/draft -d '{"tone":"formal"}'
# Returns immediately with a workflow_run_id

# Temporal UI shows the workflow running
# https://cloud.temporal.io/namespaces/noticedesk-prod/workflows
```

Demo banner adds a "Workflow: temporal" chip (not in the banner today;
you'll add this UI bit alongside the polling change).

---

## 7. Email inbound — Amazon SES

Used by: `app/routes/email.py` — partners forward GST/IT department
emails to `notices+<firm-slug>@<your-domain>`.

### 7.1 Provision

- Register your production domain (e.g. `noticedesk.in`).
- Route53 → MX record pointing at SES inbound mail servers.
- SES Console → Verified identities → verify the domain.
- SES → Email Receiving → create rule set:
  - Recipients: `notices@noticedesk.in` (with the `+slug` suffix handled
    by SES's recipient matching).
  - Actions: invoke Lambda OR publish to SNS topic that POSTs to
    `https://api.noticedesk.in/v1/email/inbound`.
- Generate a webhook shared secret. SES doesn't provide HMAC headers
  natively, so the Lambda is the place to sign the payload.

### 7.2 Env vars

```bash
EMAIL_INBOUND_WEBHOOK_SECRET=<long random string>
EMAIL_INBOUND_DOMAIN=noticedesk.in
```

### 7.3 Verify

```bash
echo "from: officer@gst.gov.in\nto: notices+mehta@noticedesk.in\nsubject: ASMT-10 Acme\n\n<base64 attachment>" \
  | aws ses send-raw-email --raw-message file:///dev/stdin
```
The inbox row should appear in `documents_inbox` with `ingest_channel='email'`.

Demo banner adds an "Email: connected" chip on Settings → Portal
Connectors → Email Forwarding card.

---

## 8. Error monitoring — Sentry

### 8.1 Provision

- `sentry.io` → create project, type Python (FastAPI).
- Copy the DSN.

### 8.2 Env var

```bash
SENTRY_DSN=https://<key>@<org>.ingest.sentry.io/<project-id>
```

That's it. `app/main.py:31` initializes Sentry only when DSN is set.

### 8.3 Verify

Throw a deliberate 500 (`/v1/this-endpoint-does-not-exist`) and confirm
it lands in the Sentry UI within 60s.

---

## 9. Frontend — Clerk + production domain

Beyond auth (covered in §4):

- Set `NEXT_PUBLIC_API_BASE_URL=https://api.noticedesk.in` in
  `apps/web/.env.production`.
- Domain + TLS — Cloudflare in front of the load balancer.
- Replace `apps/web/app/login/page.tsx` with Clerk's `<SignIn />`.
- Wrap `app/layout.tsx` in `<ClerkProvider>`.
- Hide the demo banner — it auto-hides when `ENVIRONMENT != development`
  AND every provider is real (see `apps/web/components/shell/demo-banner.tsx`).

---

## 10. Final checklist before signing a firm

Run each command on the production-bound environment.

```bash
make demo-status
# Every var should report 'set' or a real value.

curl https://api.noticedesk.in/v1/demo/providers | jq
# Every "is_stub" / "is_dev" / "is_local" should be false.

# Eval the parser against the 6 fixture PDFs with real Doc AI + Claude.
.venv/bin/python -m app.agents.evals.eval_runner --mode real

# Eval the drafter on the 10-matter fixtures with real Claude.
.venv/bin/python -m app.agents.evals.drafting.eval_runner

# RLS check: make sure tenant A cannot see tenant B's clients.
# Create two test firms in Clerk, sign in as user A, query /v1/clients,
# confirm zero overlap.

# Generate + export one filing-grade .docx and open it in Word.
# Ensure cover sheet, Times New Roman, footer with firm name + page number.
```

Sentry should show zero unhandled errors during the test pass. RDS slow-query
log should show no queries > 200ms. Cost dashboards (Anthropic, Doc AI,
IndianKanoon) should reflect what you ran.

When all 10 checks pass, the demo banner auto-hides. You're production-ready.

---

## Cost ballpark per firm per month (Phase 1 paid pilot)

| Service | Driver | Est ₹ |
|---|---|---|
| Anthropic Opus 4.7 | 50 drafts × 8K tokens output × ~150 tokens/₹ | 20,000 – 30,000 |
| Google Doc AI | 50 PDFs × 5 pages × ₹0.15/page | 400 |
| IndianKanoon paid tier | unlimited | 3,000 |
| AWS (RDS t4g.small + S3 + SES) | 5GB DB + 50GB S3 + 200 emails | 4,500 |
| Clerk Pro | per MAU pricing, ~5 users | 1,500 |
| Sentry Team | 50K events/month | 2,000 |
| Temporal Cloud | 10K actions/month | 800 |
| **Total** | | **₹32,200 – ₹42,200** |

Charge ₹75K-1L/month per firm to maintain ~50% gross margin including
your time.
