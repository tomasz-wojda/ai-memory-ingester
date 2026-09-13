# Technical Specification: Worklog-Aware Memory Upgrade

Status: implementation plan awaiting `MODE: EXECUTE`.

Companion documents: [measured evidence](worklog-aware-memory-upgrade-evidence.md) and [implementation checklist](worklog-aware-memory-upgrade-checklist.md).

---

## 1. Purpose

The existing system successfully converts prompt journals into searchable conversation turns, but the KD-6999 production test showed that ticket-only retrieval returns compact historical turns rather than authoritative current state.

This upgrade adds structured worklogs as an authoritative memory layer, creates extractive ticket-state capsules, and replaces undifferentiated ticket-facet ranking with deterministic hybrid retrieval.

The upgrade does not retire `prompt.log`. The journal remains the append-only conversational recovery source. Worklog files remain the authoritative ticket sources. Materialized documents and databases remain derived indexes.

## 2. Locked decisions

1. Keep `prompt.log` as the append-only journal.
2. Keep combined user-and-assistant turns as conversational evidence.
3. Treat structured worklogs as authoritative ticket memory.
4. Add `worklogs.db`; do not mix authoritative worklog chunks into `artifacts.db`.
5. Keep raw worklog evidence in `artifacts.db`.
6. Generate one extractive ticket-state capsule per ticket.
7. Never use an AI-generated summary as the authoritative persisted state.
8. Parse a bare ticket key before FTS processing.
9. Do not use BM25 ranking when a query contains only a ticket facet.
10. Retrieve from fixed candidate pools with deterministic quotas.
11. Resolve conflicts by source authority and source order while retaining historical evidence.
12. Assemble complete documents within a byte budget.
13. Synchronize journals, worklogs, databases, datasets, and the digest through one session-start command.
14. Preserve the existing SQLite schema and encode new facets in paths and document content.
15. Perform no commit or push during implementation.

## 3. Memory layers

The memory system has four logical layers.

The journal layer is `prompt.log` and other discovered prompt journals. It records conversational events synchronously and is the reconstruction boundary.

The conversation layer is `worklog-chat/turns` indexed in `convo.db`. It records what the user asked, what the assistant answered, operational details, and historical progression.

The authoritative layer is structured worklog chunks under `worklog-chat/worklogs`, indexed in `worklogs.db`. It records ticket research, decisions, implementation plans, action logs, delivery evidence, status, blockers, and next actions.

The state layer is `worklog-chat/ticket-state`, also indexed in `worklogs.db`. It provides the current extractive ticket view used before historical evidence.

## 4. Directory layout

Add these paths beneath the workspace root:

- `worklog-chat/worklogs/<ticket>/<category>/<ordinal>-<slug>.md`
- `worklog-chat/ticket-state/<ticket>.md`
- `worklog-chat/.index/worklog-state.json`

Raw evidence companions are materialized under:

- `worklog-chat/artifacts/<ticket>/<date>/worklog-evidence-<sequence>.md`

Ticket facets use the existing normalized form, such as `kd6999`. Category and authority path segments use lowercase ASCII alphanumeric tokens joined by single hyphens.

## 5. Database and datasets

Create `worklogs.db` with two archive names:

- `worklog:state`
- `worklog:chunks`

Raw companions use `worklog:evidence` in `artifacts.db`.

Reconcile datasets to:

- `worklog-chat`: `convo.db`, `worklogs.db`, `artifacts.db`
- `worklog-chat-turns`: `convo.db`
- `worklog-chat-authoritative`: `worklogs.db`
- `worklog-chat-all`: `convo.db`, `worklogs.db`, `artifacts.db`, `dross.db`

`worklog-chat` remains active. Dross remains excluded from normal retrieval.

## 6. Worklog discovery

`06_ingest_worklogs.groovy` recursively discovers `.log` files beneath `worklog/`, including `worklog/done/`.

A structured worklog requires both a ticket identity and separator-delimited sections.

Ticket identity is resolved in this order:

1. Ticket key in the filename.
2. `TICKET:` header in the source.
3. First ticket key in the opening summary section.

Files ending in `_raw.log`, files without a ticket identity, and files without structured sections are routed as worklog evidence artifacts rather than structured worklog chunks.

Discovery excludes `.git`, `worklog-chat`, generated output roots, and test temporary directories.

## 7. Worklog parser

Create `lib/WorklogParser.groovy` with:

- `parseFile`
- `splitSections`
- `classifySection`
- `splitLargeSection`
- `extractTicket`
- `extractSourceDate`

Recognize separator-delimited uppercase headings and normalize them into these categories:

- `summary`
- `comments`
- `repositories`
- `findings`
- `solutions`
- `decisions`
- `risks`
- `actions`
- `plan`
- `checklist`
- `action-log`
- `manual-actions`
- `status`
- `delivery`
- `next-action`
- `time`
- `other`

Parsing is lossless. Every non-separator source line belongs to exactly one emitted chunk or evidence document.

Subdivide oversized sections at numbered findings, numbered options, dated action-log entries, checklist items, or paragraph boundaries. The target chunk size is 12,288 UTF-8 bytes. A logical item larger than the target remains whole unless it exceeds the engine’s maximum file size.

Each parsed chunk carries source path, source line range, source order, original heading, normalized category, ticket key, source date, content hash, and raw content.

## 8. Worklog documents

Create `lib/WorklogDocument.groovy` with:

- `renderChunk`
- `renderEvidence`
- `hash`
- `categorySlug`
- `authorityOf`
- `currentStateOf`

Chunk front matter contains, in fixed order:

- `ticket`
- `source`
- `source_lines`
- `source_order`
- `source_modified`
- `category`
- `heading`
- `authority`
- `state`
- `content_hash`

Authority values are `state`, `authoritative`, `historical`, and `evidence`.

State values are `current`, `historical`, and `undetermined`.

The body preserves the original heading and source text.

## 9. Current-state resolution

Create `lib/TicketStateCapsule.groovy` with:

- `assemble`
- `selectLatestSection`
- `selectLockedDecisions`
- `selectBlockers`
- `selectNextActions`
- `render`

The capsule is extractive. It may copy and organize source text but may not paraphrase or infer facts.

The capsule contains:

1. Ticket summary.
2. Locked decisions.
3. Latest delivery reconciliation.
4. Latest status.
5. Current blockers.
6. Latest next actions.
7. Referenced repositories, branches, pull requests, and builds found in those selected sections.
8. Source paths and line ranges for every extract.

The latest eligible section by source order wins within each category. An explicit delivery reconciliation has priority over an earlier status section. An explicit current status has priority over action-log observations. A later worklog source modification supersedes an earlier copy of the same ticket worklog.

Earlier sections remain indexed as historical chunks.

## 10. Emission and stale replacement

Create `lib/WorklogEmitter.groovy` with:

- `loadState`
- `stageSource`
- `replaceSource`
- `removeStaleOutputs`
- `saveState`

`worklog-chat/.index/worklog-state.json` records each source path, source hash, source size, modification time, emitted chunk paths, emitted hashes, evidence paths, capsule path, and capsule hash.

An unchanged source is skipped.

A changed source is fully reparsed. New outputs are written to a temporary staging directory. Previously emitted paths absent from the new result are deleted. Staged outputs replace prior outputs only after parsing and rendering complete successfully.

If several worklogs describe the same ticket, capsule assembly considers all current chunks for that ticket and records every contributing source.

## 11. Worklog ingestion script

Create `06_ingest_worklogs.groovy`.

Supported options:

- `--all`
- `--source`
- `--changed`
- `--workspace-root`
- `--dry-run`
- `--sync-db`

The script reports discovered, unchanged, changed, structured, evidence, emitted, replaced, deleted, and failed counts.

Dry-run mode reports the identical plan without writing files, state, databases, or datasets.

With `--sync-db`, changed authoritative outputs replace `worklog:state` and `worklog:chunks` in `worklogs.db`. Changed evidence outputs replace `worklog:evidence` in `artifacts.db`.

## 12. Ticket normalization

Update `03_query_memory.groovy` so exact ticket-key tokens matching the established ticket pattern are removed from the raw FTS expression and converted to normalized path facets.

The forms `KD-6999`, `kd-6999`, and `kd6999` resolve to the same `kd6999` facet.

A bare ticket query invokes hybrid ticket retrieval. A ticket plus semantic terms uses the ticket facet as a filter and the remaining terms as the semantic expression.

The original hyphenated ticket must never reach FTS5 unquoted.

## 13. Ticket commands

Add:

- `ticket <key> [terms]`
- `context --ticket <key> [terms]`
- `:ticket <key> [terms]`

`ticket` returns a ranked candidate inventory.

`context --ticket` returns complete selected documents within the context budget.

`:ticket` provides the same candidate inventory in the interactive loop.

All commands support existing dataset, database, JSON, limit, date, mode, impact, and budget flags where meaningful.

## 14. Hybrid retrieval

Create `lib/TicketRetrieval.groovy` with:

- `retrieve`
- `stateCandidates`
- `authoritativeCandidates`
- `recentTurnCandidates`
- `impactCandidates`
- `semanticCandidates`
- `mergeCandidates`
- `assembleContext`

For a limit of twenty, allocate:

1. One state capsule.
2. Up to four authoritative chunks, ordered by category priority and source order.
3. Up to five recent turns, ordered by numeric turn sequence.
4. Up to five high-impact turns, ordered by numeric turn sequence.
5. Up to five semantic matches across worklogs, turns, and artifacts.

Category priority for authoritative chunks is:

1. `status`
2. `next-action`
3. `delivery`
4. `decisions`
5. `summary`
6. `findings`
7. Remaining categories

When no semantic terms remain after ticket normalization, semantic capacity is filled by additional authoritative chunks and then additional recent turns. BM25 is not invoked for a facet-only query.

Duplicate suppression uses database name, archive, and file path. A state capsule and its contributing source chunks are not duplicates because they serve different retrieval purposes.

## 15. Conflict handling

Candidate output records authority and current-state classification.

When facts conflict:

1. Current capsule text wins.
2. Current authoritative chunks win over historical worklog chunks.
3. Worklog chunks win over conversation turns.
4. Newer turn sequence wins only between turns of equal authority.
5. Evidence artifacts support claims but do not define current status.

Historical results are not deleted. They are labelled historical and remain available for chronology and root-cause questions.

## 16. Context assembly

The default ticket context budget is 22,000 UTF-8 bytes.

Assembly order is:

1. State capsule.
2. Current status.
3. Next action.
4. Delivery evidence.
5. Locked decisions.
6. Recent turns.
7. High-impact turns.
8. Semantic matches.

Documents are included whole. A document that would exceed the remaining budget is skipped and the assembler continues to smaller candidates.

Output reports candidate source, authority, state, bytes, and source path.

## 17. Preview generation

Update preview handling in `03_query_memory.groovy`.

For facet-only results, strip YAML front matter and show the first meaningful body paragraph, user text, assistant text, or section extract.

For semantic results, find the earliest body line containing a normalized query token and return a context window around it.

Previews target 320 readable characters. They must not consist solely of front matter. JSON output retains the `snippet` field but fills it with the new body-aware preview.

## 18. Unified session sweep

Create `07_sweep_memory.groovy` and register `sweep-memory`.

The command performs:

1. Incremental chatlog transformation.
2. Changed-worklog transformation.
3. Synchronization of every changed database tier.
4. Dataset reconciliation.
5. Final resume digest generation.

No-change runs do not rebuild databases.

The sweep writes a final reconciliation report with source changes, emitted files, deleted stale files, database counts, dataset membership, digest bytes, and elapsed time.

The workspace rule invokes this single command at session start.

## 19. Resume digest

Update `lib/ResumeDigest.groovy`.

Recent turns remain the base.

When one ticket appears in at least half of the recent turns, prepend that ticket’s capsule, current status, and next action before filling the remaining budget with recent and relevant documents.

If no ticket dominates, preserve the existing recent-turn behavior.

The digest records the contributing ticket, source paths, generation time, item count, and consumed budget.

## 20. Runner and package changes

Update `run.groovy` and `PrecompiledRunner.groovy` with:

- `worklogs`
- `ingest-worklogs`
- `ticket`
- `sweep-memory`

Register the five new library modules in dependency order.

Update `build-precompiled.sh` to compile scripts 06 and 07. Rebuild the application jar and verify interpreted and precompiled command parity.

## 21. Rule and skill changes

Update `.cursor/rules/worklog-chat-memory.mdc` so the session-start obligation invokes `sweep-memory`.

Update `skills/worklog-chat-memory/SKILL.md` with:

- Bare-ticket normalization.
- Direct SQLite recipes for `worklogs.db`.
- Hybrid ticket retrieval.
- Source-authority rules.
- Worklog facet discovery.
- Full-document context retrieval.

The skill remains path-relative and must pass all seven vault checks.

## 22. Tests

Extend `tests/TestFixtureGenerator.groovy` with:

- Structured worklogs containing every recognized category.
- Multiple revisions of one worklog.
- Superseded and current status.
- Locked and replaced decisions.
- Oversized findings and action logs.
- Raw evidence companions.
- Multiple worklogs for one ticket.
- Bare hyphenated ticket queries.
- Turns containing stale facts contradicted by later worklog state.

Create `tests/test_worklog_suite.groovy`.

Cover discovery, parsing, lossless line accounting, subdivision, state classification, extractive capsules, source provenance, unchanged skips, stale deletion, atomic replacement, evidence routing, database synchronization, dataset scope, ticket normalization, pool quotas, conflict precedence, body-aware previews, context budgets, session-sweep idempotency, and digest enrichment.

The existing chatlog and query suites remain unchanged and passing.

## 23. Production acceptance

Backfill all structured workspace worklogs.

For KD-6999, a twenty-item ticket retrieval must expose:

1. Ticket purpose.
2. Selected Option A architecture.
3. Physical collection names.
4. Reader-only requirement.
5. UAT verdict.
6. Current image 24-9.
7. Current PR 227.
8. PROD not-deployed status.
9. Develop-to-master promotion blocker.
10. Next deployment actions.

No returned preview may contain only front matter.

The context command must stay within 22,000 bytes and identify authoritative and historical sources.

The stale image 24-8 and PR 224 turns may appear as history but must not be presented as current state.

## 24. Regression and performance

Run the existing 75 chatlog tests and 63 query tests unchanged.

Run the new worklog suite and every new interpreted and precompiled command.

Verify:

- No-change session sweep emits and ingests nothing.
- Changed worklogs remove stale chunks.
- Database counts reconcile with materialized files.
- Dataset membership is correct.
- Rebuild order is deterministic.
- Direct SQLite recipes return authoritative rows.
- Precompiled ticket retrieval remains below one second minimum-of-three.
- No commit or push occurs.
