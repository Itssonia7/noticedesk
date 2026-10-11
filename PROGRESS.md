# Implementation Progress: v7 Drafting Pipeline

Branch: `feature/v7-drafting` (not merged; no PR). Production still runs the old pipeline
(`DRAFTING_PIPELINE=rag` by default), so nothing in v7 is live yet.

Last updated: 11-10-2026

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

## (c) Verification

- Offline unit suite: **168 tests, 0 failures, 1 error** (no live API calls; all models and IndianKanoon mocked). The error is the legacy `RealNoticesBatchRunnerTest`, which needs local real-notice files and failed before these changes too.
- Golden drafts compared on every run, built from the testbench notice JSON:
  - `notice_07`: capital goods ITC, Gujarat, Rs 4,00,000, Section 73.
  - `notice_10`: anti-profiteering, Delhi, Rs 15,00,000, Section 171.
- Privacy hook tests: 16 passed, 0 failed.

## (d) Open items

1. **First live trial run**: set `LLM_MODEL_PARTIAL_DRAFTING`, `LLM_MODEL_NEW_ISSUE`,
   `LLM_MODEL_HIGH_STAKES`, `INDIANKANOON_API_TOKEN`; draft a few notices with the real models.
2. **CA-approved content**: real issue cards, reply templates and stage templates (prayer, filing
   checklist). All current templates are marked "[DEV - not CA reviewed]". Approved content goes in
   `$PRIVATE_SEED_DIR`, not the repo (see `docs/PRIVATE_DATA.md`).
3. **Stage templates for other proceedings**: `asmt_10`, `scn_73`, and DRAFT `scn_74`, `scn_74a`,
   `anti_profiteering` exist (dev text, partner to review). Other stages get a placeholder + block flag.
4. **Re-run the Stage 1 matching benchmark** with the live model (now reports dummy and private sets
   separately).
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
