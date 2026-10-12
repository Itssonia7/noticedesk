# Implementation Progress: v7 Drafting Pipeline

Branch: `feature/v7-drafting` (not merged; no PR). Production still runs the old pipeline
(`DRAFTING_PIPELINE=rag` by default), so nothing in v7 is live yet.

Last updated: 12-10-2026

---

## (a) Status by stage

| Stage | What it does | Status | Commits |
|---|---|---|---|
| 0. Setup | Branch, `rag` / `v7` pipeline switch, confidentiality and template hardening | Done, review fixes applied | `5203ada`, `8220190` |
| 1. Issue matching | Reads the notice, matches each issue to a catalogue card (full / partial / none); 15-notice benchmark + eval runner | Done; benchmark not re-run since Stage 3 changes | `6f2a2b9`, `7e6bf07`, `bac98e1` |
| 2. Template fill + checks | Fills templates in code, assembles the 15-section reply, runs automatic quality checks (`draft_flags`) | Done, two rounds of CA review applied | `c021cfb`, `d6ae2c8`, `9e7ea4f` |
| 3. AI writers + citation check | AI writes partial / unmatched issues and high-stakes sections; every AI citation checked against IndianKanoon | Done (wired and tested with mocks) | `d4041fa`, `9a8e454` |
| Privacy safeguards | Commit hook + CI check for real GSTINs / PANs / documents; private data loader outside the repo | Done | `dfe2fdb` |

## (b) What Stage 3 now does (`9a8e454`)

- **Writers called by the pipeline**: partial issues go to the partial writer, unmatched issues to the
  new-issue writer, high-stakes notices (Section 74/74A, appeal stage, or demand at or above
  `HIGH_STAKES_DEMAND_THRESHOLD`) to the high-stakes model. If a writer fails, the draft keeps a
  `[[PENDING_AI]]` marker and is blocked; no stub text is inserted.
- **Citation check**: a case is VERIFIED only when name, court and year all match (plus the quote, if one
  is given). Partial matches and unchecked citations (no token, call limit, API error) block the draft;
  cases not found are removed from the text with the argument kept.
- **Notice-specific drafting**: Sections 01/02/12/14 use the notice's own section (no default
  "Section 73"); para-wise reply follows this notice's paragraphs; documents come from the template used
  or the AI output; internal notes and errors appear only in Section 13 (Internal Partner Note).
- **Extra checks**: leftover placeholders, "null", "[ref]", internal text, and a mismatch between the
  notice's section and the stage template now block the draft.
- **Database**: V26 rewritten (the original could never apply); V27 allows the new citation statuses.
  V23 to V27 tested on Postgres 16.

## (b2) Live-run fixes (first live run on `0816a44`)

- **Para-wise reply (notice 010)**: the matcher returns `para_map` as paragraph -> issues (`{"3": [1]}`);
  assembly read it as category -> paragraphs, so a 5-paragraph notice printed only Para 1. Both forms
  are now read, and the paragraph count is the largest of the matcher map, the numbered paragraphs in
  the notice text and the AI replies. AI replies are never dropped; a reply beyond the notice gets a warn flag.
- **Tax period (notice 002)**: one `PeriodNormalizer` reads "April 2020 to March 2021", "Apr-2020 to
  Mar-2021", "FY 2020-21", "F.Y. 2020-2021", "01.04.2020 to 31.03.2021", "July 2019", "07/2019" and
  FY ranges. The template version in force on the period's START date is used; a period crossing a
  version boundary gets a warn flag. (CARD-002 itself has no dev reply template, so it still goes to the AI writer.)
- **High-stakes writer (notice 002)**: JSON is extracted from fences/prose and unescaped quotes inside
  HTML are repaired; one retry that quotes the validation error, then the issue gets
  `[[PARTNER: AI draft failed — write manually]]` + block flag. Logs name the high-stakes model correctly.
  Likely causes of the 4/4 failures: double quotes inside HTML strings (Jackson "Unexpected character
  ('i')") and calls ending in the old 180 s timeout (now 600 s). Unusable responses log their shape;
  `LLM_DEBUG_LOG_RAW_OUTPUT=true` (live runner only) also logs a 400-char sample.
- **Cost control**: `LLM_HIGH_STAKES_MAX_CALLS_PER_NOTICE` (6) and `LLM_MAX_COST_INR_PER_NOTICE` (60);
  past either cap the remaining AI issues get the PARTNER placeholder + block flag, no call. The notice
  text is sent as a cached prefix block, so the 2nd and later issues of a notice read it from cache.
- **Duplicate prayer (notice 010)**: writer prompts forbid a prayer; code removes a trailing AI prayer
  (info flag). Section 12 is the only prayer.
- **Citations**: de-duplicated (case name, reporter citation, section / notification / circular number)
  before IndianKanoon checks and the citation list.
- **Stage templates**: stage now follows the notice's section (171 -> `anti_profiteering`, 74A -> `scn_74a`,
  74 -> `scn_74`, 73 -> `scn_73`) over the triage label. Dev seeds (DRAFT, partner to review) added for
  `scn_74`, `scn_74a`, `anti_profiteering`. A stage with no template gets a placeholder + block flag,
  never `scn_73`.
- **Tax period / date of receipt**: printed in Section 03 from the matcher / notice record
  (`notices.receipt_date`); `[[MISSING: ...]]` + warn flag when absent.
- **Runners**: the live runner inserts synthetic notice rows (tenant set for RLS) before the run and loads
  the dev seeds (skipped when `PRIVATE_SEED_DIR` is set). The matching eval runner writes no notice-linked
  rows, so it needed no change.
- **Answer key**: notice 007 -> none, 009 -> none, 015 -> full (CARD-009); CARD-004 summary tightened.

## (b3) Cost safeguards (`f558ef6`)

About Rs 350 was lost in live test runs that kept calling the API after the code had failed, ran
stale code, or could not parse a response. Now:

- **Fail fast**: HTTP 400 "credit balance is too low", 401 and 403 are fatal (`LlmFatalException`):
  never retried, never turned into a fallback result; the run stops with a one-line reason. Both
  live runners also stop after `LIVE_MAX_CONSECUTIVE_FAILURES` (default 2) failed or unusable calls
  in a row (truncated, refused, no text, bad JSON, exception).
- **Failed matching**: the notice is marked FAILED with the reason; no scaffold draft and no writer
  call (`MatchingFailedException`; in production the drafting job fails).
- **Version check**: both runners print `git rev-parse HEAD`; with `LIVE_EXPECTED_COMMIT` set (7+
  characters, prefix match) a different HEAD is refused. No git: a warning, the run continues.
  Uncommitted changes in tracked files print a warning.
- **Smoke mode**: `LIVE_SMOKE=true` runs the first notice only and prints its cost and status.
- **Test caps**: Rs 50 per run (`LIVE_MAX_RUN_COST_INR`), Rs 30 per notice (`LIVE_MAX_NOTICE_COST_INR`,
  applied by the runners only; each notice also gets at most what is left of the run cap).
  Production defaults in `application.yml` are unchanged (Rs 60 per notice).
- **Models**: the runners take models only from `LLM_MODEL_*` / `application.yml` (none built in) and
  refuse a model with no price, since its cost would bypass every cap. For a cheap plumbing run,
  point the writers at the matching model, e.g. `LLM_MODEL_NEW_ISSUE` and `LLM_MODEL_HIGH_STAKES` set
  to the same value as `LLM_MODEL_MATCHING`; pricing follows the override. A dated snapshot id
  returned by the API (`<model>-YYYYMMDD`) is priced as its base model.
- **Offline replay tests** (`LlmFixtureReplayTest`, fixtures in `src/test/resources/llm-fixtures/`):
  synthetic Messages API responses (thinking block first, then text; max_tokens; unescaped quotes
  inside a JSON string; billing and auth errors) are served by a local HTTP server, and the matcher
  and all three writers run end-to-end through the real Anthropic provider. Zero API cost. The
  matcher now uses the writers' JSON reader (fences, prose and stray quotes handled).
- **No tracked files touched by `mvn test`**: `RealNoticesBatchRunnerTest` runs only with
  `ENABLE_E2E_TESTS=true`; the stub-mode Scenarios / ClaudeOpusBatch runners write to `target/`
  (they still write to `output/` in an E2E run).

## (b4) Draft cleanup (`5bd1be1`, CA review of notices 002, 007, 010)

- **Documents (10 / 11 / 14)**: listed once across issues; duplicates after normalising case,
  whitespace and punctuation are removed, and a narrower entry ("GSTR-3B returns for April 2020 to
  March 2021") is merged into a wider one ("Copies of all GSTR-3B and GSTR-1 returns for ..."),
  keeping the first position.
- **Missing markers**: keys normalised (lower case, underscores) and de-duplicated in the Missing
  Data Markers list and `CHECK_MARKERS_LIST`; "date of service of notice" / "date and mode of
  service" -> `date_of_service`; `date_of_receipt` stays separate.
- **Para-wise reply (05)**: replies of several issues to the same notice paragraph are merged,
  ordered by sub-para ("Sub-para (a): ... Sub-para (c): ..."), repeated sentences dropped.
  Unmapped paragraphs keep the PARTNER placeholder.
- **High-stakes executive summary (01)**: AI narrative capped at 150 words plus one line per issue;
  the rest moves to the end of Section 03 (noted in Section 13), nothing dropped.
- **CHECK_PROCEEDING_CONSISTENT**: reads only the code-built notice description (01 / 02 / 14), the
  prayer (12) and the filing checklist (15); AI legal text may discuss Section 73 in a Section 74
  reply (Section 75(2) fallback, Section 73(9) comparison).
- **Partner note**: "Written by" shows `high_stakes` for issues the high-stakes writer wrote;
  anti-profiteering notices get Rule 133 / DGAP report points instead of Section 75(4) / 73(10) /
  74(10).
- **Matching eval vs live pipeline (notice 002)**: both send the same prompt, notice text, notice
  type (`scn_74`), effort and max_tokens. They can differ in the catalogue (the live runner reloads
  the dev seeds first; the eval runner uses the DB as it is) and the model is not deterministic
  (effort models take no temperature). Both runners now print the catalogue hash and the match
  statuses so two runs can be compared.

## (b5) Hard cost caps and two output fixes

The smoke run (notice cap Rs 5) spent Rs 13.19: matching cost Rs 2.61, then an Opus new_issue call
was allowed because only money already spent was checked, and it cost Rs 10.58. Now:

- **Hard caps**: before every billed call (matching, partial, new_issue, high_stakes; first attempt
  and retry) `NoticeCallBudget` computes the call's worst case and refuses it when
  `spent + worst case > cap`. Worst case = estimated input tokens (`ceil(characters / 2)` of system
  prompt + catalogue / notice prefix + user prompt, each token at the higher of the input and
  cache-write price) + the call's max_tokens (the value actually sent, retry cap included) at the
  output price, in INR from `noticedesk.pricing`. With a cap set, an unpriced model is refused.
  Applies in production (`llm.max-cost-inr-per-notice`) and in both live runners, where each notice
  gets the smaller of `LIVE_MAX_NOTICE_COST_INR` and what is left of `LIVE_MAX_RUN_COST_INR`.
  Every check logs a `[call-check]` (or `[call-refused]`) line with the estimate.
- **Refusals**: a refused writer call leaves `[[PARTNER: AI draft skipped — cost cap]]` and the
  `FLAG_AI_BUDGET_EXHAUSTED` block flag; a refused matching call fails the notice
  (`matching_cost_cap`) and the live runners stop.
- **Minimum caps** (current config): Opus 5.5 new_issue at 16,000 max_tokens has a worst case of
  about Rs 29 (Rs 28.16 output + about Rs 1 input); its retry at 24,000 about Rs 43. Sonnet 5.5
  matching at 8,000 max_tokens: Rs 7.04 output + input. A Rs 5 notice cap now refuses matching
  itself; one matching call + one Opus new_issue call needs a notice cap of about Rs 32+ (the
  default test cap of Rs 30 is not enough).
- **"Rs Rs"**: a template written `Rs {{issue.amount_tax}}` printed "Rs Rs 1,00,000". Filled
  template and stage-template text now drop a currency prefix (Rs / Rs. / INR / ₹) that directly
  precedes the filled "Rs X" value. The three dev reply templates use this pattern; the stage
  templates have no amount placeholders.
- **Readable missing markers**: markers are still de-duplicated by the normalised key (synonyms such
  as the date-of-service variants merged), but the Missing Data Markers list and
  `CHECK_MARKERS_LIST` show the original wording of the first occurrence.

## (b6) Live-run fixes (bench notices 004, 006, 010, 011)

- **State name (011)**: "State of Unknown State" came from a 3-code lookup. `GstStateCodes` has every
  GST state code (01-38, 97, 99; 25/26 both = Dadra and Nagar Haveli and Daman and Diu). A blank or
  placeholder state is taken from the GSTIN; an unknown code prints `[[MISSING: state]]` + warn flag.
- **Stage (011, Sections 27 / 63 / 129)**: an SCN under a provision other than 73/74/74A/171 now gets
  stage `generic_scn` (DRAFT dev template, neutral prayer and checklist, no Section 73/75(4) wording;
  neutral procedural options). It no longer falls back to `scn_73`.
- **Repeated paragraph (004)**: the partial prompt forbids restating an anchored block, and code drops
  an AI paragraph with token Jaccard >= 0.6 against a reused block (info flag
  `ai_paragraph_restates_template_block`, note in Section 13).
- **Issue splitting (001, 010)**: matching prompt rules: one issue per ground; consequential interest /
  penalty stays in the main issue unless it is the only demand or is challenged on its own ground;
  every numbered paragraph goes in `para_map`. Matching effort default `low` -> `medium`. Answer keys
  unchanged (all 15 already follow the rule); the eval runner also reports issue-count match.
- **Cost estimate**: input tokens now estimated at 2 characters per token (live matching call: estimate
  ~5,900 vs actual ~7,770 at 3). Worst cases rise slightly (e.g. Opus new_issue at 16,000 max_tokens
  about Rs 30).

## (c) Verification

- Offline unit suite (`mvn clean test`): **255 tests, 0 failures, 0 errors** (no live API calls; all models
  and IndianKanoon mocked or served from local fixtures). A plain `mvn test` no longer modifies `output/`.
- Golden drafts compared on every run, built from the testbench notice JSON:
  - `notice_07`: capital goods ITC, Gujarat, Rs 4,00,000, Section 73.
  - `notice_10`: anti-profiteering, Delhi, Rs 15,00,000, Section 171.
- Privacy hook tests: 16 passed, 0 failed.

## (d) Open items

1. **Next live run**: smoke run first (`LIVE_SMOKE=true`, `LIVE_EXPECTED_COMMIT` set), then Run 2
   on notices 01, 04, 06, 11, 10 (`V7_LIVE_NOTICES`). Set `LLM_MODEL_MATCHING`,
   `LLM_MODEL_PARTIAL_DRAFTING`, `LLM_MODEL_NEW_ISSUE`, `LLM_MODEL_HIGH_STAKES`, `INDIANKANOON_API_TOKEN`.
2. **CA-approved content**: real issue cards, reply templates and stage templates (prayer, filing
   checklist). All current templates are marked "[DEV - not CA reviewed]". Approved content goes in
   `$PRIVATE_SEED_DIR`, not the repo (see `docs/PRIVATE_DATA.md`).
3. **Stage templates for other proceedings**: `asmt_10`, `scn_73`, and DRAFT `scn_74`, `scn_74a`,
   `anti_profiteering`, `generic_scn` exist (dev text, partner to review). Other stages get a placeholder + block flag.
4. **Re-run the Stage 1 matching benchmark** with the live model (now reports dummy and private sets
   separately; now also issue-count match, to confirm the new splitting rules).
5. **Partner review of the golden drafts**, including three legal points in `notice_10`: Section 171(3A)
   effective date, transfer of anti-profiteering to CCI from 01-12-2022, and the Reckitt Benckiser
   wording in para 6.2.
6. **Possible real GSTINs already in the public repo** (in `ClaudeOpusBatchRunnerTest.java` and
   `RealNoticesBatchRunnerTest.java`): decide whether to remove them and clean history.
7. **Enable the privacy hook in every clone**: `git config core.hooksPath .githooks`.
8. **Merge to main and switch `DRAFTING_PIPELINE=v7`** once items 1 to 5 are done.

---

## Parked: RAG track

Paused while v7 is completed. Last state (commit `ac8a489`): 160 candidate issue pairs generated in
`NoticeDesk_RAG_Labelled_Set_Template.xlsx`, waiting for CA labelling; earlier details are in this
file's git history.
