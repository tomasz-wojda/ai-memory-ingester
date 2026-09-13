# Technical Specification: Replacing `prompt.log` with the `worklog-chat` Memory

Status: Phase 1 implemented; fresh main-conversation rule expansion remains a manual acceptance check.
Supersedes: [`prompt.log_ingestion.md`](prompt.log_ingestion.md) and [`prompt.log_ingestion_v2_addition.txt`](prompt.log_ingestion_v2_addition.txt).
Companion documents: [implementation checklist](prompt-log-replacement-checklist.md), [measured evidence](prompt-log-replacement-evidence.md).

---

## 1. Purpose

A workspace rule requires every prompt and response to be appended to a flat `prompt.log`, and requires that file to be read at session start. In practice the file is consumed as a tail read of roughly the last 250 to 400 lines.

This specification defines how that log is converted into a queryable memory built on the existing SQLite FTS5 engine, and how retrieval is delivered to an IDE agent. Every design choice here is backed by a measurement recorded in the [evidence document](prompt-log-replacement-evidence.md); this specification states decisions rather than re-deriving them.

The predecessor specification assumed `prompt.log` was a uniform stream of `[ISO] USER REQUEST:` and `[ISO] AGENT RESPONSE (MODE: ...)` blocks. It is not. The real file contains six interleaved dialects across twenty timestamp formats, is not chronologically ordered, and carries a documented data-loss gap. A parser written to the predecessor assumption would silently discard most of the corpus.

## 2. Problem statement

`prompt.log` performs five distinct jobs, and they must be assessed separately because the replacement covers only some of them.

| Function | Served today by | Replacement outcome |
| :--- | :--- | :--- |
| Resume context | Tail read of the last 250-400 lines | Replaced and improved by a curated, budget-bounded read |
| Semantic recall | Nothing; nobody greps 35,000 mixed-dialect lines | New capability from BM25 ranking over facet-scoped turns |
| Record of work performed | `FILES CHANGED`, `REMOTE CHANGES`, `WRITE GATE` sections | Captured as structured detail sections; the journal itself is retained |
| Human readability | A plain text file openable in the IDE | Preserved only because turns are materialised as files, not by the database |
| Durability | Trivially archived by tarring the file | Preserved by the filesystem being authoritative; databases are rebuildable |

The tail read is both wasteful and fragile. It spends roughly 22 KB to deliver 13 turns, and because the largest single block in the log is 471,718 bytes spanning 3,482 lines, a tail read landing inside such a block returns artifact text and no conversation at all.

The database is not justified by speed. At approximately 2,000 turns and under a megabyte of text, a plain text scan is already instantaneous. The database earns its place through three things a flat file cannot provide: BM25 relevance ranking, stable turn identity for deduplication, and the ability to fill a fixed context budget with the best available material.

## 3. Locked decisions

1. One row represents one turn, being a user query together with its assistant response.
2. Retrieval facets are encoded as compound single tokens inside `file_path`.
3. Turn identity is a hash of normalised turn text; ingestion is idempotent and re-runnable.
4. The filesystem is the source of truth. Databases are derived, rebuildable indexes.
5. Three databases: `convo.db` for turns, `artifacts.db` for large bodies and free-form dumps, `dross.db` for non-pertinent residue.
6. `artifacts.db` is a member of the default searchable dataset. `dross.db` is not.
7. The spill threshold is 8192 bytes.
8. Recency retrieval is curated within a byte budget, not a blind tail.
9. Agent reads go through `sqlite3` directly. Groovy is reserved for ingestion.
10. `prompt.log` remains the permanent append-only journal. Dual-write is the end state, not a transitional measure.
11. Every path persisted into a database or written into a governed skill is relative.
12. `prompt.log` is the write-ahead journal of the memory system. Turn files and databases are derived from it incrementally.
13. The per-turn obligation is a journal append and nothing else. Database ingestion is batched, never per-turn, because a per-turn database write would cost roughly two seconds and be abandoned.
14. Guaranteed delivery of resume context is achieved by an always-applied rule referencing a materialised digest, on the principle that injected context cannot be skipped whereas a requested action can.
15. Phase 1 covers backfill, the incremental sweep, the digest, the always-applied rule, and retrieval. Live per-turn file emission and the optional enforcement hook are Phase 2.
16. The numeric suffix in each canonical memory filename is the authoritative recency key. Deterministic sequence-aligned database identifiers are a secondary invariant, never the source of truth.

Code style, stated to remove ambiguity at implementation time: follow the existing repository convention exhibited by `lib/MemoryEngine.groovy` and `lib/Config.groovy`, namely a file-level documentation block and a documentation block per method, with no inline commentary.

## 4. Architecture

Ingestion is a two-stage pipeline. The first stage parses the source logs and emits one Markdown file per turn at a facet-structured path. The second stage loads those files into SQLite using the engine's existing directory-ingestion path, which stores each entry's path relative to the ingest root.

This ordering is what makes the filesystem authoritative. Turn files are readable, greppable, diffable, version-controllable, and directly referenceable by an IDE agent without invoking any toolchain. The databases hold only what files cannot: an inverted index and a relevance ranking. Losing a database is therefore not data loss, because it can be rebuilt from the files without reparsing the source logs.

A second consequence is a reduction in new code. Because loading reuses the existing directory ingest, no new database writer is required in Phase 1 and `lib/MemoryEngine.groovy` is left entirely untouched.

Directory loading is deterministic. General directory entries are ordered by relative path, while a directory consisting of canonical memory documents is ordered by numeric sequence and then relative path before insertion. Explicit sequence ordering remains mandatory at query time, so correctness survives any database created before this ordering rule or by another compatible writer.

## 5. Directory layout

The output root is `worklog-chat/` immediately beneath the workspace root. The workspace root is resolved by the precedence in section 12 and is never written as an absolute literal into any tracked file.

| Subtree | Path pattern |
| :--- | :--- |
| Turns | `worklog-chat/turns/<ticket>/<date>/<mode>/<impact>/turn-<seq>.md` |
| Artifacts | `worklog-chat/artifacts/<ticket>/<date>/artifact-<seq>.md` |
| Dross | `worklog-chat/dross/<sourceSlug>/turn-<seq>.md` |
| Hash index | `worklog-chat/.index/turn-hashes.tsv` |
| Sweep state | `worklog-chat/.index/ingest-state.json` |
| Resume digest | `worklog-chat/RESUME.md` |

Each subtree is ingested into its own database, with the tier as the archive name: `worklog-chat/turns` into `convo.db` as `chat:turns`, `worklog-chat/artifacts` into `artifacts.db` as `chat:artifacts`, and `worklog-chat/dross` into `dross.db` as `chat:dross`. Because the ingest root is the subtree, the stored `file_path` begins at the ticket facet, for example `cwp11649/d20260811/execute/impact-high/turn-000042.md`.

This yields one archive per tier rather than one archive per ticket. The trade-off is accepted deliberately: ticket scoping is served by the verified `file_path` facet, so per-ticket archives are redundant. The cost is that the `archs` listing is no longer a per-ticket inventory; per-ticket listing is available through `:files` with a path pattern.

## 6. Facet scheme

Only three columns are indexed for full-text search: `file_path`, `file_name`, and `content`. There is no column for role, mode, timestamp, ticket, or session. Facets are therefore encoded into `file_path`, which is itself a full-text column split on every non-alphanumeric character.

Naive splitting was tested and rejected. A path segment of `2026-08-11` produces the tokens `2026`, `08`, and `11`; `2026` is shared by every document and useless as a facet, while `11` is simultaneously a month, a day, and a possible ticket number, so a ticket `KD-11` collides with the eleventh of the month. Compound tokens eliminate the ambiguity and were verified to do so.

| Facet | Form | Examples | Absent value |
| :--- | :--- | :--- | :--- |
| Ticket | Key lowercased, separator removed | `cwp11649`, `kd6607` | `noticket` |
| Date | `d` followed by `yyyyMMdd` | `d20260811` | `dunknown` |
| Mode | Lowercase mode name | `research`, `innovate`, `plan`, `execute` | `nomode` |
| Impact | Fixed three-valued form | `impact-high`, `impact-low`, `impact-none` | not applicable |
| Sequence | Six-digit zero-padded | `000042` | not applicable |

Every facet segment must contain only ASCII alphanumerics, apart from the single hyphen in the impact form, so the tokenizer keeps each facet atomic. Prefix queries then provide a free hierarchy: a month by prefixing the date facet to eight characters, a year to five, and all tickets of a project by prefixing the project letters.

Primary ticket selection, when a turn references several: the first key appearing in the user text; failing that the first in the assistant text; failing that the first in any labelled section; failing all, `noticket`. Every referenced key, the primary included, is recorded in the front matter.

## 7. Data model

Rows are written into the engine's existing `documents` table with no schema change.

| Column | Value |
| :--- | :--- |
| `source_archive` | `chat:turns`, `chat:artifacts`, or `chat:dross` |
| `file_path` | Path relative to the ingested subtree root |
| `file_name` | `turn-<seq>.md` or `artifact-<seq>.md` |
| `extension` | `.md` |
| `size_bytes` | UTF-8 byte length of the rendered content |
| `content` | Rendered Markdown per section 8 |
| `is_compressed` | `0` |

For each ingested source file, the engine's manifest is recorded with `origin_path` set to the workspace-relative source path and `content_hash` set to the SHA-256 of the entire source file, which enables whole-file skip on re-ingestion.

## 8. Turn document format

Front matter is delimited by triple-hyphen lines and carries these fields in this fixed order.

| Field | Content |
| :--- | :--- |
| `timestamp` | Resolved instant |
| `timestamp_confidence` | `exact`, `local`, `date_only`, or `lost` |
| `mode` | Resolved mode or `nomode` |
| `tickets` | All referenced ticket keys, primary first |
| `session` | Source slug joined to the resolved date by a colon |
| `source` | Workspace-relative source path |
| `source_lines` | Start and end line numbers joined by a hyphen |
| `seq` | Global sequence number |
| `impact` | `high`, `low`, or `none` |
| `turn_hash` | SHA-256 of the normalised turn text |
| `spill` | Relative artifact path; present only when the body was spilled |
| `also_seen_in` | Additional source locations; present only when duplicates were found |

The body carries a `## User` heading with the user text, an `## Assistant` heading with the assistant response, and where labelled sections exist a `## Details` heading followed by each label as a third-level heading, preserved in the order encountered in the source.

Combining query and response into one document is deliberate. The user and the assistant describe the same event in different vocabularies, so a single document is indexed in both at once and either phrasing retrieves the complete unit. Split documents would each return only half, and the engine offers no parent-child column with which to reassemble them. The accepted cost is that a match can no longer be scoped to a single role, because FTS5 cannot restrict a match to a sub-section of a column.

## 9. Deduplication

Turn identity is defined by normalisation: concatenate the user and assistant text, lowercase it, collapse every whitespace run to a single space, remove every timestamp-shaped substring, remove leading label tokens, and trim. Front matter, facet path, sequence, and source location are excluded, so the same turn found in several files resolves to one identity.

Deduplication happens at file emission, not in the database. The authority is a tab-separated index at `worklog-chat/.index/turn-hashes.tsv` with columns for turn hash, emitted relative path, source relative path, source start line, source end line, and sequence. A turn whose hash is already present is not emitted; its source location is appended to the existing file's `also_seen_in` field. Because emission is deduplicated, the databases inherit deduplication with no database-side state.

A whole-file fast path precedes parsing: if a source file's SHA-256 already appears in a target database's manifest, the file is skipped without being read. This is what collapses the ten byte-identical exporter snapshots without a path-based exclusion rule.

## 10. Spill rule

Turn sizes are tightly clustered, with a median of 382 bytes and a 99th percentile of 1,489 bytes, but block-dialect entries have a heavy tail reaching 471,718 bytes. Admitting those into the turn corpus would both distort ranking and exhaust any retrieval budget in a single hit.

When a rendered body exceeds 8192 bytes, the full body is written into the artifacts subtree and the turn retains a short abstract plus a `spill` pointer to the relative artifact path. At that threshold no real turn is ever spilled, because it sits roughly five times above the turn 99th percentile, while every artifact dump is.

## 11. New library modules

| Module | Responsibility | Methods |
| :--- | :--- | :--- |
| `lib/TimestampRepair.groovy` | Normalise the twenty observed formats and assign confidence | `parse`, `interpolate`, `zoneFor` |
| `lib/ChatLogParser.groovy` | Recognise the six dialects and emit ordered raw entries | `parseFile`, `splitRegions`, `resolveFenceImbalance`, `classifyRegion`, `extractLabelledSections`, `extractTicketKeys` |
| `lib/FacetPath.groovy` | Build facet paths with atomic tokens | `build`, `sanitizeToken`, `ticketFacet`, `dateFacet`, `modeFacet`, `impactFacet`, `fileNameFor` |
| `lib/TurnClassifier.groovy` | Route turns and assign impact | `classify`, `impactOf`, `isDross` |
| `lib/TurnDocument.groovy` | Render content, define identity | `render`, `normalizeForHash`, `hash`, `abstractOf` |
| `lib/TurnEmitter.groovy` | Write files and maintain the hash index | `loadHashIndex`, `emit`, `recordDuplicate`, `saveHashIndex` |
| `lib/SweepState.groovy` | Track per-source byte offsets and detect truncation | `load`, `offsetFor`, `record`, `save`, `needsFullRescan` |
| `lib/ResumeDigest.groovy` | Render the curated digest to `RESUME.md` | `assemble`, `render`, `write` |

Modules are registered in the load-order list in `run.groovy` after `MemoryEngine` and before `DatasetRegistry`.

### 11.1 Dialect recognition

Region splitting is a single forward stateful scan. A region may contain at most one logical user side and one logical assistant side. Repeated source lines explicitly identified as one sequence remain within that side; a second independent user-side start closes the current region. This invariant is tested against consecutive entries and against the live corpus, because the first implementation collapsed 1,084 user-side markers into 56 multi-turn regions.

The scanner recognises five boundary families. First are prompt-entry fences: the exact opener, the `START` variant, the partial-entry variant, and an opener carrying its timestamp on the same line. Second are date banners delimited symmetrically by three or more equals signs or three or more dashes. Third are bracketed timestamp starts, including an inline user or prompt role, a bare timestamp, a mode header, a ticket heading, and a descriptive heading. Fourth are explicit bare date-led headers: a continuation header, a `MODE` header, a bracketed-mode ticket header, and a `PROMPT INPUT` header. Fifth are `TIMESTAMP:`, `Timestamp:`, `DATE:`, and `Date:` fields when they begin a region.

A pure equals separator of forty or more characters is context-sensitive. It closes a region that already contains role content. When it immediately follows a timestamp, mode, ticket, or descriptive header and no role content has been seen, it is formatting inside the same region and the header is carried forward. A separator with no pending region is discarded. This rule preserves the header-body association used by the bracketed and bare date-led dialects.

An assistant or response line is a continuation, not a boundary. A `PROMPT OUTPUT` date header switches the active region to its assistant half and pairs with the preceding `PROMPT INPUT` header of the same timestamp. A bracketed assistant line pairs with the nearest unmatched bracketed user or prompt start, preferring an equal timestamp. An unbracketed or qualified assistant line may likewise complete a bracketed user start.

Fences are unbalanced in the real corpus, with 583 exact openers, two `START` openers, one partial opener, twenty timestamp-bearing openers, and 633 closers in the current main journal. An opener with no matching closer terminates at the next opener, date banner, bracketed start, or context-closing equals separator, whichever comes first. A closer with no matching opener terminates a pending region and is otherwise discarded.

Date-leading text is not a boundary by itself. Filenames and operational timeline entries also begin with dates. Only the explicit banner and header forms above qualify, preventing false splits on content such as date-prefixed artifact filenames.

Classification is applied in strict precedence order.

| Order | Dialect | Recognition |
| :--- | :--- | :--- |
| 1 | Fenced | Begins with any prompt-entry opener variant and contains a user-side role |
| 2 | Labelled | Contains `TIMESTAMP:` or `Date:` with `USER PROMPT:`, `USER PROMPTS:`, or `INPUT:`, paired where present with `ASSISTANT OUTPUT:` or `OUTPUT:` |
| 3 | Block | Begins with a date banner, bare date-led header, or timestamp field and contains a user-side role |
| 4 | Ticket | Bracketed timestamp and ticket key, a dashed rule, then `PROMPT:` and `RESPONSE:` |
| 5 | Pair | Bracketed timestamp with `USER:` or `PROMPT:`, paired with bracketed or unbracketed `ASSISTANT:` or `RESPONSE:`, including qualified forms |
| 6 | Artifact | No user or prompt marker present |

User-side role aliases are `USER:`, `USER PROMPT:`, `USER PROMPTS:`, `PROMPT:`, `INPUT:`, `USER QUERY:`, `USER CAUGHT ERROR:`, and qualified `USER (...)` or `PROMPT (...)` forms. Assistant-side aliases are `ASSISTANT:`, `ASSISTANT OUTPUT:`, `ASSISTANT RESPONSE:`, `RESPONSE:`, `RESPONSE SUMMARY:`, `OUTPUT:`, and qualified `ASSISTANT (...)` or `RESPONSE (...)` forms. A qualifier may contain a mode and the assistant form may include an action suffix. Prefixes such as `Output saved:` are detail labels rather than roles and do not qualify.

Each emitted entry carries the source path, byte offset, start and end line, dialect, raw timestamp, raw mode, user text, assistant text, an ordered map of labelled sections, ticket keys, and the raw body.

### 11.2 Timestamp handling

Confidence is assigned as `exact` for a date, time, and explicit zone; `local` for a date and time without a zone, resolved against `Europe/Warsaw`; `date_only` for a date with no time, resolved to local midnight; and `lost` when the string contains the literal text `$(date` or matches no pattern. Zone tokens map as follows: `CEST` and `CET` to `Europe/Warsaw`; `UTC+2`, `(UTC+2)`, and `+0200` to a plus-two-hour offset; `Z` to UTC; a literal `Europe/Warsaw` to itself; and an absent zone to `Europe/Warsaw`.

Resolved timestamps are metadata only and are never the ordering key, because the corpus contains 68 out-of-order transitions and 54 entries whose time is unrecoverable. Ordering is the global sequence derived from source processing order and byte offset. Entries with `date_only` or `lost` confidence are interpolated from their nearest resolved neighbours by byte offset.

### 11.3 Classification rules

An Artifact-dialect entry is routed to artifacts. A non-artifact turn whose rendered body exceeds the spill threshold emits its full body as an artifact and remains in the turns tier as an abstract carrying the relative spill pointer.

A turn is routed to dross when all three of the following hold: its user text, lowercased and trimmed, is empty or appears in the allowlist; its assistant text is shorter than 120 bytes; and it carries no `FILES CHANGED` or `REMOTE CHANGES` section. The allowlist is exactly the empty string, `pushed`, `what now?`, `check now`, `go next`, `yes`, `ok`, `mode: research`, `mode: innovate`, `mode: plan`, and `mode: execute`. The classifier performs no inferential judgement beyond this list, by design, because a misclassification would silently hide real memory and silent loss is the worst failure mode for a memory system. Misclassification is recoverable, since the source logs remain on disk and emission is idempotent, so a corrected rule is applied by re-running.

Otherwise the entry is a turn.

Impact is `high` when the entry has a `FILES CHANGED` or `REMOTE CHANGES` section whose value is not solely a negation, or has a `SEMANTIC COMMIT TITLE` or `WRITE GATE` section. It is `low` when the entry has any of `FINDINGS`, `FINDING`, `VERIFICATION`, `VALIDATION`, `ROOT CAUSE`, `RESULT`, `CONCLUSION`, `RECOMMENDATION`, `EXECUTION`, or `DECISION`. It is `none` otherwise.

## 12. New top-level script

`05_ingest_chatlog.groovy` follows the existing numbered-script convention and is executed through `run.groovy` so it inherits the shared class loader. It is a transformer: it emits files and writes no database rows.

Options are `--source` (repeatable, workspace-relative or absolute), `--all`, `--since-last`, `--workspace-root`, `--spill-bytes`, and `--dry-run`, alongside the inherited `--data-dir` and `--db`.

Workspace root resolution precedence: the `--workspace-root` value; the `MEMORY_WORKSPACE_ROOT` environment variable; the canonical path two levels above the repository root.

Source discovery under `--all` is a recursive scan from the workspace root for `PR.log` and for basenames ending in `prompt.log`, `prompt.log.old`, or `prompt.log.new`, case-insensitively, excluding any path containing a `.git` segment. The suffix rule includes exporter snapshots whose filenames carry a session prefix. No other exclusion is applied, because the whole-file hash skip handles redundant copies and keeps discovery rules self-correcting. The ten exporter snapshots measured during planning are no longer present in the current working tree, but their naming form remains covered if they return.

Sources are processed in descending file size order, so the largest log is processed first and becomes the canonical location for shared turns while smaller files contribute only their net-new entries. This is deterministic and reproducible.

The per-source pipeline computes the whole-file hash and skips on a match; otherwise it parses into entries, repairs and interpolates timestamps, classifies each entry, computes the turn hash, and for each entry either records a duplicate or emits a file into the appropriate subtree. Bodies above the threshold are spilled first so the pointer is available when the turn is rendered.

Reporting to stdout separates classification from emission. Per source and in totals it reports raw classified turns, artifacts, and dross; newly emitted unique files for each tier; duplicate entries for each tier; whole-file hash skips; full-rescan status; and elapsed milliseconds. The accounting invariant is checked independently for each tier: raw classified entries equal newly emitted unique files plus duplicate entries, except that one spilled turn also emits one artifact file and is reported explicitly as a spill rather than as another raw artifact. The report never labels emitted unique files simply as turns. Under `--dry-run` the identical report is produced and nothing is written.

### 12.1 Incremental sweep

A `--since-last` mode processes only the portion of each source appended since the previous run, so the ongoing cost of keeping the memory current is proportional to new content rather than to corpus size.

State lives at `worklog-chat/.index/ingest-state.json` and records, per source path, the last processed byte offset, the byte length at that time, and a hash of the processed prefix. On each run a source is resumed from its recorded offset when the prefix hash still matches. When the file has shrunk, or the prefix hash differs, the source is re-scanned in full.

That re-scan condition is a safety property, not merely error handling. It is precisely the signature of the 2026-02-11 incident in which a write replaced an append and destroyed 3,378 lines, so the sweep detects a repeat of that accident rather than silently compounding it. Deduplication by turn hash makes a full re-scan harmless.

The sweep is intended to run at session start, where the cost is paid once per session rather than once per turn, and where it is self-healing because the offset marker guarantees nothing is skipped even if several sessions pass without a sweep.

### 12.2 Resume digest generation

`lib/ResumeDigest.groovy` renders `worklog-chat/RESUME.md` using the same curated assembly and byte budget as the resume query described in section 15. Methods are `assemble`, `render`, and `write`.

The digest is regenerated at the end of every transformer run, including every incremental sweep, so it is never more stale than the last sweep. It is a plain Markdown file carrying a generation timestamp, the turn count it summarises, and the budget consumed, so that a reader can tell at a glance how current it is.

## 13. Modifications to existing files

**`run.groovy`.** Add command-map entries `chatlog` and `ingest-chatlog` pointing at `05_ingest_chatlog.groovy`, and `recent`, `resume`, and `context` pointing at `03_query_memory.groovy`. Add all five to the list that injects `commandName` into sub-script arguments. Register the eight new library modules in the load-order list. Extend the usage text with a Conversation Memory section. No change to existing flag parsing, class-loading order, or dependency resolution.

**`03_query_memory.groovy`.** Add colon commands `:recent` and `:resume` to the interactive loop and its help output. Add non-interactive handling for the `recent`, `resume`, and `context` subcommands. Add the flags `--budget`, `--ticket`, `--mode`, `--impact`, `--since`, `--until`, and `--json`.

**`02_ingest_archive.groovy`.** Keep archive ingestion unchanged. In directory mode, ingest canonical memory documents by ascending numeric sequence with relative path as the tie-breaker; ingest other directories in deterministic relative-path order.

**`lib/ArchiveHandler.groovy`.** Return recursively discovered directory entries in deterministic relative-path order.

**`lib/Config.groovy`.** Add constants only: the three database names, the three dataset names, `CHAT_SPILL_BYTES` of 8192, `RESUME_BUDGET_BYTES` of 22000, `RESUME_RECENT_TURNS` of 12, the dross allowlist, the impact label sets, the output root name, and the `workspaceRoot` resolver. No existing constant is modified.

**`lib/MemoryEngine.groovy`.** Unchanged in Phase 1.

Two artefacts are created outside this repository. The always-applied rule is written to `.cursor/rules/worklog-chat-memory.mdc` beneath the workspace root, and the governed skill is added to the `ai-vault` repository per section 17.5. Both are listed in the checklist so that neither is lost at implementation time.

## 14. Datasets and tiering

Dataset membership is what makes a tier unsearchable, and it requires no new code: the registry resolves search scope from the active dataset, so a database that is not a member is never queried.

| Dataset | Members | Purpose |
| :--- | :--- | :--- |
| `worklog-chat` | `convo.db`, `artifacts.db` | Active default |
| `worklog-chat-turns` | `convo.db` | Pure conversational recall |
| `worklog-chat-all` | `convo.db`, `artifacts.db`, `dross.db` | Forensic and audit queries |

`artifacts.db` is deliberately inside the default set, because artifacts hold the hard facts most often searched for, such as cluster names, endpoints, and custom field identifiers. Because the tiers are separate databases their BM25 normalisation is independent, so artifact length cannot contaminate turn ranking, and the federated engine fuses their rankings by reciprocal rank fusion.

Dataset creation is idempotent: an existing dataset of the same name has its member list reconciled rather than being recreated. `worklog-chat` is set active after creation.

## 15. Retrieval design

Two retrieval modes are required, and conflating them was the central flaw of the predecessor specification. Search answers what was ever done about a subject. Recency answers what was being done most recently. Delivering only search would lose the resume function that the tail read actually provided.

The two modes also differ in how reliably they can be delivered, and the design exploits that difference. Recency is delivered as injected context by way of the digest and the always-applied rule described in section 17, which makes it effectively guaranteed. Search remains an action the agent chooses to take, and is therefore advisory however well documented.

**Recency** uses no full-text search at all. It selects only `chat:turns` documents, derives the authoritative numeric sequence from the canonical filename, orders each database's candidates by descending sequence, and merges them by descending sequence. Database name, file path, and identifier provide deterministic tie-breakers. All ticket, mode, impact, and date filters are applied before ordering and limiting. This remains correct even when insertion identifiers are unrelated to sequence.

**Curated resume** assembles content within a byte budget in a fixed priority order: the most recent twelve `chat:turns` documents; then, when a ticket is supplied or a single ticket dominates those recent turns, the highest-ranked relevance hits for that ticket facet; then the highest-impact items for that ticket. Artifacts may participate in the relevance and high-impact tiers but never displace turns in the recent tier. Items are added in priority order until the budget is reached, duplicates by document identity are suppressed, and the output reports bytes used against the budget. The default budget is 22000 bytes, matching the measured size of the historical four-hundred-line tail read, so the same context cost buys curated rather than blind material.

**Facet flags** translate into constraints appended to the match expression. A ticket constrains the ticket facet, a mode the mode facet, an impact the impact facet, and a date range expands to a disjunction of month-level prefix terms.

**JSON output** emits a single JSON array with the fields id, source_archive, file_path, file_name, extension, rank, and snippet, carrying no highlight markers, no box drawing, and no progress text on stdout. It applies to `query`, `recent`, `resume`, `:files`, `:doc`, `:stats`, and `:archives`.

## 16. Startup cost remediation

A real query issued through `run.groovy` costs 2,071 milliseconds, of which the engine reports 21.86 milliseconds as the actual search. Approximately 99 percent of the cost is startup: dependency resolution and runtime compilation of seven modules on every invocation. The equivalent direct `sqlite3` query costs 18 milliseconds.

Two consequences follow. First, agent reads must bypass Groovy entirely and use `sqlite3`, which also supplies JSON output natively. Second, the Groovy path is still worth repairing for interactive and ingestion use.

The remedy is precompilation, scoped concretely rather than left open: compile `lib/` and the numbered scripts with `groovyc` into a single jar, vendor the SQLite JDBC jar into a `vendor/` directory, and provide a thin launcher that invokes the JVM against the jar plus the vendored dependency. This removes both dependency resolution and runtime compilation. Phase 1 achieved a minimum of 760 milliseconds across three real-query process measurements, down 63 percent from the 2,071 millisecond baseline but still above the 300 millisecond threshold and the 21.86 millisecond search floor. Direct SQLite remains the agent-read path. A resident warm process remains out of scope for Phase 1 on grounds of process-lifecycle complexity and is retained for later evaluation.

## 17. Enforcement: rule, digest, and governed skill

### 17.1 What can and cannot be guaranteed

The IDE offers no way to make a skill load on every query. Skills are selected by the model from their description field, and the only mechanism that keeps one resident across turns is a custom mode the user must enter manually. The vendor's own enterprise guidance classifies rules, skills, commands, and tool servers as non-deterministic steering, and reserves the term deterministic enforcement for hooks, approvals, and sandboxing.

A rule, by contrast, is documented as always included in context on every request when it declares itself as always applied. That makes the rule the correct carrier for anything that must be present every time. Two syntax constraints apply: rule files must use the `.mdc` extension, because a plain Markdown file in the rules directory is silently ignored, and rules do not apply to inline edit, only to agent conversations.

The design therefore assigns obligations by what the mechanism can actually promise.

| Obligation | Mechanism | Guarantee |
| :--- | :--- | :--- |
| Resume context present every query | Always-applied rule referencing the digest | Content injected; cannot be skipped |
| Journal append after every turn | Always-applied rule instructing the append | Advisory, but demonstrated reliable in practice |
| Batched sweep and index refresh | Same rule, at session start | Advisory; self-healing via the offset marker |
| Semantic search when relevant | Governed skill | Advisory by nature |
| Hard per-prompt enforcement | Hook on prompt submission | Deterministic, but unavailable where policy blocks hooks |

### 17.2 Why the per-turn obligation is a journal append and nothing else

A per-turn database write would cost on the order of two seconds, and an obligation that expensive is abandoned by agent and human alike. The architecture avoids it entirely by making `prompt.log` the write-ahead journal: the only thing required after each response is the append that already happens, and everything else is derived from the journal by the incremental sweep.

This is also why index staleness is harmless. Resume context comes from the digest, the current session's exchanges are already present in the conversation itself and in the journal tail, and the only material that needs indexing is history, which by definition does not change. The index may therefore lag by a session without any loss of function.

### 17.3 The always-applied rule

A project rule at `.cursor/rules/worklog-chat-memory.mdc` declares itself always applied and stays deliberately short, since it is injected on every request. It references the digest file so that resume context arrives as context rather than as an instruction to go and fetch something. Its remaining content is the journal-append obligation, the session-start sweep instruction, and a pointer to the governed skill for anything more elaborate.

One further provision raises compliance without any enforcement mechanism: the rule requires a response that relied on memory to name what it consulted. That converts a silent omission into a visible one.

### 17.4 Hook, where available

Where policy permits hooks, a hook on prompt submission is the preferred carrier for the sweep and for any obligation that must be certain, because it is the only deterministic per-prompt mechanism available. Configuration layers differ in scope, covering enterprise management, team distribution, project-level configuration, and user-level configuration, so a block at one layer does not necessarily imply a block at another and is worth verifying per layer before the hook path is abandoned.

The rule-and-digest architecture is designed to stand alone without a hook, and a hook is therefore an enhancement that raises the advisory obligations to deterministic ones rather than a prerequisite.

### 17.5 Governed skill

The skill is governed in the `ai-vault` repository, at `skills/worklog-chat-memory/SKILL.md`, with the directory name and the frontmatter name both `worklog-chat-memory`, a version of `1.0.0` per that repository's versioning policy, and a folded-style description under 1024 characters following the house style of the existing `pr-review-comments` skill.

The vault validator enforces seven checks, and the skill must satisfy all of them on first commit: frontmatter beginning at line 1 and parsing as valid YAML, with a name matching the directory and a quoted three-part version; a ceiling of 500 lines; resolvable relative markdown links; an even number of code-fence lines; no tool-call markup; registration in the skills manifest; and a machine-path ban rejecting any occurrence of absolute user paths, `file:///`, or Homebrew paths. That ban is what enforces the relative-paths requirement mechanically, so the skill cannot hardcode the workspace location and must resolve it from `MEMORY_WORKSPACE_ROOT`.

Manifest registration declares the name and directory as `worklog-chat-memory`, `required` as true on the grounds that retrieval is meant to occur on every query, and all three supported IDEs, preserving the existing alphabetical ordering of entries.

Required content is fixed so that no authoring decisions remain: the read recipes as direct `sqlite3` JSON invocations against the three databases; the facet vocabulary from section 6; an instruction to discover facets by globbing the turns tree rather than guessing token forms, since a wrong guess returns zero rows and an agent will conclude no memory exists; the resume recipe with its byte budget; a statement that turn files are readable directly and may be opened or referenced with no tool invocation at all; and the write-path rule that content is supplied through stdin or a temporary file and never through an interpolated text argument.

That last rule is not theoretical. The existing `prompt.log` contains 54 entries whose timestamp is a literal unexpanded shell substitution, which is shell interpolation failure already present in the record.

A skill alone cannot satisfy the requirement that retrieval fire on every query, because skills are advisory and an agent deep in a task will not reliably re-query. The skill documents how to search; the rule and digest of sections 17.3 and 12.2 are what make resume context present regardless.

## 18. Role of `prompt.log`

`prompt.log` is not retired, and its role is elevated rather than diminished. It becomes the write-ahead journal of the memory system: the single thing written synchronously after every turn, from which turn files, the databases, and the digest are all derived by the incremental sweep. The three databases are the queryable index over it, and the digest is its curated head.

This makes the existing logging rule load-bearing rather than merely historical, and it is left unchanged by Phase 1. It also means the journal is the disaster-recovery boundary: everything downstream is reconstructible from it, so the only irreplaceable artefact is the one that is plain text, append-only, and trivially backed up.

## 19. Tests

A new suite `tests/test_chatlog_suite.groovy` follows the in-process hermetic pattern of the existing suite and covers: compound facet token atomicity, including that a short ticket key matches neither a longer key sharing its prefix nor a bare day component; facet retrieval by ticket, mode, date prefix, and compound constraint; parsing of all six dialects; both fence imbalance cases; every timestamp confidence level including an unexpanded shell substitution and neighbour interpolation; hash normalisation treating two differently formatted copies of one turn as identical; emission idempotency; whole-file hash skip; dross classification for each allowlist entry and non-classification of a short turn bearing a changed-files section; all three impact classes; spill routing with a resolvable pointer and non-empty abstract; recency ordering; resume budget adherence; dataset scoping proving a dross-only term is unreachable under the default dataset and reachable under the forensic one; JSON output parsing with the specified fields; sweep resumption emitting only appended content; truncation triggering a full re-scan that produces no duplicates; and digest rendering staying within budget. Recency fixtures deliberately insert turn sequences out of order and give an artifact a newer identifier, proving explicit sequence ordering, artifact exclusion from the recent tier, filtered ordering, resume-tier precedence, and sequence-aligned identifiers after deterministic directory rebuild.

The parser tests additionally cover the measured boundary matrix: three- and five-equals date banners; dash date banners; exact, `START`, partial, timestamp-bearing, unmatched-open, and unmatched-close fences; bracketed inline user, inline prompt, qualified assistant, unbracketed assistant, bare timestamp, mode, ticket, and descriptive headers; bare continuation, date-mode, bracketed-mode ticket, `PROMPT INPUT`, and paired `PROMPT OUTPUT` headers; `INPUT`/`OUTPUT`; every approved qualified role alias; header carry-forward across a pure equals separator; and rejection of date-leading filenames and timeline events as boundaries. Multi-entry fixtures assert one parsed entry per logical turn and a corpus invariant asserts that no emitted raw region contains more than one independent user-side start.

Fixtures live under `tests/fixtures/chatlog/`, one synthetic file per dialect plus files covering the complete boundary matrix, an unmatched opener, an unmatched closer, an oversized body, an exact duplicate, an appended-to source for sweep resumption, and a truncated source for full-rescan detection, produced by extending `tests/TestFixtureGenerator.groovy`. Fixtures must be synthetic rather than copied from the real log. Source-pattern fixtures mirror the structural form of `prompt.log.new`, `PR.log`, `cluster-scale`, `env-setup`, `bng_pipelines`, `ai-worklog-framework`, `jira_integration`, and `newrelic` without copying their content.

## 20. Regression scope

Because `run.groovy`, `03_query_memory.groovy`, `02_ingest_archive.groovy`, and `lib/ArchiveHandler.groovy` are modified, every pre-existing command and feature of both command paths must be re-verified: `analyze` with and without a directory; `ingest` by name, with `all`, and with a directory; `query` with terms; `compress` and `decompress` with and without an archive; dataset listing; dataset use, create, delete, rename, add-database and remove-database, together with the set aliases and the hyphenated variants; database listing; archive listing; archive egest; archive rename; stream; and append by text and by file. The colon commands `:files`, `:ext`, `:doc`, `:stats`, `:archives`, `:datasets`, `:dbs`, `:help`, and `:quit` must be exercised, as must the flags `--db`, `--data-dir`, `--dataset`, `--ext`, `--extension`, `--no-ext`, `--limit`, `--all`, `--raw`, `--lines`, and `--snippet-size`. General directory ingestion must remain deterministic by relative path, and canonical memory directory ingestion must be deterministic by numeric sequence. The existing `tests/test_query_suite.groovy` must pass unchanged. Any individual check exceeding sixty seconds may be skipped, with the skip and its reason reported.

## 21. Verification

The backfill is verified first by structural accounting rather than by a stale cardinality gate. For each tier, raw classified entries must reconcile to unique emissions plus duplicate entries, with spills separately reconciling one turn to one additional artifact. The corrected parser must leave no region containing multiple independent user-side starts. `prompt.log.old`, `prompt.log.new`, and any returning exporter snapshots must contribute no false aggregate-only turn after their constituent entries are split; any genuinely unique constituent turn remains visible in the per-source report. The earlier estimate of roughly 1,900 to 2,400 turns is retained only as a diagnostic range for raw turn-classified entries, not as a pass condition. Exact raw, unique, duplicate, spill, and whole-file-skip counts become the recorded Phase 1 baseline.

Verification then spot-reads emitted turn files for front matter order, role sections, and relative-path-only content; reconciles database document counts against emitted file counts; runs facet queries by ticket, by mode, and by month prefix; confirms a dross-only phrase is unreachable under the active dataset and reachable under the forensic one; and compares recent results against the greatest sequences discovered from the emitted turn tree before any rebuild. It then re-runs emission to prove no new files appear, deletes and rebuilds the databases from files to prove the index is derived, confirms `convo.db` identifiers ascend with turn sequence after deterministic ingestion, and runs resume to confirm it stays within budget while beginning with recognisably recent turns.

The enforcement path is verified separately, because its failure mode is silence rather than error. A synthetic turn is appended to the journal and the incremental sweep is run, confirming that exactly one turn file appears, that sweep state advances, and that the digest is regenerated containing the new turn. A fresh conversation is then started to confirm the digest content is present in context without any tool invocation, which is the only direct evidence that the always-applied rule is functioning.

## 22. Deferred phases

Phase 2 covers live per-turn file emission through a dedicated logger, reintroducing the database-side turn index deferred from Phase 1, and the enforcement hook of section 17.4 wherever policy permits it. Neither is a prerequisite for a working memory, because Phase 1 delivers resume context by injection and keeps the index current by sweep.

Phase 3 covers the distilled archives, being a lessons index of failure and resolution pairs queried before risky actions and a decision ledger used as the session-start payload, plus the session replay manifest and tiered hot and cold compression by month.

## 23. Related documents

The [implementation checklist](prompt-log-replacement-checklist.md) carries the ordered, executable steps.
The [measured evidence](prompt-log-replacement-evidence.md) carries every measurement cited here.
