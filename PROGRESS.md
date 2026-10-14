# Implementation Progress: v7 Drafting Pipeline

Branch: `feature/v7-drafting` (not merged; no PR). Production still runs the old pipeline
(`DRAFTING_PIPELINE=rag` by default), so nothing in v7 is live yet.

Last updated: 14-10-2026 (matching prompt v3.2: status decided on notice-visible facts only; client facts become a confirm-with-client list in the partner note)

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

## (c) Verification

- Offline unit suite (`mvn test`): **322 tests, 0 failures, 0 errors** on 14-10-2026 after prompt v3.2 (316 after
  v3.1 the same day; no live API
  calls; all models and IndianKanoon mocked or served from local fixtures), plus the Spring context test
  `NoticedeskApiApplicationTests`, which needs the local Postgres (pgvector) and was not run in that
  container. A plain `mvn test` no longer modifies `output/`. V23 to V28 applied on Postgres 16.
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
9. **Provisional templates**: review the first promoted templates (`reply_templates.status='provisional'`)
   and either set them 'active' (with the card) or retire them; set `INDIANKANOON_COST_INR_PER_*` from
   the IndianKanoon plan; run `CitationCheckRunnerTest` once the token exists.

---

## Parked: RAG track

Paused while v7 is completed. Last state (commit `ac8a489`): 160 candidate issue pairs generated in
`NoticeDesk_RAG_Labelled_Set_Template.xlsx`, waiting for CA labelling; earlier details are in this
file's git history.
