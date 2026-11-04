# Plan: Re-rank Enhancement, Tranche 2 (baseline, owner inputs, neighbors, warm-up)

Status: proposal, written in PLAN mode on 2026-11-04. Nothing in it is started. Execution needs the owner's approval of this plan. Any deviation from it returns to PLAN mode.

Related documents: [rerank_enhacement.md](rerank_enhacement.md) (findings and the full checklist), [rerank_enhacement_plan.md](rerank_enhacement_plan.md) (tranche 1, done), [comparison.md](comparison.md).

**Scope.** The three steps the owner asked to prepare:
1. **Re-measure the four tasks on today's data** (a frozen snapshot, with a cold-call and cached-call latency split).
2. **Prepare the owner inputs**: review packs for ledgers C and D (R02), a proposed list of new ledger tasks (R03), the budget questions (R10), and a decision memo on how the service starts (R49).
3. **Tranche 2 build**: neighbor turns (H8) and the service warm-up.

Chunking, query side, source mix, cascade and fallback stay in later tranches.

## 0. Corrections to earlier statements

These came from reading the code on 2026-11-04.

| Earlier statement | Correct fact | Source |
|---|---|---|
| R46 "cache scores" is open | The service already caches scores for 14 days, keyed by model id, query hash and passage hash. The semantic suite tests it ("the second call is served from the score cache") | `service/ai_memory_service/store.py` `cache_get`, `cache_put`; `config.py` `score_cache_days` |
| `purge_if_due` might unload idle models | It deletes score-cache rows older than `score_cache_days`, at most once per hour. Models stay loaded until the service stops | `server.py` `purge_if_due` |
| Latency repeats in the harness measure the service | Each harness run starts a service with an empty cache. Repeat 1 is uncached and repeats 2 to 5 are cache hits. The p50 therefore understates cold calls, and only the first call (and the p95 of five) is a real uncached measurement. This was not measured directly. It follows from the code | `bench/common.py` `run_repeated`; `server.py` rerank path |
| The Polish ArgoCD regression and the C and D drift might be a model or data fault | The neighbor finding explains the regression (tranche 1, section 10.4). The drift is probably normal re-ingest of changed worklogs: document IDs in `worklogs.db` moved while paths and order stayed the same. This is a hypothesis until step 10 | tranche 1 results |
| `--help` is safe on every command | It runs the command for `sweep-memory`, `chatlog`, `ingest-chatlog`, `reconcile-transcripts`, `project-journal`, `reconcile-project`, `09` and `decompress` | wrapper check on 2026-11-04 |

## 1. Specification

### 1.1 Step 1: baseline on a frozen snapshot

- **Snapshot copy.** New file `bench/snapshot_copy.py`. It copies `convo.db`, `worklogs.db` and `artifacts.db` with the SQLite backup API (consistent, safe while the engine is idle) into `data/eval/snapshots/<yyyymmdd>/` (git-ignored through `data/eval/`), writes a `manifest.json` with the content fingerprints of `bench/snapshot.py`, and refuses to overwrite an existing folder. About 340 MB per snapshot.
- **Data folder switch.** `bench/common.py`: `run_engine` adds `--data-dir <folder>` when the environment variable `BENCH_DATA_DIR` is set, and `connection()` opens the databases from the same folder. `prompt.log` is not part of the snapshot (it is append-only and the cutoffs fix the range). The report records which data folder it used.
- **Latency protocol.** `bench/common.py` `run_repeated` stores the first call as `cold_wall` and the other calls as `cached_walls`. Reports show `cold_wall` and the p50 and p95 of the cached calls separately. Gate 3 (degraded rate and p95) uses cold calls only.
- **Runs on the snapshot:** `three_way.py` with `--repeats 5 --budget-ms 1200` for the default (weight 12) and with `--engine-args "--rerank-weight 5"`; `model_bench.py` with systems `lexical,mmarco`, settings `default,rrf:5`, depth 30, one repeat, head evidence. Each runs twice to prove determinism on the snapshot.
- **Drift check (read-only).** List the `ingestion_manifest` rows of the three databases and the sweep state (`SweepState`) to find when each archive was last ingested and how many documents were replaced. State the cause, or state "cause not determined" with the evidence tried.
- **Documents.** `comparison.md` gets a new section "Re-measurement on the frozen snapshot of <date>" with the four-task tables in the layout of the score summary and the cold and cached latency table. The earlier sections stay as they are. `rerank_enhacement.md` section 10 gets the new baseline and the corrections of section 0.

### 1.2 Step 2: owner inputs

**R02: review packs for ledgers C and D.**
- New file `bench/ledger_review.py` (tracked, holds no ledger text). It reads one task file. For each fact it finds the first line of each listed source where all anchor groups match, and prints the line number and one excerpt line. It also prints the number of turns before the freeze that match, flags facts with no source match, and marks which facts are in the strict set.
- Task files get an optional field `review_sources` (a list of paths). `task_c.py`: `worklog-chat/ticket-state/kd6945.md` and `worklog/2026-06-11_KD-6945.log`. `task_d.py`: `worklog-chat/ticket-state/kd8130.md` and `worklog/2026-10-30_KD-8130.log`.
- Output: `data/eval/ledger-run/review/task_c.md` and `task_d.md` (git-ignored).
- Questions for the owner per fact: keep, change weight, change or add an anchor, or drop. Also: should the non-strict facts (C01, C17, C18, D03, D14, D20) get strict anchors?

**R03: proposed new ledger tasks.** Facts from `convo.db`, `worklogs.db` (statistics gathered on 2026-11-04):

| Group | Ticket | Turns | Worklog KB | Why |
|---|---|---:|---:|---|
| Long | DEVOPS-849 | 461 | 119 | Largest ticket after KD-6945 |
| Long | KD-6919 | 414 | 66 | Long, different team |
| Long | CWP-11649 | 202 | 101 | Long, older (July to September) |
| Long | SCTASK1280900 | 191 | 98 | Spans May to October |
| Medium | SCTASK1759003 | 123 | 50 | Active |
| Medium | KD-7242 | 107 | 37 | Closed (September) |
| Medium | KD-7136 | 71 | 15 | Medium length, small worklog |
| Short | KD-7172 | 36 | 21 | Short with a capsule |
| Short | SCTASK1541060 | 31 | 44 | Short turns, large worklog |
| Short | KD-6863 | 31 | 42 | Short with a capsule |
| Short | DEVOPS-796 | 21 | 16 | Has a September manual reference |
| Topic | `nri-flex` (no ticket) | not counted | n/a | Only if a source for the ledger exists (the Cursor answers are redacted). Skip otherwise |

KD-6999 and KD-7289 are left out on purpose: they share the topic of task B. Together with tasks A to D this gives 15 or 16 tasks.
- Three queries per new task: a ticket-key question (`summary of <KEY>`), a topical keyword query of 3 to 4 words, and a Polish paraphrase. That is 33 new queries and 43 in total.
- New file `bench/candidates.py` produces the statistics table above from the databases (local output), so the list can be refreshed.
- Ledger drafting rules (same as tasks C and D): freeze point is the capsule's `authoritative_through` (or the last ACTION LOG date); turn cutoff from `convo.db`; journal cutoff from the freeze point; 16 to 20 facts with weights 3, 2 and 1; strict anchors checked against the source (all match) and against 3,000 unrelated turns (at most 1% false matches); at least 70% of the strict weight must occur in some turn, otherwise the fact is marked "source only". Each new task gets a review pack (as for R02).
- Only adjudicated tasks count in the gates. Others are reported as "unadjudicated".

**R10: questions for the owner.** (a) Is 1.2 s still the budget for the re-rank call? (b) Must CPU-only machines meet it (mmarco needs 0.6 s for 30 passages on CPU, so yes at depth 30)? (c) Is a loss of 3 points on one task acceptable when the mean rises 5 or more? If there is no answer, the defaults of tranche 1 apply.

**R49: decision memo on starting the service.** Facts: the service holds mmarco in memory (about 1.1 to 1.3 GB peak) until it stops; the model loads in 3 to 6 s; the first uncached call after a start took 2.9 to 5.5 s in tranche 1; files are in `.ai-worklog/run/` with 0600 mode; `service/ai-memory-service status`, `stop` and `doctor` exist; nothing starts the service today, so `--rerank auto` falls back to lexical order unless the service runs.

| Option | How it works | Pro | Con |
|---|---|---|---|
| A. Manual (today) | The user runs `serve` | No new risk | Re-ranking is off in daily use |
| B. Engine auto-start | `query` starts a detached service when the socket is missing | Needs no user action | The first query degrades; a background process starts without the user asking; two parallel starts need a lock; memory stays used |
| C. macOS LaunchAgent | A template in `service/launchd/` that the user installs with `launchctl` | Always up after login | 1.3 GB resident all day; installed outside the repository by the user |
| D. Start from `sweep-memory` | The existing session-start command starts the service when `AI_MEMORY_SERVICE_AUTOSTART=1` | Fits the rule that every session starts with `sweep-memory` | Starts only when a session starts; the model load runs in the background |

Recommendation: D, opt-in by an environment variable, with C as the alternative for an always-on service. B is not recommended. This is the owner's decision (R49). No code for it is written in this tranche.

### 1.3 Step 3a: neighbor turns (H8)

Current behavior: `lib/MemoryRetrieval.groovy` calls `NeighborExpansion.expand(fused, dataDir, allowMeta, 1)` after re-ranking. For every chat-turn row it inserts the previous and next turn directly after it (rows get `pool = 'neighbor'`), then keeps the first `limit × 2` rows. Neighbors are never scored. On the benchmark they take top-10 slots: engine order reaches 44.0 and model order 47.4.

New setting: neighbor mode, default `adjacent` (today's behavior, unchanged).
- `lib/Config.groovy`: add `NEIGHBOR_MODES` (`adjacent`, `after`, `scored`, `off`), `NEIGHBOR_MODE_DEFAULT` (`adjacent`), `NEIGHBOR_ANCHORS_DEFAULT` (10), `neighborMode()` (property `ai.memory.neighbors`, environment `AI_MEMORY_NEIGHBORS`, unknown values give the default) and `neighborAnchors()` (environment `AI_MEMORY_NEIGHBOR_ANCHORS`, 1 to 30, otherwise the default). Same pattern as `rerankWeight()`.
- `03_query_memory.groovy`: option `--neighbors adjacent|after|scored|off` with the usual error text (exit 2) and a help line; the value goes into the options map as `neighbors`. The `--explain` output gets one line `Neighbors: mode=<mode> added=<n> scored=<k>`.
- `lib/NeighborExpansion.groovy` `expand`: two optional parameters with defaults that keep today's behavior: `Integer maxAnchors = null` (expand only the first `maxAnchors` chat-turn rows) and `boolean neighborsOnly = false` (return only the neighbor rows).
- `lib/MemoryRetrieval.groovy` resolves the mode once per search (option first, then `Config`), then:
  - `adjacent`: no change.
  - `off`: radius 0, so no neighbor rows.
  - `after`: expand as today, then move all rows with `pool == 'neighbor'` behind all other rows, keeping the relative order of each group. Filters and the `limit × 2` cut run afterwards, so neighbors are cut first.
  - `scored`: only when re-ranking runs. Before the re-rank, take the neighbors of the first `neighborAnchors` chat anchors of the head (`neighborsOnly`) and append them to the head rows, so they are hydrated and scored in the same service call. After the re-rank, skip the post-re-rank expansion. If the re-rank is degraded, or not wanted, drop the pre-expanded neighbors and take the normal `adjacent` path, so the output equals `adjacent` mode.
- `lib/RerankStage.groovy` `apply`: for rows with `pool == 'neighbor'` select at most 1 passage (`PassageBuilder.select` with `max` 1). Head rows keep 3. Neighbors come after the head rows, so the 100-passage cap cuts neighbor passages first. Rows that are not scored pass through. The info map gets `neighbors_scored`.
- Result map: `neighbors: [mode, added, scored]`.
- Benchmark: `bench/model_bench.py` `parse_settings` accepts `neighbors=<mode>` tokens, combined with a fusion token by `+` (for example `rrf:12+neighbors=after`), and passes `--neighbors`.
- Decision rule, run on the frozen snapshot with the full adjudicated benchmark (or the 10-query benchmark, marked provisional, if fewer than 14 tasks are adjudicated): compare `after`, `scored` and `off` with `adjacent` on cov@10 and precision@10 in engine order. A mode wins when it passes the gates of section 6 of `rerank_enhacement.md` against `adjacent` and raises mean cov@10 by at least 1.7 points (half of the known 3.4-point gap). Ties and no winner keep `adjacent`. A new default needs the owner's approval, as in tranche 1.

### 1.4 Step 3b: warm-up

Current behavior: `service/ai_memory_service/server.py` `App.warm` scores one pair ("warm up") with the default model, in a background thread started by `__main__.py` after the service listens. The first uncached call after a start took 2.9 to 5.5 s in tranche 1, against 1.3 s later.

- Measure first. New file `bench/cold_start.py`: for N fresh services (default 10), wait for the model to load, send the first request with 30 passages of realistic length and a query never seen before (so the cache cannot help), then four more distinct queries. Report the service-side `elapsed_ms` and the wall time of each call, and whether the first call was degraded. The script can run with warm-up shapes switched off to give the "before" figure.
- Config (`service/ai_memory_service/config.py`): `warm_shapes`, a list of word counts, default `[80, 160, 200]`; `warm_batch` defaults to the existing `batch_size` (16). `LIMITS` clamp the list to at most 5 shapes of 1 to 400 words. An empty list keeps today's one-pair warm-up.
- `App.warm`: after loading the model, score, for each shape, one batch of `warm_batch` synthetic passages of that many words, built deterministically from a fixed word list in the code (no user data), through the backend directly so nothing is written to the score cache. A failure leaves `warmed` false and never stops the service.
- `/v1/health` gets `warmed` (true after the warm-up) and `warm_ms`. `docs/semantic-retrieval/service-contract.md` is updated.
- Acceptance: on 10 fresh starts, the first uncached query completes inside the 1.2 s budget (not degraded) in at least 9. If it does not, the next measurement tests fixed-length padding. That is a separate decision and is not built here.
- Tests: `tests/service/test_server.py` (stub backend counts the calls): warm-up scores one batch per shape; the cache stays empty after warm-up; `warmed` is false before and true after; a failing backend leaves `warmed` false and the service running; an empty list scores one pair; invalid values are clamped. `tests/service/test_protocol_store.py` only if the health schema is checked there.

## 2. Regression scope

Must not change with default settings: strict and relaxed query, filters (`-A`, `-e`), ticket facets and capsule pins, `adjacent` neighbor behavior, late pool, degraded reasons, fusion options and weight 12, JSON fields, evaluation systems S0 to S6, the service contract (new fields only), the wrapper and its flags.

Suites: `test_query_plan_suite` (119), `test_semantic_suite` (125), `test_query_suite` (74), `test_retrieval_suite` (31), `test_eval_suite` (27), `test_chatlog_suite` (100), `test_journal_suite` (25), `test_worklog_suite` (42), `test_reconciliation_suite` (33), `test_shadow_validation_suite` (44), `test_memory_lock_suite` (36), and the Python service tests under three interpreters (78). New tests only add to the counts.

Golden outputs: 12 queries run through the previous jar and the new jar back to back on the same data (the same method as the wrapper change). With defaults they must be identical except for the new explain line `Neighbors: ...`.

## IMPLEMENTATION CHECKLIST

**Phase 0: baseline on a frozen snapshot (step 1 of the request)**
1. [ ] Run every Groovy suite and the Python service tests and save the pass counts. Stop if any suite fails.
2. [ ] Save the current jar (`git show HEAD:dist/ai-memory-ingester.jar`) outside the repository for the later golden comparison.
3. [ ] Create `bench/snapshot_copy.py` as in section 1.1.
4. [ ] Add the `BENCH_DATA_DIR` switch to `run_engine` and `connection()` in `bench/common.py`, and record the data folder in every report.
5. [ ] Take the snapshot. Run the 12 golden queries against the live data and the snapshot: identical output.
6. [ ] Change `run_repeated` for the cold and cached split and update the CSV and JSON output.
7. [ ] Run `three_way.py` on the snapshot for weight 12 and for weight 5 (repeats 5, budget 1200).
8. [ ] Run `model_bench.py` on the snapshot (lexical, mmarco, settings `default,rrf:5`, depth 30).
9. [ ] Repeat steps 7 and 8 once and confirm identical coverage fields.
10. [ ] Run the read-only drift check and state the cause or "cause not determined".
11. [ ] Add the re-measurement section to `docs/comparison.md` without changing earlier sections.
12. [ ] Update `docs/rerank_enhacement.md` section 10 with the new baseline and the corrections of section 0.

**Phase 1: owner inputs (step 2 of the request; can run in parallel with phase 2)**
13. [ ] Create `bench/ledger_review.py` and add `review_sources` to `task_c.py` and `task_d.py`.
14. [ ] Generate the review packs for C and D and check that every fact has a source match or is flagged.
15. [ ] Hand the packs to the owner (R02). Record the owner's changes in the task files.
16. [ ] Create `bench/candidates.py` and refresh the candidate table.
17. [ ] The owner approves or edits the list of new tasks (R03).
18. [ ] For each approved task: freeze point, cutoffs, 16 to 20 facts, strict anchors, anchor validation, 3 queries, task file, review pack.
19. [ ] The owner reviews the new packs. Mark each task adjudicated or unadjudicated.
20. [ ] Send the R10 questions to the owner. If there is no answer, record that the defaults apply.
21. [ ] Deliver the R49 memo (section 1.2) to the owner. Record the choice. Write no code for it in this tranche.

**Phase 2: neighbor turns (step 3 of the request)**
22. [ ] Add the neighbor settings to `lib/Config.groovy` (section 1.3).
23. [ ] Add `maxAnchors` and `neighborsOnly` to `NeighborExpansion.expand` with defaults that keep today's behavior.
24. [ ] Implement the four modes in `lib/MemoryRetrieval.groovy`, including the fallback of `scored` to `adjacent` when the re-rank is degraded or not wanted.
25. [ ] Change `RerankStage.apply` for neighbor rows (1 passage each, after the head rows) and add `neighbors_scored` to the info map.
26. [ ] Add `--neighbors`, the help line and the `Neighbors:` explain line to `03_query_memory.groovy`.
27. [ ] Check that `tests/SemanticFixtureBuilder.groovy` has consecutive chat turns. Add them if it has none.
28. [ ] Add tests to `tests/test_semantic_suite.groovy`: `adjacent` equals the previous order; `after` puts every neighbor row behind every other row and keeps each group's order; `off` has no neighbor rows; `scored` gives neighbor rows a model score and respects the passage cap; degraded re-rank in `scored` mode equals `adjacent`; unknown mode falls back to `adjacent`; settings and options precedence.
29. [ ] Add option parsing and help tests to `tests/test_query_suite.groovy`.
30. [ ] Add `neighbors=<mode>` settings to `bench/model_bench.py`.
31. [ ] Rebuild the jar with `build-precompiled.sh`.
32. [ ] Run every suite and compare the counts with step 1. Run the 12 golden queries through the previous and the new jar: identical except for the new explain line.
33. [ ] Run the neighbor sweep (`after`, `scored`, `off` against `adjacent`) on the snapshot and compute the paired statistics.
34. [ ] Apply the decision rule and write the table and the decision into `docs/rerank_enhacement.md`.
35. [ ] If a mode wins, ask the owner to approve it as the default. Change no default before the approval.

**Phase 3: warm-up**
36. [ ] Create `bench/cold_start.py` and measure the "before" figure with warm-up shapes switched off.
37. [ ] Add `warm_shapes` and `warm_batch` with limits to `service/ai_memory_service/config.py`.
38. [ ] Implement the multi-shape warm-up in `App.warm` in `server.py`.
39. [ ] Add `warmed` and `warm_ms` to the `/v1/health` reply and update `docs/semantic-retrieval/service-contract.md`.
40. [ ] Add the tests of section 1.4 to `tests/service/test_server.py`.
41. [ ] Run the Python service tests under all three interpreters.
42. [ ] Run `bench/cold_start.py` "after". Apply the acceptance rule (at least 9 of 10 first queries inside the budget) and record the result. If it fails, record the figures and do not build further.

**Close**
43. [ ] Run every suite and the golden comparison once more. Confirm that `git status` shows no ledger or internal text as tracked.
44. [ ] Update `docs/rerank_enhacement.md` (results, status of H8, the warm-up and R02, R03, R10, R49) and `docs/rerank_enhacement_plan_tranche2.md` (status of each step).
45. [ ] Run `repos/ai-vault/scripts/commit_handoff.py render` once for `ai-memory-ingester` with only the files written in this tranche (include the rebuilt jar).
46. [ ] Record the turn through the governed worklog writer.
47. [ ] Open the next plan (chunking, query side, source mix) only after steps 34 and 42, using their numbers.

Any deviation from this plan returns to PLAN mode.

---

Created using Anthropic Claude. Keep this internal until a person has reviewed and verified it. This plan is a proposal and should stay on internal versions until a human has reviewed it. It refers to internal tickets: share it only with people allowed to see them.
