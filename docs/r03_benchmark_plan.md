# Plan R03: eleven more benchmark tasks

Status: proposal, written in PLAN mode on 2026-11-05. Nothing in it is started. Execution needs `MODE: EXECUTE`, which also counts as approval of this plan and of the disk use. Any deviation from it returns to PLAN mode.

Parent documents: [rerank_enhacement.md](rerank_enhacement.md) (R03, gates in section 6, results in section 12), [rerank_enhacement_plan_tranche2.md](rerank_enhacement_plan_tranche2.md) (R03 list, drafting rules), [frozen_corpus_plan.md](frozen_corpus_plan.md) (per-task corpora).

## 1. Goal

Raise the benchmark from 4 tasks and 10 queries to 15 tasks and 43 queries, so that paired intervals can separate the settings that the 10-query benchmark could not. Two questions depend on it:
- the neighbor default (`scored` has the largest mean gain, +10.8 cov@10, but its interval is [-0.9, 24.7]);
- a check that weight 12 (D-35, decided by the owner on 2026-11-05) is not contradicted by more data.

## 2. Owner input already given

The owner approved the proposed 11 tickets of the tranche 2 plan, section 1.2, on 2026-11-05. The topic task `nri-flex` is not in the list: it has no usable source (the Cursor answers are redacted), and the tranche 2 plan says to skip it in that case.

| Task | Ticket | Group | Turns | Worklog file | Capsule `authoritative_through` |
|---|---|---|---:|---|---|
| E | DEVOPS-849 | long | 461 | `worklog/2026-09-11_DEVOPS-849.log` | 2035-05-22 (invalid, see 3.2) |
| F | KD-6919 | long | 414 | `worklog/2026-06-03_KD-6919.log` | 2026-11-01T01:53Z |
| G | CWP-11649 | long | 202 | `worklog/2026-07-07_CWP-11649.log` | 2026-09-26T01:59Z |
| H | SCTASK1280900 | long | 191 | `worklog/2026-10-12_SCTASK1280900.log` | 2026-10-15 |
| I | SCTASK1759003 | medium | 123 | `worklog/2026-10-21_SCTASK1759003.log` | 2026-11-03T21:48Z |
| J | KD-7242 | medium | 107 | `worklog/2026-09-07_KD-7242.log` | 2026-09-25 |
| K | KD-7136 | medium | 71 | `worklog/2026-09-29_KD-7136.log` | 2026-10-15T19:25Z |
| L | KD-7172 | short | 36 | `worklog/2026-09-25_KD-7172.log` | 2026-10-16 |
| M | SCTASK1541060 | short | 31 | `worklog/2026-08-28_SCTASK1541060.log` | 2026-10-08 |
| N | KD-6863 | short | 31 | `worklog/2026-05-20_KD-6863.log` | 2026-10-24 |
| O | DEVOPS-796 | short | 21 | `worklog/2026-05-29_DEVOPS-796.log` | 2026-06-01 |

All 11 worklog files and capsules exist (checked 2026-11-05). Turn counts are from `data/eval/ledger-run/review/candidates.md`.

## 3. Facts and design

### 3.1 What exists

- A task is a git-ignored file `data/eval/ledger-run/tasks/task_<letter>.py` with `ledger`, `strict`, `forms`, `extra_queries`, `cutoff_turn`, `excluded`, `journal_cutoff`, `journal_keys`, `text_match`, `review_sources` and `freeze_instant`. `bench/common.py case_queries` already runs `forms['question']` plus every `extra_queries` entry. So three queries per task need no harness change.
- `bench/task_corpus.py ensure` builds one frozen corpus per distinct (cutoff, freeze) key. Tasks with the same key share a folder.
- `bench/ledger_review.py` produces a review pack per task. `bench/candidates.py` produced the candidate table.
- Chat turn documents carry a `timestamp:` line in their head (the regex in `candidates.py` reads it). Some turns have no date (folder `dunknown`).

### 3.2 Freeze rule

- Freeze instant = the capsule's `authoritative_through`, except when it is not a plausible date (DEVOPS-849 shows 2035-05-22). For such a ticket use the last dated ACTION LOG entry of the worklog file, and state the choice in the task file.
- `cutoff_turn` = the highest turn number whose timestamp is at or before the freeze instant, among the turns of the ticket and the turns around them. I do not know yet whether turn numbers rise with time without exceptions (undated turns exist). Step 3 checks this on the snapshot and stops on a conflict.
- `journal_cutoff` = freeze instant (same rule as tasks C and D).
- Active tickets (SCTASK1759003, KD-6919) are frozen at the capsule date. Later work is not part of the task.

### 3.3 Queries (three per task, 33 in total)

1. Question form: `summary of <KEY>` (as in tasks C and D).
2. Keyword form: 3 to 4 words that a person would type for the topic, taken from the ticket summary, without the key.
3. Polish paraphrase: a short Polish question for the same content.

The three forms share one ledger. Results are reported per form, so English against Polish is visible.

### 3.4 Ledger drafting and checks

Rules from the tranche 2 plan, kept as they are: 16 to 20 facts, weights 3, 2 and 1, strict anchors checked against the source.
- New tracked file `bench/ledger_check.py` (no ledger text inside). For one task it reports: (a) each strict anchor matches in a review source; (b) the false-match rate of each strict anchor on 3,000 unrelated chat turns (turns at or before the cutoff that do not contain the ticket key, fixed seed): at most 1%; (c) the share of strict weight that occurs in some turn at or before the cutoff: at least 70%, otherwise the fact is marked "source only" and leaves the strict set. It exits non-zero on a failure of (a) or (b).
- A task is dropped, and the drop is reported, if fewer than 12 facts have strict anchors after the checks.
- Ledgers are drafted from the capsule and the worklog by the benchmark author. They are not adjudicated until the owner confirms them (section 3.5).

### 3.5 Adjudication

- Each task gets a field `adjudicated` (default `False`). `ledger_review.py` writes a review pack `review/task_<letter>.md` for every new task (git-ignored). The owner confirms or changes the facts, as for R02.
- `three_way.py` and `model_bench.py` get `--adjudicated-only`. Reports always record the flag. The gates apply to the adjudicated set. The full set is reported next to it as "unadjudicated included".
- Until the owner has reviewed the packs, all results of this plan are provisional and the document says so.

### 3.6 Runs and statistics

All runs: snapshot `20261104`, `--frozen`, mmarco on MPS, depth 30, twice (determinism).
1. Baseline: `lexical`, `mmarco` (weight 12) and `mmarco[rrf:5]`, 43 queries.
2. Neighbor sweep: `neighbors=after`, `scored`, `off` against `adjacent`.
3. Paired statistics with `bench/stats.py` (10,000 resamples, seed 1): all settings against lexical, neighbor modes against `adjacent`, weight 5 against weight 12.
4. Breakdown: per group (long, medium, short), per query form (question, keyword, Polish), and per task.

### 3.7 Decision rules

- Neighbor default: a mode wins when it passes the gates of section 6 of `rerank_enhacement.md` against `adjacent` and raises mean cov@10 by at least 1.7 points. No winner keeps `adjacent`. The outcome is a recommendation. The owner decides.
- Weight: D-35 stays closed. It is reopened as a recommendation only if weight 5 beats weight 12 with a paired interval that lies fully above zero on the adjudicated set.
- Intervals that include zero are reported as "not significant", whatever the mean difference.

### 3.8 Cost and limits

- Disk: one corpus per distinct (cutoff, freeze) key, up to about 130 MB each, so up to about 1.4 GB for 11 tasks, under `data/eval/snapshots/20261104/tasks/` (git-ignored). 273 GiB were free on 2026-11-04. The tool rebuilds the corpora.
- Time: the 4-task runs took about 2 to 3 minutes per `model_bench` call. Expect about 10 minutes per call for 15 tasks. Drafting 11 ledgers is the long part; it is done task by task with the checks of 3.4.
- Worklog documents and state capsules cannot be frozen. The chat-turn branches are fully frozen. The indexed-worklog and all-source branches stay not frozen, and the report says so. The ledgers draw on those same worklog files, so the worklog branch is expected to score high by construction (as in tasks C and D).
- Ledger text and fixtures stay git-ignored. The documents report numbers only.

## 4. Flags

- Personal data: the ledgers can name people (staff, reporters, vendors). Ledgers and review packs stay local and git-ignored. Privacy Legal approval is needed before any ledger text, fact list or excerpt leaves the workspace or enters a shared document. This plan does not include ticket text.
- The turns and worklogs are internal and confidential content. Results are internal until a person has reviewed and verified them.

## 5. Regression scope

- Existing tasks A to D reproduce their frozen results in the same run (identical non-timing fields against `model-bench-fza.json`).
- Unfrozen runs of tasks A to D still reproduce the saved snapshot results.
- `python3 -m py_compile bench/*.py`, `git status` (no ledger, snapshot or corpus tracked), and a test of `ledger_check.py` on tasks C and D (the strict anchors of those two tasks must pass checks (a) and (b), or the failure list is reported).
- The engine, the jar and the service are not touched.

## Amendment B1 (proposal, not approved): time-based freeze

Found in step 3 on 2026-11-06. Execution stopped there, as the plan requires on a conflict. Steps 1 and 2 are done: the candidate table is identical on the snapshot, and `bench/ledger_check.py` exists. Test on the existing tasks: task D passes; task C fails check (b) for one anchor (C10, 1.40% false matches on 3,000 unrelated turns, limit 1%). The ledgers of C and D are not changed: they belong to R02.

Finding:
- Turn numbers follow the order of ingestion, not time. Of 12,630 turns, 737 have no date. In number order the dates reset backwards 539 times (for example turn 14 is dated 2026-09-11 and turn 15 is dated 2026-02-11). Turns that were ingested later but written earlier carry high numbers.
- The rule "cutoff = highest number whose date is at or before the freeze instant" gives 13,074 for task C (the task uses 9,980). It cannot be used.
- The cutoff by number used for tasks A to D (and by `task_corpus.py`) leaks almost nothing (0 to 36 turns dated after the freeze), but it deletes turns that were written before the freeze: 1,416 and 1,435 for A and B, 2,205 for C, 2,357 for D.
- For the 11 tickets, a number cutoff set just before the first post-freeze number removes 5 to 30% of the ticket's own pre-freeze turns (DEVOPS-849: 110 of 418; KD-6919: 87 of 258; SCTASK1759003: 30 of 83). For DEVOPS-796 the rule collapses (cutoff 1), because an early-numbered turn has a later date.

Options:
1. **Time-based freeze (recommended).** `task_corpus.build` and `common.eligible_row` cut chat turns by their timestamp (`parse_instant`): a turn is kept when its time is at or before `freeze_instant`. Undated turns (737) are kept only when their number is at or below a per-task fallback number (the highest number of a dated turn at or before the freeze, taken from the live sequence). `cutoff_turn` stays in the task files for the excluded-turn rule and for reports. The four existing corpora are rebuilt, and tasks A to D are re-run (baseline and sweeps, twice), because about 1,400 to 2,400 turns per task come back. The decisions D-35 (weight 12) and the neighbor default were taken on the number-based corpora: they are re-checked on the rerun, and reopened as a recommendation if the outcome changes.
2. **Keep the number cutoff.** Simplest and consistent with A to D. Cost: each new task loses 5 to 30% of its own turns, so check (c) may mark real facts "source only", and DEVOPS-796 needs a hand-set cutoff.

Checklist if option 1 is approved (inserted before step 3): (a) change `task_corpus.py` and `common.eligible_row` for time-based cuts, with `verify` extended (no turn dated after the freeze; undated turns above the fallback number removed); (b) rebuild the four corpora and verify them; (c) re-run tasks A to D (frozen baseline and sweeps, twice) and compare with section 12 of `rerank_enhacement.md`; (d) record the outcome and any change to D-35 or the neighbor default; then continue with step 3.

## IMPLEMENTATION CHECKLIST

1. [x] Re-run `bench/candidates.py` on the snapshot and confirm the 11 tickets and their turn counts.
2. [x] Write `bench/ledger_check.py` with checks (a), (b) and (c) of section 3.4. Test it on tasks C and D.
3. [ ] For each ticket, list its turns with number and timestamp, check that turn numbers and times agree around the freeze instant, and set `freeze_instant`, `cutoff_turn` and `journal_cutoff` (section 3.2). Stop on a conflict.
4. [ ] Add the field `adjudicated` and the option `--adjudicated-only` to `three_way.py`, `model_bench.py` and `common.load_tasks` (default `False`; tasks A to D keep their current status in the report).
5. [ ] Draft the ledger, the strict set, the three query forms and `review_sources` for task E. Run `ledger_check.py`. Repeat for tasks F to O, one task at a time. Drop and report a task that fails section 3.4.
6. [ ] Build the review packs with `ledger_review.py --frozen` for all surviving new tasks.
7. [ ] Build and verify the frozen corpora (`task_corpus.py build` and `verify`). Stop on any failure.
8. [ ] Run the regression of section 5 (tasks A to D, frozen and unfrozen).
9. [ ] Run the baseline of 3.6 item 1, twice. Compare the two runs.
10. [ ] Run the neighbor sweep, twice. Compare the two runs.
11. [ ] Compute the statistics and breakdowns of 3.6 items 3 and 4.
12. [ ] Apply the rules of 3.7 and write the tables and the outcome.
13. [ ] Update `rerank_enhacement.md` (new section 13), `comparison.md`, `decision-log.md` (a proposed row for the neighbor default if a mode wins) and mark R03 in the checklist of `rerank_enhacement.md`.
14. [ ] Check that no ledger text, snapshot or corpus is tracked by Git, and run `python3 -m py_compile bench/*.py`.
15. [ ] Render the commit handoff once for `ai-memory-ingester`.
16. [ ] Record the turn through the governed worklog writer.
17. [ ] Hand the review packs and the recommendations to the owner. Results stay provisional until the packs are adjudicated.

Any deviation from this plan returns to PLAN mode.

---

Created using Anthropic Claude. Keep this internal until a person has reviewed and verified it. This plan is a proposal and should stay on internal versions until a human has reviewed it. It refers to internal tickets: share it only with people allowed to see them.
