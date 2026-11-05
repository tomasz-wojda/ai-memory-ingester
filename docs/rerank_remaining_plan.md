# Plan: remaining re-rank work, with R03 first

Status: proposal, written in PLAN mode on 2026-11-05. Nothing in it is started. Execution needs `MODE: EXECUTE` for one phase at a time. Any deviation from it returns to PLAN mode.

This plan puts the unimplemented items of [rerank_enhacement.md](rerank_enhacement.md) (R01 to R53) in one order, with R03 as phase 1. It does not repeat the detail of [r03_benchmark_plan.md](r03_benchmark_plan.md): that document stays the plan for phase 1. Evidence and numbers: sections 10 to 12 of `rerank_enhacement.md`.

## 1. Rules for every phase

- Every experiment runs on the frozen corpora (`--frozen`) with the same harness, twice (determinism), and is judged by the gates of section 6 of `rerank_enhacement.md` and the paired statistics of `bench/stats.py`.
- Every accepted change ships behind a flag, off by default (R51). Turning a flag on is an owner decision recorded in `decision-log.md`.
- An experiment that does not pass is recorded as "evaluated, not built", with its numbers (as R37 was).
- Until the packs of R02 are adjudicated, results are provisional.
- Settled: weight 12 (D-35, 2026-11-05) and `adjacent` as the neighbor default until phase 1 gives a result.

## 2. Phases

### Phase 0: owner inputs (no code)

| Item | What is needed | Blocks |
|---|---|---|
| R02 | Review the packs for tasks C and D, later E to O | Adjudicated gates |
| R10 | Budget: 1.2 s at depth 30, CPU-only machines, accepted loss per task | Phases 3 and 4 |
| R44 | IP Legal confirms Apache-2.0 for the chosen models and the 41 packages | Any model change |
| R49 | How the service starts (recommended: `sweep-memory` opt-in) | Daily use, R51 |

### Phase 1: a benchmark that can separate settings (R03, R04)

- R03: tasks E to O, steps 1 to 17 of `r03_benchmark_plan.md`.
- R04: add nDCG@10 and recall@k against graded facts to the harness (latency percentiles, recall and the precision proxy exist). Claim precision, freshness, temporal correctness and contradictions stay manual and are reported as such.
- Outcome: the neighbor default (R39 depends on it) and a check of weight 12.
- Exit: 15 tasks, 43 queries, results recorded in `rerank_enhacement.md` section 13.

### Phase 2: candidate set and query side (cheap, evidence exists)

The frozen results show where coverage is lost: task C (0.0 at first stage), artifacts crowding the top 20 (9 of 20 on task C), and a stale capsule.

| Order | Items | Reason |
|---|---|---|
| 2.1 | R32 cap or down-weight `chat:artifacts`; R33 low-information turn filter; R34 near-duplicates | Evidence: tasks B and C. Each is a first-stage change and can be tested without the model |
| 2.2 | R28 route ticket-key queries; R35 stale-capsule rule; R36 journal selector fix | Evidence: task D (5.0 to 47.5 on the journal branch) |
| 2.3 | R29 short and vague queries; R30 diacritics test; R31 language-aware handling | R30 is a test only. R31 is built only if a per-language gain shows, so the Polish forms of phase 1 are needed |

Exit per item: pass the gates, or recorded as not built.

### Phase 3: passages and depth (needs R10)

Order matters: measure before changing.

1. R21 measure truncation per model tokenizer.
2. R22 window sizes, R23 passages per document, R24 replace the word-overlap filter. Run as one grid, not item by item.
3. R25 structure-aware chunks and R26 two-level chunks, only if step 2 shows a gain. R27 (migration for `semantic.db` and the late-interaction index) is built only with the chunking change that needs it.
4. R17 passage cap tied to the time budget; R18 depths 30, 50, 100 and 200 with the cap raised; R19 partial result at the deadline (target: degraded rate below 2%).

### Phase 4: models (needs R10, R44)

1. R41 bake-off on the larger benchmark: mmarco, bge-reranker-v2-m3, and any new Apache-2.0 or MIT candidate.
2. R20 and R42 cascade: mmarco on 50 passages, then bge-m3 on the top 10. Estimate in the checklist is 0.3 s plus 0.6 s on MPS; this is an estimate and must be measured. Needs a documented CPU fallback.
3. R45 fallback queue, only if a second model is adopted. R50 writes the default model and checksum into `.ai-worklog/memory-service.json` after R40 and R44.
4. R43 fine-tuning stays closed until a few hundred graded pairs exist (owner).

### Phase 5: output and operations

1. R38 pack by gain; R39 expand neighbors after packing (uses the phase 1 neighbor result); R37 is closed (condition not met) and reopens only if phase 3 changes the passages.
2. R47 batch and thread tuning for MPS and CPU.
3. R48 latency percentiles in the service (p50, p95, degraded rate, reasons).
4. R51 shadow mode, R52 regression routine before each change is turned on, R53 documents.

## 3. Dependencies

R03 and R04 come first, because every later gate uses them. R10 gates phases 3 and 4. R44 gates any model change. R49 gates shadow mode (R51). Phase 2 can start after R03 without R10. Phases 3 to 5 each get their own detailed plan, written when the phase before it ends, with the numbers of that phase. This avoids a plan that depends on results that do not exist.

## 4. Limits and flags

- I do not have time estimates for phases 2 to 5. Phase 1 timing is in `r03_benchmark_plan.md` section 3.8. I do not know yet how many of the items in phase 2 and 3 will pass the gates.
- Ledgers can name people. They stay local and git-ignored; Privacy Legal approval is needed before ledger text leaves the workspace.
- Worklog and all-source branches are not fully frozen (worklog documents carry no date).

## IMPLEMENTATION CHECKLIST

1. [ ] Update the stale boxes in the checklist of `rerank_enhacement.md` (R18 partly, R37 evaluated, R46 built, R48 partly) without changing other text.
2. [ ] Phase 0: send the owner the four inputs of section 2 and record the answers (or "defaults apply") in `decision-log.md`.
3. [ ] Phase 1: execute `r03_benchmark_plan.md` steps 1 to 17, and add R04 (nDCG@10 and recall@k) to the harness.
4. [ ] Phase 1 close-out: record the neighbor default and the weight check.
5. [ ] Write the detailed plan for phase 2. Execute it. Close with a section in `rerank_enhacement.md`.
6. [ ] Write the detailed plan for phase 3 (after R10). Execute it. Close it.
7. [ ] Write the detailed plan for phase 4 (after R10 and R44). Execute it. Close it.
8. [ ] Write the detailed plan for phase 5. Execute it. Close it.
9. [ ] After each phase: render the commit handoff, record the turn, report the recommendations to the owner.

Any deviation from this plan returns to PLAN mode.

---

Created using Anthropic Claude. Keep this internal until a person has reviewed and verified it. This plan is a proposal and should stay on internal versions until a human has reviewed it. It refers to internal tickets: share it only with people allowed to see them.
