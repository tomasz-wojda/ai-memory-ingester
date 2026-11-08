# Plan: ticket coverage selection (new retrieval approach for ticket-key queries)

Status: written on 2026-11-08 under the owner's `/goal` ("continue till new retrieval approach is done"). The owner's open questions use their documented defaults: R10 defaults, `adjacent` stays the neighbor default (D-36 open), no service start (R49 open), no model change (R44 open). Every new setting is off by default. Any deviation from this plan returns to PLAN mode.

Parent documents: [rerank_remaining_plan.md](rerank_remaining_plan.md) (phase 2, items R28, R33, R34, R38), [rerank_enhacement.md](rerank_enhacement.md) (section 13).

## 1. Facts found in the code and the benchmark (2026-11-08)

- `MemoryRetrieval.parseQuery` removes a ticket token from the query. For `summary of KD-6919` the ticket facet is `kd6919` and the terms are `summary of`.
- With a ticket facet the candidate pools come from the path facet (`exactFacetPool`, `recency`, `authority`, `impact`), ordered by authority and by recency, limit 50. Relevance to the terms plays almost no role.
- The re-ranker therefore scores passages against the query `summary of`. This is a generic query with no topic.
- Result on 12 ticket-key queries (tasks D to O, frozen corpora): lexical 31.1 mean cov@10, the 10 newest turns of the ticket 30.7, weight 12 25.9. The lexical result is the recency order of the ticket.

## 2. Offline prototype (not the engine; Python over the frozen corpora)

Greedy selection of 10 turns from the ticket's turns: each step takes the turn that adds the most idf-weighted new tokens. Variants were fixed before the run. The development tasks are D to I; the held-out tasks are J to O.

| Variant | Development | Held-out | All 12 |
|---|---|---|---|
| lexical (current) | 22.6 | 39.7 | 31.1 |
| weight 12 (current) | 26.6 | 25.1 | 25.9 |
| V1 all tokens | 30.5 | 43.4 | 37.0 |
| V2 informative tokens only | 35.9 | 52.4 | 44.1 |
| V3 V1 with length normalisation | 30.0 | 44.8 | 37.4 |
| V4 assistant text only | 29.1 | 44.5 | 36.8 |
| V5 V2 with length normalisation | 36.0 | 47.7 | 41.9 |
| V6 V2 with a recency prior (factor 0.5) | 37.2 | 54.2 | 45.7 |

Further checks: tokens split on hyphens and dots (alphanumeric only) give 36.7; idf computed only inside the ticket gives 28.5. So the engine must keep identifiers such as `kd-6919`, `a_b`, `v1.2.3` and paths as one token, and it needs a global document frequency.

Tokens: lowercase `[a-z0-9][a-z0-9_#./-]{2,}`. Informative: contains a digit, or has 7 or more characters, or contains one of `_ # . / -`. Weight: idf over the first 1,050 characters of every chat turn (the evidence text of the benchmark). Score of a turn: sum of the idf of its informative tokens not yet covered, times `1 + 0.5 * (n - n_min) / (n_max - n_min)` over the turn numbers of the ticket.

## 3. Approach

New setting `ticket_select` (option `--ticket-select off|gate|coverage`, environment `AI_MEMORY_TICKET_SELECT`, default `off`):
- `gate`: for a query with a ticket facet and only generic terms, skip the re-rank (reason `generic_ticket_query`). The result equals the lexical result for those queries. Generic terms are the words `summary of status overview state about for the what is on ticket current latest update updates tell me give show` and nothing else.
- `coverage`: the gate, plus: the chat turns of the ticket are selected by the greedy rule of section 2. They replace the chat rows of the fused list in order (non-chat rows keep their place). Turns not selected stay behind in the original order. Neighbor expansion is skipped (the selected turns are already from the ticket).
- Document frequency: a sidecar file `term-df.tsv` in the data folder, built on first use from `convo.db` (term, document count), valid for the document count of the database; rebuilt when the count changes. It holds terms and counts, no text.
- The result map gets `ticket_select: [mode, applied, candidates, selected]`; `--explain` prints one line.
- `bench/model_bench.py` gets the settings token `ticketselect=<mode>`.

## 4. Definition of done (the goal)

1. Built behind the flag (default off), with unit tests, the engine suites passing, golden queries unchanged with the flag off, and the jar rebuilt.
2. Evaluated on the 43 queries (frozen, time-based), twice, for `off`, `gate`, `coverage`, and `coverage` with `neighbors=after` for the other 31 queries; paired statistics against lexical, weight 12 and `neighbors=after`; development and held-out split reported.
3. Results, the gate outcome and a recommendation recorded in `rerank_enhacement.md` (section 14), `comparison.md`, `decision-log.md`. The owner decides about defaults.
4. Commit handoff rendered and the turn recorded.

## 5. Limits and risks

- The ledgers of tasks D to O are not adjudicated. Task D was part of the development split by design of the plan, not by tuning.
- Only 12 of the 43 queries are ticket-key queries. The keyword and Polish forms are not changed by this approach.
- The selection rewards distinct identifiers and numbers. It can favour turns with long logs or tables. The benchmark measures fact coverage in the first 1,050 characters, not readability.
- First use builds the sidecar: expect a one-time cost. I will measure it.
- The sidecar holds terms from private conversations. It stays in the data folder (git-ignored).
- The evidence text of the engine is a turn's head. A turn whose facts sit after 1,050 characters is under-counted for every system.

## 6. Regression scope

Tests: `test_semantic_suite.groovy`, `test_query_suite.groovy`, `test_retrieval_suite.groovy`, `tests/test_wrapper.sh`, and the Python service tests are unchanged and must pass. With the flag off, a frozen run of tasks A to O reproduces `model-bench-r3-ndcg.json` in all non-timing fields. `python3 -m py_compile bench/*.py`.

## IMPLEMENTATION CHECKLIST

1. [x] Add `Config.TICKET_SELECT_MODES`, `ticketSelect()` and the generic-term list.
2. [x] Add `lib/TicketCoverage.groovy`: tokenizer, informative filter, sidecar load and build, greedy selection, generic-terms test.
3. [x] Change `MemoryRetrieval.searchTopic` for `gate` and `coverage`; add the result map and the explain line.
4. [x] Add `--ticket-select` and the help line to `03_query_memory.groovy` (exit 2 on a bad value).
5. [x] Add unit tests (tokenizer, informative filter, selection order, generic terms, gate, config) and run the engine suites.
6. [x] Rebuild the jar; run the wrapper test and the golden queries with the flag off.
7. [x] Add `ticketselect=<mode>` to `bench/model_bench.py` settings; measure the sidecar build time.
8. [x] Run the benchmark twice (section 4 item 2) and compute the statistics and the held-out split.
9. [x] Check that the flag-off run reproduces `model-bench-r3-ndcg.json`.
10. [x] Record the results and the recommendation in the documents (section 4 item 3).
11. [x] `py_compile`, `git status` (no ledger, corpus or sidecar tracked), render the commit handoff, record the turn.

_Execution note, 2026-11-08: steps 1 to 10 are done (results: section 14 of rerank_enhacement.md; step 11 is the handoff and the record). Steps 1 to 7: Suites after the build: semantic 177, query 80, query plan 119, retrieval 31, eval 27, chatlog 100, journal 25, worklog 42, reconciliation 33, shadow validation 44, memory lock 36, Python service 83 on three interpreters, wrapper 41; all pass. First use of `coverage` on a task corpus adds about 1 s once (the sidecar, 0.8 MB); later calls cost the same as `off`._

Any deviation from this plan returns to PLAN mode.

---

Created using Anthropic Claude. Keep this internal until a person has reviewed and verified it. This plan is a proposal and should stay on internal versions until a human has reviewed it. It refers to internal tickets: share it only with people allowed to see them.
