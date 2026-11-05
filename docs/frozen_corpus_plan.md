# Plan: a frozen corpus per task (amendment A1)

Status: proposal, written in PLAN mode on 2026-11-05. Nothing in it is started. Execution needs the owner's approval. Any deviation from it returns to PLAN mode.

Related documents: [rerank_enhacement.md](rerank_enhacement.md) (findings, section 11.5 proposed this amendment), [rerank_enhacement_plan_tranche2.md](rerank_enhacement_plan_tranche2.md), [comparison.md](comparison.md), [semantic-retrieval/decision-log.md](semantic-retrieval/decision-log.md) (D-34, D-35).

## 1. Problem

The benchmark cuts turns after a task's freeze point only at the end, after the 30-document re-rank window is already filled. On the snapshot of 2026-11-04, most of the corpus lies after the freeze:

| Task | Freeze (turn number or lower) | Chat turns above it | Post-freeze turns in the 30-document window |
|---|---:|---:|---:|
| A, B | 2884 | 10,001 of 12,630 | 11 to 27 |
| C | 9980 | 2,972 | 17 |
| D | 10467 | 2,485 | 16 |

So coverage depends on how much newer text exists, and results from different days do not compare. The decisions D-34 (weight 12), D-35 (re-decide it) and the neighbor default are open for this reason.

## 2. What was checked (2026-11-05, on a throwaway copy)

- The full-text index of `convo.db` is `contentless_delete`. Deleting the chat turns above 9980 and the two excluded turns took 0.4 s, left 9,656 documents and 9,656 index rows, passed `integrity-check`, and left no index hit without a document.
- Dates: all 1,011 `chat:artifacts` documents carry a timestamp with a time zone: 426 in the form `2026-10-06T11:04:53Z` and 585 in the form `Wednesday, Oct 21, 2026, 7:34 PM (UTC+2)`. No artifact has a zone-less timestamp. The 52 `worklog:state` capsules have a timestamp, but it is their current regeneration time, not a freeze time. The 628 `worklog:chunks` have none. So chat turns (by number) and artifacts (by timestamp) can be cut. Worklog documents cannot. (An earlier draft of this plan counted only the first form and wrongly called the other 585 undated.)
- There are 4 distinct freeze points (tasks A, B, C and D), each with its own turn cutoff and freeze instant.

## 3. Scope

1. A frozen corpus per task, built from the snapshot, used by the benchmark harness.
2. A re-run of the baseline, the weight sweep and the neighbor sweep on those corpora.
3. The decision rules for D-35 and the neighbor default, applied to the new numbers.

No engine code changes. The jar stays as it is. The default settings are not changed by this plan. A new default needs the owner's approval, as before.

## 4. Specification

### 4.1 Freeze instant in the task files (local, git-ignored)

Add `freeze_instant` (ISO UTC) to `data/eval/ledger-run/tasks/task_a.py` to `task_d.py`, derived from the existing journal cutoffs (the harness shifts `Z` times by a fixed 2 hours): A `2026-09-13T21:27:00Z`, B `2026-09-14T06:52:00Z`, C `2026-10-30T20:10:00Z`, D `2026-11-03T11:50:00Z`. Tasks added later must carry the field.

### 4.2 `bench/task_corpus.py` (new, tracked, no ledger text)

- **Key.** A corpus is identified by (turn cutoff, sorted excluded turn numbers, freeze instant). Tasks with the same key share a folder.
- **Folder.** `data/eval/snapshots/<snapshot name>/tasks/<key>/` with `datasets.json`, `convo.db`, `worklogs.db`, `artifacts.db` and `manifest.json`.
- **Build.**
  1. Copy `datasets.json`. Copy `worklogs.db` unchanged (no dates to cut by).
  2. `convo.db`: copy with the SQLite backup API, then delete the `chat:turns` documents whose turn number is above the cutoff or is in the excluded set. The delete triggers remove the index rows. Then run the full-text `optimize` command and `VACUUM`.
  3. `artifacts.db`: copy, then delete the `chat:artifacts` documents whose timestamp (the first of `timestamp`, `event_time`, `generated_at` in the first 700 characters, in either of the two forms above, converted to UTC with its own zone) is later than the freeze instant. A document whose timestamp cannot be parsed stays and is counted in the manifest (expected: 0).
  4. Write `manifest.json`: the key, the parameters, content fingerprints (as in `bench/snapshot.py`), the number of documents removed per archive, the number of artifacts with an unparseable timestamp kept, the number of worklog documents kept (undated), and the folder sizes.
- **Rules.** Refuse to build into a folder that exists. `ensure(task, snapshot)` returns an existing folder only when its manifest has the same key and the same source fingerprints, otherwise it builds.
- **Verify** (a function and a command): no chat turn above the cutoff and no excluded turn; document count equals index row count; `integrity-check` passes; no index hit without a document for a probe term; and for 12 probe queries (the golden queries) the lexical first-stage result set of the corpus equals the result set of the full snapshot with the post-freeze documents removed (ranks may differ slightly because the index statistics change). The command prints a table and exits non-zero on any failure.

### 4.3 Harness changes (`bench/common.py` and callers)

- `common.py`: `connection(origin, data_dir=None)` keeps one connection per (folder, database). `turn_snippet`, `document_snippet`, `full_text`, `turn_texts`, `multi_texts` and `fact_recall` take the corpus folder of the task. `run_engine`, `run_repeated` and `warm_up` take a `data_dir` argument that adds `--data-dir`. A helper `task_data_dir(task, frozen)` returns the folder from `task_corpus.ensure` when frozen, otherwise the base data folder.
- `three_way.py`, `model_bench.py`: option `--frozen` (also the environment variable `BENCH_FROZEN=1`), off by default so the old behavior and the old numbers stay reproducible. The reports record `frozen`, the corpus keys and their manifests.
- `ledger_review.py`: `turn_matches` uses the task corpus when frozen. `cold_start.py` is not changed.
- **Crowding check.** In frozen mode the harness asserts that no chat turn above the cutoff appears in the first 30 rows of any query and prints the count of worklog documents (which carry no date) among the top 20. A violation stops the run.

### 4.4 Runs and decisions

All runs use the snapshot `20261104`, mmarco on MPS, depth 30, and run twice for determinism.

1. **Baseline, frozen:** `three_way.py --frozen` (weight 12 and weight 5, repeats 5, budget 1.2 s) and `model_bench.py --frozen` (lexical, mmarco, settings `default,rrf:5`). Compare with the unfrozen snapshot numbers to show the effect of freezing.
2. **Weight sweep, frozen:** `rrf:5, rrf:8, rrf:12, rrf:20, rrf:40, score:5, score:12, score:20, cut`. Paired statistics against lexical and against weight 12.
3. **Neighbor sweep, frozen:** `neighbors=after, scored, off` against `adjacent`, with paired statistics.
4. **Rule for D-35** (the gates of section 6 of `rerank_enhacement.md` apply): (a) if weight 12 raises mean cov@10 by at least 5 points over lexical, no task falls by more than 3 points against weight 5, and the paired interval against weight 5 does not lie below zero, it stays and D-35 is closed as "kept"; (b) otherwise, if weight 5 passes the gates against lexical, recommend returning to 5; (c) otherwise state that no weight is proven beneficial on this benchmark and recommend keeping 12 only with that caveat. The outcome is a recommendation. The owner decides.
5. **Rule for the neighbor default:** as in the tranche 2 plan: a mode wins when it passes the gates against `adjacent` and raises mean cov@10 by at least 1.7 points. No winner keeps `adjacent`.
6. The benchmark still has 10 queries. An interval that includes zero is reported as "not significant", whatever the mean difference. A larger benchmark (R03) is the way to narrow it, and every new task gets its own frozen corpus through `ensure`.

### 4.5 Documents

`rerank_enhacement.md` gets a section 12 with the frozen results and the outcome of the rules. `comparison.md` gets a section "Frozen per-task corpus". `decision-log.md` gets the recommendation for D-35 and, if a mode wins, a proposed row for the neighbor default. Rows stay "Proposed" until the owner decides. The tranche 2 plan marks amendment A1 as done.

### 4.6 Limits that remain (to be stated in the results)

- Worklog documents and the state capsules show today's content: they cannot be cut. The indexed-worklog and all-source branches are therefore not frozen. Only the chat-turn branches are fully frozen.
- Artifact timestamps are the time of writing. An artifact written before the freeze is kept as it is, which is correct for a frozen view.
- The ledgers were drafted by the benchmark author from the worklog and are not adjudicated by the owner (R02).
- Disk: one corpus is about 150 to 170 MB (convo about 25 to 110 MB after the cut, artifacts 47 MB, worklogs 9 MB). Four corpora are about 600 MB under `data/eval/snapshots/` (git-ignored). 273 GiB are free on the volume. The owner can delete that folder at any time. The tool rebuilds it.

## 5. Regression scope

- Unfrozen runs (no flag) must reproduce the saved snapshot results (`three-way-t2-w12-a.json`, `three-way-t2-w5-a.json`, `model-bench-t2-a.json`, `model-bench-t2-nb-a.json`): identical coverage fields.
- The engine, the jar and the service are untouched. Their suites do not need to be re-run for this plan. A Python syntax check of `bench/*.py` and `git status` (no ledger text, snapshot or corpus tracked) close the work.

## IMPLEMENTATION CHECKLIST

1. [ ] Save the reference results (the four files above) and run `python3 -m py_compile bench/*.py`.
2. [ ] Add `freeze_instant` to the four task files (section 4.1).
3. [ ] Create `bench/task_corpus.py` with `build`, `ensure` and `verify` (section 4.2).
4. [ ] Build the four corpora from the snapshot `20261104` and print their manifests (sizes, documents removed per archive, unparseable artifact timestamps, worklog documents kept).
5. [ ] Run `verify` on each corpus. Stop on any failure.
6. [ ] Rebuild one corpus into a second folder (inside the job's temporary folder) and compare content fingerprints: they must be identical.
7. [ ] Change `bench/common.py` and its callers for the per-task folder and add `--frozen` and `BENCH_FROZEN` (section 4.3).
8. [ ] Run the unfrozen `three_way.py` and `model_bench.py` and confirm identical coverage fields against the references of step 1.
9. [ ] Run the frozen baseline (4.4 item 1) and confirm that the crowding check passes (0 post-freeze chat turns in the window) for every query.
10. [ ] Repeat the frozen baseline once and confirm identical coverage fields.
11. [ ] Run the frozen weight sweep and its paired statistics (4.4 item 2).
12. [ ] Run the frozen neighbor sweep and its paired statistics (4.4 item 3).
13. [ ] Repeat steps 11 and 12 once and confirm identical coverage fields.
14. [ ] Apply the rules of 4.4 items 4 and 5 and write the tables and the outcome (4.5).
15. [ ] Update `rerank_enhacement.md`, `comparison.md`, `decision-log.md` and the tranche 2 plan (4.5).
16. [ ] Check that no ledger text, snapshot or corpus is tracked by Git, and run `python3 -m py_compile bench/*.py`.
17. [ ] Render the commit handoff once for `ai-memory-ingester` (the changed and new `bench/` files and the documents).
18. [ ] Record the turn through the governed worklog writer.
19. [ ] Report the D-35 and neighbor recommendations to the owner for decision.

Any deviation from this plan returns to PLAN mode.

---

Created using Anthropic Claude. Keep this internal until a person has reviewed and verified it. This plan is a proposal and should stay on internal versions until a human has reviewed it. It refers to internal tickets: share it only with people allowed to see them.
