# Plan: five-way retrieval test on real queries (gold set)

Status: proposal, written on 2026-11-10. Nothing is built or run. It needs the owner's query phrases (section 3) and answers to the open questions (section 11). Any deviation from it returns to PLAN mode.

Parent documents: [comparison.md](comparison.md) (the three-branch method, its scoring formulas and weights, reused here), [rerank_enhacement.md](rerank_enhacement.md) sections 13 to 17 (why real queries are needed), [semantic-retrieval/tasks.md](semantic-retrieval/tasks.md) (T005, the gold set).

## 1. Purpose

All results so far rest on model-drafted ledgers and queries that ask for a summary of a topic. This test uses queries that the owner writes (real questions, Polish and English) and compares five ways of getting an answer from the same data, under the same context budget:

| # | Folder name | Method |
|---|---|---|
| 1 | `01-prompt-log` | The usual manual way: `grep` and `tail` on `prompt.log` |
| 2 | `02-engine-current` | `ai-memory-ingester query` as it runs today (after `sweep-memory`), without a re-ranker |
| 3 | `03-bm25` | Pure SQLite FTS5 `bm25` order, with no fusion, no extra signals, no neighbor rows |
| 4 | `04-mmarco` | Method 2 plus the cross-encoder `mmarco-mMiniLMv2-L12-H384-v1` (service running) |
| 5 | `05-bge` | Method 2 plus the cross-encoder `bge-reranker-v2-m3` (service running) |

Assumptions that the owner must confirm (section 11): "retrieval with memory-sweep like it is done right now" means the engine query with today's defaults; "retrieval like now with bm25" means the raw `bm25` ranking of the full-text index, without the engine's fusion and signals.

The test answers: which method gives an agent the most correct, current, traceable context for a real question, at what cost in time and bytes. It is not a test of answer writing.

## 2. Methods in detail

Common rules for all methods:
- Same snapshot (section 3.2): the data and `prompt.log` are frozen at one cutoff. `sweep-memory` runs once before the freeze, never during Phase 1.
- Same context budget: at most 22,000 UTF-8 bytes and 20 items (as in `comparison.md`).
- Same evidence per item: the first 1,050 characters of the document body (the rule of the earlier benchmarks); for method 1 the raw lines.
- Same exclusions: turns that discuss this test, or any comparison prompt, are removed from every method (the exclusion policy of `comparison.md`).
- Wall time includes process start. Each method runs 3 times; the first call is the cold time, the median is the warm time. A failed run is listed separately and not timed.

| # | What runs (template) | Output kept |
|---|---|---|
| 1 | Session-start read: `head -n $CUT prompt.log \| tail -n 400`. Topic search: `head -n $CUT prompt.log \| grep -n -i -E "$WORDS" -C 2`, where `$WORDS` are the grep words (rule changed on 2026-11-10, see the execution note): the identifiers in the phrase (upper-case or digit tokens such as `BNG`, `GHA`, `KD-6919`), or the terms that the owner gives in the column `grep_terms` of `queries.tsv`; when the phrase holds no identifier, the 3 rarest content words found in the log. Matches are taken newest first until the budget is full. Rule fixed before the run: for a query of type `recent` the context is the tail; for every other type it is the grep. | `raw-tail.txt`, `raw-grep.txt`, `context.md` |
| 2 | `./ai-memory-ingester query "$PHRASE" --limit 20 --json --rerank off --data-dir $SNAP`. Defaults stay: ticket selection `coverage`, neighbor setting `adjacent`, topic routing off, all archives (chat turns, worklogs, state capsules, artifacts). | `results.json`, `context.md` |
| 3 | Direct SQL on the snapshot databases: `documents_fts MATCH` with all content words (AND), ordered by `bm25(documents_fts)`; when fewer than 10 rows match, the same words joined by OR (the engine's relax rule). Same archives as method 2; rows from the databases merged by score. No recency, authority, impact, ticket or neighbor logic. | `results.json`, `context.md` |
| 4 | A service started from a temporary configuration with the model mmarco (512 tokens), then method 2's command with `--rerank on --rerank-depth 30 --rerank-budget-ms 10000`. Fusion weight 12 (today's default). | `results.json`, `context.md` |
| 5 | The same as method 4 with a service configured for `bge-reranker-v2-m3` (512 tokens). | `results.json`, `context.md` |

The budget of 10,000 ms for methods 4 and 5 measures quality without the effect of a time-out. The service time of each call (`service_ms`) is recorded, so the result against the production budget of 1.2 s can be read afterwards. A degraded run (no score from the model) is recorded as degraded and kept.

`context.md` is rendered by one function for methods 2 to 5: for each item in rank order, a header (rank, archive, path, date, score) and the evidence text, until the budget is full. Method 1 renders the raw lines with their line numbers.

## 3. Test set

### 3.1 Queries

The owner provides the phrases: the questions as the owner would type them, in Polish or English. Target: 30 queries (task T005 asks for 30). A pilot of 6 comes first. Suggested mix, to be adjusted by the owner:

| Type | Share | Example form |
|---|---|---|
| `narrow` | 30% | one fact: "which port did we choose for the CORS fix?" |
| `topic` | 25% | a topic without a ticket key: "gateway api migration konto" |
| `ticket` | 15% | a ticket key and a general word: "status of KD-6919" |
| `recent` | 15% | what happened in the last days |
| `old` | 10% | a decision from more than 2 months ago |
| `cross` | 5% | a question that spans two tickets |

At least 40% Polish. Each query goes into `queries.tsv` (tab separated): `id`, `phrase`, `language`, `type`, `ticket` (if any), `asked_on`, `writer`, `grep_terms` (optional, separated by `|`).

### 3.2 Snapshot and exclusions

Before Phase 1: run `sweep-memory` once, then freeze. Record the cutoff time, the line count and SHA-256 of `prompt.log` up to the cutoff, and the content fingerprints of the databases (as `bench/snapshot.py` does). The engine and SQL methods run on a copy of the data folder (`--data-dir`), the prompt log methods read only the lines up to the cutoff. The turns of this test session, and any turn that discusses the test, are listed as exclusions and removed from every method.

## 4. Folder layout (private, never tracked by Git)

Root: `repos/ai-memory-ingester/data/eval/real/` (git-ignored; the content is internal conversation text).

```text
real/
  queries.tsv
  snapshot.json
  test/
    q001/
      query.json                 phrase, language, type, ticket, cutoff, exclusions
      01-prompt-log/             command.txt  raw-tail.txt  raw-grep.txt  context.md  meta.json
      02-engine-current/         command.txt  results.json  context.md  meta.json
      03-bm25/                   command.txt  results.json  context.md  meta.json
      04-mmarco/                 command.txt  results.json  context.md  meta.json
      05-bge/                    command.txt  results.json  context.md  meta.json
      phase2/                    ledger.md  answers/  scores.csv  blind-map.json
    q002/ ...
```

`meta.json` holds: wall times (cold and warm), items selected, bytes, date span of the evidence, archives of the items, degraded flag, `service_ms`, exact flags and environment. `results.json` holds the ordered items (rank, archive, path, hash, score, bytes).

## 5. Phase 1: collection

For each query in `queries.tsv`:
1. Write `query.json` and the exclusions.
2. Run the five methods (section 2), three times each; keep the output of the first successful run and the three timings.
3. Render `context.md` for each method; check that no excluded turn is present.
4. Write `meta.json`.

After all queries: create the blind labels A to E per query (random, fixed seed) and store the map in `phase2/blind-map.json`; do not open it during scoring. Run the whole collection twice for the engine methods; the two result lists must be identical (determinism check; timings may differ).

The collector is a new tracked script, `bench/gold_collect.py`, with no query text inside. Failures (service did not start, query error) are written to `failures.tsv` and the method is marked failed for that query, never estimated.

## 6. Phase 2: scoring

### 6.1 Categories

The seven dimensions, formulas and weights are those of `comparison.md`:

| Dimension | Weight | Formula (short) |
|---|---|---|
| Factual coverage | 25% | 100 × recovered fact weight / eligible fact weight |
| Precision | 20% | average of retrieval precision (useful items / all items) and claim precision (supported claims / all claims) |
| Freshness | 15% | average of the event recency score and the latest-state coverage |
| Temporal correctness | 15% | share of status items classified correctly as current, historical or superseded |
| Authority | 10% | scale: 100 verified live or repository evidence, 80 structured worklog, 70 atomic turn or journal, 50 model summary, 0 unsupported |
| Traceability | 10% | claims with exact path and line or hash provenance / claims |
| Contradictions | 5% | known conflicts detected and resolved / known conflicts |

Diagnostics, reported next to the score and not part of it: wall time (cold and warm, p50 and p95 over the queries), bytes, items, duplicates, degraded runs, failures.

Two tiers, so that the cost stays under control:
- **Tier A (retrieval only, from the context alone):** coverage, retrieval precision, freshness, authority. Overall A uses the weights 25, 20, 15, 10 rescaled to 100.
- **Tier B (needs an answer):** claim precision, temporal correctness, traceability, contradictions. The full overall score (all seven) is computed only for queries with Tier B done.
A value that is not measured is written `Not measured` and never estimated.

### 6.2 Steps

1. **Ledger first (owner, blind to the results).** Before opening any `context.md`, the owner writes `phase2/ledger.md` for the query: 3 to 10 facts that a correct answer must contain, weights 3, 2 or 1, and which facts describe the latest state. Optional: known conflicts (for the contradiction score).
2. **Fact scoring.** For each method, each fact gets Full (1), Partial (0.5) or Missing (0), judged on the context only. Done per blind label. Coverage, freshness and retrieval precision (each item: useful, not useful, derivative of the test) come from this step.
3. **Isolated answers (Tier B).** A model writes an answer for each method from its context only, with the query and nothing else, and cites the item ids. The answers are produced before any cross-method comparison. Claim precision, temporal correctness, traceability and contradictions are judged on these answers.
4. **Score sheet.** `scores.csv` per query: one row per method and dimension, with the numerator and denominator (the denominators are always disclosed).
5. **Unblind** after all queries are scored.

### 6.3 Ranking by category

For each query and each dimension the five methods get ranks 1 to 5 (ties share the mean rank). Reported over all queries: the mean rank per dimension, the mean score per dimension, the weighted overall score (Tier A for all queries, the full score where Tier B exists), wins, and a breakdown by query type and by language. Paired bootstrap against method 2 (10,000 resamples, seed 1, `bench/stats.py`); an interval that includes zero is "not significant", whatever the mean difference. A second person scores 20% of the queries (every fifth); the agreement must reach kappa 0.6, otherwise the scoring rules are tightened and the sample is scored again.

### 6.4 Result tables

The final section of the report has one populated table per category (score, rank, denominator, timing) and one summary table with overall scores and diagnostics, as required by `comparison.md`.

## 7. Decision rules

- A method is recommended for a query type when its overall score is higher than method 2 with an interval above zero on at least 10 queries of that type, no dimension falls by more than 10 points, and the warm time stays within the budget of the use (1.2 s for the re-rank call).
- Methods 4 and 5 are compared with method 2 for the value of the cross-encoder, and with each other for the model choice (R41). The test settles whether the cross-encoder is worth its start and memory cost (about 4 s load and about 1.3 GB for mmarco; bge was not measured).
- If method 1 (grep and tail) is not beaten by methods 2 to 5 on a query type, that type stays a manual task and the engine is not recommended for it.
- The outcome is a recommendation. The owner decides.

## 8. Effort (counts, not time)

Per query: Phase 1 runs 5 methods three times (15 runs; the engine runs take about 1 to 2 s, a first service start about 4 s for mmarco; the start time of bge was not measured). Phase 2: one ledger (3 to 10 facts), then up to 5 × (3 to 10) fact judgments, the item judgments (up to 20 × 5), and for Tier B five answers with about 10 claims each. I have no measured time for the owner's work; the pilot of 6 queries gives it.

## 9. Privacy and handling

The collected files hold internal conversation text and may hold personal data. They stay in the git-ignored folder, are not shared, and are deleted when the owner decides. Only numbers and conclusions go into `docs/`. Answers written by a model stay in `phase2/answers/`. Any text taken into a document needs the owner's check first.

## 10. Limits

- 30 queries are a small sample. The pilot and the type breakdown show where the sample is thin.
- The ledger is the owner's judgement; the second reviewer limits, but does not remove, bias.
- Model-written answers for Tier B depend on the writer; the same writer and prompt are used for all methods, and the labels hide the method.
- Method 1 depends on the grep words; a person may search better than the fixed rule. The rule is fixed to keep the test repeatable.
- The snapshot is frozen; later turns are not seen by any method.
- The benchmark text limit of 1,050 characters per item hides facts that sit later in a long turn, for every method.

## 11. Open questions for the owner

1. Do the readings of methods 2 and 3 in section 1 match what you meant?
2. Your phrases: 30 queries (or 6 for the pilot first), with type and language. The mix in section 3.1 can change.
3. Is the folder `repos/ai-memory-ingester/data/eval/real/` (git-ignored) acceptable as the `test/` location?
4. All archives (chat turns, worklogs, capsules, artifacts) for methods 2 to 5, or chat turns only?
5. A budget of 22,000 bytes and 20 items for every method: acceptable?
6. For method 5 (bge): measure with a 10 s time limit (quality) and also report the service time against 1.2 s?
7. Who writes the ledgers (you), and who scores 20% as the second reviewer?
8. Tier B (isolated answers by a model): do you want it, or Tier A only at first?

## Execution note, 2026-11-10 (first query q001)

- The owner gave the first phrase (q001, type `topic`, English). The open questions of section 11 were not answered; the defaults of this plan were used: all archives, 22,000 bytes and 20 items, bge with a 10 s limit and the service time recorded.
- Snapshot frozen after one `sweep-memory` (journal sequence 29552). Excluded from every method: all turns (251) and artifacts (9) of this Claude Code session (found by the journal observation id and the session field), and the 75 `prompt.log` entries of this session (found by their exact text). The snapshot is `data/eval/real/snapshot/` (databases, a filtered `prompt.log`, `snapshot.json`).
- Collector `bench/gold_collect.py` written (commands `snapshot`, `collect`, `labels`). The blind labels are not made yet: they are made after the last query.
- Method 1 rule changed during the dry run: the first rule (the 3 rarest content words) picked `informations`, `gather`, `development` and produced a useless context. A person greps identifiers, so the rule now takes the upper-case or digit tokens of the phrase (here `BNG`, `GHA`), or the owner's `grep_terms`.
- Finding from q001: methods 2 to 5 return only `chat:artifacts` (model reasoning text) in the top 20; no chat turn, worklog chunk or state capsule is among them. The same problem was seen in earlier benchmarks (R32). Method 1 (grep `BNG|GHA`) matches 4,188 lines in 463 windows; the context holds the 20 newest windows.
- Timings for q001 (cold / warm, process start included): method 1 0.29 / 0.29 s; method 2 1.09 / 0.98 s; method 3 0.02 / 0.02 s; method 4 1.87 / 1.26 s; method 5 10.46 / 1.09 s (the first call loads the 2.1 GB model).
- Phase 2 for q001 needs the owner's ledger (section 6.2, step 1), written before the results are opened.

## IMPLEMENTATION CHECKLIST

1. [ ] Owner: provide the phrases (`queries.tsv`) and answer the open questions of section 11.
2. [x] Freeze the snapshot (`sweep-memory`, cutoff, hashes, data copy) and list the exclusions.
3. [x] Write `bench/gold_collect.py` (methods 1 to 5, folder layout, `meta.json`, failures, blind map).
4. [~] Dry run on 2 queries (done on q001 only on 2026-11-10); check the folders, the budget, the exclusions and the determinism.
5. [ ] Pilot: 6 queries, Phase 1 and Phase 2; report the effort and fix the rules.
6. [ ] Full set: Phase 1 for all queries.
7. [ ] Write `bench/gold_score.py` (score sheets from the ledgers, ranks per category, bootstrap, tables) and test it on the pilot.
8. [ ] Owner: ledgers, then fact and item scoring per blind label; second reviewer for every fifth query.
9. [ ] Tier B if chosen: isolated answers, claim scoring.
10. [ ] Unblind, compute the tables of section 6.4, write the report in `docs/` and the decision log entry.
11. [ ] `py_compile`, `git status` (nothing from `data/eval/real/` tracked), commit handoff, record the turn.

Any deviation from this plan returns to PLAN mode.

---

Created using Anthropic Claude. Keep this internal until a person has reviewed and verified it. This plan is a proposal and should stay on internal versions until a human has reviewed it. It refers to internal conversation data: share it only with people allowed to see it.
