# Plan: topic routing and window ordering (phase 2 of the retrieval upgrade)

Status: proposal, written on 2026-11-09 in PLAN mode after the owner accepted the phase 2 proposal ("continue with proposal, accepted"). Nothing in it is built. Execution needs `MODE: EXECUTE`. Any deviation from it returns to PLAN mode.

Parent documents: [rerank_remaining_plan.md](rerank_remaining_plan.md) (phase 2), [ticket_coverage_plan.md](ticket_coverage_plan.md) (the method reused here), [rerank_enhacement.md](rerank_enhacement.md) sections 15 and 16.

## 1. Facts

- Section 16: for the 23 adjudicated queries that are not ticket-key queries, the first 30 lexical rows hold about 61 points of the facts, the first 10 hold about 40, and about a quarter of the facts are never in the first 100 rows.
- Since D-37 a query with a ticket key and generic words is routed to the ticket and its turns are chosen by coverage. A query about the same ticket without its key (keywords, Polish) is not routed.
- On the adjudicated set the default is +0.6 over lexical (section 15); the re-ranker with fusion does not capture the +21 points that the window leaves.

## 2. Offline prototype (2026-11-09; Python over the frozen corpora, 23 non-ticket queries)

Step 1: take the ticket folder that holds most of the first 10 lexical chat rows (the dominant ticket). Step 2: choose turns from that ticket with the coverage rule of the ticket plan (informative tokens, global idf, recency prior 0.5).

- The dominant ticket was the right one for 20 of 23 queries. When it held at least 3 of the first 10 rows (19 queries), it was right every time. The misses: task G keywords (a different ticket), task O keywords and Polish (no ticket in the first 10).
- Mean cov@10 over the 23 queries: lexical 31.7; S1 (routed ticket, coverage turns only, when at least 3 of 10) 45.1; S2 (first 4 lexical rows, then coverage turns) 40.5; S3 (first 6 lexical rows, then coverage turns) 36.5.
- Per query, S1 gains most on tasks M, N, F, I, J and loses on K (61.0 to 56.1), L keywords (77.1 to 62.9), H Polish (48.7 to 38.5) and K Polish (65.9 to 56.1): where lexical is already strong.

Caveat (important): every benchmark query asks for a summary of a topic, and the ledger counts facts spread over the ticket. A query-independent selection fits this. A narrow question ("which port did we choose?") needs the lexically best turn, and S1 would replace it. The plan therefore needs a guard (section 4).

## 3. Design

New setting `topic_route` (option `--topic-route off|on`, environment `AI_MEMORY_TOPIC_ROUTE`, default `off`; the owner decides the default after the evaluation):
1. Run the first stage as today. Find the dominant ticket among the first 10 chat rows. If it holds fewer than `N` of 10 (default 3), do nothing.
2. Build the routed list with the coverage rule (`TicketCoverage.select`) over the ticket's chat turns.
3. Blend, by one of two rules fixed in the evaluation (not tuned on the held-out tasks): (a) S1, the routed list only; (b) a specificity guard: keep the lexical order when the query is specific (the strict first stage already returns at least `M` rows, or the query holds an identifier token), otherwise use the routed list. The guard is built only if the needle test (section 4) shows a loss for S1.
4. Result map `topic_route: [applied, ticket, share, selected]`; `--explain` prints one line.

Window ordering (phase 2b, only after phase 2a): measure on the existing harness, one change at a time, flags off by default: depth 50 and 100 with the passage cap raised (R18), passages per document 1, 3 and 5 (R23), passage choice by BM25 inside the document instead of word overlap (R24). Each is kept only if it passes the gates of section 6 of `rerank_enhacement.md` on the adjudicated set.

## 4. Evaluation protocol

1. Benchmark: `model_bench` frozen, adjudicated-only (35 queries), twice. Settings: default, `topic_route=on` (S1), and the guard variant if built. Paired statistics against lexical and the default. Development tasks E to J, held-out tasks C and K to O; report both.
2. Needle test (guard against harm): for each adjudicated task take up to 3 strict facts of weight 2 or 3 whose anchor groups are plain words (letters, digits, `_`, `-`). The query is those words (at least 2). Metric: the fact is in the first 10 turns (evidence head). The test compares lexical, the default and `topic_route=on`. A loss of more than 5 points of hit rate against the default stops S1 and starts the guard.
3. Regression: with the flag off, a frozen run reproduces `model-bench-adj-a.json` in all non-timing fields; suites and wrapper pass.
4. Timing: median wall time of the non-ticket queries with `topic_route=on` must stay within 0.3 s of the default.

## 5. Definition of done

1. `topic_route` built behind the flag (default off) with unit tests, suites passing, jar rebuilt.
2. Evaluated as in section 4, results and recommendation in `rerank_enhacement.md` section 17 and in the decision log (a proposed decision for the default).
3. Phase 2b measured for depth, passages per document and passage choice, each recorded as built or "evaluated, not built".
4. Handoff rendered, turn recorded.

## 6. Limits and risks

- All 23 queries are summary-type. The needle test is a guard, not a proof for real narrow questions; no owner-written narrow queries exist yet (T005 gold set).
- 23 queries on 11 tickets; the detection rule `N = 3` comes from this set. The held-out split limits, but does not remove, the risk of fitting.
- Detection fails when the query's words match no ticket (task O) or another ticket (task G). Then nothing changes.
- A query that names no ticket but concerns several tickets is routed to the dominant one only.
- Evidence is the first 1,050 characters of a turn.

## 7. Regression scope

All engine suites, wrapper test and Python service tests unchanged and passing; flag-off reproduction of `model-bench-adj-a.json`; `python3 -m py_compile bench/*.py`; `git status` (no ledger, corpus or sidecar tracked).

## IMPLEMENTATION CHECKLIST

1. [ ] Write the needle-query builder (`bench/needles.py`, no ledger text inside) and run lexical and default on it as the baseline.
2. [ ] Add `Config.topicRoute()` and `Config.TOPIC_ROUTE_*`; write `TopicRoute` (dominant ticket, routing) reusing `TicketCoverage`.
3. [ ] Change `MemoryRetrieval.searchTopic` for `topic_route`; add the result map and the explain line; add `--topic-route` to `03_query_memory.groovy`.
4. [ ] Add unit tests; run the engine suites; rebuild the jar; run the wrapper test.
5. [ ] Add the settings token `topicroute=<mode>` to `bench/model_bench.py` and a needle mode to the harness.
6. [ ] Run the benchmark and the needle test (section 4); compute statistics and the development and held-out split.
7. [ ] Decide S1 or the guard by the needle result; build and evaluate the guard if needed.
8. [ ] Check the flag-off reproduction and the timing limit.
9. [ ] Phase 2b: measure depth 50 and 100, passages per document, passage choice; record each result.
10. [ ] Record results in `rerank_enhacement.md` section 17, `comparison.md` and the decision log; tick R29, R31, R18, R23, R24 as built or not built.
11. [ ] `py_compile`, `git status`, render the commit handoff, record the turn.

Any deviation from this plan returns to PLAN mode.

---

Created using Anthropic Claude. Keep this internal until a person has reviewed and verified it. This plan is a proposal and should stay on internal versions until a human has reviewed it. It refers to internal tickets: share it only with people allowed to see them.
