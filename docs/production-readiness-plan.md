# Production Readiness Plan

Status: proposed, not implemented
Created: 2026-10-12
Scope: `ai-memory-ingester`, with follow-up edits in `ai-worklog-framework` and
`ai-vault` where they depend on the data location or the writer contract

## Assessment

`ai-memory-ingester` is reliable as a single-user local tool and not yet ready
to hand to other users or run unattended.

Strengths verified on 2026-10-12:

- `journal.db` is append-only: triggers `journal_observations_no_update` and
  `journal_observations_no_delete` reject edits, writes use WAL,
  `synchronous=FULL`, `BEGIN IMMEDIATE`, and a 30-second busy timeout, and
  idempotency keys and integrity runs are recorded.
- The live journal holds 24,061 observations (cursor 4,564, claude 119,
  historical 19,378) with `journal_meta.schema_version = 1`.
- In a clean clone six suites pass: chatlog 100, journal 25, query (all),
  reconciliation 32, retrieval 16, worklog 42. The shadow validation suite
  passes 43 of 44 inside the workspace layout and covers 16 concurrent writers.
- No SQL is built by string interpolation, archives are read as streams, and
  the external 7-Zip call uses an argument vector without a shell.
- The precompiled launcher starts in about 0.3 seconds.

## Findings

| # | Finding | Risk |
| --- | --- | --- |
| F1 | Conversation text is journaled without redaction; tokens, passwords, or personal data pasted into a chat become permanent in an append-only store | High |
| F2 | No backup, export, or restore; the only copy of the journal and derived databases (about 260 MB) is `data/` | High |
| F3 | `data/` defaults to a folder inside the git clone, so `git clean -xfd` or a re-clone deletes all memory | High |
| F4 | `dist/ai-memory-ingester.jar` and six vendor JARs (about 17 MB) are committed; `build-precompiled.sh` copies dependencies from the local Grape cache, so a fresh machine cannot rebuild; nothing checks the JAR against the sources | Medium |
| F5 | No README, and `.gitignore` excludes `*.md` outside `docs/`, so one cannot be committed; `help` prints a single usage line and lists none of the commands | Medium |
| F6 | No CI and no test runner; the shadow suite reads `../../repos/ai-worklog-framework/shared/journal-writer-contract.json`; two checks depend on the user's real `prompt.log` (the live-corpus check and the `context faster than prompt.log parse` timing check); the query suite writes into the tracked `test-data/` | Medium |
| F7 | The 7-Zip fallback is hard-coded to `C:\Program Files\7-Zip\7z.exe` and ignores its exit status; the launcher exits without a message when `groovy` is missing | Low |
| F8 | `master` is one commit ahead of origin (`ded0c9f`) | Low |
| F9 | Bulk ingestion sets `synchronous=OFF`; `memory.db` was last written on 2026-09-18; the writer contract is duplicated in `ai-vault` and `ai-worklog-framework` | Low |

## Phase 0 — Immediate

1. Push `ded0c9f` so other machines run the current build.
2. Take a manual backup before any later phase touches storage:
   `sqlite3 data/journal.db ".backup data/journal-<date>.db"` and copy it outside
   the repository.

## Phase 1 — Secrets and Personal Data (F1)

1. Add `lib/Redactor.groovy`, applied in `JournalEvent` before an observation is
   built, so every writer (`record-event`, chatlog, transcript adapters,
   reconciliation) redacts the same way.
2. Load patterns from a shared rules file. Reuse the framework's
   `shared/redaction-patterns.json` format (sensitive key names and value
   patterns such as `Bearer …`, `ghp_…`, `NRAK-…`, `AKIA…`, `token=…`); keep a
   copy in this repository and add a drift test against the framework copy.
3. Replace each match with `***REDACTED***` and record the count and pattern
   names, not the values, in the observation metadata, so redaction is auditable.
4. Decide, with the data owner and WBD Privacy Legal for personal data, how to
   handle what is already stored. The append-only design means existing rows
   cannot be edited; the options are a supervised one-time rebuild into a new
   journal with redaction applied (keeping the old file offline), or a redaction
   overlay applied at read and projection time. Document the decision here
   before implementing either.
5. Tests: each pattern redacted in `user_text`, `assistant_text`, and
   `raw_payload`; idempotency keys stay stable for already-redacted input;
   non-secret text unchanged.

## Phase 2 — Data Location and Backup (F2, F3, F9)

1. Default the data directory to `<workspace>/.ai-worklog/memory/` when a
   workspace root resolves (`MEMORY_WORKSPACE_ROOT` or discovery), keeping
   `MEMORY_DATA_DIR` as the override and `data/` only as a legacy fallback.
2. Add `migrate-data`: verify `PRAGMA integrity_check`, checkpoint WAL, copy all
   databases and `datasets.json` with the SQLite online backup API, verify row
   counts and the latest sequence, and leave the legacy `data/` untouched until
   the user removes it.
3. Add `backup` (online backup to a timestamped file, then integrity check of
   the copy) and `restore` (into an empty data directory only, refusing to
   overwrite). Document a daily backup.
4. Update the dependents of the old path together:
   `ai-worklog-framework/shared/journal-validation.json`,
   `ai-worklog-framework/.../core/JournalValidation.groovy`,
   the preflight message in `PreflightCommands.groovy`, and
   `ai-vault/skills/jira-worklog-processor/SKILL.md`. Accept both locations
   during the transition.
5. Confirm every derived database can be rebuilt from the journal and document
   the rebuild command; only then keep `synchronous=OFF` for bulk ingestion.
   Remove or rebuild the stale `memory.db`.

## Phase 3 — Build and Packaging (F4, F7)

1. Replace `build-precompiled.sh` with a Gradle build that resolves
   `sqlite-jdbc`, `slf4j`, `pdfbox`, `fontbox`, and `commons-logging` from Maven
   Central and produces `build/libs/ai-memory-ingester.jar` plus a classpath
   file, matching the `ai-worklog-framework` launcher approach.
2. Stop committing `dist/` and `vendor/`; remove them from git and add them to
   `.gitignore`.
3. Make the launcher compare the JAR against the newest source file and either
   rebuild or print the exact build command, instead of running a stale JAR.
4. Resolve symlinks in the launcher, report a missing `java` or `groovy` with a
   clear message, and support `JAVA_HOME`.
5. Make the 7-Zip path configurable (`SEVEN_ZIP` environment variable, then
   `7z` or `7zz` on `PATH`, then the Windows default), check its exit status, and
   report failures instead of continuing.

## Phase 4 — Documentation and CLI (F5)

1. Change `.gitignore` so `README.md` can be committed (`!/README.md`).
2. Add `README.md`: purpose, requirements, build, data location, backup and
   restore, the writer contract, every command with one example, and how
   `ai-worklog` and `ai-vault` call this tool.
3. Make `help` list all commands with one-line descriptions, and `help <command>`
   show its options.
4. Keep the existing specifications and checklists in `docs/` as design records.

## Phase 5 — Tests and CI (F6)

1. Add a fixture copy of `journal-writer-contract.json` under `test-data/` and
   load it when the sibling repository is absent; keep a separate opt-in check
   that compares it with the framework copy.
2. Make the live-corpus and timing checks opt-in
   (`AI_MEMORY_LIVE_TESTS=1`), and compare the timing check against a generated
   fixture of realistic size by default.
3. Run the query suite against a temporary copy of `test-data/` so tracked files
   never change.
4. Add one runner (`tests/run-all.sh` or a Gradle `test` task) that runs all
   seven suites and fails on any failure.
5. Add CI on macOS and Linux that builds, runs the runner, and checks that the
   build output is not committed.

## Acceptance

- A fresh clone on a new machine builds, passes all suites without network
  access to the user's workspace, and runs `help` with the full command list.
- `record-event` stores a payload containing a sample token as `***REDACTED***`
  and reports the redaction count.
- Memory lives outside the repository by default; `backup` and `restore`
  round-trip a journal with identical row counts and latest sequence.
- `ai-worklog preflight` and the ai-vault skills find the journal at the new
  location.
- No build output or third-party JARs are tracked in git.

## Order and Effort

| Phase | Depends on | Size |
| --- | --- | --- |
| 0 | — | Minutes |
| 1 | 0 (backup first) | Medium, plus the data-owner decision on existing rows |
| 2 | 0 | Medium; coordinated edits in two other repositories |
| 3 | — | Medium |
| 4 | 3 (document the final build) | Small |
| 5 | 3 | Medium |

---
Created using Anthropic Claude. Keep this on internal versions until a human
has reviewed and verified the content.
