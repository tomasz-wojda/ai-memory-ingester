# Implementation Checklist: Worklog-Aware Memory Upgrade

Status: worklog-aware upgrade and cross-source freshness correction implemented; fresh-main-conversation rule expansion remains a manual acceptance check.

Governing specification: [worklog-aware-memory-upgrade-specification.md](worklog-aware-memory-upgrade-specification.md).

Supporting measurements: [worklog-aware-memory-upgrade-evidence.md](worklog-aware-memory-upgrade-evidence.md).

---

## Preconditions

Execution is allowed only under `MODE: EXECUTE`.

The implementation must preserve `prompt.log`, existing materialized turns, the three existing databases, and all current datasets until replacement databases have been verified.

Worklog parsing and state capsules must be extractive and provenance-preserving. No generated interpretation may be persisted as authoritative text.

No commit and no push may be performed.

## IMPLEMENTATION CHECKLIST

1. Record the current turn, artifact, dross, database, dataset, and digest counts so the upgrade has a rollback and reconciliation baseline.

2. Add `worklogs.db`, worklog archive names, worklog output paths, worklog state path, chunk-size target, ticket-context budget, category names, authority values, and dataset constants to `lib/Config.groovy` without changing existing values.

3. Implement `lib/WorklogParser.groovy` with structured discovery, ticket resolution, section splitting, category classification, source-order preservation, and lossless large-section subdivision.

4. Implement `lib/WorklogDocument.groovy` with fixed-order front matter, category and authority normalization, content hashing, source provenance, chunk rendering, and evidence rendering.

5. Implement `lib/TicketStateCapsule.groovy` with deterministic extraction of summary, locked decisions, latest delivery reconciliation, latest status, blockers, next actions, repository references, and exact source ranges.

6. Implement `lib/WorklogEmitter.groovy` with state loading, temporary staging, unchanged-source skipping, atomic source replacement, stale-output deletion, multi-source ticket capsule regeneration, and state persistence.

7. Implement `06_ingest_worklogs.groovy` with `--all`, repeatable `--source`, `--changed`, `--workspace-root`, `--dry-run`, and `--sync-db`.

8. Route structured worklogs to `worklog-chat/worklogs`, capsules to `worklog-chat/ticket-state`, and `_raw.log` or unstructured companions to `worklog-chat/artifacts` as `worklog:evidence`.

9. Add per-source and aggregate reporting for discovered, unchanged, changed, structured, evidence, emitted, replaced, deleted, and failed counts, with dry-run guaranteed to write nothing.

10. Register the worklog modules and worklog commands in `run.groovy` and `PrecompiledRunner.groovy` in dependency order.

11. Add `worklogs.db`; create `worklog:state` and `worklog:chunks`; reconcile `worklog-chat`, `worklog-chat-turns`, `worklog-chat-authoritative`, and `worklog-chat-all`; retain `worklog-chat` as active.

12. Extend `tests/TestFixtureGenerator.groovy` with structured, revised, superseded, oversized, multi-source, raw-evidence, and stale-turn worklog fixtures.

13. Create parser and emitter tests proving ticket discovery, complete line accounting, section categorization, chunk subdivision, deterministic paths, hash identity, unchanged skips, atomic replacement, and stale deletion.

14. Update `03_query_memory.groovy` to recognize and normalize exact ticket keys before FTS parsing, making `KD-6999`, `kd-6999`, and `kd6999` equivalent and preventing hyphenated ticket text from reaching FTS5.

15. Add non-interactive `ticket <key> [terms]`, `context --ticket <key> [terms]`, and interactive `:ticket <key> [terms]` commands with existing dataset, database, JSON, limit, date, mode, impact, and budget compatibility.

16. Implement `lib/TicketRetrieval.groovy` with independent state, authoritative, recent-turn, high-impact, and semantic candidate collectors.

17. Apply the fixed twenty-result allocation of one state capsule, four authoritative chunks, five recent turns, five high-impact turns, and five semantic matches.

18. For ticket-only queries, bypass BM25 and fill unused semantic capacity with additional authoritative chunks followed by recent turns.

19. Implement deterministic duplicate suppression, category priority, source-authority ordering, current-versus-historical classification, and conflict resolution.

20. Implement complete-document ticket context assembly using the 22,000-byte budget and the specified state, status, next-action, delivery, decision, recent, impact, and semantic priority.

21. Replace front-matter-first previews with body-first previews for facet-only results and match-centered previews for semantic results, retaining the existing JSON `snippet` field.

22. Create `tests/test_worklog_suite.groovy` covering worklog ingestion, capsules, databases, datasets, ticket normalization, candidate quotas, semantic and facet-only behavior, stale-state precedence, preview quality, context budget, and JSON output.

23. Implement `07_sweep_memory.groovy` and `sweep-memory` to run incremental chatlog transformation, changed-worklog transformation, changed-tier database synchronization, dataset reconciliation, and final digest generation.

24. Ensure no-change sweeps rebuild no databases and report zero emitted, replaced, or deleted documents.

25. Update `lib/ResumeDigest.groovy` to prepend a dominant ticket’s state capsule, current status, and next action while preserving the existing behavior when no ticket dominates.

26. Update `build-precompiled.sh` to compile scripts 06 and 07, rebuild the application jar, and verify command parity between interpreted and precompiled runners.

27. Update `.cursor/rules/worklog-chat-memory.mdc` so session start invokes `sweep-memory`, while retaining the journal append and consulted-memory disclosure obligations.

28. Update `skills/worklog-chat-memory/SKILL.md` with worklog database recipes, bare-ticket normalization, hybrid retrieval, worklog facet discovery, authority handling, and complete-context retrieval.

29. Run the AI Vault validator and require all seven checks to pass.

30. Execute a full worklog dry run and record structured source, evidence source, chunk, capsule, replacement, deletion, and duplicate counts before writing production outputs.

31. Backfill worklog chunks and capsules, synchronize `worklogs.db` and worklog evidence, reconcile all dataset members, and verify database counts against materialized files.

32. Run the existing chatlog suite unchanged and require all 75 tests to pass; run the existing query suite unchanged and require all 63 tests to pass; run the complete new worklog suite with no failures.

33. Execute the KD-6999 production acceptance query and confirm the first twenty results expose all ten required current facts, identify stale image 24-8 and PR 224 as historical, show no front-matter-only preview, and produce context within 22,000 bytes.

34. Modify a synthetic worklog, run the incremental sweep, and prove that obsolete chunks disappear, replacement chunks and the capsule appear atomically, affected databases synchronize, and an immediate no-change rerun performs no work.

35. Delete and rebuild `worklogs.db` from materialized worklog files, prove deterministic document ordering and count reconciliation, and confirm the KD-6999 answer remains unchanged.

36. Measure precompiled ticket retrieval as the minimum of three complete process runs and require it to remain below one second; record direct SQLite retrieval time separately.

37. Start a fresh main Agent conversation and verify that the regenerated digest and updated always-applied rule provide the dominant ticket’s authoritative current state without a manual file read.

38. Append the final implementation outcome, measured counts, regressions, acceptance results, and any unresolved limitations to `prompt.log`.

39. Provide semantic commit titles and descriptions for `ai-memory-ingester` and `ai-vault` without committing or pushing.

## Exit criteria

The upgrade is complete only when structured worklogs and state capsules are materialized and rebuildable; stale worklog outputs are removed safely; worklogs.db and all datasets reconcile; ticket normalization prevents hyphen-related FTS failures; ticket retrieval follows fixed candidate quotas and source precedence; previews expose useful body content; context remains byte-bounded; KD-6999 returns all required current facts before stale history; session sweep synchronizes every changed layer; existing and new tests pass; the skill validator passes; a fresh main Agent conversation receives authoritative digest context; and no commit or push has occurred.

## Cross-source freshness completion

1. Baseline captured the incorrect KD-7289 order with turn 2747 before September turns and an August worklog labelled current.
2. Freshness values and explicit `blockers` and `related-ticket` categories were added.
3. `TicketFreshness` now derives authoritative and evidence timestamps without promoting turns to authority.
4. Capsule selection begins with the true ticket summary, preserves related tickets separately, and includes explicit blockers.
5. Stale warnings record exact authoritative-through, newer-evidence-through, and evidence paths.
6. Parser version 2 migrated all 49 sources, replaced 428 paths, removed 14 obsolete paths, retained 433 chunks, and regenerated 40 capsules.
7. Worklog ingestion can refresh capsule state independently and avoids chunk rebuilds when only freshness changes.
8. Unified sweep defers digest creation until all tiers are synchronized and performs no writes on a true no-change run.
9. Ticket turns are ordered by timestamp confidence and event time before sequence.
10. Stale ticket retrieval places newer operational evidence before stale authoritative sections.
11. CLI, JSON, context, and resume artifacts expose freshness.
12. The worklog suite passes 39 tests, including stale/fresh transitions, section selection, ordering, pool precedence, digest warning order, context budget, and refresh idempotency.
13. Existing chatlog tests pass 75/75, query tests pass 63/63, and AI Vault validation passes 7/7.
14. KD-7289 reports authoritative-through 2026-08-21 and newer evidence through 2026-09-02; turn 2747 is absent from the first twenty.
15. KD-6999 was re-evaluated and is legitimately stale because two September 3 operational turns postdate its September 2 worklog.
16. Three precompiled KD-7289 retrieval runs measured 0.90, 0.79, and 0.82 seconds.
17. No commit or push was performed by this implementation.
