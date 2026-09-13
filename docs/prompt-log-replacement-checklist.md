# Implementation Checklist: `prompt.log` Replacement, Phase 1

Status: Phase 1 implementation complete; fresh main-conversation rule expansion remains a manual acceptance check.
Governing specification: [prompt-log-replacement-specification.md](prompt-log-replacement-specification.md).
Supporting measurements: [prompt-log-replacement-evidence.md](prompt-log-replacement-evidence.md).

---

## 1. Preconditions

This checklist is executed only under `MODE: EXECUTE`. Nothing outside the steps below may be implemented. If any step cannot be completed as written, work stops and the plan is revised rather than adapted in place.

Phase 1 covers backfill, the incremental sweep, the resume digest, the always-applied rule, and retrieval. Live per-turn file emission and the enforcement hook belong to Phase 2 and must not be attempted here.

Three locations are touched. Steps 1 through 33 apply to `ai-memory-ingester`. Steps 34 and 35 apply to the workspace rules directory. Steps 36 through 39 apply to `ai-vault`. Step 40 applies to the workspace journal.

Regression testing is mandatory because four existing files are modified. Steps 23 through 25 are not optional.

No commit and no push is performed at any point. Semantic commit titles and descriptions are reported instead.

Execution checkpoint: parser correction and production backfill completed through step 30. The expanded chatlog suite passed 68 tests and the unchanged query suite passed all 63 tests. The production-only baseline is 20 discovered sources and 9 whole-file hash skips: 5,371 raw turns reconcile to 2,607 unique and 2,764 duplicate turns; 552 raw artifacts reconcile to 238 unique and 314 duplicate artifacts; 28 raw dross entries reconcile to 11 unique and 17 duplicates; 10 spills reconcile to 4 unique and 6 duplicate spill artifacts. Synthetic source-pattern fixtures were isolated and their five contaminated outputs were removed before database loading. Production databases contain 2,607 turns, 242 artifacts including spill artifacts, and 11 dross documents; datasets, facets, and dross scoping validate. Step 30 failed only because identifier-based preselection returned sequences 2311, 336, and 47 instead of 2857, 2856, and 2855. Steps 17, 18, 20 through 24, 30, and 31 are reopened for the approved dual sequence-order amendment; steps 25 and 32 through 40 remain pending.

Completion checkpoint: the sequence amendment is implemented and the expanded suite passes 75 tests; the unchanged query suite passes all 63 tests; every legacy command, alias, colon command, and flag completed successfully. Rebuild and post-sweep synchronization leave 2,608 turn documents, 244 artifact documents, and 11 dross documents, with zero sequence inversions by identifier and recent sequence 2859 returned first. The incremental sweep emitted exactly one synthetic turn and regenerated the digest with its token. The precompiled launcher records a 760 millisecond minimum against the 2,071 millisecond baseline. The always-applied rule and governed skill are present, and the skill validator passes all seven checks. A fresh subagent confirmed the rule body but cannot validate referenced-file expansion because subagents receive clean context; the digest expansion check must be performed in a fresh main Agent conversation. No hook configuration is present, so organization-level hook permission remains unknown and no Phase 2 hook was attempted.

## 2. Checklist

1. Add the new constants to `lib/Config.groovy`: the three database names `convo.db`, `artifacts.db`, and `dross.db`; the three dataset names; `CHAT_SPILL_BYTES` of 8192; `RESUME_BUDGET_BYTES` of 22000; `RESUME_RECENT_TURNS` of 12; the dross allowlist; the impact label sets; the `worklog-chat` output root name; the digest and sweep-state file names; and the `workspaceRoot` resolver, leaving all existing constants unmodified.

2. Implement `lib/TimestampRepair.groovy` with `parse`, `interpolate`, and `zoneFor`, covering all twenty observed formats and the four confidence levels.

3. Implement `lib/ChatLogParser.groovy` with `parseFile`, `splitRegions`, `resolveFenceImbalance`, `classifyRegion`, `extractLabelledSections`, and `extractTicketKeys`, applying the six-dialect precedence order and the complete stateful boundary matrix from specification section 11.1. Enforce one logical user side and one logical assistant side per region; preserve header metadata across formatting separators; pair bracketed, unbracketed, and qualified role forms; pair date-led `PROMPT INPUT` and `PROMPT OUTPUT`; support `INPUT` and `OUTPUT`; and reject date-leading filenames and timeline events as boundaries.

4. Implement `lib/FacetPath.groovy` with `build`, `sanitizeToken`, `ticketFacet`, `dateFacet`, `modeFacet`, `impactFacet`, and `fileNameFor`, emitting paths rooted at the tier subtree and guaranteeing ASCII-alphanumeric facet tokens.

5. Implement `lib/TurnClassifier.groovy` with `classify`, `impactOf`, and `isDross`, using only the explicit allowlist and label sets.

6. Implement `lib/TurnDocument.groovy` with `render`, `normalizeForHash`, `hash`, and `abstractOf`, emitting front matter in the fixed field order.

7. Implement `lib/TurnEmitter.groovy` with `loadHashIndex`, `emit`, `recordDuplicate`, and `saveHashIndex`, writing turn, artifact, and dross files and maintaining the hash index as the deduplication authority.

8. Implement `lib/SweepState.groovy` with `load`, `offsetFor`, `record`, `save`, and `needsFullRescan`, persisting per-source byte offset, byte length, and processed-prefix hash, and reporting a required full re-scan when a source has shrunk or its prefix hash no longer matches.

9. Implement `lib/ResumeDigest.groovy` with `assemble`, `render`, and `write`, reusing the curated assembly and byte budget of specification section 15 and emitting a generation timestamp, summarised turn count, and budget consumed.

10. Register the eight new library modules in the load-order list in `run.groovy`, after `MemoryEngine` and before `DatasetRegistry`.

11. Implement `05_ingest_chatlog.groovy` as a transformer: option parsing, workspace-root resolution, suffix-based prompt-log and exact `PR.log` source discovery, descending-size processing order, whole-file hash skip, per-entry parse, timestamp repair, classification, hash deduplication, spill handling above the threshold, file emission into the three subtrees, and a stdout report with a dry-run mode that emits nothing. The report must separate raw classified, unique emitted, and duplicate counts by tier, report spills independently, and verify the per-tier accounting invariants.

12. Add the `--since-last` mode to `05_ingest_chatlog.groovy`, resuming each source from its recorded byte offset, performing a full re-scan when truncation or prefix-hash mismatch is detected, and updating sweep state only after successful emission.

13. Regenerate the resume digest at the end of every transformer run, including every incremental sweep.

14. Add the `chatlog` and `ingest-chatlog` command-map entries to `run.groovy` pointing at `05_ingest_chatlog.groovy`, and update the usage text with a Conversation Memory section.

15. Add the `recent`, `resume`, and `context` command-map entries to `run.groovy`, and extend the `commandName` injection list with all five new commands.

16. Add the `:recent` and `:resume` colon commands, the `recent`, `resume`, and `context` subcommand handling, and the `--budget`, `--ticket`, `--mode`, `--impact`, `--since`, and `--until` flags to `03_query_memory.groovy`.

17. Implement recency retrieval in `03_query_memory.groovy` as a non-FTS selection restricted to `chat:turns`, ordered in each database by the numeric sequence extracted from the canonical filename and merged across dataset members by descending sequence, with database name, file path, and identifier as deterministic tie-breakers. Preserve all ticket, mode, impact, and date filters.

18. Implement curated resume assembly in `03_query_memory.groovy` with the fixed priority order, duplicate suppression, and budget reporting, sharing the assembly logic with `lib/ResumeDigest.groovy` rather than duplicating it. The recent tier contains only sequence-ordered `chat:turns`; artifact results may participate only in the relevance and high-impact tiers.

19. Add the `--json` output mode to `03_query_memory.groovy`, covering `query`, `recent`, `resume`, `:files`, `:doc`, `:stats`, and `:archives`, with stdout carrying only the JSON array.

20. Extend `tests/TestFixtureGenerator.groovy` with a synthetic chat-log fixture producer, and generate fixtures under `tests/fixtures/chatlog/` covering all six dialects; three- and five-equals date banners; dash date banners; exact, `START`, partial, timestamp-bearing, unmatched-open, and unmatched-close fences; bracketed inline user and prompt starts; qualified and unbracketed assistant continuations; bare, mode, ticket, and descriptive bracketed headers; bare continuation, date-mode, bracketed-mode ticket, `PROMPT INPUT`, and `PROMPT OUTPUT` headers; `INPUT`/`OUTPUT`; every qualified role alias; separator header carry-forward; date-leading non-boundaries; source-pattern files for every current producer; an oversized body; an exact duplicate file; an appended-to source for sweep resumption; a truncated source for full-rescan detection; and production-shaped memory directories whose creation, traversal, insertion, and numeric sequence orders differ, including an artifact with a newer identifier than the turns.

21. Implement `tests/test_chatlog_suite.groovy` covering compound facet token atomicity including the short-key against longer-key and bare-day collision case, facet retrieval by ticket, mode, date prefix and compound constraint, all six dialects, the complete boundary and role matrix from step 20, one-entry-per-logical-turn behavior for consecutive mixed-format entries, rejection of date-leading non-boundaries, both fence imbalance cases, every timestamp confidence level including an unexpanded shell substitution and neighbour interpolation, hash normalisation equivalence, emission idempotency, whole-file hash skip, per-tier report accounting, dross classification for each allowlist entry and non-classification of a short turn bearing a changed-files section, all three impact classes, spill routing with a resolvable pointer and non-empty abstract, recency ordering under adversarial identifiers and directory topology, exclusion of artifacts and dross from the recent tier, filtered recent ordering, resume recent-tier precedence with artifact eligibility in later tiers, sequence-aligned identifiers after deterministic rebuild, resume budget adherence, dataset scoping proving a dross-only term is unreachable under the default dataset and reachable under the forensic one, JSON output being parseable with the specified fields, sweep resumption emitting only appended content, truncation triggering a full re-scan that produces no duplicates, digest rendering staying within budget, and a live-corpus assertion that no parsed region contains multiple independent user-side starts.

22. Run `tests/test_chatlog_suite.groovy` and confirm every case passes, including the reopened sequence-order, recent-tier, deterministic-ingestion, boundary, source-pattern, report-accounting, and live-corpus assertions.

23. Run `tests/test_query_suite.groovy` unchanged and confirm it still passes.

24. Regression-test every pre-existing `run.groovy` command: `analyze` with and without a directory; `ingest` by name, with `all`, and with a directory; `query` with terms; `compress` and `decompress` with and without an archive; `datasets`; dataset use, create, add-database, remove-database, rename, and delete, together with the set aliases and hyphenated variants; `dbs`; `databases`; `archs`; `archives`; `egest`; `rename-archive`; `stream`; and `append` by text and by file. Confirm general directory ingestion is deterministic by relative path and canonical memory directory ingestion is deterministic by numeric sequence.

25. Regression-test every pre-existing colon command and flag: `:files`, `:ext`, `:doc`, `:stats`, `:archives`, `:datasets`, `:dbs`, `:help`, `:quit`, and the flags `--db`, `--data-dir`, `--dataset`, `--ext`, `--extension`, `--no-ext`, `--limit`, `--all`, `--raw`, `--lines`, and `--snippet-size`, reporting any check skipped for exceeding sixty seconds with its reason.

26. Execute the chatlog transformer across all sources in dry-run mode before any file is written. Confirm separately for turns, artifacts, and dross that raw classified entries equal unique emissions plus duplicates, with spill-created artifacts reconciled separately; confirm no parsed region contains multiple independent user-side starts; record exact raw, unique, duplicate, spill, source, and whole-file-skip counts as the Phase 1 baseline; and treat the earlier 1,900-to-2,400 raw-turn estimate as diagnostic only. Confirm that old, new, and any returning exporter snapshots create no aggregate-only false turn after constituent splitting.

27. Execute the chatlog transformer for real to emit the three subtrees, then spot-read three emitted turn files to confirm front matter order, role sections, and relative-path-only content.

28. Ingest the three subtrees: the turns subtree into `convo.db` as `chat:turns`, the artifacts subtree into `artifacts.db` as `chat:artifacts`, and the dross subtree into `dross.db` as `chat:dross`.

29. Create the `worklog-chat`, `worklog-chat-turns`, and `worklog-chat-all` datasets with their specified membership, reconciling members idempotently, and set `worklog-chat` active.

30. Verify the load: reconcile database document counts against emitted file counts, run facet queries by ticket, by mode, and by month prefix, confirm a dross-only phrase is unreachable under the active dataset and reachable under the forensic one, and compare recent results against the greatest sequences discovered from the emitted turn tree before rebuild to prove correctness independent of identifiers.

31. Re-run the transformer and confirm no new files are emitted, proving emission idempotency; then delete the three database files, re-run the three ingest commands, and confirm document counts match, proving the index is rebuildable from files. Confirm `convo.db` identifiers ascend with numeric turn sequence after deterministic ingestion and that post-rebuild recency still matches the emitted tree.

32. Verify the sweep against the live journal: append a synthetic turn to the workspace journal, run the transformer in incremental mode, and confirm exactly one new turn file is emitted, the sweep state advances, and the digest is regenerated with the new turn present.

33. Precompile the toolchain: compile `lib/` and the numbered scripts into a jar, vendor the SQLite JDBC jar, add a launcher invoking the JVM against jar plus vendored dependency, then re-measure a real query as minimum of three and record the achieved figure against the 2,071 millisecond baseline and the 21.86 millisecond search floor.

34. Author `.cursor/rules/worklog-chat-memory.mdc` beneath the workspace root, declaring itself always applied, referencing the digest file, and carrying only the journal-append obligation, the session-start sweep instruction, the requirement to name consulted memory, and a pointer to the governed skill. Confirm the `.mdc` extension, since a plain Markdown file in the rules directory is silently ignored.

35. Verify the rule takes effect by starting a fresh conversation and confirming the digest content is present without any tool invocation, and record whether project-level hooks are permitted by policy so that specification section 17.4 can be settled either way.

36. Author the `worklog-chat-memory` skill in the `ai-vault` repository with the frontmatter, constraints, and required content specified in section 17.5 of the specification.

37. Register `worklog-chat-memory` in the `ai-vault` skills manifest with `required` true and all three IDEs, preserving alphabetical entry ordering.

38. Run the `ai-vault` skill validator and confirm all seven checks pass with no findings.

39. Verify the skill end to end by following its own read recipes against the loaded databases, confirming the direct SQLite JSON invocations return rows and that facet discovery by globbing the turns tree enumerates real facets.

40. Append the outcome to the workspace journal, and report the semantic commit title and description for each affected repository without committing or pushing.

## 3. Exit criteria

Phase 1 is complete when the parser passes the complete measured boundary matrix and emits no region containing multiple independent user-side starts; dry-run raw, unique, duplicate, spill, source, and whole-file-skip counts reconcile and are recorded as the baseline; the three subtrees exist on disk; the three databases are loaded and reconcile against the emitted file counts; emission and rebuild are both proven idempotent; the incremental sweep is proven to emit appended turns and to detect truncation; the digest renders within budget and reaches context through the always-applied rule without any tool invocation; the default dataset excludes dross while the forensic dataset includes it; resume returns within budget; the full regression scope passes; the skill validator reports no findings; and the achieved post-precompilation query figure has been recorded.
