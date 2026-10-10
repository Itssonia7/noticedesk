# Implementation Progress: v7 Drafting Pipeline

Branch: `feature/v7-drafting` (not merged; no PR). Production still runs the old pipeline
(`DRAFTING_PIPELINE=rag` by default), so nothing in v7 is live yet.

Last updated: 10-10-2026

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

## (c) Verification

- Offline unit suite: **72 tests, 0 failures** (no live API calls; all models and IndianKanoon mocked).
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
3. **Stage templates for other proceedings**: only `asmt_10` and `scn_73` exist. Anti-profiteering
   (Section 171) and others fall back to `scn_73` and are blocked by the consistency check.
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
