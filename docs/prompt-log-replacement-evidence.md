# Measured Evidence: `prompt.log` Corpus, Engine Behaviour, and Retrieval Cost

Companion to the [specification](prompt-log-replacement-specification.md) and the [checklist](prompt-log-replacement-checklist.md).

Every design decision in the specification traces to a measurement recorded here. Measurements were taken on a single macOS workstation running Groovy 6.0.0-beta-3 on JVM 26.0.2.1 with SQLite 3.43.2. Timing figures are the minimum of three runs, because single-shot timings on a loaded machine proved unreliable; one early single-shot measurement exceeded the total runtime of the process that contained it, which is why minimum-of-three was adopted.

---

## 1. Source corpus inventory

Nineteen candidate files exist across the workspace. Most contribute nothing, and this was established by set comparison rather than assumption.

| Source | Lines | Unique lines | Net-new lines |
| :--- | ---: | ---: | ---: |
| Workspace `prompt.log` | 35,753 | — | canonical trunk |
| `prompt.log.old` | 22,168 | 15,586 | 0 (complete subset of trunk) |
| Exporter snapshot | 22,092 | 15,541 | 0 (subset of `prompt.log.old`) |
| `prompt.log.new` | 254 | 193 | 0 |
| `scripts/newrelic/prompt.log` | 1,075 | 776 | 758 |
| `TVN-gitadmins-bng_pipelines/prompt.log` | 1,096 | 730 | 723 |
| `PR.log` | 915 | 688 | 672 |
| `ai-worklog-framework/prompt.log` | 545 | 343 | 335 |
| `scripts/cluster-scale/prompt.log` | 218 | 115 | 114 |
| `env-setup/prompt.log` | 77 | 53 | 52 |
| `TVN-gitadmins-jira_integration/prompt.log` | 40 | 36 | 29 |

Three findings follow.

`prompt.log.old` is a complete subset of the trunk: all 15,586 of its unique lines already appear there, so it adds nothing. `prompt.log.new` adds zero new lines. The session exporter holds **ten byte-identical copies** of one snapshot, confirmed by a single distinct checksum across ten files, and that snapshot's 15,541 unique lines are entirely contained in `prompt.log.old` and therefore in the trunk. Roughly 16 MB of candidate bytes are pure redundancy.

The genuinely additive sources total about 2,700 net-new lines, making the trunk approximately 93 percent of the corpus. This is why the specification chooses content-addressed deduplication over canonical-file selection: pointing the transformer at every source and letting hashing collapse the overlap is simpler and self-correcting.

A whole-file hash skip handles the ten identical copies without any path-exclusion rule.

## 2. Corpus anatomy

The predecessor specification assumed one uniform format. Marker counts taken across the trunk disprove that.

| Marker | Count |
| :--- | ---: |
| `ASSISTANT:` | 1,561 |
| `USER:` | 1,556 |
| `TIMESTAMP:` | 673 |
| Bracketed-ISO header lines | 652 |
| `--- END PROMPT LOG ENTRY ---` | 633 |
| `--- PROMPT LOG ENTRY ---` | 583 |
| `PROMPT:` | 381 |
| `MODE:` | 373 |
| Eighty-character separators | 1,038 |
| Dashed separators | 191 |
| `RESPONSE:` | 67 |
| `USER PROMPT:` | 38 |
| `ASSISTANT OUTPUT:` | 30 |

The dominant dialect is the bracketed-ISO header followed by single-line `USER:` and `ASSISTANT:` pairs, not the documented block form. The fenced dialect is **unbalanced**, with 583 openers against 633 closers, so fence-based parsing without imbalance rules will misalign. Distribution across the file shows the dialects interleaved rather than layered by era, so a single pass must recognise all of them.

Roughly ninety distinct uppercase block labels exist beyond the role markers. The most frequent form a de facto schema worth preserving as structured detail rather than flattening into prose.

| Label | Count |
| :--- | ---: |
| `FILES CHANGED` | 277 |
| `REMOTE CHANGES` | 154 |
| `SEMANTIC COMMIT TITLE` | 89 |
| `ANSWER` | 61 |
| `SEMANTIC COMMIT DESCRIPTION` | 56 |
| `DESCRIPTION` | 31 |
| `OUTPUT` | 27 |
| `CONCLUSION` | 27 |
| `FINDINGS` | 12 |

A sixth dialect carries no role marker at all: large free-form artifact dumps such as an EKS cluster and Redis inventory around lines 4800 to 4900. These are context, not conversation, which is why the specification routes them to a separate tier.

## 3. Timestamps

Twenty distinct formats appear.

| Shape (digits shown as N) | Count |
| :--- | ---: |
| `NNNN-NN-NN NN:NN (UTC+N)` | 155 |
| `NNNN-NN-NN NN:NN:NN CEST` | 108 |
| `NNNN-NN-NN NN:NN (CEST)` | 89 |
| `NNNN-NN-NNTNN:NN:NNZ` | 72 |
| `NNNN-NN-NN` (date only) | 61 |
| `NNNN-NN-NN NN:NN:NN +NNNN` | 50 |
| `NNNN-NN-NN NN:NN CEST` | 37 |
| `NNNN-NN-NN NN:NN UTC+N` | 21 |
| `NNNN-NN-NNT$(date +%H:%M:%S)Z` | 20 |
| `NNNN-NN-NN NN:NN:NN` | 13 |
| `NNNN-NN-NN NN:NN (Europe/Warsaw)` | 9 |
| `NNNN-NN-NN NN:NN` | 8 |
| `NNNN-NN-NNTNN:NN` | 6 |
| `NNNN-NN-NN NN:NN:NN (UTC+NNNN)` | 6 |
| `NNNN-NN-NN NN:NN (local, UTC+N)` | 5 |
| `NNNN-NN-NNTNN:NN:NN+NN:NN` | 3 |
| `NNNN-NN-NN (CEST)` | 3 |
| `NNNN-NN-NN NN:NN:NN (UTC+N)` | 2 |
| `NNNN-NN-NNTNN:NN+NN:NN` | 1 |
| `NNNN-NN-NN NN:NN-NN:NN UTC+N` | 1 |

Fifty-four lines contain a literal unexpanded shell substitution, so those times are unrecoverable. This is direct evidence that shell-argument-based logging corrupts content, and it is the basis for the specification's rule that the future write path must use stdin or a temporary file rather than an interpolated text argument.

Of 586 parseable date-and-time values, there are **68 out-of-order transitions** across a March-to-September 2026 range. Ordering by timestamp is therefore unsafe, which is why the specification derives ordering from source processing order and byte offset and treats the timestamp as metadata only.

Monthly distribution of parseable timestamps: March 37, April 49, May 26, June 17, July 117, August 89, September 251.

## 4. Mode and ticket recoverability

Mode survives well enough to be a primary facet. Explicit headers number 373, with one malformed by excess whitespace. Inline mentions number 1,819.

| Mode | As header | Inline |
| :--- | ---: | ---: |
| `EXECUTE` | 165 | 827 |
| `RESEARCH` | 161 | 665 |
| `PLAN` | 30 | 249 |
| `INNOVATE` | 16 | 78 |

Four junk values appear once each and must be rejected rather than accepted as modes.

Content is strongly ticket-anchored, which makes the ticket key the natural primary partition.

| Ticket | Mentions |
| :--- | ---: |
| CWP-11649 | 167 |
| KD-6999 | 164 |
| KD-7289 | 88 |
| CWP-14912 | 57 |
| KD-7242 | 53 |
| KD-6534 | 44 |
| KD-6494 | 33 |
| KD-6945 | 29 |
| KD-6541 | 29 |
| KD-6863 | 26 |

## 5. Duplication and data loss

The 1,556 `USER:` lines reduce to 1,466 unique, so deduplication is a requirement rather than an optimisation. The most frequent repeats are also the least informative, which is what justifies the dross tier.

| Repeated user line | Occurrences |
| :--- | ---: |
| (empty) | 28 |
| `MODE: EXECUTE` | 19 |
| `MODE: PLAN` | 11 |
| `pushed` | 5 |
| `what now?` | 4 |
| `mode: plan` | 4 |
| `mode: execute` | 4 |
| `MODE: INNOVATE` | 4 |

Several entries are explicitly labelled "canonical EOF entry" duplicates, confirming intentional re-appending.

The trunk's own header records that lines 1 to 3378 of the original log were lost on 2026-02-11 to a write performed instead of an append. That incident is the reason the append-only rule exists, and it is why the specification keeps the filesystem authoritative and the databases rebuildable rather than inverting the relationship.

## 6. Size distributions

Turn-shaped entries are remarkably uniform; block-shaped entries are not.

| Measure | Query-and-response pairs | Block-dialect entries |
| :--- | ---: | ---: |
| Count | 1,366 | 1,039 |
| Minimum | 23 | 1 |
| 25th percentile | 214 | 38 |
| Median | 382 | 549 |
| 75th percentile | 613 | 1,120 |
| 90th percentile | 834 | 2,197 |
| 99th percentile | 1,489 | 34,033 |
| Maximum | 2,274 | 471,718 |
| Mean | 448 | 2,378 |

All figures are bytes. The turn distribution sits squarely in the range where BM25 discriminates sharply, and the whole turn corpus is under a megabyte at an estimated 1,900 to 2,000 turns. The block tail is what the 8192-byte spill threshold exists to divert: that threshold is roughly five times the turn 99th percentile, so no genuine turn is spilled.

## 7. Why the tail read is the weaker option

The trunk averages 70.8 bytes per line.

| Measurement | Value |
| :--- | ---: |
| Last 400 lines | 22,250 bytes |
| Last 250 lines | 14,650 bytes |
| Turns within the last 400 lines | 13 |
| Largest single block | 471,718 bytes across 3,482 lines |

Two conclusions. The tail read is wasteful, spending 22 KB to deliver 13 turns, with a large share going to separators, section labels, and blank lines. More seriously it can fail outright: the largest block spans 3,482 lines, so while it sat at the end of the file a 400-line read returned a fragment of one infrastructure inventory and no conversation at all, across roughly 8.7 consecutive reads' worth of appends.

A curated read spending the same 22 KB can instead combine recent turns for resume with ticket-relevant and high-impact turns. The gain is not more context but control over which context, which is why the resume budget default is set to 22000 bytes.

## 8. FTS5 facet verification

The engine's exact schema was replicated in a throwaway database: the `documents` table, a contentless FTS5 table over `file_path`, `file_name`, and `content` with `contentless_delete` enabled, the `unicode61` tokenizer with diacritic removal, prefix indexes of 2, 3, and 4, and the insert trigger.

One environmental note: the `sqlite3` command-line tool rejects the insert trigger with an unsafe-virtual-table error until trusted schema is enabled. The JDBC path is unaffected because trusted schema defaults on for ordinary connections.

### 8.1 Naive path splitting works but produces poor facets

A path of `worklog-chat/CWP-11649/2026-08-11/EXECUTE/turn-0042.md` indexed these tokens:

> worklog, chat, cwp, 11649, 2026, 08, 11, execute, turn, 0042, md

All facet query forms returned correct results: ticket as a conjunction of the two split tokens, mode as a single token, a compound of ticket with mode and month, a prefix query on the mode, a quoted hyphenated literal, and a path facet combined with a content term.

The token list nevertheless exposes two defects. `2026` is shared by every document and is therefore useless as a facet, and `11` is simultaneously a month, a day, and a possible ticket number, so a ticket numbered 11 would collide with the eleventh of the month.

### 8.2 Compound tokens are strictly better

Retested with `cwp11649` and `d20260811` as single segments, the indexed tokens were:

> worklog, chat, cwp11649, kd6607, kd11, d20260317, d20260811, d20260911, execute, research, plan, impact, high, low, none, turn, 0042, 0007, 0101, 0500, md

Each facet is now one atomic token. Verified behaviours: an exact day returned both documents carrying it; a month prefix returned the same two; a project prefix returned both tickets of that project; the short ticket `kd11` matched only itself and neither `kd6607` nor the bare day `11`; and a four-way compound of mode, impact, and month prefix returned exactly the expected rows.

Prefix indexes cover lengths 2 through 4, so longer prefixes fall back to a term scan. At this corpus size that is immaterial.

### 8.3 BM25 ranking and length dilution

An early reading of zero for BM25 was an artifact of rounding to four decimal places when the values are of order 1e-06, not a property of the contentless table. The `documents_fts_docsize` shadow table exists and ranking is computed correctly.

| Document | Length (bytes) | BM25 |
| :--- | ---: | ---: |
| Short turn | 55 | -5.06240547673616e-06 |
| Long session | 117,057 | -1.14238571085786e-06 |

FTS5 treats more-negative as better, so the identical phrase ranks **4.4 times better** in the short document. This quantifies the length dilution that drives both the turn-grain decision and the spill rule.

## 9. Retrieval cost

This is the measurement that determined the access layer.

| Path | Cost |
| :--- | ---: |
| Bare JVM start | 81 ms |
| Bare Groovy script | 554 ms |
| `run.groovy` usage exit | 960 ms |
| `run.groovy` real query | 2,071 ms |
| Engine-reported search within that query | 21.86 ms |
| Equivalent direct SQLite JSON query | 18 ms |

The usage-exit figure is a floor rather than a query cost, because the runner prints usage and exits before dependency resolution and module compilation occur. The meaningful comparison is the real query at 2,071 milliseconds against 18 milliseconds, roughly 115 times slower, with approximately **99 percent of the cost being startup** rather than search.

Two decisions follow directly. Agent reads bypass Groovy and use SQLite directly, which also supplies JSON output natively. Separately the Groovy path is worth repairing by precompiling into a jar with a vendored driver, which removes both dependency resolution and runtime compilation; the recorded baseline to beat is 2,071 milliseconds against the 21.86 millisecond search floor.

Phase 1 precompiled `lib/` and all five numbered scripts into one application jar, vendored the runtime dependencies, and added a direct JVM launcher. Three complete process measurements of the same real query were 810, 760, and 810 milliseconds, for a minimum of **760 milliseconds**. This is a 63 percent reduction from the 2,071 millisecond interpreted path, but it remains above the 300 millisecond threshold. Direct `sqlite3` therefore remains the required agent-read path, and a resident process remains a later optimisation rather than becoming part of Phase 1.

## 10. Engine capabilities and constraints confirmed by reading

| Fact | Consequence |
| :--- | :--- |
| Only `file_path`, `file_name`, `content` are indexed | Facets must be encoded in the path |
| Directory ingestion stores paths relative to the ingest root | Materialised facet paths survive into `file_path` unchanged |
| Directory ingestion names the archive from the directory | Per-tier archives; per-ticket archives would require separate invocations |
| Streaming ingestion reads standard input | A safe write path exists that avoids shell interpolation |
| `egest` purges rather than exports | The engine has **no export capability**, so a database-only design is a one-way door |
| Dataset scope resolves from the active dataset | An unlisted database is never searched, giving the dross tier for free |
| Federated search fuses by reciprocal rank with a constant of 60 | Tiers can be blended without cross-contaminating ranking |
| Manifest carries unused origin and content hash columns | Whole-file skip needs no schema change |
| Maximum indexed file size is 1 MB; batch size is 500 | Emitted turns are far below the limit |
| Repository ignore rules exclude `*.db`, `*.md`, and `data/*.json` | Databases and new documents are untracked unless forced |

The absence of an export path is the single strongest argument for materialising turns as files. Without it, ingestion into a binary index would be irreversible, and a memory system whose contents cannot be read back without its own toolchain is fragile.

## 11. Governed skill contract

The `ai-vault` validator runs seven checks, and they constrain the skill in ways that happen to enforce the relative-path requirement mechanically.

| Check | Requirement |
| :--- | :--- |
| Skill metadata | Frontmatter at line 1, valid YAML, name equal to directory, description under 1024 characters, quoted three-part version |
| Skill sizes | At most 500 lines per skill file |
| Relative links | Every markdown link must resolve relative to its file |
| Code fences | Fence line count must be even |
| Tool-call leakage | No tool-call or function-call markup anywhere in the repository |
| Machine paths | No absolute user paths, no file URLs, no Homebrew paths |
| Skills manifest | Version 1, unique name equal to directory, boolean required flag, non-empty IDE list, no undeclared skill directories |

Existing skills range from 69 to 480 lines, so the 500-line ceiling is a real constraint on how much the new skill can carry. The machine-path ban means the skill cannot name the workspace location and must resolve it from an environment variable.

## 12. Production recency ordering

The first production load materialised 2,607 turn documents, 242 artifact documents, and 11 dross documents. Facet and dataset-scope verification passed, but the first `recent --limit 3` query returned turn sequence values 2311, 336, and 47. The emitted turn tree's three greatest sequence values were 2857, 2856, and 2855.

The mismatch was caused by directory traversal order. Directory ingestion assigned SQLite identifiers in the order returned by filesystem recursion, while the query selected a limited candidate set by descending identifier before sorting that incomplete set by sequence. The global sort therefore could not recover turns excluded by the identifier-based preselection.

Two independent safeguards are required. Numeric sequence from the canonical `turn-<sequence>.md` filename is the authoritative query ordering key, so correctness does not depend on insertion order. Directory enumeration and memory ingestion are also made deterministic, with canonical memory files inserted by ascending numeric sequence, so SQLite identifiers become sequence-aligned after rebuild and remain useful for inspection.

The production-only backfill baseline excludes five synthetic source-pattern fixtures that were isolated after the initial dry run: 20 discovered source files, 9 whole-file duplicate-hash skips, 5,371 raw turns, 2,607 unique turns, and 2,764 duplicate turns. Artifact accounting remains 552 raw, 238 unique, and 314 duplicate entries; dross remains 28 raw, 11 unique, and 17 duplicate entries; 10 spills remain split into 4 unique and 6 duplicate spill artifacts.
