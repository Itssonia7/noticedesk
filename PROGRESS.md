# Implementation Progress: v7 Drafting Pipeline

Branch: `feature/v7-drafting` (not merged; no PR). Production still runs the old pipeline
(`DRAFTING_PIPELINE=rag` by default), so nothing in v7 is live yet.

Last updated: 20-10-2026 (shortlist v2: rarity-weighted label scoring, AI title pre-pass, siblings by title, stage-pool widening; shortlist dry-run tool)

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

## (b7) Cache-aware cost estimate, provisional templates, citation-only runner

- **Cache-aware worst case** (`NoticeCallBudget.estimate`, `PromptCacheRegistry`): the hard cap priced the
  whole prompt (rules + ~330k-character catalogue) at the cache-write rate on every call, so one Sonnet
  matching call was estimated at about Rs 36 + Rs 7.04 output and refused under the test caps even when
  the catalogue was already cached. The estimate is now split: the cacheable prefix (system prompt +
  catalogue; for the writers system prompt + notice prefix) is priced at the cache-READ rate when this
  process wrote or read the same prefix hash for the same model within the cache's warm window (5 min
  TTL minus 30 s), else at cache-write (first call of a run); the per-call part at the input rate
  (`CHARS_PER_TOKEN=2`); output = max_tokens x output price. The token count the API reports for the
  prefix (cache_creation / cache_read tokens) is recorded on the first call and reused for later
  estimates of the same prefix. `[call-check]` logs the breakdown (`prefix_tokens`, `prefix_pricing`,
  `prefix_INR`, `per_call_INR`, `output_INR`). Sonnet 5.5 matching on an 83,000-token catalogue at
  max_tokens 8000: first call Rs 25.30 (prefix Rs 18.26 cache-write + Rs 7.04 output), later calls
  Rs 8.50 (prefix Rs 1.46 cache-read + Rs 7.04), plus the notice text at the input rate (about Rs 1.76
  for a 20,000-character notice). A Rs 12 notice cap refuses the first call and allows the next.
- **AI section -> provisional template** (`ProvisionalTemplateService`, `TemplateScrubber`, V28): an
  approved `ai_issue_sections` row becomes a 'draft' issue card (`NDK-AI-nnn`, or a new version of the
  card given in the request) and a 'provisional' `reply_templates` row whose blocks are the section's
  04/05/06 paragraphs with every notice-specific value replaced by a placeholder (`{{client.legal_name}}`,
  `{{client.gstin}}`, `{{notice.reference}}`, `{{notice.issue_date}}`, `{{issue.amount_tax}}`,
  `{{issue.period}}`, `{{issue.<fact>}}` for identifying / numeric facts, `{{notice.total_demand_amount}}`),
  in every form the writers use (Rs 4,82,310/-, 482310, 4.82 lakh, 15.10.2023, 15th October 2023,
  FY 2022-23, April 2022 to March 2023, M/s ABC Pvt. Ltd. / ABC Private Limited). A value that is still
  present afterwards refuses the promotion (nothing written). Citations are carried per block with their
  check status, documents from Section 10; provenance (source section, notice, tenant, approver, time)
  on the card, the template and the section. Idempotent: a second promotion returns the existing
  template. Endpoints `POST /api/v7/ai-sections/{id}/promote` (body `{approvedBy, cardId?}`) and
  `GET /api/v7/ai-sections?noticeId=` (tenant from the auth claims, RLS tenant set for the transaction).
  Matching sees promoted cards when `CATALOGUE_INCLUDE_PROVISIONAL=true` (default); `TemplateFillService`
  uses 'provisional' templates like 'active' and adds the info flag `FLAG_PROVISIONAL_TEMPLATE`; the
  partner note shows "Template status=PROVISIONAL". `notice.reference` is now a fill variable.
  Note: issue_cards / reply_templates are the global catalogue (no tenant, no RLS), so a promoted
  template is shared by every tenant of the deployment: hence the strict placeholder replacement.
- **IndianKanoon cost and failures**: each v7 search / fragment call is logged at
  `INDIANKANOON_COST_INR_PER_SEARCH` / `_FRAGMENT` (default 0, set from the firm's plan; step
  `ik_search` / `ik_fragment` in api_usage_logs and the run ledger) and counted against the per-notice
  cap; past the cap the remaining citations are NOT_CHECKED with `IK_COST_CAP_REACHED`. HTTP 401 / 403 /
  429 from IndianKanoon throw `IndianKanoonFatalException` (an `LlmFatalException`): the run stops
  instead of silently marking citations NOT_CHECKED.
- **Citation-only live runner** (`CitationCheckRunnerTest`, `ENABLE_E2E_TESTS=true` +
  `INDIANKANOON_API_TOKEN`): runs the citation agent only (no model calls) on `CITATION_CHECK_LIST`
  ("case|court|year;...") or five defaults (Hindustan Steel 1969, CIT v Vatika Township 2014, Whirlpool
  v Registrar of Trade Marks 1998, Mangalore Chemicals 1991, one invented name that must be NOT_FOUND)
  and prints the status per citation, the IndianKanoon call count and the cost.

## (b8) High-stakes mode off by default (13-10-2026)

- The high-stakes drafting mode is now OFF unless switched on by env var: `HIGH_STAKES_SECTION_74=true`
  (Section 74/74A notices), `HIGH_STAKES_APPEAL_STAGE=true` (appeal-stage notices) or
  `HIGH_STAKES_DEMAND_THRESHOLD=<amount>` (demand at or above the amount). All three default to off /
  blank in `application.yml` and `AppProperties`; the high-stakes code and prompt are unchanged.
- With the mode off, every notice (Section 74/74A and appeal stage included) takes the normal path:
  code template fill for full matches, the partial writer for partial matches, and the new-issue writer
  only for issues with no card.

## (b9) Private notice set for the live runners (13-10-2026)

- The firm's real catalogue (269 cards NDK-001..NDK-269, 269 templates TPL-001..TPL-269) and 308
  private test notices now live in `$PRIVATE_SEED_DIR` (never in the repo); the 20 dummy cards were
  deleted from the dev database. Test code only, no production change.
- `MatchingEvalRunnerTest`: `MATCHING_EVAL_SETS` (`both` default | `dummy` | `private`) and
  `MATCHING_EVAL_NOTICES` (chosen private files, in order; a missing name refuses the run before any
  call). `LIVE_SMOKE=true` with `MATCHING_EVAL_SETS=private` runs the first private notice.
- Card-level accuracy added next to the old status-level metrics: an expected issue counts as correct
  only when a returned issue has the same status and one of its card ids (status `none` needs no card);
  the runner also counts "status right, wrong card", missed and extra issues, and notices with every
  issue correct. Wrong-match details for the private set show notice id, statuses and card ids only.
- `V7LivePipelineRunnerTest`: `V7_LIVE_NOTICES` names resolve on the classpath first, then in
  `$PRIVATE_SEED_DIR/notices`, or as an absolute path; a missing name refuses the run. Header reading
  moved to `BenchNoticeHeader` (unit-tested offline): `Date:`/`Dated:` with `-` or `.`, `Reference No.:`,
  `FY 2019-20` / `F.Y.`, `Section 73(1)` -> `Section 73`; bracket / underscore placeholders are null.
  A notice without a GSTIN uses the synthetic allow-listed bench GSTIN for its DB rows only.
- Both runners refuse to run when `PRIVATE_SEED_DIR` is set and `CARD-*` cards remain in the database,
  and print the catalogue size and hash first. Details in `docs/PRIVATE_DATA.md`.

## (b10) Matching prompt v2: one primary card + alternates (13-10-2026)

- Live finding on 40 private notices against the 269-card catalogue: the matcher returned 2-3 card ids per
  issue instead of choosing one, called a match "partial" even when the right card was among its candidates,
  bundled several distinct grounds into one issue (96 expected grounds came back as 56 issues) and missed
  date-regime traps between sibling cards.
- `prompts/issue_matching_v2.md` (v1 kept untouched; `IssueMatchingAgent.PROMPT_VERSION` = `issue_matching_v2`):
  the model first lists every distinct ground (`grounds`), then returns exactly one issue per ground; per issue
  ONE primary `card_id` (or null) plus up to two `alternates`; the primary is chosen on the card's DECISIVE facts
  and every NOT THIS IF rule ("Not this if <fact> (use NDK-xxx)" forces the sibling); status `full` = all
  DECISIVE facts present and no NOT THIS IF triggered (missing amounts do not matter), `partial` = the card's
  ground but a DECISIVE fact absent / contradicted or a material element the card does not cover (named in
  `why`), `none` = no card; doubt between siblings is never "partial"; a period-regime check (order / notice /
  tax-period dates against each sibling's PERIOD and date boundaries) precedes the choice; a self-check list
  closes the prompt. The rules on no invention, para_map and issue splitting 4(a)-(c) are kept.
- Result shape: `MatchedIssue.cardIds` now holds ONLY the primary (one element, or empty for `none`), so
  template fill and every downstream consumer are unchanged; the new field `alternateCardIds` (at most two,
  never the primary) holds the other candidates. The parser reads both the v2 shape (`card_id` + `alternates`)
  and the old v1 shape (`card_ids`: first = primary, rest = alternates). Unknown primary -> status `none` +
  issue flag `unknown_card_id` (as before); unknown alternate -> dropped with a log line. New non-failing
  flags: issue flag `hedged` (status full / partial without a primary), result flag `issue_count_mismatch`
  (`issues.length != grounds.length`). Alternates are not persisted (`issue_matches` has no spare JSON column
  and no migration was added); they live in the result object only.
- `MatchingEvalRunnerTest` scores the PRIMARY card only: the answer key's expected issues are first
  de-duplicated by card_ids set (keys that list interest / penalty as a separate issue on the main card), then
  each expected issue is paired as correct primary (status + card), primary card right but status wrong, right
  card only in alternates, wrong, missed; extra counts against the de-duplicated expected count. The "Got"
  column of WRONG MATCH DETAILS shows the alternates (`full[NDK-006] alt[NDK-007]`).

## (b11) Matching prompt v3: visible-defect issues, written date-boundary checks (13-10-2026)

- Live finding on 40 private notices (269 cards, prompt v2): (1) the matcher never raised procedural /
  visible defects as issues because v2 defined an issue as a "ground of demand" (a corrigendum with no DIN
  -> none; a Section 74 SCN dated before the GSTR-9 due date -> none; a Section 73 SCN after the Section
  73(10) limitation -> partial), although the catalogue has about 40 cards for such defects (missing
  DIN/RFN, unsigned SCN, premature Section 74 SCN, time-barred Section 73 SCN, no DRC-01A, no hearing,
  wrong officer, garnishee while the SCN is pending, Section 83 attachment, refund rejected without
  RFD-08); (2) on two clear date-boundary traps the model still picked the sibling card (order served
  21.07.2023 with the return in 26 days -> the on/after-01.10.2023 card; tax paid before the SCN -> the
  "paid after SCN" card); (3) "partial" was overused and the eval withheld the model's reasons for private
  notices, so the cause could not be diagnosed.
- `prompts/issue_matching_v3.md` (v1 and v2 kept; `IssueMatchingAgent.PROMPT_VERSION` = `issue_matching_v3`):
  - Every ground and issue carries `kind`: `demand` (a ground of demand or action, as before) or `defect`
    (a procedural, jurisdictional, limitation, service, signature, DIN, hearing or form defect visible in
    the document even though the department does not state it). A new Step 1b scans the document against
    a checklist: DIN/RFN missing or placeholder on a central-tax document dated on/after 08.11.2019;
    unsigned / not digitally authenticated; SCN date vs the Section 73(10)/74(10)/74A limitation for the
    FY (computed, with the extended Section 73 deadlines in the prompt); Section 74 SCN before the GSTR-9
    due date; no DRC-01A in the Rule 142(1A) mandatory window (09.10.2019 to 14.10.2020); no personal
    hearing where an adverse order is proposed; reply time below the statutory minimum; officer's rank /
    jurisdiction vs the power exercised; recovery / attachment / garnishee without an adjudicated demand;
    order silent on the reply; refund rejection without RFD-08/RFD-09/hearing. A defect ground maps to a
    card exactly like a demand ground; its paras are `"header"` or the paragraphs that reveal it; a
    placeholder in the DIN field is a missing DIN; a defect is never invented.
  - Date-boundary discipline replaces the v2 period-regime section: for every candidate card with a date,
    FY or before/after boundary the model writes in `why` the line
    `<fact>: <notice value> vs <card boundary> -> satisfied|not satisfied`; a card with a "not satisfied"
    line can never be the primary. Two worked examples: order-service date vs 01.10.2023 (Section 62(2)
    30-day vs 60-day cards) and tax paid before the SCN vs within 30 days after it (Section 73(5)/(6) vs 73(8)).
  - Status discipline: `partial` must quote the card's DECISIVE text for the absent or contradicted fact;
    all DECISIVE facts present = `full` even when amounts, DIN, hearing dates or annexures are missing and
    even when alternates exist; the stage is read from the form name / heading and counts as a DECISIVE fact.
  - `para_map`: header-only defect grounds need no entry; the demand paragraphs map to all demand issues
    as before. The JSON example shows one demand ground + one missing-DIN defect ground with the written
    date lines; the self-check list covers the kind, the boundary lines and the partial rule.
- `IssueMatchingAgent`: `MatchedIssue` gains the field `kind` (`demand` default for v1/v2 output, `defect`
  when the model says so; `isDefect()`); `cardIds` still holds only the primary; no DB change (the
  informational issue flag `defect_without_card` is set for a defect ground with status `none` and is stored
  with the other flags). Downstream (template fill, assembly, writers) is untouched: kind is additive.
- `MatchingEvalRunnerTest`: `MATCHING_EVAL_SHOW_WHY=true` prints the per-issue `why` and
  `not_this_if_checked` lines for the private set too (console, one line each per issue with notice id and
  issue number, and WRONG MATCH DETAILS); the notice text and the results JSON stay as before. The
  per-notice issue line now shows the kind (`demand:full[NDK-006] alt[NDK-007]`, `defect:none[]`). Per set:
  "Defect issues: returned=N | expected=M", the expected count read from the optional
  `$PRIVATE_SEED_DIR/defect_cards.txt` (one card id per line; absent = skipped). See `docs/PRIVATE_DATA.md`.

## (b12) Matching prompt v3.1: written checks trimmed to the chosen cards; max_tokens 12000/20000 (14-10-2026)

- Live finding on 29 private notices (prompt v3): output tokens doubled, from about 2,000 to about 5,000 per
  notice on average, because v3 made the model write a boundary line for EVERY candidate card and a
  `not_this_if_checked` entry for the alternates too. One notice hit the 8,000 max_tokens cap and its retry at
  16,000 was refused by the per-notice cost cap, so that notice got no match. Accuracy improved under v3, so
  the mechanism stays and only the volume is cut.
- `prompts/issue_matching_v3.md` edited in place as **v3.1** (heading "(v3.1)"; `IssueMatchingAgent.PROMPT_VERSION`
  = `issue_matching_v3_1`, the resource name stays `issue_matching_v3` via the new `PROMPT_FILE` constant):
  - Date-boundary lines (`<fact>: <notice value> vs <card boundary> -> satisfied|not satisfied`) are written
    ONLY for the chosen primary card and for each listed alternate (at most 2), and only for boundaries that
    exist on those cards; candidates considered and rejected get no lines. A primary still never carries a
    "not satisfied" line.
  - `not_this_if_checked` holds only the primary card's rules, one short entry per rule
    (`<rule in 12 words or fewer> - triggered|not triggered|cannot test`); no entries for alternates.
  - `why` is at most 60 words of prose plus the boundary lines; "Do not restate the notice; cite paragraph
    numbers."
  - Visible-defect guard: a defect ground is raised only when the document itself shows the defect; a header
    with a DIN, a reference number and a signed or digitally signed block has no DIN/signature defect; a
    "proper officer" defect needs the officer's designation in the document AND a card stating the monetary
    limit or jurisdiction rule it breaches.
  - Self-check items 5 to 7 and the JSON example follow the trimmed form.
- `application.yml`: `max-tokens-matching` 12000 (was 8000) and `max-tokens-matching-retry` 20000 (was 16000),
  env vars `LLM_MAX_TOKENS_MATCHING` / `LLM_MAX_TOKENS_MATCHING_RETRY`; the `AppProperties` fallbacks match.
  `NoticeCallBudget` is unchanged: its worst case prices the full max_tokens of the call, so the matching
  estimate moves automatically from Rs 7.04 to Rs 10.56 of output (Sonnet, Rs 88 per USD): 83k-token
  catalogue cold Rs 28.82 (was 25.30), warm Rs 12.02 (was 8.50); the retry at 20,000 prices Rs 17.60 of output.
- Tests: `MatchingPromptRulesTest` asserts the v3.1 wording (primary-only `not_this_if_checked`, boundary
  lines for the primary and alternates only, the DIN/signature guard, the "proper officer" guard, the 60-word
  cap on the example's `why`); `ApiUsagePricingTest`, `HardCostCapTest`, `CacheAwareCostEstimateTest` and
  `IssueMatchingAgentTest` use the new max_tokens (the warm-catalogue cap test now runs at Rs 15).

## (b13) Matching prompt v3.2: status on notice-visible facts only; client facts to confirm (14-10-2026)

- Live finding on 35 private notices (prompt v3.1): about a third of the CORRECT primary cards came back
  "partial" because the model reported a DECISIVE fact "absent from the notice" when that fact is a
  client-side fact the department's document can never state ("no consignment note issued", "recipient
  holds invoices and bank proof", "nil returns were filed", "supplier active on the portal", "interest and
  late fee paid"). The catalogue cards are being rewritten in parallel so each SUMMARY reads
  "ALLEGES: ... DECISIVE: <facts visible in the department's document> CLIENT: <facts to confirm with the
  client; 'none' if none> DEFENCE: ...".
- `prompts/issue_matching_v3.md` edited in place as **v3.2** (heading "(v3.2)"; `IssueMatchingAgent.PROMPT_VERSION`
  = `issue_matching_v3_2`; the resource name stays `issue_matching_v3`):
  - The new SUMMARY shape is explained: DECISIVE facts are what the document states or shows and alone decide
    the status; CLIENT facts are what only the taxpayer can supply, so one that is absent from the notice is
    expected to be absent.
  - Status rules: "full" and "partial" are decided ONLY on DECISIVE facts and on NOT THIS IF rules that can be
    tested from the document. CLIENT facts never affect status in either direction. A NOT THIS IF rule whose
    fact cannot be determined from the document is `cannot test` with NO effect on status (v3.1 made any
    "cannot test" at most partial); the only untestable check that still makes a match partial is a DATE
    boundary whose date the notice does not state (`-> cannot test (date not stated)`).
  - New per-issue output field `client_facts_to_confirm`: the primary card's CLIENT facts, one short line
    each in the card's words, minus any the notice already evidences; `[]` for `CLIENT: none` and for status
    none. Never a DECISIVE fact; never a CLIENT fact in `why` as a reason for the status.
  - Partial still quotes the specific DECISIVE fact absent or contradicted. Self-check gains "No issue is
    partial because of a CLIENT fact.", the no-downgrade rule for "cannot test" and a `client_facts_to_confirm`
    check. Grounds, visible defects, boundary lines for primary + alternates only and the 60-word `why` are
    unchanged from v3.1. The JSON example carries the field on both issues and a client-side rule recorded as
    "cannot test" on an issue that stays full.
- Parser (`IssueMatchingAgent`): `MatchedIssue` gains `clientFactsToConfirm` (`List<String>`, empty by default;
  the v3 / v3.1 ten-argument constructor is kept and delegates). Non-text, blank and null entries are dropped;
  a missing or non-array value reads as an empty list. New informational issue flag `partial_on_client_fact`
  when status is partial and the `why` text contains the token "CLIENT" (heuristic, never a failure, status kept).
- Partner note (`ReplyAssemblyService`, Section 13 "Internal Partner Review Note", the per-issue `<li>` that shows
  Match Status / Cards / Template / Written by): one extra line per issue, `Confirm with client: <fact 1>;
  <fact 2>; ...`, only when the list is non-empty. The surrounding code appends its values without HTML
  escaping and this line follows it. Client-facing sections are untouched. `issue_matches` rows are unchanged
  (no new column).
- `MatchingEvalRunnerTest`: with `MATCHING_EVAL_SHOW_WHY=true` the per-issue `not_this_if` console line ends
  with `| client_facts_to_confirm: <count>` (count only, never the facts); each set prints and stores
  "issues with client facts" and "partial_on_client_fact flags" (`issues_with_client_facts`,
  `partial_on_client_fact_flags` per set and per notice in the results JSON). `docs/PRIVATE_DATA.md` updated.
- Tests: `IssueMatchingAgentTest` (field present, absent, non-array; flag on a partial mentioning CLIENT, no flag on
  full / none / a DECISIVE-only partial; record defaults), `ReplyAssemblyServiceTest` (the partner-note line for
  the one issue with facts, none for the other, nothing in client-facing sections), `MatchingEvalCardScoreTest`
  (count line and set totals), `MatchingPromptRulesTest` (v3.2 wording, example).

## (b14) Matching prompt v3.3: card stage tested against the response the input document calls for (14-10-2026)

- **Live finding (35 private notices)**: the catalogue cards describe the stage of the FIRM'S RESPONSE (an APL-01
  appeal, a reply to an SCN, a REG-21 revocation application, an RFD-09 reply), while the input document is the
  DEPARTMENT'S document that the response answers. The model read a card's "APL-01 appeal stage" and wrote
  "contradicted - the document is the DRC-07 order", downgrading a correct card to partial.
- `prompts/issue_matching_v3.md` edited in place as **v3.3** (heading "(v3.3)"; `IssueMatchingAgent.PROMPT_VERSION`
  = `issue_matching_v3_3`; the resource name stays `issue_matching_v3`). Prompt-only change; no parser, output or
  database change.
  - New section "Stage of the response" right before the status rules, with an input-document to response-stage
    table: DRC-07 order -> APL-01 appeal; RFD-06 refund rejection order -> APL-01 appeal; REG-19 cancellation
    order -> REG-21 revocation application or APL-01 appeal; MOV-09 penalty order -> APL-01 appeal; REG-17 SCN ->
    REG-18 reply; RFD-08 -> RFD-09 reply; ASMT-10 -> ASMT-11 reply; DRC-01A -> Part B reply; DRC-01 / Section
    73/74/74A SCN -> reply to the SCN; DRC-22 attachment -> Rule 159(5) objection; recovery letter / DRC-13 ->
    representation.
  - Rule: a card's STAGES value and any stage word in its DECISIVE text ("appeal", "APL-01", "reply", "RFD-09",
    "REG-21", "objection", "representation") describe the RESPONSE the firm will draft and are satisfied when the
    input document is the one that response answers per the table. The stage is never "contradicted" merely
    because the input document is the order, notice or intimation itself; it is contradicted only when the input
    calls for a different response (an appeal card against a DRC-01 SCN that still awaits a reply; a reply card
    against a confirmed order). Sibling cards that differ only by response stage are picked by the table, the
    other sibling goes to `alternates`. The test is written in `why` as
    `stage: <input document> -> <response> vs card <stage> -> satisfied|not satisfied`.
  - The "Stage" bullet of the status rules now names the response stage as the DECISIVE fact tested (the input
    document's form still comes from its form name and heading). Self-check gains "Every stage test was made
    against the response stage from the table, not against the input document's own name". The JSON example
    carries a stage line.
- Tests: `MatchingPromptRulesTest` (version, section placement, table header and rows, rule wording, example and
  self-check lines; the v3.2 stage wording is asserted gone).

## (b15) Matching prompt v3.4: forced ten-item defect checklist; consequential interest / penalty with a dedicated card (15-10-2026)

- **Live findings (40 private notices on v3.3)**: (1) procedural defects were found in 3 of 12 expected cases; the
  same time-barred Section 73 notice was caught in two earlier runs and missed in this one, so the free-form defect
  scan (Step 1b) was being treated as optional. (2) The catalogue has cards whose ground is the interest or penalty
  itself ("no Section 50(3) interest on ITC availed but never utilised", "Section 50(1) interest on net cash
  liability only", "interest-only DRC-01 cannot carry penalty", "late fee only, no Section 125 penalty"); rule 4(b)
  folded consequential interest / penalty into the main issue, so those cards were never selected and the reply lost
  that defence paragraph. The firm's own drafts argue them separately in 28 of 288 cases.
- `prompts/issue_matching_v3.md` edited in place as **v3.4** (heading "(v3.4)"; `IssueMatchingAgent.PROMPT_VERSION`
  = `issue_matching_v3_4`; the resource name stays `issue_matching_v3`). No change to writers, assembly, caps, models,
  the loader or the database.
  - **Forced defect checklist.** Step 1b is now a mandatory top-level `defect_checks` object the model fills for
    every notice, ten entries in this order, each `{"result": "ok" | "defect" | "cannot test", "basis": "<15 words
    citing the header line or paragraph>"}`: `din_rfn`, `signature`, `limitation` (SCN or order date vs the Section
    73/74/74A deadline computed as in section 5, both dates written in the basis), `premature_74`, `drc01a_missing`,
    `hearing` (no hearing offered on a proposed adverse order, an order with no hearing, an order silent on a hearing
    after a reply; the refund-rejection-without-RFD-08 case sits here), `reply_time`, `officer` (only when the
    designation and the rule are both stated), `recovery_without_demand`, `non_speaking`. A check that does not apply
    to the document is `ok` with basis `not applicable: <reason>`; `cannot test` only when the text is cut off,
    unreadable or the date not stated. Rule: every `defect` result produces a ground of kind `defect` (matched to a
    card as before, status none when no card covers it) and every defect ground corresponds to a `defect` result.
    Self-check 1a: "`defect_checks` has all ten entries; the number of defect grounds equals the number of entries
    with result `defect`". The guard lines stay (a placeholder counts as a missing DIN; a DIN present and a signed
    block means ok; do not invent a defect).
  - **Consequential interest / penalty with a dedicated card.** Rule 4(b)/(c) amended: interest or penalty
    consequential to a demand stays inside the main issue UNLESS the catalogue has a card whose ground is that
    interest or penalty itself (its TITLE or ALLEGES is about Section 50 interest, Section 47 late fee, Section
    122/125 penalty, or an "interest-only" / "penalty-only" demand). Then it becomes its own ground with kind
    `consequential`, placed immediately after the demand ground it rides on, with `parent_issue_no` = that ground's
    number, matched to the card like any other ground (primary, alternates, status by DECISIVE facts, CLIENT facts).
    Never created when no such card exists. The old exceptions (interest-only notice, interest challenged on an
    independent ground) stay as demand grounds under 4(d). `para_map`: the interest / penalty paragraph maps to both
    the parent demand issue and the consequential issue. The JSON example now has three issues (demand, consequential
    with parent 1, defect) and the ten checks.
- **Parser** (`IssueMatchingAgent`): kind `consequential` (`KIND_CONSEQUENTIAL`, `MatchedIssue.isConsequential()`)
  and `MatchedIssue.parentIssueNo` (Integer, nullable; integral or numeric-string `parent_issue_no`, null otherwise);
  `MatchingResult.defectChecks` (`Map<String, DefectCheck{result, basis}>` in the model's order, empty before v3.4);
  `DEFECT_CHECK_NAMES` (the ten names). Result flags: `defect_checks_missing` (object absent, not an object, or fewer
  than ten entries) and `defect_count_mismatch` (number of defect issues differs from the number of `defect` results;
  tested only when the object has at least one entry, so a pre-v3.4 output carries only the missing flag). Both
  informational: the issues are kept. Older constructors keep working (no parent, no checks). Downstream was read:
  no code outside the agent switches on `kind` (`TemplateFillService`, `ReplyAssemblyService`, the writers and the
  pipeline read `cardIds`, `status`, `facts`, `why`, `clientFactsToConfirm` only), so a consequential issue is
  already filled, written and assembled exactly like a demand issue. Nothing new is stored in the database.
- **Eval runner** (`MatchingEvalRunnerTest`): with `MATCHING_EVAL_SHOW_WHY=true` one compact `defect_checks` line per
  notice (`din_rfn=defect, signature=ok, ...`, basis never printed, `(absent)` when none); per set "Defect checks
  (v3.4): present on N of M successful | defect results total | defect_checks_missing flags | defect_count_mismatch
  flags" (`defect_checks_present`, `defect_results_total`, `defect_checks_missing_flags`,
  `defect_count_mismatch_flags` in the results JSON, `defect_checks_present` and `defect_results` per notice). The
  "got" column shows `consequential(parent 1):...`. Scoring unchanged: a consequential issue is scored like a demand
  issue against the answer key.
- Tests: `IssueMatchingAgentTest` (consequential kind and parent, defect_checks read in order with result normalised,
  the two flags: absent, nine entries, not an object, too many / too few defect grounds, all-ok with a consequential
  issue and no flag, readers and record defaults; the pre-v3.4 fixtures now assert the `defect_checks_missing` flag),
  `MatchingPromptRulesTest` (version, the ten check names in order and their wording, not-applicable / cannot-test
  rule, the one-ground-per-defect-result rule both ways, the kept guard lines, rule 4(b)/(c)/(d) wording, Step 2 and
  `para_map` lines, self-check lines, the example's checks and consequential issue), `MatchingEvalCardScoreTest`
  (defect_checks line, set counts, got column, consequential scored like demand).

## (b16) Template version by period overlap; writer skipped for empty grounds; defect issues in Section 07 (16-10-2026)

- **Live findings (10 private notices, full pipeline on v3.4; private catalogue of 269 cards, one template each,
  `effective_from` mostly 2017-07-01, a few with a legal date window such as 2020-03-01 to 2022-02-28 for the
  COVID-limitation card)**:
  1. `TemplateFillService.selectTemplate` picked the version "in force on the period START". "FY 2017-18"
     normalises to 2017-04-01, before 2017-07-01, so no version matched, the fill returned NONE
     ("No template available for card NDK-037") and five full matches fell through to the AI writers: about
     INR 65 wasted and the per-notice budget exhausted. The same happened for a card whose window is a legal
     regime date rather than a tax period.
  2. An issue with status none and no substantive allegation (a corrigendum changing the figures of an earlier
     SCN) was sent to the new-issue writer (INR 13.5).
  3. An issue of kind `defect` matched to a procedural card rendered as an empty "Issue #3 Grounds of Defence"
     in Section 04 while Section 07 kept the "[[PARTNER: add procedural objections ...]]" placeholder.
- **Fix 1: template version by period overlap** (`TemplateFillService`). The period is normalised and its start
  clamped to 2017-07-01 (`GST_START`; a wholly pre-GST period collapses to that day). Among the versions whose
  `[effective_from, effective_to]` OVERLAPS the period, the one in force on the period END is chosen, else the one
  in force on the start, else the highest overlapping version; `warn:period_crosses_template_version` is kept and
  now fires whenever any version boundary falls inside the period (also when the period starts before the chosen
  window). When no version overlaps but the card has template rows, the version with the nearest window (smallest
  gap in days, ties to the highest version) is used with the new flag **`warn:template_window_mismatch`**; its
  details carry `card_id`, `period`, `period_start`, `period_end`, `template_id`, `template_version`,
  `template_effective_from`, `template_effective_to`, `gap_days`. When the period cannot be normalised, the latest
  version is used with the new flag **`warn:period_not_normalised`** (details: `card_id`, `period`, `template_id`,
  `template_version`); the issue still gets the template (before: only an undated template matched). The NONE /
  `no_template_available` result now exists only when the card truly has no active / provisional (/ draft)
  template row. `FilledTemplateResult` gained `flagDetails` (old 11-argument constructor kept) and `hasTemplate()`;
  `TemplateSelection` gained `flags` and `flagDetails`. The pipeline merges those details into the
  `FLAG_TEMPLATE_WINDOW_MISMATCH` / `FLAG_PERIOD_NOT_NORMALISED` check rows (`draft_flags.details`), and the
  Section 13 line of the issue explains the window or period used. Behaviour change to note: a period crossing a
  version boundary now takes the END version (e.g. FY 2020-21 against v1 to 2020-12-31 / v2 from 2021-01-01 gives
  v2, before v1), still with the crossing warn flag.
- **Fix 2: writer routing** (`V7DraftingPipelineService.lacksSubstantiveGround`). Before the new-issue writer is
  called for an issue with status none and no card, the issue must carry a substantive ground: the writer is
  skipped when `facts.amount` and `facts.legal_section` are both absent, OR the issue's why / label (facts `label`,
  `ground`, `title`, `allegation`, `issue`) contains any of "corrigendum", "hearing date", "acknowledg",
  "reminder", "no ground", "no demand". A skipped issue gets one Section 04 line
  `[[PARTNER: no catalogue card and no substantive demand identified for this ground; review the document]]`
  (also its Section 01 bullet), the flag **`info:new_issue_writer_skipped`**, a Section 13 note, "Written by=AI
  none (new-issue writer skipped: no substantive ground)" and no `ai_issue_sections` row. The skip applies only
  when a new-issue writer is configured (the Stage 1+2-only runner keeps its `[[PENDING_AI]]` markers). Verified
  and tested: a full match filled from a template (any version chosen by fix 1, including FY 2017-18 and a
  mismatched window) never reaches the partial or the new-issue writer. Partial matches still go to the partial
  writer with their template, as designed.
- **Fix 3: section routing** (`ReplyAssemblyService`). Issues of kind `defect` render their template blocks and AI
  text (template / writer sections 04 and 06) under **Section 07 Procedural Objections** as "Issue #n Procedural
  Objection", numbered 7.1, 7.2 ...; they no longer appear in Sections 04 or 06. When at least one defect issue
  exists the "[[PARTNER: add procedural objections after checklist review]]" placeholder is replaced by the
  rendered objections (the checklist options list stays in the Section 13 partner note); Section 02 adds
  "(procedural objections in Section 07)". Section 04 shows demand and consequential issues only; a consequential
  issue renders right after its parent with the heading "Issue #n (consequential to Issue #m) Grounds of Defence"
  (an orphan consequential issue stays in matcher order). Cross-references (`{{ref:ISSUE-n}}`) resolve to 7.x for
  a defect issue, so Section 01 and the Section 05 para-wise reply keep pointing at the right paragraph. Section
  13 names the kind ("Kind=defect (rendered in Section 07)", "Kind=consequential (parent Issue #m)").
  `DraftCheckService.CHECK_EVERY_ISSUE_ANSWERED` (one-line change) accepts a defect issue answered in Section 07.
  No change to prompts, caps, models, the loader or the matching agent.
- Tests: `TemplateFillServiceTest` (FY 2017-18 against a 2017-07-01 window; legal window 2020-03-01..2022-02-28
  against July 2019 - March 2020 -> overlap; no overlap -> nearest version + flag with details; unparseable period
  -> latest + flag; card without templates -> NONE; `clampToGst`), `PeriodNormalizerTest` (selection table and
  crossing test updated to the END preference; unreadable period -> latest + flag), `V7Stage3PipelineTest`
  (corrigendum / hearing-date issues skip the writer while a substantive unmatched issue is still written; the
  rule table; template present for FY 2017-18 and for a mismatched window -> no writer call, warn check with
  details; defect issues answered in Section 07 pass `CHECK_EVERY_ISSUE_ANSWERED`), `ReplyAssemblyServiceTest`
  (defect issue in Section 07 with 7.1 / 7.2, consequential heading after its parent, Section 05 references,
  AI-written defect, placeholder kept without defects, partner-note lines for the two new flags).

## (b17) Template variable extraction step; per-paragraph stance in the para-wise reply (16-10-2026)

- **Live findings (10 private notices, full pipeline on v3.4 after (b16))**:
  (A) Template variables the notice states were printed as `[[MISSING: issue.return_filing_date]]`, and a template's
  "order dated {{notice.issue_date}}" printed the covering letter's date instead of the assessment order's date. The
  matcher extracts only the generic facts (`period`, `amount`, `legal_section`) while the private templates use
  card-specific variables. (B) Section 05 printed "Denied. Please refer to ..." for EVERY paragraph, factual
  recitals and the department's own description of the proceedings included.
- **Fix A: `TemplateVariableAgent`** (new, `prompts/template_variables_v1.md`, `PROMPT_VERSION template_variables_v1`),
  called by `V7DraftingPipelineService` ONCE per notice after matching and template selection and before the fill
  (Stage 1b). Input: the notice OCR text (cut to `drafting.ocr-excerpt-chars`) and, per matched issue whose card has
  a template, the issue number, the card title and the template's declared variables that are still unknown
  (`TemplateFillService.unknownVariables`: a `client.*` / `notice.*` value the pipeline already has and the matcher's
  own `issue.period` / `issue.amount_tax` / `issue.legal_section` are excluded; `ref:` entries are never variables),
  each with a one-line hint derived from the name (`hintFor`: "date" -> dd.mm.yyyy, amount / tax / interest /
  penalty / turnover / demand / fee -> digits, "period" -> as written). Output
  `{"issues":[{"issue_no":1,"variables":{"issue.return_filing_date":"16.08.2023"}}],"not_found":["issue.xyz"]}`;
  prompt rules: copy values exactly as the document states them, never invent, leave out what the document does not
  state. Parser: unknown variable names and issues are ignored, blank values dropped, numbers rendered as digits.
  The values are merged into the issue's facts map under `TemplateFillService.factKeyForVariable` (`issue.X` /
  `facts.X` -> `X`, read back by `buildContextMap` as `issue.X`; a `notice.*` / `client.*` name is kept whole and is
  only a fallback for a value the pipeline lacks), so the fill resolves the placeholders; a value the document does
  not contain stays a `[[MISSING: ...]]` marker. The merged facts are what `issue_matches` persists. Model: the
  partial-drafting model (`LLM_MODEL_PARTIAL_DRAFTING`; agent key `template_variables`, effort
  `LLM_EFFORT_TEMPLATE_VARIABLES` default `low`, max_tokens `LLM_MAX_TOKENS_TEMPLATE_VARIABLES` default 2000,
  retry cap `LLM_MAX_TOKENS_TEMPLATE_VARIABLES_RETRY` default 4000; nothing new to price). The call goes through
  `WriterCall` like the other writers: budget pre-check against the per-notice cap, `[call] step=template_variables`
  usage line, one retry on unusable output, `LlmFatalException` passed on. Flags: **`info:variable_agent_skipped`**
  when no issue has a template with an unknown variable (no call), **`warn:variable_agent_not_called`** when the
  budget refused the call, **`warn:variable_agent_failed`** when it returned no usable output twice; the Section 13
  note lists the values read per issue and the variables the document does not state. The live runner prints a
  new per-notice line `Variables Step: INR=...` (the Writer Step line no longer includes it).
  Date semantics (`TemplateFillService.buildContextMap`): `{{notice.issue_date}}` is the date of the document being
  answered (matcher notice block / intake record); a template that needs the ORDER's date uses `{{issue.order_date}}`,
  which the agent extracts; an `issue.*` value present in the facts always wins over a `notice.*` fallback. No
  template was rewritten.
- **Fix B: `para_stance`** (matching prompt **v3.5**, `PROMPT_VERSION issue_matching_v3_5`, rule 7a, example and
  self-check 2a). For every `para_map` key the matcher returns one of `admitted` (factual recital: jurisdiction,
  dates, returns filed, notice received, the department's description of the proceedings), `denied` (an allegation
  or proposed demand), `explained` (a correct fact that needs context, e.g. payment under protest) or
  `not_applicable` (closing formalities: reply time, hearing offer, ex-parte warning). Parser:
  `Map<String,String> paraStance` on `MatchingResult` (default empty; the 10- and 11-argument constructors are kept;
  values normalised, "n/a" / "noted" read as `not_applicable`, anything else dropped). `ReplyAssemblyService`
  Section 05 prints "Para N: Admitted." / "Para N: Denied. Please refer to the reply to Issue #k at para 4.x." /
  "Para N: Explained. Please refer to ..." / "Para N: Noted." (not_applicable); a whole-paragraph key wins over a
  sub-paragraph key of the same paragraph; a writer's own para reply still prints first; when no stance is available
  (earlier output) today's wording stays.
- Tests: `TemplateVariableAgentTest` (good JSON, unknown names / issues, not_found only, neither key -> validation
  error, hints, capped payload, stub mode, live call on the partial model with effort low / max_tokens 2000 and the
  `template_variables` usage step), `V7Stage3PipelineTest` (agent called once with only the unknown variables and the
  values fill the template; skipped with the info flag when none; failure and not_found leave the markers),
  `ReplyAssemblyParaAndFactsTest` (the four stances; no stance keeps today's wording; AI para replies win;
  `stanceByPara`), `IssueMatchingAgentTest` (para_stance parsed and normalised; absent -> empty; `withIssues` /
  `withFacts`), `TemplateFillServiceTest` (issue.order_date vs notice.issue_date, `factKeyForVariable`,
  `unknownVariables`), `MatchingPromptRulesTest` (v3.5 wording, example keys), `ApiUsagePricingTest` (defaults).

## (b18) Deterministic stage correction for order-type inputs; summary wording uses the detected section (17-10-2026)

Findings from the 10-notice full-pipeline run on `99bb118`. No prompt, cap, model or loader change; no LLM call added.

- **Fix A: stage correction after matching** (`V7DraftingPipelineService.applyStageCorrection`, Stage 1a, before the
  template variable step and the template fill). On an RFD-06 refund rejection ORDER the matcher chose the reply-stage
  refund cards (NDK-225, NDK-221) although the appeal-stage siblings (NDK-224, NDK-222) exist; another run chose
  correctly. New `StageResolver` (matching package) maps the input document to the response it calls for, from the
  document type, then the first form name in the heading (first 1,500 characters; the triage label `scn_73` yields to a
  form name in the heading): DRC-01, DRC-01A, ASMT-10, ADT-01, REG-17, RFD-08, MOV-07, summons, Section 46 -> reply;
  Section 62 / ASMT-13, DRC-13, recovery letter -> representation; DRC-22 -> objection; DRC-07, RFD-06, REG-05,
  MOV-09, order-in-original, order-in-appeal -> appeal; REG-19 -> revocation (no correction); anything else -> no
  correction. A card is appeal-stage when its STAGES contain `order_appeal` or its title starts with "Appeal" /
  "APL-01", reply-stage otherwise. When the class is wrong, the candidates are the catalogue cards named in the
  primary card's NOT THIS IF rules plus the matcher's alternates, of the right class: exactly one -> it becomes the
  primary, the old primary the first alternate, the status is kept, issue flag `stage_corrected: <old> -> <new>` and
  draft flag **`info:stage_corrected`** (detail: the corrections); none or several -> card kept, issue flag
  `stage_mismatch: <input stage> vs <card stage>` and draft flag **`warn:stage_mismatch`**. Mirror swap for a reply /
  objection / representation input matched to an appeal card. One log line and one Section 13 note per issue; the
  Section 13 issue line reads "Note=stage corrected from NDK-225 to NDK-224". `CatalogueService.cardsById()` gives the
  pipeline read access to the cards' stages and NOT THIS IF rules (setter-injected; absent in tests -> no correction).
- **Fix B: the provision the draft names** (`ReplyAssemblyService.resolveNoticeSection`). The executive summary and
  notice understanding said "under Section 73" for a Section 50 interest letter and a Section 75 order (the notice
  record's header section). Order now: the matcher's reading (its notice block carries no section, so the
  `legal_section` of the main issue, the first demand issue that has one, when it names a proceeding provision:
  50, 54, 61-64, 73, 74, 74A, 75, 76, 122, 125, 129, 130, 171; a charging provision such as 16(4) falls back), then
  the notice record's section (the one the StageDetector used); never a default. When it differs from the record,
  the pipeline re-detects the stage template from it (`StageDetector.detect`), and the checks receive the same
  section and stage. A document that is not an SCN (order, letter, intimation, other notice, by `StageResolver`) is
  worded "Reply to Order Ref ... issued under Section 75", "has received order reference ... issued under", and
  "Order issued under Section 75" on the notice type line; never "Show Cause Notice".
- Tests: `StageResolverTest` (mapping table, heading over the scn label, specific type over the heading, first form
  wins, document kinds, unknown -> null), `V7StageCorrectionTest` (RFD-06 + NDK-225 with NDK-224 in NOT THIS IF ->
  swapped with flag; sibling from the alternates; no sibling -> warn only; two candidates -> warn; mirror swap;
  right stage / revocation / unknown unchanged; pipeline step flags, details and notes),
  `ReplyAssemblySectionWordingTest` (Section 50 letter and Section 75 order: wording, no "Section 73" or
  "Show Cause Notice" in 01/02/14, CHECK_PROCEEDING_CONSISTENT passes; charging-provision fallback; notice type line).

## (b19) Procedural defect checks in code; stage-sibling tie-break; issue flags visible (17-10-2026)

Why: across four live 10-notice runs on the same input the matcher found the procedural defects 2, 3, 4 and 2 times
out of the 5 expected; the same time-barred Section 73 SCN and the same short-reply DRC-01A were caught in one run and
missed in the next. These are date arithmetic and form checks, so code now does them. No prompt, cap, model, loader
or writer change; no LLM call added (the matcher's `defect_checks` stay as a second opinion).

- **`DefectChecker`** (matching package, pure, no network). Reads the matcher's notice block (issue date, FY, DIN) and
  the StageDetector / StageResolver results, falling back to regex on the text when a field is null (dates dd.mm.yyyy,
  dd-mm-yyyy, dd/mm/yyyy; "FY 2019-20" / "Financial Year 2019-20"; "Section 73(1)" / "Section 74(1)" / "Section 74A").
  Each check returns defect / ok / cannot_test with a basis of at most 20 words naming the dates used:
  - `limitation_73`: Section 73 SCN dated after the SCN deadline of its FY (2017-18 30.09.2023; 2018-19 31.01.2024;
    2019-20 31.05.2024; 2020-21 30.11.2024; 2021-22 30.09.2025; 2022-23 30.09.2026; 2023-24 30.09.2027; the deadline
    day itself is in time). Reply card NDK-105 only for FY 2017-18 / 2018-19; other FYs have no card (see below).
  - `premature_74`: Section 74 SCN dated before the GSTR-9 due date of its FY (07.02.2020 ... 31.12.2024) -> NDK-104.
  - `din`: central-tax document (header or officer designation says Central Tax / CGST / Central Goods and Services
    Tax, Act names ignored) dated on or after 08.11.2019 with no DIN line, or a blank / bracket / underscore / X /
    "not generated" value -> NDK-141 (reply), NDK-142 (appeal). "Central / State Tax" with no central designation ->
    cannot_test; a State Tax document -> not applicable.
  - `signature`: no evidence in the last 25 lines (Digitally signed, Sd/-, Signature valid, e-signed, Signed by
    <name>) AND a blank signature line / empty "Signature:" or "Name:" field, or "does not require signature" ->
    NDK-143 (reply), NDK-144 (appeal).
  - `drc01a_reply_time`: DRC-01A whose Part B / reply date (or "within N days") is fewer than
    `drafting.defects.drc01a-min-days` days away (**`DEFECT_DRC01A_MIN_DAYS`**, default 15, firm to confirm) -> NDK-148.
  Both date tables sit in `DefectDeadlines` with "[VERIFY: partner to confirm the extended dates]".
- **Merge** (`CodeDefectMerger`, `V7DraftingPipelineService.applyCodeDefects`, Stage 1a0: after matching, before the
  stage correction and template selection). A code defect the matcher also raised (a defect issue on one of the
  check's cards, or whose why names the check's `defect_checks` entry) is kept with issue flag
  **`info:defect_confirmed_by_code`**; one it missed is ADDED as a kind=defect issue (card of the response stage,
  status full; no card or a card not in the catalogue -> status none with
  "[[PARTNER: <basis>; no catalogue card for this regime]]", no writer call), why "Code check <check>: <basis>",
  notice_paras ["header"], flag **`info:defect_added_by_code`**; a check the code tested ok while the matcher raised
  it keeps the issue with **`warn:defect_disputed_by_code: <basis>`** ("not applicable" and cannot_test never dispute).
  Issues are renumbered so defects follow the demand / consequential issues (parent numbers and para_map follow).
  Section 13 notes list every check's result and each added / confirmed / disputed issue.
- **Stage-correction tie-break**: with more than one sibling of the right stage, candidates already the primary card
  of another issue of the notice are dropped; exactly one left is swapped as before; otherwise the warn stays.
- **Visibility**: issue-level flags (stage_corrected, stage_mismatch, defect_added_by_code, defect_confirmed_by_code,
  defect_disputed_by_code) are listed on the issue's Section 13 line ("Issue flags=[DEFECT_ADDED_BY_CODE:info]") and
  added to the draft's check results as `<FLAG>#<issue no>`, so the live runner's "Flags:" line reads e.g.
  `DEFECT_ADDED_BY_CODE#3:info`.
- Golden drafts: notice 07 (Central Tax SCN dated 22-10-2023 with no DIN line) now gets defect issue #2 on NDK-141;
  notices 06 / 07 / 10 list the code checks in Section 13.
- Tests: `DefectCheckerTest` (positive, negative and cannot_test case per check with the synthetic header styles;
  limitation and GSTR-9 table boundary days; configurable DRC-01A minimum; regex fallbacks; 20-word basis),
  `CodeDefectMergerTest` (add, confirm by card and by why, dispute, appeal-stage card, null / unknown card -> status
  none + partner placeholder, renumbering with parents and para_map), `V7StageCorrectionTest` (two appeal siblings,
  one already primary elsewhere -> swap to the other; both free -> warn), `V7CodeDefectPipelineTest` (full pipeline:
  variable step on the renumbered issue, template fill, Section 07 rendering, Section 13 flags, runner flag line).

## (b20) Code shortlist of catalogue cards before matching; runner wiring fix (20-10-2026)

Why: every matching call carried all 269 cards. Plain code now picks the cards a notice can need and only those
are rendered into the prompt. No model, cap or writer change; the matching prompt is v3.6.

- **`CardShortlister`** (matching package, pure). Step 1 stage filter: the card's `stages` against a notice-key table
  (`NOTICE_STAGES`, one place: scn_73 -> scn_73 + generic_scn; scn_74 -> scn_74 + generic_scn; drc_01a / asmt_10 ->
  themselves + generic_scn; appeal / DRC-07 -> order_appeal, RFD-06 + refund, REG-05 + registration, MOV-09 +
  inspection; REG-17 -> registration; REG-19 -> registration + order_appeal; refund / RFD-08 -> refund; DRC-13 /
  DRC-22 / recovery letter -> recovery; MOV-07 / Section 129 / 130 -> inspection; ADT-01 -> audit). Keys come from
  the detected notice type, the StageResolver response stage and the forms in the heading; their stages are
  united. other / unknown / a type not in the table: no stage filter. A card with stage `other` passes everything.
  Step 2 keyword filter on LABEL keywords only (sections, rules, forms, e-way bill, notifications, circulars; free
  text ignored), normalised on both sides ("Sec." / "u/s" / spacing / hyphens / leading zeros); a section or rule
  hit is exact at the keyword's sub-section level ("Section 16" hits 16(4); "Section 16(4)" does not hit 16(2)(c)).
  Step 3 siblings named in kept cards' NOT THIS IF rules (one level). Step 4 floor: fewer than 3 -> stage filter
  only (level `stage`), still fewer -> full catalogue (level `full`). Dynamic size, no cap, catalogue order.
- **Matching agent**: `matching.shortlist.enabled` (**`MATCHING_SHORTLIST`**, default true). With a shortlist the
  system prompt is the rules alone (cached) and the shortlisted cards go in the per-call user message before the
  notice; with every card shown (shortlist off, level full, widening call) the catalogue stays in the cached system
  prompt. The budget's worst-case pre-check uses that split. The catalogue hash is always the full catalogue's.
- **Widening on miss**: shortlist level not full and an issue with status none on a substantive ground (the
  new-issue writer's test) -> matching runs ONCE more on the full catalogue through the same budget and that result
  is used (**`info:shortlist_widened`**: sizes, issue numbers). A failed / refused second call keeps the first result
  with a partner note. Every shortlist adds **`info:shortlist`** {size, level, catalogue size, step counts} and one
  Section 13 line; the live runner prints "Shortlist: 23 cards (level keyword; stage 61, keyword 19, siblings 4)".
- **Runner fix**: `V7LivePipelineRunnerTest` built the pipeline by hand and never called `setCatalogueService`, so
  the stage correction (and the code-defect card lookup) never ran in live tests. It is called now.
- Dev seeds (20 cards, code only, no calls): notices 01 / 06 / 08 / 09 / 13 / 14 -> 3-4 cards (keyword); 02 / 03 /
  04 / 07 / 10-12 / 15 -> 10-18 (stage); 05 -> full (dev card stage `rfd_08` is not in the table). Two answer-key
  cards fall outside: notice 08 CARD-010 (no label keyword at all) and notice 13 CARD-004 (keyword "section 16(5)",
  the defence; the notice cites 16(4) and CARD-003 names CARD-004 by text, not id). Both depend on the matcher
  answering none (then widening fires) rather than forcing CARD-001 / CARD-003.
- Tests: `CardShortlisterTest` (stage table, normalisation, sub-section exactness, forms, notifications, siblings,
  floor levels, determinism), `V7ShortlistWideningTest` (widening with a stubbed matcher, no widening for a
  non-substantive ground, failed widening kept, stage correction active only with a CatalogueService),
  `CacheAwareCostEstimateTest` (prompt layout, rules as the cached prefix, full-catalogue hash).

## (b21) Shortlist v2: label scoring + AI title pass; stage-pool widening; dry-run tool (20-10-2026)

Why: the first live run of (b20) gave 66 to 128 cards per notice (target 15 to 40) and cost more than the full
cached catalogue. Measured causes and the fix for each are in open item 9. Matching prompt is v3.7.

- **`CardShortlister` v2** (pure). Stage pool as before, plus letter patterns for documents with no form name and
  no known type (Section 62 / ASMT-13, "recovery", Section 79 -> recovery; Section 129 / 130 / MOV-07 ->
  inspection; Section 65 / ADT-01 -> audit; REG-17 / 19, RFD-08 / 06, DRC-13 / 22), earliest match in the text wins.
  Label scoring: each label keyword weighs ln(N / df) (df = cards carrying it, cached per catalogue); a card scores
  the sum over its DISTINCT keywords found; kept = score >= `relative-cutoff` x best (0.35), topped up to
  `min-cards` (15) by score, positive scores only. Full cards = label picks + AI picks + in-pool NOT THIS IF
  siblings of the AI picks; every other named sibling goes in a closing "OTHER CARDS NAMED BY NOT THIS IF RULES"
  block by id and title. Floor: fewer than 3 full cards -> stage pool.
- **`ShortlistTitleAgent`** (new, `prompts/shortlist_titles_v1.md`): one call per notice before matching; system
  prompt = instructions + `NDK-001 | <title>` for the whole catalogue (cached prefix), user message = notice type +
  text; `LLM_MODEL_SHORTLIST` (blank: matching model), effort `LLM_EFFORT_SHORTLIST` low, max_tokens
  `LLM_MAX_TOKENS_SHORTLIST` 1500; budgeted through `WriterCall`. `MATCHING_SHORTLIST_AI` (default true). A failed
  or refused call -> labels only + **`warn:shortlist_ai_skipped`**.
- **Matcher**: shortlist render drops the KEYWORDS line (the full-catalogue render keeps it, so the hash is
  unchanged). A full match whose primary is a titles-only card is lowered to partial (issue flag
  `titles_only_primary`).
- **Widening**: one more call on the STAGE POOL only (full catalogue only when the pool is the catalogue), never when
  the shortlist already is the pool; refused by the budget -> no call, **`info:shortlist_widening_skipped`**.
- Log / runner line: "Shortlist: 27 cards (pool 128; labels 19, ai 14, siblings 6; titles-only 11)". Both live
  runners wire the title agent and price-check its model.
- **`ShortlistDryRunTest`** (skipped unless `SHORTLIST_DRY_RUN_DIR` is set; zero cost unless
  `SHORTLIST_DRY_RUN_AI=true`): per notice pool, label picks, full / titles-only counts and each answer-key card as
  full / titles-only / MISSED; summary median / p90 / max size and recall. Card JSON read by
  `PrivateSetSupport.readCards`. Prints ids and counts only.
- Tests: `CardShortlisterTest` (weights, distinct keywords, cutoff, min-cards, letter stages, union, titles-only
  block, render), `ShortlistTitleAgentTest` (prompt, parser, settings, degrade, budget refusal, fatal errors, union
  in the matcher, titles-only primary), `V7ShortlistWideningTest` (stage-pool widening, skip flag, ai-skipped flag),
  `MatchingPromptRulesTest` (v3.7), `ShortlistDryRunTest` offline run on a synthetic set.

## (c) Verification

- Offline unit suite (`mvn -o test`): **492 tests, 0 failures, 0 errors, 1 skipped** (the env-gated shortlist dry run)
  on 20-10-2026 after (b21); 460 after (b20).
- Offline unit suite (`mvn test`): **439 tests, 0 failures, 0 errors** on 17-10-2026 after (b19), `RealNoticesBatchRunnerTest`
  included (it passed); 409 after (b18) the same day. Before that: 364 tests (`RealNoticesBatchRunnerTest` excluded)
  on 16-10-2026 after (b17) (345 after (b16) the same day, 329 after prompt v3.4 on 15-10-2026, 323 after v3.3, 322 after v3.2 and 316 after v3.1 on 14-10-2026; no live API
  calls; all models and IndianKanoon mocked or served from local fixtures). The 329 include the Spring context test
  `NoticedeskApiApplicationTests`, which passed in that container (it retries the local Postgres connection for
  about five minutes before the context comes up). A plain `mvn test` no longer modifies `output/`. V23 to V28
  applied on Postgres 16.
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
9. **Shortlist size and recall** (measured on the private catalogue, 269 cards, and 308 test notices; v2 in b21):
   "any label hit" kept most cards because common keywords are in nearly every notice (APL-01 on 46 cards, DRC-07
   36, DRC-01 28, ASMT-10 25, Section 74 23, Section 73 18, GSTR-3B 17); NOT THIS IF siblings added 28 to 61 full
   cards; letters with no form name passed all 269 through the stage filter; widening fell back to all 269 (INR 46)
   and the per-notice cap refused it. Any label-only filter misses about 7% of the right cards (the notice cites the
   allegation section, e.g. 73, the card is about the defence section, e.g. 77; procedural cards such as "vague SCN",
   "no section cited", "no DIN" lack the keyword because the defect exists), hence the AI title pass. Next: run
   `ShortlistDryRunTest` (labels, free), then with `SHORTLIST_DRY_RUN_AI=true` on a few notices, and tune
   `relative-cutoff` / `min-cards` before the next live run.
10. **Provisional templates**: review the first promoted templates (`reply_templates.status='provisional'`)
   and either set them 'active' (with the card) or retire them; set `INDIANKANOON_COST_INR_PER_*` from
   the IndianKanoon plan; run `CitationCheckRunnerTest` once the token exists.

---

## Parked: RAG track

Paused while v7 is completed. Last state (commit `ac8a489`): 160 candidate issue pairs generated in
`NoticeDesk_RAG_Labelled_Set_Template.xlsx`, waiting for CA labelling; earlier details are in this
file's git history.
