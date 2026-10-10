# Private data: nothing real goes into the repo

This repository is **public**. Real client data — names, GSTINs, PANs, notices, replies,
the firm's real issue cards and reply templates — must **never** be committed, not even
temporarily (git history keeps it forever, and the GitHub mirror is public).

Only synthetic data lives here: the testbench notices, unit-test fixtures and dev seeds,
whose fake IDs are listed in `.githooks/allowed_ids.txt`.

## Where real data lives

Keep real data in a folder **outside** the repository and point `PRIVATE_SEED_DIR` at it:

```
noticedesk-private/                 # anywhere outside the repo, e.g. ~/noticedesk-private
├── cards/*.json                    # issue_cards rows      (card_id, version, title, summary, ...)
├── templates/*.json                # reply_templates rows  (template_id, version, card_id, blocks, ...)
├── stage_templates/*.json          # stage_templates rows  (stage, version, sections, ...)
└── notices/*.json                  # real notices for the matching eval (same shape as testbench notices)
```

Each JSON file holds one object or an array of objects whose keys are the table's column names.

```bash
export PRIVATE_SEED_DIR=$HOME/noticedesk-private
# load the private catalogue into the database (upsert; safe to re-run)
SPRING_PROFILES_ACTIVE=private-seed mvn -f noticedesk-java/pom.xml spring-boot:run
```

- `PRIVATE_SEED_DIR` defaults to empty (`application.yml`). Unset → the loader logs
  `private seed skipped` and does nothing.
- `PrivateSeedLoader` runs only with the `private-seed` profile. It validates every file before
  writing, and logs counts only — never file names or contents.
- `MatchingEvalRunnerTest` (opt-in, `ENABLE_E2E_TESTS=true`) also evaluates
  `$PRIVATE_SEED_DIR/notices` and reports the private set separately from the dummy testbench.
  The results JSON (`testbench/results/`, git-ignored) stores notice IDs and metrics only.

As a second line of defence `.gitignore` ignores `private/`, `noticedesk-private/`, `*.real.json`,
`*.real.sql`, `*.real.docx` and `*.real.pdf`.

## Commit-time check (enable once per clone)

```bash
git config core.hooksPath .githooks
```

`.githooks/pre-commit` runs `scripts/check_sensitive_data.sh --staged`. The same script runs in
CI (`.github/workflows/sensitive-data-check.yml`) on every push and pull request, on each new
commit. It looks at **added lines only** and rejects:

| What | Rule |
|------|------|
| GSTIN | `[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]` not in `.githooks/allowed_ids.txt` |
| PAN | `[A-Z]{5}[0-9]{4}[A-Z]` not in the allow-list (a PAN inside an allow-listed GSTIN is fine; GSTINs are checked first) |
| Documents | a newly added / renamed / copied `.pdf`, `.docx` or `.xlsx` not in `.githooks/allowed_binaries.txt` |

Findings show file, line number and a masked value, e.g. `notes.txt:12: possible GSTIN 27IIIJJ****I1Z9`.
The offending line is never printed.

If the check fires:

1. Real data? Remove it from the change and keep it under `$PRIVATE_SEED_DIR`.
2. A new **fake** ID for a test? Prefer one already in the allow-list. Otherwise add it to
   `.githooks/allowed_ids.txt` in the same commit — reviewers check that it is synthetic
   (e.g. repeated letters, `1234`-style digits).
3. A synthetic document that must be committed? Add its exact path to `.githooks/allowed_binaries.txt`.

Do not bypass the hook with `--no-verify`; CI runs the same check and will fail the push.

Test the check itself with `bash scripts/test_check_sensitive_data.sh`.
