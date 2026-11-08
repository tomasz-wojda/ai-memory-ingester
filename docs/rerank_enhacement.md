# Re-rank Retrieval Enhancement: Findings and Checklist

Status: proposal. No step in the checklist is started. The findings come from the tests of 2026-11-03. Related documents: [comparison.md](comparison.md) (task results), [semantic-retrieval/evidence.md](semantic-retrieval/evidence.md) section 7 (model speed and load time), [semantic-retrieval/decision-log.md](semantic-retrieval/decision-log.md) (D-04), [semantic-retrieval/tasks.md](semantic-retrieval/tasks.md).

Legend: `OWNER` marks a step that needs the owner. `[ING]` means the work is in `repos/ai-memory-ingester`. Every item states the evidence behind it, how to measure it, and the acceptance rule. A hypothesis is marked as such. Nothing in this document is proven on real, graded data: the gold set has no grade 2 or 3 labels, and the ledger benchmarks hold four tasks.

---

## 1. Purpose and scope

Goal: raise the quality of the context an agent gets from memory by improving the re-rank stage, without breaking the time budget (1.2 s for 30 passages by default).

What "perfect" can mean here: no retrieval system is perfect, so this document defines it as measurable gates (section 6). A step is kept only when it passes its gate on the benchmark in section 5, with no regression on any task.

---

## 2. How the re-rank stage works today

Facts read from the code on 2026-11-03.

| Part | Behavior | Source |
|---|---|---|
| First stage | Strict FTS5 AND query, then a relaxed OR and prefix query when the strict query returns fewer than 10 rows. Strict rows stay first | `lib/MemoryRetrieval.groovy`, `lib/Config.groovy` |
| Candidates | The fused first-stage list. Depth 30 by default, 1 to 100 allowed (`--rerank-depth`) | `03_query_memory.groovy` |
| Passage split | Structure first, then windows of 900 characters with a stride of 675 | `lib/PassageBuilder.groovy` |
| Passage choice | At most 3 passages per document. When a document has more than 3, the 3 with the most query words in common are kept (ASCII-folded word overlap). If none share a word, the first passage is kept | `PassageBuilder.select` |
| Passage cap | At most 100 passages per request | `lib/RerankStage.groovy` |
| Scoring | The service scores each (query, passage) pair with the cross-encoder. A document keeps its best passage score (MaxP) | `lib/RerankStage.groovy` |
| Model input limit | `max_tokens` is 256 unless the model entry sets another value | `service/ai_memory_service/backends.py` |
| Fusion | The re-rank signal is added to the other signals by weighted reciprocal rank fusion: `weight / (60 + rank)`. Weights: exact 4, bm25 3, bm25_relaxed 2, recency 2, authority 2, impact 1, rerank 12 (5 until 2026-11-04, see D-34), late 3 | `lib/SignalFusion.groovy` |
| Degraded mode | Fewer than 10 passages scored, a down service, or a missed deadline keeps the lexical order and reports a reason | `lib/RerankStage.groovy` |
| Default model | `mmarco-mMiniLMv2-L12-H384-v1` on MPS at depth 30 (D-04, confirmed 2026-11-03, for now) | `decision-log.md` |

Consequences of these facts that matter for the checklist:

- The final order is not the model order. A document that is first for the model gets at most `5 / 61` from the re-rank signal, while the other signals can add up to 8 to 12 weight units together.
- A lexical word-overlap filter picks the passages the model sees. The neural model never sees a passage that shares no word with the query, unless it is the first passage.
- With 3 passages per document and a cap of 100 passages, only about 33 documents are scored, however large the depth is.
- The evidence that the engine prints (snippet) is the head of the document. The best passage that the model chose is stored in `best_passage`, but the benchmark scored the head, not that passage.

---

## 3. Findings

### 3.1 Model speed, load time and memory

Apple M4, 128 words per passage, medians (evidence.md section 7). The engine limit is 1.2 s for 30 passages.

| Model | 30 passages on MPS | 30 passages on CPU | Per passage (MPS / CPU) | Load (MPS / CPU) | Peak memory |
|---|---:|---:|---|---|---|
| ms-marco L-6 (English) | 70 ms | 226 ms | 2.3 / 7.5 ms | 4.0 / 2.2 s | 0.6 GB |
| ms-marco L-12 (English) | 131 ms | 434 ms | 4.4 / 14.5 ms | 2.4 / 2.2 s | 0.6 GB |
| **mmarco (multilingual, small)** | **171 ms** | **597 ms** | 5.7 / 19.9 ms | 3.6 / 3.4 s | 1.1 to 1.3 GB |
| bge-reranker-v2-m3 (large) | 1,816 ms | 4,467 ms | 60.5 / 149 ms | 6.1 / 4.7 s | 1.0 to 2.5 GB |
| polish-reranker-roberta-v3 | 2,356 ms | 5,589 ms | 78.5 / 187 ms | 4.4 / 3.7 s | 0.7 to 2.2 GB |

- Load time depends little on model size (2 to 6 s). The service loads the default model at start, so a query does not pay it. A model that is not the default loads on first use and misses the 1.2 s budget.
- Only the three small models fit the budget at depth 30 on MPS. mmarco and the two English models also fit on the CPU.
- A first benchmark counted words as tokens and overstated the load about twice. The corrected table above replaced it.
- polish-reranker-roberta-v3 has Gemma terms and is excluded by the model policy unless IP Legal approves.

### 3.2 Sanity probe (a failure detector, not a ranking)

Eight made-up paraphrase items per set. A random order scores about 0.52 (MRR).

| Model | English | Polish | ASCII question, diacritics in passages | All ASCII |
|---|---:|---:|---:|---:|
| ms-marco L-6 | 0.46 | 0.51 | 0.57 | 0.51 |
| ms-marco L-12 | 0.40 | 0.53 | not run | 0.59 |
| mmarco | 0.82 | 0.84 | 0.73 | 0.50 |
| bge-reranker-v2-m3 | 0.81 | 0.84 | 0.82 | 0.64 |
| polish-reranker-roberta-v3 | 0.69 | 0.91 | 0.91 | 0.91 |

- English-only models are near random on this probe. They are no candidates for a bilingual memory.
- mmarco falls to random when both the question and the passages lose their diacritics. Passages must keep their diacritics.
- With 8 items per set, a difference of 0.1 is noise.

### 3.3 Ledger benchmark of the models (8 queries, chat turns only)

Two tasks (SCTASK1544083 and MongoDB metrics), four phrasings each. Weighted fact coverage (%) at the top 5, 10 and 20 chat turns. The time budget was set to 10 s so that all models could finish.

| System @ depth | Engine order (5 / 10 / 20) | Model-only order (5 / 10 / 20) |
|---|---|---|
| lexical (no re-rank) | 25.6 / 39.3 / 58.8 | same |
| ms-marco L-6 @30 | 27.4 / 40.2 / 62.9 | 36.5 / 46.1 / 65.0 |
| ms-marco L-12 @30 | 24.5 / 45.3 / 62.9 | 38.5 / 46.1 / 66.0 |
| mmarco @30 | 28.9 / 44.7 / 65.3 | 39.9 / 51.6 / 64.2 |
| bge-m3 @30 | 25.2 / 32.0 / 62.4 | 31.1 / 52.3 / 62.7 |
| polish-roberta @30 | 32.4 / 38.5 / 66.0 | 42.4 / 52.3 / 63.7 |
| mmarco @100 | 30.3 / 37.1 / 61.9 | 36.0 / 55.9 / 60.8 |

- Every model beats lexical order in the model-only order at the top 10 (46 to 56 against 39.3).
- The models cannot be separated on 8 queries. One query is worth about 12% of a cell.
- **The engine order loses most of the gain.** mmarco @100 scores 55.9 in model-only order at the top 10 and 37.1 in engine order, below lexical (39.3). The cause is the fusion with the other signals.
- Without an archive filter, the re-ranker scored about 30 documents, mostly worklogs and artifacts, and the chat-turn order never changed. The benchmark used `-A chat:turns` for this reason.
- Depth 100 scores only about 33 documents (the 100-passage cap with up to 3 passages per document).
- On the Polish ArgoCD queries (task A) every model scored 27.8 and 19.4 at the top 10 in model-only order, against 41.7 and 33.3 for lexical. All four models gave identical numbers. This is **unexplained**.

### 3.4 Four-task comparison (comparison.md)

Weighted strict-fact coverage over all selected items (%). One query per task. The ledgers for tasks C and D were drafted by the benchmark author from the worklog or capsule, so the indexed worklog branch has a built-in advantage on them.

| Task | Direct prompt.log | FTS5 top 20 (chat turns) | FTS5 + mmarco (chat turns) | Indexed worklogs | FTS5 all sources + mmarco |
|---|---:|---:|---:|---:|---:|
| A: SCTASK1544083 | 63.9 | 61.1 | 61.1 | 86.1 | **94.4** |
| B: MongoDB metrics | **93.8** | 77.1 | 77.1 | 66.7 | 56.2 |
| C: Trace in Konto (KD-6945) | 12.8 | 5.1 | 33.3 | **82.1** | 46.2 |
| D: Summary of KD-8130 | 47.5 | 50.0 | 57.5 | 47.5 | **65.0** |

Task A and B values for the worklog and all-source columns come from `three-way-run8.json`. They are not in comparison.md.

- **No branch wins on every task.** The all-source re-rank is best on A and D, worst on B, and middle on C.
- **Re-ranking changes the selected set only when the first 20 do not already fit.** On A and B, the chat-turn re-rank changed only the order inside the same 20 results. On C and D, it pulled different turns into the top 20 (C: 5.1 to 33.3, D: 50.0 to 57.5).
- **Short or vague queries are the weak spot of plain FTS5.** For "Trace in Konto", the top 20 holds short administrative turns ("still waiting on your Tempo lookup") and scores 5.1.
- **The source mix decides the all-source result.** On C, 9 of the 20 results were model-reasoning artifacts. On B, only 9 of 20 were useful. The mix of archives mattered more than the model.
- **Direct prompt.log is slow (4.7 s) and fragile.** On C, the newest entries are large (up to 17 KB), so a 22,000-byte newest-first window holds 3 to 7 entries and none holds a decision fact. On D, selecting by the parsed ticket key gives 5.0, while selecting by ticket text gives 47.5, because 17 entries mention the ticket without a parsed key.
- **The state capsule is stale for a live ticket.** For KD-8130 it matched 8 of 20 fact patterns and still said PLAN with G1 and G2 pending.

### 3.5 Limits of the evidence

- Four tasks, one query each (eight queries for tasks A and B in the model benchmark). A one-fact difference is worth 2.5 to 12 points.
- Ledgers C and D were drafted by the benchmark author and not adjudicated by the owner. Anchors are regular expressions, not human grading.
- Only coverage, a retrieval-precision proxy, authority and traceability were measured. Freshness, temporal correctness, contradictions and claim precision were not measured.
- Snippets of 1,050 characters from the head of each document were scored, not the best passage chosen by the model.
- No time cutoff applies to worklog and artifact documents in the all-source branch.
- Unexplained: the Polish ArgoCD regression, the identical results across four models, and why depth 100 ran faster than depth 30 in the model benchmark.

---

## 4. Hypotheses to test (not findings)

| ID | Hypothesis | Basis |
|---|---|---|
| H1 | A larger weight for the re-rank signal, or ordering by the model score, recovers most of the model-only gain | 3.3: model-only 55.9 against engine 37.1 at the top 10 |
| H2 | Scoring the evidence with the best passage instead of the head of the document raises coverage under a fixed byte budget | 3.5: the benchmark scored the head, the model chose a passage |
| H3 | Scoring more passages per document and more documents raises recall at the top | 2: lexical filter, 3 passages, 100 cap |
| H4 | Bigger windows help questions whose answer spans several sentences, and hurt when the window exceeds `max_tokens` | 2: windows of about 1,000 characters with the prefix give about 246 tokens for mmarco against a limit of 256 |
| H5 | Dropping or capping low-information turns and model-reasoning artifacts before re-ranking raises precision | 3.4: tasks B and C |
| H6 | Routing a ticket-key query to the ticket facet and the capsule first raises the result on ticket questions | 3.4: task D |
| H7 | A cascade (mmarco on 50, a large model on the top 10) beats mmarco alone within budget | 3.1: about 0.3 s plus 0.6 s on MPS, an estimate |

---

## 5. Measurement foundation (needed before tuning)

The tuning steps below are only safe when the measurement is sound. These steps come first.

- [x] **R01 [ING] Promote the harness into the repository.** The benchmark scripts live in `data/eval/ledger-run/` (git-ignored). Move the generic code (`model_bench.py`, `three_way.py`) into a tracked folder. Keep the ledgers and any internal text out of the repository. Accept: one command runs every task and writes a JSON report.
- [ ] **R02 OWNER Adjudicate ledgers C and D.** Review the facts, weights and anchors that the author drafted. Accept: owner sign-off recorded in `decision-log.md`.
- [x] **R03 [ING] Add at least 10 more tasks** with ledgers from different tickets and topics: long and short tickets, Polish and English, ticket-key questions, keyword questions, and paraphrases. Accept: at least 14 tasks, with at least 3 queries per task. A one-fact change then moves a score by about 1 point or less. _Done 2026-11-08: 15 tasks, 43 queries, ledgers E to O not yet adjudicated (section 13)._
- [ ] **R04 [ING] Add the measures that are missing**: nDCG@10 and recall@k against graded facts, claim precision, freshness, temporal correctness and contradictions (manual for the last four), and the p50 and p95 latency. Accept: the full seven-dimension score is computed for at least the two newest tasks. _Partly done 2026-11-08: nDCG@10 added (section 13.7); recall@k is cov@k; the four manual dimensions are not scored._
- [x] **R05 [ING] Score the best passage, not the head.** Add an evidence mode that uses `best_passage` (or the top passage list) as the snippet. Accept: both modes are reported side by side (tests H2).
- [x] **R06 [ING] Add paired statistics.** Report per-query differences against the lexical baseline, a bootstrap interval, and a win/loss count. Accept: a change counts as an improvement only when the interval excludes zero or it wins on a clear majority without any large loss.
- [x] **R07 [ING] Add a cutoff for worklog and artifact documents** in the all-source branch (by document date, or a frozen database copy). Accept: no document newer than the freeze point enters the scored set.
- [x] **R08 [ING] Freeze and version the databases per run.** Record the file sizes, hashes and sweep time of `convo.db`, `worklogs.db` and `artifacts.db` in every report. Accept: a rerun on the same snapshot gives identical numbers.
- [x] **R09 [ING] Explain the three open anomalies**: the Polish ArgoCD regression (all models identical), the depth 100 speed, and the identical results of different models on some queries. Accept: each has a documented cause or a reproduction.
- [ ] **R10 OWNER Decide the target and the budget.** Confirm the time budget (1.2 s at depth 30), whether CPU-only machines must also meet it, and the accepted regression per task.

---

## 6. Gates (proposed values, to be confirmed by the owner)

A change passes when all of the following hold on the full benchmark:

1. Mean weighted coverage at the top 10 rises by at least 5 points against the lexical baseline, and does not fall on any task by more than 3 points against the current engine.
2. Retrieval precision does not fall on any task by more than 5 points.
3. The p95 retrieval time with the re-ranker stays within the budget (default 1.2 s for the re-rank call) on MPS, and the degraded rate stays below 2% of queries in the benchmark.
4. The result is reproducible on the frozen snapshot.
5. The paired interval of R06 excludes zero, or the change wins on at least 75% of queries with no loss above 10 points.

---

## 7. Improvement checklist

Order: measure first (section 5), then fusion, then recall, then chunking, then query side, then source mix, then evidence assembly, then model choice, then operations. Each step is tested alone and behind a flag, so a failed step is undone before the next one (one change at a time).

### Phase A: fusion (highest evidence)

- [x] **R11 [ING] Make the signal weights configurable** (`AI_MEMORY_RERANK_WEIGHT` and a config key) without changing the default. Accept: the default order is byte-identical to today.
- [x] **R12 [ING] Sweep the re-rank weight** over 5, 8, 12, 20 and a model-order-only mode. Measure the engine-order coverage against the model-only coverage from R04. Evidence: 3.3. Accept: the gap between engine order and model-only order at the top 10 falls below 5 points, with the gates passed.
- [x] **R13 [ING] Test score-based fusion.** Replace the rank-based re-rank signal with a normalized model score (for example a logistic or z-score of the cross-encoder score) added to the other signals. Reason: rank fusion discards how far apart two scores are. Accept: gates pass and it beats the best weight of R12.
- [x] **R14 [ING] Test "re-rank, then cut"** for the final list: when the re-rank is not degraded, order the scored documents by model score and keep the other signals only as tie-breakers and for unscored documents. Accept: gates pass.
- [x] **R15 [ING] Keep the guarantees.** The strict rows stay first, the ticket capsule pin still works, and the rank of an exact match is not lowered below a defined floor. Accept: the existing retrieval suites (query plan, semantic) pass without edits to their expected values, or each changed value is justified.

### Phase B: candidate recall and depth

- [x] **R16 [ING] Measure recall of the first stage.** For each ledger fact, find the rank of the first passage that carries it in the first-stage list. Accept: a table of recall@10, 30, 50, 100 per task. Evidence: facts that never enter the first 30 cannot be repaired by re-ranking.
- [ ] **R17 [ING] Make the passage cap depend on the time budget.** Today the cap is a fixed 100 passages and 3 per document. On MPS, mmarco needs about 5.7 ms per passage (measured up to depth 50), so 200 passages is about 1.1 s (an estimate, to be measured). Choose the number of passages from a measured cost per passage and the remaining budget. Accept: p95 stays inside the budget on MPS and on CPU, and the degraded rate stays below 2%.
- [ ] **R18 [ING] Test depth against documents scored.** Run depths 30, 50, 100 and 200 with the passage cap raised, and report how many documents are scored, the coverage and the time. Accept: a depth that gains at least 3 points inside the budget, or a documented decision to keep 30.
- [ ] **R19 [ING] Use a partial result when the deadline is near.** Score the highest-ranked passages first, and use the scored subset instead of dropping the whole re-rank when the time runs out. Accept: the degraded rate falls without a quality loss.
- [ ] **R20 [ING] Test a cheap first re-rank stage.** English-only models are not suitable as a cheap first stage. Test mmarco at depth 100 followed by a second model on the top 10 (see R42). Accept: gates pass within budget.

### Phase C: chunking (bigger chunks and alternatives)

- [ ] **R21 [ING] Measure truncation.** Count how many passages exceed `max_tokens` (256 by default) for each model tokenizer, and the effect on the score. Evidence: an engine window of about 1,000 characters (900 plus the prefix) is 246 tokens for mmarco and 308 for polish-roberta, so a larger window is truncated unless `max_tokens` rises. Accept: a table of truncated passages per model and window size.
- [ ] **R22 [ING] Test window sizes.** Windows of 600, 900 (current), 1,500 and 2,400 characters, with `max_tokens` set to fit (256 to 512), stride at 75% of the window. Report coverage, time per passage and truncation. Evidence for the cost: time rises with the number of tokens. Accept: the best size wins on the gates, or the current size is kept with evidence.
- [ ] **R23 [ING] Test passages per document.** Test 1, 3 and 5 per document, with the selection by word overlap, and with all passages scored when the budget allows. Accept: gates pass.
- [ ] **R24 [ING] Replace the word-overlap passage filter.** Candidates for the filter: BM25 score of the passage inside the document, or scoring every passage of the top documents. Reason: a passage with no shared word, for example a Polish inflection or a paraphrase, is invisible to the model today. Accept: coverage rises on paraphrase and Polish queries without a time loss.
- [ ] **R25 [ING] Test structure-aware chunking.** Cut at sections, headings, turn boundaries and sentences instead of fixed windows. Keep the document title, ticket key and section name as a prefix (a prefix exists today). Accept: gates pass.
- [ ] **R26 [ING] Test a two-level chunk (small to find, large to read).** Score small windows, then return or score the larger parent window. Accept: coverage under the byte budget rises (links with R32).
- [ ] **R27 [ING] Rebuild or invalidate the stored passages when the chunking changes.** The late-interaction index and `semantic.db` depend on the passage split. Accept: a documented migration and a test.

### Phase D: query side

- [ ] **R28 [ING] Route ticket-key queries.** When the query holds a ticket key, restrict or boost that ticket's documents and keep its capsule first (the pin exists for facets). Evidence: task D. Accept: ticket-key tasks improve without a loss on others.
- [ ] **R29 [ING] Handle short and vague queries.** Add query expansion from the ticket vocabulary, or a minimum information rule that sends such a query to the relaxed path and the re-ranker with a larger depth. Evidence: "Trace in Konto" scored 5.1 with plain FTS5. Accept: short-query tasks improve.
- [ ] **R30 [ING] Keep the diacritics path.** Check that the passages and the query keep their diacritics up to the model, and measure the ASCII-only case (3.2). Accept: a test that fails if diacritics are stripped before scoring.
- [ ] **R31 [ING] Test language-aware handling.** Detect Polish and English queries, and test a model or setting per language only if the benchmark shows a gain. Accept: documented result, no change without a gain.

### Phase E: source mix and noise

- [ ] **R32 [ING] Down-weight or exclude model-reasoning artifacts** (`chat:artifacts`) in the default all-source search, or cap each archive's share of the top 20. Evidence: task C (9 of 20) and task B. Accept: tasks B and C improve, and task A does not fall.
- [ ] **R33 [ING] Filter low-information turns before re-ranking.** Candidates: very short turns, turns that only wait for the user, repeated status lines. Accept: precision rises without losing facts (checked against all ledgers).
- [ ] **R34 [ING] Remove near-duplicates and neighbor turns** from the scored set. Accept: precision rises and no fact is lost.
- [ ] **R35 [ING] Add a freshness rule for stale capsules.** When the capsule's content is older than newer worklog action entries, rank the newer entries higher or mark the capsule stale (the freshness metadata exists). Evidence: the KD-8130 capsule matched 8 of 20 facts. Accept: ticket tasks improve on freshness.
- [ ] **R36 [ING] Fix the journal selector for the direct branch.** Match entries that mention the ticket even without a parsed key, and cap the size of a single entry in the packed window. Evidence: task D (5.0 to 47.5) and task C. Accept: the direct branch improves on D and C without a loss on A and B.

### Phase F: evidence assembly

- [ ] **R37 [ING] Use the best passage as the snippet** in the engine output and in the packed context, with a link to the full document. Hypothesis H2. Accept: coverage under a fixed budget rises (R05). _Evaluated 2026-11-04: the condition was not met (section 10.6), not built._
- [ ] **R38 [ING] Pack the context by gain, not by rank.** Choose items by new facts per byte (a diversity rule such as MMR) and skip items that repeat content. Accept: coverage per byte rises.
- [ ] **R39 [ING] Expand neighbors only after the packed set is chosen**, and measure whether the expansion adds facts or noise. Accept: gates pass.

### Phase G: model choice

- [ ] **R40 OWNER Keep mmarco as the default** until the larger benchmark of R03 to R04 exists. The current evidence cannot separate the models.
- [ ] **R41 [ING] Run the model bake-off on the larger benchmark** (mmarco, bge-reranker-v2-m3, and any new Apache-2.0 or MIT candidate). Accept: a model replaces the default only when it wins on the gates and fits the budget.
- [ ] **R42 [ING] Test a cascade**: mmarco on 50 passages, then bge-reranker-v2-m3 on the top 10 (about 0.3 s plus 0.6 s on MPS, an estimate). Accept: gates pass within budget on MPS, and a documented CPU fallback.
- [ ] **R43 OWNER Decide on fine-tuning only after labels exist.** At least a few hundred graded pairs from the owner's real questions, split into dev and test, are needed. Accept: a written decision.
- [ ] **R44 OWNER IP Legal confirms Apache-2.0** for the chosen models and the 41 packages (`tqdm` is MPL-2.0 AND MIT). No model changes before this.
- [ ] **R45 [ING] Implement the model fallback queue** if wanted (see the earlier design): ordered models, a fallback on load failure, a cost-based passage cut on slowness, and a lexical end of the queue for Polish queries. Accept: tests for each failure case.

### Phase H: operation and speed

- [x] **R46 [ING] Cache scores** by hash of (model revision, query, passage). Accept: a repeated query is served from the cache with identical results.
- [ ] **R47 [ING] Tune the batch size and threading** for MPS and CPU, and report the effect. Accept: lower latency at the same scores.
- [ ] **R48 [ING] Report latency percentiles in the service** (p50, p95, degraded rate, reason counts) so the gates can be watched in daily use.
- [ ] **R49 OWNER Decide how the service starts.** Today the engine does not start it, so `--rerank auto` does nothing unless it runs. Options: a launcher, a login item, or a documented manual start.
- [ ] **R50 [ING] Write the default model into the service config** (`.ai-worklog/memory-service.json`) with the folder checksum, after R40 and R44.

### Phase I: rollout

- [ ] **R51 [ING] Ship each accepted change behind a flag, off by default.** Run it in shadow mode (compute, log, do not use) for a period, and compare the logged order against the live order.
- [ ] **R52 [ING] Keep a regression run in the routine.** The full benchmark runs before each change is turned on. A change that fails a gate is not turned on.
- [ ] **R53 [ING] Update the documents.** Record each accepted change in `decision-log.md`, the numbers in `evidence.md`, the task state in `tasks.md`, and the contract in `service-contract.md`.

---

## 8. Priority and expected value

| Priority | Steps | Why first |
|---|---|---|
| 1 | R01 to R09 | Without a larger and sound benchmark, any gain below about 5 points is noise |
| 2 | R11 to R15 | The largest measured gap (model-only against engine order) |
| 3 | R37, R05 | Cheap, and it may change what the benchmark rewards |
| 4 | R16 to R19 | Recall and the passage cap limit what the model can fix |
| 5 | R21 to R26 | Bigger chunks and a better passage filter (the owner's question) |
| 6 | R28, R29, R32, R33, R35, R36 | Query and source problems seen on tasks B, C and D |
| 7 | R38 to R45 | Packing, model choice and fallback |
| 8 | R46 to R53 | Operation and rollout |

The expected size of each gain is unknown until the steps run. The only measured upper bound is the gap between the model-only order and the engine order (about 19 points at the top 10 for mmarco @100 on the 8-query set).

---

## 9. Owner decisions

| ID | Question | Needed for |
|---|---|---|
| R02 | Are the ledgers for tasks C and D correct? | R03, R04 |
| R10 | What are the budget, the CPU requirement and the accepted regression? | The gates in section 6 |
| R40 | Keep mmarco until a larger benchmark exists? | Phase G |
| R43 | Collect labels for fine-tuning, and when? | Phase G |
| R44 | Ask IP Legal to confirm Apache-2.0 | Any model change |
| R49 | How should the service start? | Phase H |

---

## 10. Tranche 1 results (plan: [rerank_enhacement_plan.md](rerank_enhacement_plan.md))

Status on 2026-11-04. This section is written during execution and extended at each gate.

### 10.1 Harness (R01, R03 tooling, R05, R06, R08, R11 part, R12, R14)

- The harness is in `bench/` (`common.py`, `model_bench.py`, `three_way.py`, `stats.py`, `snapshot.py`, `dump_journal.groovy`). It has no absolute path. Ledgers stay in `data/eval/ledger-run/tasks/` (git-ignored).
- Equivalence: the new `three_way.py` and the old script gave 0 differences over all fields of all branches for the four tasks on the same snapshot. The new and old `model_bench.py` gave 0 differences over 20 cells (lexical and mmarco, all queries).
- Determinism: three consecutive runs of the new harness on the same data were identical in every coverage field.
- New options: `--evidence head|best_passage`, `--repeats N` (p50, p95, degraded runs), `--settings` (fusion and weight, used after step 21), per-query CSV, first-stage fact recall, undated-document counter, and paired statistics (`stats.py`: mean difference, wins, losses, ties, bootstrap 95% interval with 10,000 resamples and seed 1).
- The benchmark has 10 queries, not 12 as written earlier (tasks A and B have 4 each, tasks C and D have 1 each).

### 10.2 Findings about the measurement itself

- **A query changes the database files.** The engine sets WAL mode when it opens a database, so a query alone changes the byte hash of `convo.db`, `worklogs.db` and `artifacts.db` while the content stays the same. A plan deviation: `snapshot.py` records size, modification time and SHA-256 as specified, plus a content fingerprint (document count, content bytes, maximum id, and a hash over ids and contents). The fingerprint stays stable after queries and is the key used to compare snapshots.
- **Numbers for tasks C and D changed between runs on different days.** Against `three-way-run8.json` (2026-11-03), tasks A and B reproduce exactly, but the re-ranked cells of C and D differ: C all-source 46.2 became 23.1, D all-source 65.0 became 60.0, and some byte counts changed. The cause is not determined: the earlier run recorded no content fingerprint, so a data change between the runs (new swept turns or documents) cannot be proved or excluded. The re-ranker values for tasks C and D in `comparison.md` are therefore as of the earlier run and are not reproducible on the current data. All Tranche 1 comparisons use runs on one snapshot.

### 10.3 First-stage recall (R16 part)

Share of strict fact weight that appears in the first-stage list (no re-rank, limit 300), by rank. A fact counts when any retrieved document contains its anchors anywhere in its full text.

| Task | Source | @10 | @30 | @50 | @100 | Never found |
|---|---|---:|---:|---:|---:|---|
| A | chat turns | 33.3 | 69.4 | 69.4 | 69.4 | F03, F07, F12, F18 |
| A | all sources | 94.4 | 100 | 100 | 100 | none |
| B | chat turns | 75.0 | 93.8 | 93.8 | 93.8 | M10 |
| B | all sources | 87.5 | 100 | 100 | 100 | none |
| C | chat turns | 0.0 | 33.3 | 48.7 | 71.8 | C07, C09, C11, C12, C19 |
| C | all sources | 94.9 | 100 | 100 | 100 | none |
| D | chat turns | 35.0 | 65.0 | 70.0 | 70.0 | D02, D05, D13, D18 |
| D | all sources | 67.5 | 85.0 | 90.0 | 90.0 | none |

- With all sources, every fact is in the first 30 documents for tasks A, B and C, and 85% by weight for task D. Candidate recall is not the limit there.
- With chat turns only, the first 30 documents hold only a third of the weight on task C, and facts that are absent from every turn in the list cannot be repaired by re-ranking. This confirms that raising depth matters for chat-turn search (R18).

### 10.4 Anomalies (R09)

**Polish ArgoCD regression (task A, queries `pl` and `pl-diacritics`).**
- Not a model failure. The lexical top 10 for these queries consists mostly of neighbor triplets (for example turns 914 to 916 and 1665 to 1666). Neighbors are added after re-ranking and are never scored.
- In the model-only order, unscored rows follow all scored rows, so the neighbor turns that carry the facts F02 and F17 drop out of the top 10. In the engine order, the model promotes other anchors (turns 366, 882 and 1070 with their neighbors) over turns 915 and 1665, so those two facts also leave the top 10.
- At the top 20 there is no loss. The cause is a set effect at a small cut-off, not a quality loss at depth.

**Identical results across models.**
- Not a harness fault. For every query the five models produce five different orders. The top-10 sets are identical for some queries (for example the Polish query of task A and the English paraphrase of task B), because only 12 to 17 chat documents are scored and the top 10 is almost the whole scored window. Coverage at the top 10 is a set measure, so models that rank the same window give the same number. This is why the model benchmark cannot separate the models.

**Depth 100 faster than depth 30.**
- An ordering effect. With the order alternated five times per depth on a fresh service, depth 30 takes 1.3 s and depth 100 takes 1.3 to 1.4 s (17 and 34 documents scored), so depth costs about 0.1 s here. The first call of a fresh service pays a one-time cost: 5.5 s when the first call is depth 30, and 2.9 s when it is depth 100. The model benchmark ran depth 30 first and absorbed that cost.
- Consequence for the default budget: with the 1.2 s budget the first real query after the service starts is likely to miss it. The start-up warm-up scores one short dummy pair and does not cover a realistic batch. This is a follow-up for phase H (a warm-up with a realistic batch).

### 10.5 Fusion sweep (R11 to R15, plan steps 17 to 29)

Setup: the rebuilt jar with `--rerank-weight` and `--rerank-fusion`, mmarco on MPS, depth 30, the 10 queries of the four tasks, chat turns only, head evidence, budget 10 s (no deadline skips). Weighted fact coverage (%), engine order unless stated. Two sweeps gave identical coverage.

| Setting | cov@5 | cov@10 | cov@20 | Precision@10 | Model-only cov@10 |
|---|---:|---:|---:|---:|---:|
| lexical (no re-rank) | 22.2 | 33.2 | 52.5 | 62.0 | 33.2 |
| **current default (rrf, weight 5)** | 25.6 | 40.3 | 61.3 | 55.0 | 47.4 |
| rrf, weight 8 | 21.7 | 41.5 | 61.3 | 56.0 | 47.4 |
| **rrf, weight 12** | 21.7 | **44.0** | 61.3 | 57.0 | 47.4 |
| rrf, weight 20 | 25.0 | 44.0 | 61.3 | 57.0 | 47.4 |
| rrf, weight 40 | 25.0 | 44.0 | 61.3 | 57.0 | 47.9 |
| score, weight 5 | 24.8 | 42.4 | 60.0 | 56.0 | 47.1 |
| score, weight 12 | 22.9 | 41.0 | 60.5 | 55.0 | 47.1 |
| score, weight 20 | 22.9 | 41.0 | 60.5 | 54.0 | 47.1 |
| cut | 25.0 | 44.0 | 61.3 | 57.0 | 47.9 |

Paired statistics for cov@10 (10 pairs, bootstrap 95% interval):

| Setting | Against | Mean difference | Interval | Wins / losses / ties | Worst loss | Per task (A, B, C, D) |
|---|---|---:|---|---|---:|---|
| rrf 12, rrf 20, rrf 40, cut (identical per query) | current engine | +3.69 | [0.12, 7.73] | 5 / 1 / 4 | -5.6 | -1.4, +3.7, +15.4, +12.5 |
| rrf 12 | lexical | +10.78 | [-4.32, 25.63] | 6 / 3 / 1 | -25.0 | -10.4, +23.5, +28.2, +27.5 |
| current engine | lexical | +7.09 | [-6.69, 20.89] | 6 / 3 / 1 | -25.0 | -9.0, +19.8, +12.8, +15.0 |
| rrf 8 | current engine | +1.19 | [-1.47, 4.83] | 2 / 1 / 7 | -5.6 | |
| score 5 | current engine | +2.12 | [-2.62, 6.51] | 4 / 1 / 5 | -15.0 | |
| score 12 and 20 | current engine | +0.73 | [-4.04, 5.31] | 3 / 2 / 5 | -15.0 | |

Depth 50 (default, rrf 12, cut):

| Setting at depth 50 | cov@5 | cov@10 | cov@20 | Precision@10 | Model-only cov@10 |
|---|---:|---:|---:|---:|---:|
| default (rrf, weight 5) | 26.8 | 35.7 | 58.6 | 59.0 | 49.7 |
| rrf, weight 12 | 23.6 | 38.1 | 57.8 | 60.0 | 49.7 |
| cut | 25.2 | 33.6 | 57.0 | 54.0 | 49.7 |

**Gate check for rrf weight 12 at depth 30** (gates of section 6, against the current engine unless stated):

| Gate | Result |
|---|---|
| 1. Coverage at the top 10 at least +5 over lexical, and no task falls by more than 3 against the current engine | Pass: +10.8 over lexical. Per task against the current engine: -1.4, +3.7, +15.4, +12.5 |
| 2. Retrieval precision does not fall by more than 5 on any task | Pass: precision@10 changes by -2.5, -2.5, +20.0, +20.0 |
| 3. Time budget and degraded rate | Pass: 0 degraded runs out of 40 at the 1.2 s budget on a quiet machine. The weight does not change latency (p50 differences within 0.15 s). Whole-command time with Java start-up was p50 1.2 to 1.5 s and p95 1.4 to 2.1 s |
| 4. Reproducible on the same snapshot | Pass: two sweeps identical |
| 5. Paired interval excludes zero, or 75% wins and no loss above 10 | Pass: interval [0.12, 7.73] against the current engine, 5 wins, 1 loss, worst loss -5.6 |

**Findings**
- The weight saturates at 12. Weights 12, 20 and 40 and the `cut` mode give the same coverage on every query, so a larger weight than 12 adds nothing on this benchmark.
- Score fusion (`score`) does not beat rank fusion. It is no better than the current default at weights 12 and 20 and loses 15 points on task D.
- **A gap of about 3.4 points to the model-only order remains** (44.0 against 47.4 at the top 10). I did not test the cause directly. The neighbor expansion runs after re-ranking and inserts unscored neighbor turns next to their anchors, while the model-only order in the harness places all unscored rows last. This is a hypothesis for the next plan (H8: score or place the neighbors by their anchor's score).
- **Depth 50 is worse in engine order** than depth 30 for every setting (default 35.7 against 40.3, weight 12 38.1 against 44.0) although the model-only coverage rises (47.4 to 49.7). The `cut` mode loses 32.5 points on task D at depth 50. Depth 30 stays.
- **Secondary measure, all sources (three-way harness, 5 repeats, budget 1.2 s):** weight 12 against the default changes cov@10 on the all-source branch by -5.5 on task A (86.1 to 80.6, about one fact), 0 on B and C, and +5.0 on D. Over all 20 results it gains +6.3 on B and +5.0 on D and changes nothing on A and C. On the chat-turn branch it gains +15.4 on C and +12.5 on D. The one loss on task A is larger than the 3-point rule, but this branch is not the benchmark defined for the gates.
- Limits: 10 queries, one model, a ledger drafted by the benchmark author, head evidence.

**Decision under the rule of section 1.5 of the plan.** Four settings tie at the highest coverage (rrf 12, rrf 20, rrf 40 and cut). The plan has no tie rule. I applied the smallest change: rank fusion with the lowest weight that reaches the plateau, **rrf weight 12 at depth 30**. This is a candidate only. **No default was changed.** The default changes only after the owner approves it (plan step 30).

### 10.6 Best-passage evidence (R05, R37, plan step 31)

The benchmark scored the snippet from the best passage chosen by the model instead of the head of the document (rows without a passage keep the head; 9 to 14 of the top 20 rows fall back).

| Order | cov@10, head | cov@10, best passage | Difference | Interval | Wins / losses / ties |
|---|---:|---:|---:|---|---|
| Engine order, default weight | 40.3 | 38.1 | -2.18 | [-6.05, 1.45] | 2 / 3 / 5 |
| Engine order, weight 12 | 44.0 | 41.3 | -2.70 | [-6.67, 1.20] | 2 / 4 / 4 |
| Model-only order | 47.4 | 41.0 | -6.38 | [-12.02, -2.01] | 1 / 6 / 3 |

The condition for R37 (at least +3 points at the top 10 with no task losing more than 3) is **not met**. The `AI_MEMORY_SNIPPET` setting was not built (plan steps 32 to 34 skipped). H2 is not supported on this benchmark: the best 900-character passage carries less context than the head of the document, which holds the title and the first facts.

### 10.8 Default change (plan step 30, D-34)

On 2026-11-04 the owner approved rrf weight 12 at depth 30 as the default. `Config.RERANK_WEIGHT_DEFAULT` and the rerank entry of `SignalFusion.SIGNAL_WEIGHTS` are now 12, the help text says so, and `--rerank-weight` and `AI_MEMORY_RERANK_WEIGHT` still override it. The tables in sections 10.5 and 10.6 describe the former default (weight 5) as the "current default".

- Verification: the semantic suite has 125 tests and passes. A benchmark run with the new default is identical, query by query, to the earlier run with `--rerank-weight 12` (coverage at 5, 10 and 20, bytes, precision and the top 20 turns). `--rerank-weight 5` reproduces the old default.
- Golden outputs: of 12, ten are identical. The two that use the live service changed, because the re-rank order follows the new weight, and the explain line now ends with `fusion=rrf weight=12`.
- Benchmark labels: in `bench/model_bench.py` the setting `default` now means weight 12. Use `rrf:5` for the former default.
- The three-way results of `comparison.md` were measured with weight 5 and are not updated.

### 10.7 Status of the checklist

| Item | Status |
|---|---|
| R01, R05, R06, R08, R09 | Done |
| R07 | Done in a changed form: an undated-document counter replaces a time cutoff (the databases hold no per-document date) |
| R11 to R15 | Done: settings `AI_MEMORY_RERANK_WEIGHT` and `AI_MEMORY_RERANK_FUSION` and the options `--rerank-weight` and `--rerank-fusion`, off by default. R13 (`score`) and R14 (`cut`) tested, neither beats rank fusion with a higher weight |
| R16 | Done for the first stage (section 10.3). The per-fact table is in the JSON reports |
| R18 | Partly: depth 50 tested, not adopted |
| R37 | Evaluated, condition not met, not built |
| R02, R03, R04, R10 | Open: owner input and more tasks are needed. R04 is partly covered (latency percentiles, recall, precision proxy) |
| New: H8 | Neighbor turns are never scored and cap the engine order at about 44.0 against 47.4 model-only |
| New: warm-up | The start-up warm-up does not cover a realistic batch (section 10.4) |

Deviations from the plan, all small: the snapshot has a content fingerprint in addition to the byte hash (section 10.2); the explain-field tests are in `test_semantic_suite.groovy` because that suite has the service fixture, and only the option and help tests are in `test_query_suite.groovy`; the benchmark CSV has a precision@10 column, added for gate 2; a tie rule was applied for the decision (section 10.5).

Test results: semantic suite 125 tests (was 90), query suite 74 (was 68), all other suites unchanged, Python service tests 78 under three interpreters. Of 12 golden outputs, 11 are byte-identical and the twelfth differs only by the new `fusion=rrf weight=5` fields.

## 11. Tranche 2 results (plan: [rerank_enhacement_plan_tranche2.md](rerank_enhacement_plan_tranche2.md))

Status on 2026-11-04.

### 11.1 Baseline on a frozen snapshot

The full tables are in [comparison.md](comparison.md), section "Re-measurement on a frozen snapshot (2026-11-04)". Two findings change how the earlier results must be read:
- **The numbers moved because the corpus moved.** Each archive is replaced as a whole on ingest, so document IDs change, and `chat:turns` grew from 10,159 to 12,630 documents in one day.
- **Candidate crowding.** Turns after a task's freeze point occupy the 30-document re-rank window and are removed only at the end. Tasks C and D had 3 and 1 such turns in the morning run and 17 and 16 on the snapshot (this session's own discussion of those topics). Tasks A and B have 11 to 27 of 30 in both runs. Coverage therefore falls as the corpus grows.
- **The weight-12 gain did not reproduce.** On the snapshot, chat turns, 10 queries: weight 12 36.5, weight 5 36.4, lexical 33.7 at the top 10 (this morning 44.0, 40.3 and 33.2). On the all-source branch weight 12 is lower than weight 5 at the top 10 on tasks B, C and D. This is recorded as D-35 (proposed): re-decide D-34 after a frozen, per-task benchmark.

### 11.2 Neighbor modes (H8, plan steps 22 to 35), provisional

Setting `--neighbors adjacent|after|scored|off` (`AI_MEMORY_NEIGHBORS`, default `adjacent`, unchanged). Snapshot, mmarco, depth 30, 10 queries, chat turns, engine order. Two sweeps gave identical results.

| Mode | cov@5 | cov@10 | cov@20 | Precision@10 | Model-only cov@10 |
|---|---:|---:|---:|---:|---:|
| lexical (no re-rank) | 18.4 | 33.7 | 53.3 | 63.0 | 33.7 |
| **adjacent (default, today)** | 21.2 | 36.5 | 56.0 | 57.0 | 42.1 |
| after | 28.3 | 43.5 | 53.0 | 65.0 | 43.5 |
| scored | 29.0 | 43.3 | 55.0 | 65.0 | 43.7 |
| off | 29.0 | 41.8 | 54.5 | 64.0 | 41.8 |

Paired statistics for cov@10 against `adjacent`:

| Mode | Mean difference | Interval | Wins / losses / ties | Worst loss | Per task (A, B, C, D) | Precision@10 difference |
|---|---:|---|---|---:|---|---|
| after | +7.04 | [-2.79, 19.39] | 5 / 5 / 0 | -13.9 | +9.0, +9.9, -7.7, +2.5 | +8.0 (A 0, B +17.5, C -10, D +20) |
| scored | +6.79 | [-3.07, 19.17] | 4 / 5 / 1 | -13.9 | +9.0, +9.9, -7.7, 0.0 | +8.0 |
| off | +5.29 | [-5.24, 18.31] | 4 / 6 / 0 | -15.0 | +9.0, +9.9, -7.7, -15.0 | +7.0 |

**Decision under the rule of the plan: no mode wins.** Task C falls by 7.7 points and its precision by 10 (the gates allow 3 and 5), every interval includes zero, and wins are at most 5 of 10. `adjacent` stays the default.

What the sweep shows, with the caveat that the corpus is crowded and the sample small:
- With `after`, the engine order equals the model-only order (43.5). This supports H8: neighbors placed next to their anchors cost coverage at the top 10.
- Scoring the neighbors (`scored`) adds nothing over placing them after the anchors (43.3 against 43.5), and costs an extra service load.
- `off` is lower than `after` (41.8 against 43.5), so the neighbors do add facts when they come after the anchors.
- The mean gain of about 7 points is about as large as the one measured for weight 12, and equally unsupported by an interval that excludes zero. It needs the frozen, per-task benchmark (A1) before a default changes.

### 11.3 Warm-up (plan steps 36 to 42)

New: `warm_shapes` (default word counts 80, 160, 200) and `warm_batch` (16) in the service config, a multi-shape warm-up in `App.warm`, and `warmed` and `warm_ms` in `/v1/health` (the service contract is updated). `warm_ms` was 4,540 ms in one check and includes the model load. 83 Python tests pass under three interpreters (78 before).

Cold-start measurement (`bench/cold_start.py`, mmarco on MPS, budget 1.2 s, a never-seen query per call, fresh service per start):

| Wait after start | Before: first calls inside budget | First-call service time | After: first calls inside budget | First-call service time |
|---|---|---|---|---|
| 15 s (10 starts) | 10 of 10 | 759 to 792 ms | 10 of 10 | 754 to 929 ms |
| 5 s (5 starts) | 5 of 5 | 756 to 772 ms | 5 of 5 | 770 to 802 ms |
| 0 s (5 starts) | 0 of 5 | degraded | 0 of 5 | degraded |

Later calls took a median of 731 ms (before) and 751 ms (after).

- **The acceptance rule is met (10 of 10), but it was already met before the change.** The multi-shape warm-up gives no measurable gain.
- **The 2.9 to 5.5 s first call seen in tranche 1 did not reproduce.** I did not find its cause. It happened while other jobs were running, which is a likely but unproven explanation.
- **The real gap is the first 4 to 5 seconds after a start,** while the model loads. A request in that window degrades. The `warmed` flag lets a caller wait for readiness, which matters for R49.
- The default `warm_shapes` follows the plan. The start-up time without the shapes was not measured. If you prefer minimal start-up work, setting `warm_shapes` to `[]` restores the single pair.

### 11.4 Owner inputs

Delivered, waiting for replies (plan steps 15, 17 to 21):
- Review packs for ledgers C and D (`data/eval/ledger-run/review/task_c.md` and `task_d.md`, git-ignored) from `bench/ledger_review.py`. Pack C has 2 flagged lines and pack D has 14 (facts whose anchors are only in the worklog, because the state capsule lags).
- The candidate table (`review/candidates.md`, 20 tickets with turns, worklog chunks and a state capsule) from `bench/candidates.py`, and the proposed list of 11 tickets in the plan.
- The R10 questions and the R49 decision memo (tranche 2 plan, section 1.2).

### 11.5 Amendment proposal A1: a frozen corpus per task

Needed to make the benchmark reproducible and to remove candidate crowding. Not built: it is outside the approved plan.
- New `bench/task_corpus.py`: for each task, a copy of the snapshot's `convo.db` with the chat turns after the task's cutoff (and the excluded turns) deleted (the index triggers keep the full-text index consistent), stored under `data/eval/snapshots/<name>/tasks/<key>/`, with a content fingerprint. Tasks with the same cutoff share one copy.
- The harness points the engine and the evidence lookups at the per-task folder (`--data-dir`) for that task's queries. Worklog and artifact documents stay unfiltered, because they carry no per-document date. The report counts them.
- Verification: the golden queries on a per-task copy, determinism on two runs, and the crowding counter (post-freeze turns in the window) must be 0 for chat turns.
- Then re-run the baseline, the weight sweep and the neighbor sweep, and decide D-35 and the neighbor default.
- Cost: about 108 MB per distinct cutoff. Four tasks need up to 430 MB.

### 11.6 Status of the checklist items

| Item | Status |
|---|---|
| H8 neighbors | Built behind a flag; evaluated provisionally; no mode adopted |
| Warm-up | Built; no measurable gain; `warmed` flag added |
| R46 score cache | Already built in the service (14 days, per model, query and passage) |
| R48 latency metrics | Partly: `warmed` and `warm_ms` in the health reply |
| R02, R03, R10, R49 | Delivered, waiting for the owner |

Test results: semantic suite 157 tests (was 125), query suite 80 (was 74), Python service tests 83 under three interpreters (was 78); the other suites are unchanged (see the close-out of the plan). Of 12 golden queries, 11 are identical between the saved jar and the new jar, and the twelfth differs only by the new `Neighbors:` explain line.

## 12. Frozen per-task corpus results (plan: [frozen_corpus_plan.md](frozen_corpus_plan.md))

Date: 2026-11-05. Snapshot `20261104`, mmarco on MPS, depth 30, 10 queries over tasks A to D. Each task has its own copy of the snapshot (`bench/task_corpus.py`, `--frozen`): chat turns after the task's cutoff and the excluded turns are deleted, artifacts written after the freeze instant are deleted, and the full-text index is rebuilt. The crowding check (no post-freeze turn in the first 30 rows) passed in every frozen run. Every run was done twice; the two runs are identical in all non-timing fields. Unfrozen runs reproduce the saved snapshot results with 0 differences.

### 12.1 Effect of freezing (mean cov@10, chat turns, 10 queries)

| System | Unfrozen | Frozen |
|---|---|---|
| lexical | 33.7 | 33.6 |
| mmarco, weight 12 | 36.5 | 43.3 |
| mmarco, weight 5 | 36.4 | 41.3 |

The lexical result does not change. The re-ranker results rise, because turns after the freeze no longer fill the re-rank window. Median wall time is unchanged (1.7 to 1.8 s for weight 12, 0.9 s for weight 5; no case over the byte budget, no degraded case).

### 12.2 Weight sweep, frozen (cov@10, paired bootstrap, 10,000 resamples)

| Setting | Mean | Against lexical (difference, interval) | Against weight 12 (difference) |
|---|---|---|---|
| lexical | 33.6 | | -9.7 |
| rrf:5 | 41.3 | +7.6 [0.97, 14.4], significant | -2.0 |
| rrf:8 | 41.6 | +8.0 [-1.3, 17.3] | -1.7 |
| rrf:12 (default) | 43.3 | +9.7 [-0.25, 19.4] | |
| rrf:20 | 42.5 | +8.8 [-2.0, 19.3] | -0.8 |
| rrf:40 | 43.1 | +9.5 [-0.6, 19.3] | -0.2 |
| score:5 / 12 / 20 | 40.4 / 40.1 / 40.1 | +6.7 / +6.5 / +6.5, not significant | -3.0 / -3.2 / -3.2 |
| cut | 42.3 | +8.7 [-0.9, 18.0] | -1.0 |

Weight 12 against weight 5: difference +2.0, interval [-2.9, 6.9], not significant. Per task: A -1.4, B -0.5, C +15.4, D +12.5. On the all-source branch (not fully frozen, see 12.5) weight 12 is now equal to or higher than weight 5: A 86.1 and 86.1, B 60.4 and 52.1, C 17.9 and 17.9, D 45.0 and 40.0. The unfrozen result (lower on B, C, D) is not reproduced.

### 12.3 Neighbor sweep, frozen (cov@10)

| Mode | Mean | Against adjacent (difference, interval) | Per task against adjacent (A, B, C, D) |
|---|---|---|---|
| adjacent (default) | 43.3 | | |
| after | 52.4 | +9.1 [-3.6, 23.9] | -0.7, +29.7, -7.7, -17.5 |
| scored | 54.1 | +10.8 [-0.9, 24.7] | -0.7, +29.7, -7.7, 0.0 |
| off | 51.4 | +8.1 [-5.7, 23.5] | -0.7, +29.7, -7.7, -27.5 |

Against lexical (33.6) all three modes are significant (+18.7, +20.5, +17.7).

### 12.4 Outcome of the decision rules

- **D-35 (weight), rule (a): met.** Weight 12 raises mean cov@10 by 9.7 over lexical (needs 5); no task falls by more than 3 against weight 5 (worst -1.4); the interval against weight 5 is not below zero. Recommendation: keep 12 and close D-35 as "kept". Caveats: the interval of weight 12 against lexical includes zero (-0.25), and task B is 3.7 below lexical. Only weight 5 is significant against lexical. The gate 5 test (interval excludes zero, or 75% wins) is not met by any weight with 10 queries.
- **Neighbor default: no winner.** No mode passes the gates against `adjacent`: task C falls by 7.7 in every mode (more than the 3-point limit), `after` and `off` also fall on D by 17.5 and 27.5, and no interval excludes zero (wins at most 4 of 10). `adjacent` stays the default. The mean gain of `scored` (+10.8) is the largest effect in this benchmark and is the first thing to re-test on a larger benchmark (R03).
- Owner decision, 2026-11-05: keep weight 12 (D-35 closed as "kept"). The neighbor default is still open.

### 12.5 Limits

- Worklog documents and state capsules show today's content and cannot be cut; 90 artifacts have no date and stay. The indexed-worklog and all-source branches are therefore not frozen. Only the chat-turn branches are fully frozen.
- The ledgers are drafted by the benchmark author and not adjudicated by the owner (R02). The benchmark still has 10 queries; an interval that includes zero is "not significant".
- Corpora: four, 127, 118, 57 and 57 MB under `data/eval/snapshots/20261104/tasks/` (git-ignored); the tool rebuilds them.
- Results are in `three-way-fza-w12.json`, `three-way-fza-w5.json`, `model-bench-fza.json`, `model-bench-fz-sweep-a.json` and `model-bench-fz-nb-a.json` (and the `b` runs) in `data/eval/ledger-run/` (git-ignored).

### 12.6 Re-run on time-based corpora (amendment B1 of the R03 plan, 2026-11-06)

Turn numbers follow the order of ingestion, not time (539 date resets in number order, 737 undated turns). The number cutoff of 12.1 to 12.4 deleted turns that were written before the freeze: 1,416 (A), 1,435 (B), 2,205 (C) and 2,357 (D). The corpora are now cut by timestamp (`--freeze-by time`, the default of `--frozen`): a turn stays when its date is at or before `freeze_instant`; an undated turn stays when its number is at or below `cutoff_turn`. Kept turns: A 4,044, B 4,062, C 11,859, D 12,466 (number cutoff: 2,629, 2,629, 9,658 and 10,145). `--freeze-by number` rebuilds the old corpora; it reproduces `model-bench-fza.json` with 0 non-timing differences. Unfrozen runs still reproduce `model-bench-s8.json` with 0 differences. All time-based runs were done twice and match in all non-timing fields. Verification passes on all four corpora.

Mean cov@10, chat turns, 10 queries:

| System | Number cutoff (12.1) | Time cutoff |
|---|---|---|
| lexical | 33.6 | 23.1 |
| mmarco, weight 12 | 43.3 | 40.0 |
| mmarco, weight 5 | 41.3 | 38.8 |

More pre-freeze turns compete for the top 10, so the first-stage result falls and the re-ranker gains more. Weight 12 against lexical: +16.9, interval [11.5, 22.1], significant, 9 wins, 0 losses, 1 tie (number cutoff: +9.7, interval [-0.25, 19.4]). Median wall time is unchanged (1.63 s for weight 12, 0.93 s for weight 5).

Weight sweep (against weight 12): rrf:5 38.8 (-1.2, [-4.8, 2.3]); rrf:8 41.0 (+1.0, [0.0, 3.1], 1 win and 9 ties); rrf:20 37.7 (-2.3); rrf:40 37.8 (-2.2); score:5, 12, 20 35.9, 34.4 and 34.4 (score:12 and score:20 are significantly lower, about -5.6); cut 37.8 (-2.2). Weight 12 against weight 5: +1.2, interval [-2.3, 4.8], per task A +3.5, B -1.1, C 0.0, D +2.5.

Neighbor sweep (against `adjacent`, 40.0):

| Mode | Mean | Difference, interval | Per task (A, B, C, D) |
|---|---|---|---|
| after | 44.2 | +4.2 [-6.2, 14.9] | -5.5, +19.8, -7.7, -7.5 |
| off | 44.9 | +4.9 [-5.3, 15.3] | -5.5, +19.8, -7.7, 0.0 |
| scored | 44.9 | +4.9 [-5.3, 15.3] | -5.5, +19.8, -7.7, 0.0 |

Outcome of the rules, unchanged from 12.4:
- **D-35:** rule (a) is met (+16.9 over lexical; worst task against weight 5 is -1.1; the interval against weight 5 is not below zero). Weight 12 stays. Weight 8 is 1.0 higher with an interval that starts at 0.0 and 9 ties: not significant.
- **Neighbor default:** no mode passes the gates. Task C falls by 7.7 and task A by 5.5 in every mode (limit 3), and no interval excludes zero. `adjacent` stays. In this run `scored` and `off` give the same result.
- Limits of 12.5 apply. Results are in `*-tma*`, `*-tmb*`, `model-bench-tm-sweep-*` and `model-bench-tm-nb-*` files in `data/eval/ledger-run/` (git-ignored).

## 13. R03 results: 43 queries over 15 tasks (plan: [r03_benchmark_plan.md](r03_benchmark_plan.md))

Date: 2026-11-08. Snapshot `20261104`, time-based frozen corpora, mmarco on MPS, depth 30, `model_bench.py`, 43 queries (tasks A to D: 10 queries; tasks E to O: 3 queries each: `summary of <KEY>`, a keyword form and a Polish paraphrase). Each run was done twice and the two runs match in all non-timing fields. Tasks A to D give the same results as in section 12.6 (0 differences).

**Status: provisional.** The ledgers of tasks E to O were drafted by a model from the state capsule and the worklog of each ticket. They are not adjudicated (`adjudicated` is false for all 15 tasks, so `--adjudicated-only` selects none). Each passes `bench/ledger_check.py` (strict anchors found in the sources, at most 1% false matches on 3,000 unrelated turns, all strict weight present in some turn). Strict facts: E 17, F 16, G 17, H 16, I 16, J 17, K 18, L 15, M 15, N 15, O 13. Review packs: `review/task_e.md` to `task_o.md` (git-ignored).

### 13.1 Baseline (mean over 43 queries, chat turns)

| System | cov@10 | precision@10 | Against lexical (difference, interval, wins/losses/ties) |
|---|---|---|---|
| lexical | 30.6 | 53.5 | |
| mmarco, weight 12 | 32.8 | 55.8 | +2.2 [-1.8, 6.2], 17/17/9, not significant |
| mmarco, weight 5 | 33.2 | 54.4 | +2.6 [-0.8, 6.1], 18/13/12, not significant |
| re-ranker order alone (weight 12 run) | 37.5 | | +6.9 [2.5, 11.7], 25/14/4, significant |

Weight 5 against weight 12: +0.4, interval [-1.3, 2.0]. Bytes at the top 20 stay under the budget (about 17,700 against 22,000); no degraded case. Median wall time: lexical 0.94 s, weight 12 1.73 s (later settings in a run use the score cache, so their wall times are lower).

### 13.2 Where the gain is (weight 12 against lexical, cov@10)

| Subset | Queries | lexical | weight 12 | Difference, interval |
|---|---|---|---|---|
| Tasks A to D | 10 | 23.1 | 40.0 | +16.9 [11.5, 22.1] |
| New tasks E to O | 33 | 32.8 | 30.6 | -2.2 [-6.0, 1.6] |
| Long tickets E, F, G, H | 12 | 25.4 | 24.1 | -1.3 [-4.2, 2.3] |
| Medium I, J, K | 9 | 35.5 | 34.9 | -0.6 [-8.4, 7.0] |
| Short L, M, N, O | 12 | 38.2 | 33.9 | -4.3 [-12.2, 3.5] |
| E to O, form `summary of <KEY>` | 11 | 32.8 | 25.9 | -6.9 [-13.5, -0.35], significant loss |
| E to O, keyword form | 11 | 35.3 | 32.9 | -2.4 [-8.5, 3.3] |
| E to O, Polish form | 11 | 30.4 | 32.9 | +2.6 [-2.5, 8.7] |

Per task against lexical (weight 12): A +21.6, B +12.5, C +20.5, D +12.5, E 0.0, F -1.9, G -1.8, H -1.7, I +10.5, J -8.4, K -4.1, L -13.3, M -2.0, N +4.9, O -6.9. The gain found on tasks A to D is not found on the new tasks. Weight 12 against weight 5 on E to O: -0.9 [-2.7, 1.1].

### 13.3 Neighbor sweep (against `adjacent`, 32.8)

| Mode | Mean | Difference, interval, wins/losses/ties | Tasks below -3 against `adjacent` |
|---|---|---|---|
| after | 37.6 | +4.8 [0.9, 9.0], 24/11/8, significant | A -5.6, C -7.7, D -7.5, I -6.1 |
| off | 36.8 | +4.1 [0.3, 8.0], 21/9/13, significant | A -5.6, C -7.7, I -6.1, K -3.3 |
| scored | 35.2 | +2.4 [-0.7, 5.8], 16/8/19 | A -5.6, C -7.7, I -9.6, K -3.3 |

Biggest gains: B +19.8 in all modes, N +29.4 (after), +21.6 (off), +6.9 (scored). Against lexical: after +7.0 [2.4, 11.9], off +6.3 [1.6, 11.2], scored +4.7 [0.02, 9.6]; all significant.

### 13.4 Outcome of the decision rules

- **D-35 (weight): stays closed.** The reopen rule (weight 5 beats weight 12 with an interval fully above zero) is not met (+0.4, [-1.3, 2.0]). Caveat for the owner: on this larger set weight 12 is not shown to beat lexical (+2.2, not significant), and on the new tasks it is lower (-2.2), significantly lower on the ticket-key form (-6.9). Rule (a) of section 12.4 (at least +5 over lexical) would not be met on all 43 queries. The result depends on the ledgers of E to O being adjudicated.
- **Neighbor default: no mode wins under the rules.** Every mode has a task that falls by more than 3 points against `adjacent` (gate 1). The rule of section 6 allows a pass through the interval clause of gate 5, and `after` and `off` have intervals above zero, but the per-task limit is the owner's open question R10(c). `after` has the best mean (+4.8) and the best win count (24 of 43).
- Proposed decision D-36 is in `decision-log.md`. It is the owner's decision.

### 13.5 Observations (not verified causes)

- With `adjacent` the fused order trails the re-ranker's own order by 4.7 points (32.8 against 37.5). With `after` or `off` the gap disappears (37.6 and 36.8 against 37.5 and 36.2). This is consistent with unscored neighbor rows taking top-10 slots, as found earlier.
- The re-ranker alone beats lexical significantly (+6.9). The fused result at weight 12 with `adjacent` neighbors does not. The fusion, not the model, is where the gain is lost on this set.

### 13.7 nDCG@10 added (R04, 2026-11-08)

`bench/common.py` now computes nDCG@10 per query. The gain of a fact is its weight. A fact counts once, at the first turn (in the evidence text, first 1,050 characters) that holds its strict anchors; the gain of a turn is the weight of the facts it adds. The ideal list is built greedily over all turns of the task's frozen corpus. Recall at k against the graded facts is the existing cov@k (weighted share of strict facts in the top k). The coverage fields of the new run are identical to section 13.1 and 13.3 (0 differences).

| System | nDCG@10 | Against lexical (difference, interval) | Against `adjacent` (difference, interval) | Tasks below -3 against `adjacent` |
|---|---|---|---|---|
| lexical | 29.6 | | | |
| weight 12, `adjacent` | 32.5 | +2.9 [-1.9, 7.6], not significant | | |
| weight 5 | 33.0 | +3.4 [-0.3, 7.1], not significant | | |
| `after` | 35.2 | +5.6 [0.9, 10.4], significant | +2.7 [0.16, 5.3], significant | D, I, O |
| `off` | 34.8 | +5.2 [0.4, 10.1], significant | +2.3 [-0.09, 4.8] | I |
| `scored` | 33.9 | +4.3 [-0.6, 9.3] | +1.4 [-0.8, 3.5] | I |

nDCG@10 gives the same direction as cov@10. With nDCG the loss on single tasks is smaller (cov@10: A, C, D, I below -3 for `after`; nDCG@10: D, I, O). R04 is only partly done: claim precision, freshness, temporal correctness and contradictions are manual and are not scored.

### 13.6 Limits

- Ledgers E to O are unadjudicated and were drafted from the same worklogs and capsules that the indexed-worklog branch reads; the strict anchors had to occur in some chat turn before the freeze.
- The keyword and Polish forms have 11 queries each; task O has a small corpus (646 unrelated turns for the false-match check).
- Evidence for a turn is its first 1,050 characters (`--evidence head`); the best-passage mode was not run here.
- Only the chat-turn branch was run (model-bench). The indexed-worklog and all-source branches were not part of this plan.
- Results are in `model-bench-r3-base-{a,b}` and `model-bench-r3-nb-{a,b}` files in `data/eval/ledger-run/` (git-ignored).

---

Created using Anthropic Claude. Keep this internal until a person has reviewed and verified it. The tasks and results refer to internal tickets and memory content: share this document only with people allowed to see them.
