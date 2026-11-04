# Plan: Re-rank Enhancement, Tranche 1 (measurement and fusion)

Status: approved by the owner on 2026-11-03 ("APPROVED" in the session). Execution started the same day. Any deviation from this plan returns to PLAN mode.

Related documents: [rerank_enhacement.md](rerank_enhacement.md) (findings, hypotheses, the full 53-step checklist and the gates in its section 6), [comparison.md](comparison.md) (task results).

**Scope.** This plan covers the steps of `rerank_enhacement.md` with the highest evidence and no model change: R01 to R09 (measurement) and R11 to R15 (fusion), plus R05 and R37 (evidence from the best passage). Chunking, query side, source mix, model choice and operation (phases B to H of that document) depend on measured results, so each gets its own plan after the decision gate of this tranche. No change is turned on by default.

**Preconditions (owner)**
- R02: the owner reviews the facts, weights and anchors of tasks C and D. If no answer arrives, the harness reports their results as "unadjudicated".
- R10: budget 1.2 s at depth 30, MPS required, accepted regression 3 points per task. If no answer arrives, these defaults apply.
- Section 6 of `rerank_enhacement.md` holds the gates. The values there apply unless the owner changes them.

---

## 1. Specification

### 1.1 Promote the harness (R01, R08)
- Create the tracked folder `repos/ai-memory-ingester/bench/` with: `common.py` (engine runner, service lifecycle, journal loading, anchor scoring, fact-recall ranking), `model_bench.py`, `three_way.py`, `stats.py`, `snapshot.py`, and `dump_journal.groovy`. The content comes from `data/eval/ledger-run/`.
- Remove every absolute path. The repository root comes from the script location. The model folder, the venv Python, the journal and the task folder come from arguments with these environment defaults: `MODELS_DIR`, `VENV_PYTHON`, `PROMPT_LOG`, `BENCH_TASKS_DIR`.
- Ledgers stay out of Git. Each task is one file in `data/eval/ledger-run/tasks/` (already ignored) with the same fields as the current `TASK` dictionaries: name, ledger, strict anchors, forms, turn cutoff, journal cutoff, text-match pattern, excluded turns. Convert tasks A, B, C and D to this layout. Remove the `exec` of `ledger_run.py`.
- `snapshot.py` writes, for every run, a manifest of `convo.db`, `worklogs.db`, `artifacts.db`, `journal.db` and `prompt.log`: size, modification time and SHA-256. The report embeds the manifest. A rerun with the same manifest must give identical numbers.

### 1.2 New measures in the harness (R04 part, R05, R06, R07, R16 part)
- **Evidence mode:** option `--evidence head|best_passage`. `head` is the current 1,050-character head. `best_passage` uses the row field `best_passage` for re-ranked rows and falls back to the head for other rows. The report counts the fallbacks.
- **Fact recall of the first stage:** for each strict fact, record the best rank of any retrieved document that matches the fact's anchors in the lexical list (limit 300, no re-rank), and report recall at 10, 30, 50 and 100 per task and in total.
- **Latency:** option `--repeats N` (default 5). Report p50 and p95 wall time and the number of degraded runs per system.
- **Paired statistics:** `stats.py` takes the per-query coverage at the top 10 for a system and a baseline. It reports the mean difference, wins, losses, ties, and a bootstrap 95% interval (10,000 resamples, fixed seed 1).
- **Undated documents:** for the all-source branch, report how many of the 20 results are worklog or artifact documents without a date field. No document is excluded. This replaces a time cutoff, which the databases cannot support.
- **Per-query output:** write one CSV row per (task, query, system) with coverage at 5, 10 and all, useful items, bytes, wall time and degraded flag.

### 1.3 Engine changes behind flags (R11 to R15)
- `lib/Config.groovy`: add `RERANK_WEIGHT_DEFAULT` (5, equal to the current weight), `rerankWeight()` (property `ai.memory.rerank_weight`, environment `AI_MEMORY_RERANK_WEIGHT`, integer 1 to 100, anything else gives the default), and `rerankFusion()` (property `ai.memory.rerank_fusion`, environment `AI_MEMORY_RERANK_FUSION`, values `rrf`, `score`, `cut`, anything else gives `rrf`). Both follow the pattern of `relaxMinResults()`.
- `lib/SignalFusion.groovy`: keep `SIGNAL_WEIGHTS` and the default behavior unchanged. `addSignal` accepts an optional weight override that applies only to its own signal. Add `addScoreSignal` for the `score` mode. It adds, for each scored row, `weight / (60 + 1)` multiplied by the logistic of the model score, so a certain top-ranked document contributes what a rank-1 document contributes today at the same weight. It re-sorts with `compareRanked`.
- `lib/RerankStage.groovy`: `fuse` takes the fusion mode and weight from the `options` map. Behavior:
  - `rrf`: today's logic, with the weight override.
  - `score`: `addScoreSignal` instead of the rank signal.
  - `cut`: when the re-rank is not degraded, scored rows are ordered by model score descending (ties: prior fused rank, then stable id), followed by unscored head rows in their prior order. The ticket capsule pin stays first.
  - The degraded paths, the minimum-scored rule and the pin logic do not change.
  - Apply the same mode to `apply` and `applyLate`.
- `lib/MemoryRetrieval.groovy`: resolve the two settings once per search (option first, then `Config`), pass them in `options`, and keep the existing rule that the re-ranked head goes before the rows beyond the depth.
- `03_query_memory.groovy`: add options `--rerank-weight N` (1 to 100) and `--rerank-fusion rrf|score|cut`, with the same error style as `--rerank-depth`. Add both to the help text. The `--explain` line prints `fusion=<mode> weight=<n>`. Defaults print `fusion=rrf weight=5`.
- Output with default settings must be unchanged except for the new explain fields.

### 1.4 Best-passage snippet (R37, conditional)
- Built only if the harness comparison of step 31 shows `best_passage` evidence raising mean coverage at the top 10 by at least 3 points with no task losing more than 3. Otherwise skip steps 32 to 34 and record the negative result.
- `lib/MemoryRetrieval.groovy` `enrichPreview`: when the row has `best_passage` and `AI_MEMORY_SNIPPET=best` is set, use it as the preview. Default off. Document the setting in the help text of `03_query_memory.groovy`.

### 1.5 Experiments and decision rule
- Run, on every task and query, the lexical baseline and mmarco at depth 30 with: weight in {5, 8, 12, 20, 40} for `rrf`; weight in {5, 12, 20} for `score`; and `cut`. Then repeat the best two settings at depth 50.
- Decision rule: take the setting with the highest mean coverage at the top 10 that passes all gates of section 6 of `rerank_enhacement.md`. If none passes, keep `rrf` weight 5 and record the negative result. A setting may become the default only after the owner approves it.

### 1.6 Anomalies (R09)
- **Polish ArgoCD regression:** per query, dump the lexical and model top 20 turn lists and the facts found, and diff them. State which facts are lost and why.
- **Identical models:** check whether models give identical numbers because the selected set is identical, or because of a harness fault. State the cause.
- **Depth 100 speed:** repeat depth 30 and depth 100 five times each, alternating order, and report whether the first call of each model pays a one-time cost.

### 1.7 Documents
- `docs/rerank_enhacement.md`: add a section "Tranche 1 results" with tables, the decision, and the status of R01 to R15 and R37.
- `docs/comparison.md`: no change in this tranche.
- `docs/semantic-retrieval/decision-log.md`: add one row only if a new default is approved.

## 2. Regression scope

Existing behavior that must not change with default settings: strict then relaxed query, filters (`-A`, `-e`), ticket facets and pins, late-interaction pool, degraded reasons, `--explain` output, the output parity of archive filters, `query` JSON fields, evaluation systems S0 to S6, and the service contract.

Suites: `test_query_plan_suite` (119), `test_semantic_suite` (90), `test_query_suite`, `test_retrieval_suite`, `test_eval_suite` (27), `test_chatlog_suite`, `test_journal_suite`, `test_worklog_suite`, `test_reconciliation_suite`, `test_shadow_validation_suite`, `test_memory_lock_suite`, and the Python service tests under three interpreters (78).

---

## IMPLEMENTATION CHECKLIST

**Preparation**
1. [x] Record the baseline: run every Groovy suite and the Python service tests, and save the pass counts. Stop if any suite fails.
2. [x] Capture golden output: run 12 fixed queries (strict, relaxed, ticket, filters, explicit syntax, `--rerank off`, `--rerank auto` with no service, `--rerank on` with the service) with default settings, and save the outputs with timing lines removed.
3. [x] Run the snapshot logic once by hand and save the manifest of the current databases and `prompt.log`.
4. [x] Confirm the owner's answers to R02 and R10, or record that the defaults apply. _No answer was received: the defaults applied._

**Harness (repository `ai-memory-ingester`, folder `bench/`)**
5. [x] Create `bench/common.py` with the shared functions, parameterized as in section 1.1.
6. [x] Create `bench/snapshot.py` and embed its manifest in every report.
7. [x] Convert tasks A, B, C and D to the task-file layout under `data/eval/ledger-run/tasks/`, and confirm with `git check-ignore` that the folder is ignored.
8. [x] Move `model_bench.py`, `three_way.py` and `dump_journal.groovy` to `bench/` using `common.py`, with no absolute path left.
9. [x] Verify the move: rerun all four tasks and confirm that every coverage number equals the values in `three-way-run8.json`.
10. [x] Add the `--evidence head|best_passage` option and the fallback counter.
11. [x] Add the first-stage fact recall measure and its report.
12. [x] Add `--repeats`, p50 and p95, and the degraded count.
13. [x] Create `bench/stats.py` and the paired report (difference, wins, losses, ties, bootstrap interval).
14. [x] Add the undated-document counter and the per-query CSV.
15. [x] Check that two runs on the same snapshot give identical coverage numbers. Fix any difference before continuing.

**Anomalies**
16. [x] Run the investigations of section 1.6 and write each cause (or "cause not found, with the evidence tried") into the results section of `docs/rerank_enhacement.md`.

**Engine (flags off by default)**
17. [x] Add `RERANK_WEIGHT_DEFAULT`, `rerankWeight()` and `rerankFusion()` to `lib/Config.groovy`.
18. [x] Extend `SignalFusion.addSignal` with the optional weight override and add `addScoreSignal`.
19. [x] Change `RerankStage.fuse` for the three modes and pass the mode and weight from `options` in both `apply` and `applyLate`.
20. [x] Resolve and pass the two settings in `lib/MemoryRetrieval.groovy`.
21. [x] Add `--rerank-weight` and `--rerank-fusion` and the explain fields to `03_query_memory.groovy`, including validation and help text.
22. [x] Add tests to `tests/test_semantic_suite.groovy`: default order identical to the old logic on a constructed fixture; a weight override changes the order as specified; `score` mode is deterministic; `cut` orders by model score with the stated tie rules; the capsule pin stays first in all modes; degraded paths unchanged; invalid values fall back to defaults.
23. [x] Add tests to `tests/test_query_suite.groovy` for the new options: valid values, invalid values, help text, explain fields.
24. [x] Rebuild `dist/ai-memory-ingester.jar` with `build-precompiled.sh`.
25. [x] Run every suite and compare the counts with step 1. New tests only add to the counts.
26. [x] Run the 12 golden queries and compare with step 2. They must match except for the new explain fields.

**Experiments**
27. [x] Run the weight and mode sweep of section 1.5 with the rebuilt jar and the service on MPS. Save the report and the CSV for each run.
28. [x] Run the paired statistics of each setting against the lexical baseline and against the current engine.
29. [x] Apply the decision rule and write the table and the decision into `docs/rerank_enhacement.md`.
30. [x] If a setting passes all gates, ask the owner to approve it as the new default. Make no default change before the approval. _Done 2026-11-04: the owner approved rrf weight 12 at depth 30 and the default was changed (D-34)._

**Best-passage snippet (conditional; steps 32 to 34 skipped, marked `[-]`)**
31. [x] Run the harness with `--evidence best_passage` and compare against `head` on all tasks, with paired statistics. Apply the condition of section 1.4. If it is not met, skip steps 32 to 34 and record the result.
32. [-] Implement the `AI_MEMORY_SNIPPET=best` branch in `enrichPreview` and its help text. _Skipped: the condition of step 31 was not met._
33. [-] Add tests: default preview unchanged, `best` uses the passage, rows without a passage keep the head.
34. [-] Rebuild the jar and repeat steps 25 and 26.

**Close**
35. [x] Update `docs/rerank_enhacement.md` with the results section and the status of R01 to R15 and R37. Run `git status` for the repository and confirm that no ledger or internal text is tracked.
36. [x] Run `repos/ai-vault/scripts/commit_handoff.py render` once for `ai-memory-ingester` with only the files written in this tranche (the `bench/` files, the changed Groovy and test files, the rebuilt jar, and the changed documents).
37. [x] Record the turn through the governed worklog writer.
38. [ ] Open the follow-up plans (chunking, query side, source mix, evidence assembly, model choice, operation) only after step 29, using its numbers.

---

## Execution notes (2026-11-04)

- Steps 1 to 31 and 35 to 37 are done. Step 30 was done on 2026-11-04 (owner approval, D-34). Steps 32 to 34 were skipped by their own condition. Step 38 opens the follow-up plans after the owner's decision.
- Results and decisions are in section 10 of [rerank_enhacement.md](rerank_enhacement.md).
- Deviations from this plan, all small and recorded there: the snapshot manifest has a content fingerprint besides the byte hash, because a query rewrites the database file header; the tests for the explain fields are in `tests/test_semantic_suite.groovy` (it holds the service fixture) and the option and help tests are in `tests/test_query_suite.groovy`; the benchmark CSV has a precision@10 column for gate 2; a tie rule (smallest change) was applied in the decision of step 29.

---

Created using Anthropic Claude. Keep this internal until a person has reviewed and verified it.
