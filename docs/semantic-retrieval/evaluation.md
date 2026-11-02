# Evaluation Plan: Semantic Retrieval Upgrade

Status: proposal. Part of [the specification](specification.md).

Nothing in the upgrade is switched on until this plan shows it helps. A gate is passed only when the command and its result are recorded in the verification file of the phase.

---

## 1. Query classes and languages

| Class | Meaning | Today's behavior (see evidence) |
|---|---|---|
| K | Keyword style, 1 to 3 content words or a ticket key. The style that works today. | Works |
| Q | Natural-language question, 5 or more words, with question and filler words | Returns nothing |
| P | Paraphrase: the question uses other words than the answer document | Returns nothing or the wrong item |
| T | Ticket-scoped: a ticket key plus terms or a question | Ticket path; terms may fail as in Q |
| X | Explicit operators: `OR`, phrases, prefix terms, several tickets | Works; must not change |

Language tags: `en`, `pl` (with diacritics), `pl-ascii` (Polish typed without diacritics, the owner's usual style), `mix`.

## 2. Two datasets

| Dataset | Purpose | Where | In git |
|---|---|---|---|
| **Fixture set** | Test the pipeline logic in seconds, without a model, in the normal test suites. About 40 synthetic documents (English and Polish), about 25 queries covering every class and every invariant, a stub scorer based on term overlap. | `tests/fixtures/semantic/` | Yes |
| **Gold set** | Measure real quality on the real memory. At least 150 queries with graded labels from real tickets and turns. | `data/eval/gold.jsonl` (ignored by git) | **No**: it names real tickets and may contain personal data |

The gold set stays out of git because the memory holds internal and personal data (see [security-privacy.md](security-privacy.md)). Only metric summaries without text are committed.

## 3. Gold set construction

### 3.1 Size and mix (minimum)

| Class | Queries | Language mix |
|---|---|---|
| Q | 60 | 50% `en`, 25% `pl-ascii`, 15% `pl`, 10% `mix` |
| P | 30 | same |
| K | 30 | same |
| T | 20 | same |
| X | 10 | `en` |
| Total | 150 | |

### 3.2 Sources of queries and labels

1. **State-derived (about 40%).** Each decision, blocker and next action in a ticket state file becomes a question. The extractive capsule (`worklog-chat/ticket-state/<ticket>.md`) lists its `sources` as `file:line-range`. These give the positive document with no manual search.
2. **Journal-derived (about 30%).** Turns in which the owner asked to recall earlier work (for example "what did we decide", "where is", "is there anything about"). The positive is the turn or chunk that the later work cites or that the owner confirms.
3. **Owner-written (about 20%).** The owner writes 30 queries in one sitting, in the style they really use.
4. **Synthetic paraphrases (at most 30% of class P).** A model rewrites the question of an existing item with different words. They are tagged `synthetic: true`, reviewed by the owner, and reported separately so they cannot inflate the score.

### 3.3 Labels

1. Graded relevance per (query, document): 0 not relevant, 1 related, 2 useful, 3 answers the question.
2. **Pooling.** For each query, the union of the top 20 results of every system under test (S0 to S5 below) is labelled. This avoids a bias toward the system that built the gold set.
3. **Review.** A second pass by the owner on a random 20% of the queries. Agreement is Cohen's kappa; the target is at least 0.6. Below that, the label rules are rewritten and the 20% is relabelled.
4. **Identity.** A document is identified by its stable id (`origin_db:source_archive:file_path`) plus the first 12 characters of its content hash and a 60-character quote. If the path changes after a rebuild, the quote re-anchors the label.
5. **Format** (one JSON object per line):

```json
{"qid":"q-017","class":"Q","lang":"pl-ascii","query":"<text>","ticket":null,"synthetic":false,
 "labels":[{"doc":"convo.db:chat:turns:kd8130/d20261030/execute/impact-high/turn-000412.md","hash12":"a1b2c3d4e5f6","quote":"<60 chars>","rel":3}],
 "split":"dev"}
```

### 3.4 Split

70% dev and 30% test, split **by ticket** (not by query), so a ticket seen in tuning never appears in the test set. Weights and thresholds are tuned on dev only. The test split is run once per phase gate.

### 3.5 Effort

About 150 queries need roughly 6 to 8 hours of labelling in total when state-derived items are generated automatically and only the pools are reviewed. The owner's own part is the 30 written queries and the 20% review (about 2 hours). The remaining time can be done by an assistant session and then reviewed. Question Q4 in the [decision log](decision-log.md) asks the owner to decide.

### 3.6 Storing, switching and editing the gold set

The gold set lives in `data/eval/gold.jsonl` (ignored by git). The `eval` command manages it:

| Command | What it does |
|---|---|
| `eval --gold-import FILE` | Merges a file in the gold set format (for example the export of the labelling page). A query with the same `qid` replaces the old one. Repeat the flag for more files; `--replace` drops every query that is not in the files. An invalid file is refused and the gold set stays as it was. |
| `eval --gold-status` | Shows the flag, the number of questions, the grades given and warnings (questions without grades, no grade 2 or 3, fewer than 150 questions) |
| `eval --gold-enable` / `--gold-disable` | Switches the default gold set on or off. **It is off by default.** `AI_MEMORY_GOLD=1` or `0` overrides the state file `data/eval/gold.state.json`. |
| `eval --gold-history` / `--gold-restore NAME` | Lists the snapshots kept before each change and puts one back (the version it replaces is kept first) |
| `eval --gold-export FILE` | Copies the gold set to a file, to edit it by hand and import it again |

- **While the flag is off,** `eval --run` and `eval --pool-export` refuse to use the default gold set (exit code 4). A set given with `--set FILE` is always used, and `--validate` and the commands above always work.
- **Editing later.** Change a grade or a question in the labelling page (it opens with the stored answers pre-filled), export, and import the file. Each import that changes the set first saves the old version in `data/eval/gold-history/`, with a log of the changes (counts only, no text).
- **The first answers (2026-11-02).** 18 questions are stored: 16 generated from ticket notes, of which 3 have grades (3 times grade 0 and 21 times grade 1, none 2 or 3), and 2 questions the owner wrote with a note on the expected answer (no grades yet). The set is switched off. It is too small and has no grade 2 or 3, so it cannot support a measurement yet.

## 4. Metrics

Computed per query, then averaged per class, per language and overall.

| Metric | Definition |
|---|---|
| Zero-result rate | share of queries with no result |
| Recall@K | share of relevant documents (rel at least 2) found in the top K; K = 10, 50, 100 |
| nDCG@10 | gain `2^rel - 1`, discount `1 / log2(rank + 1)`, normalized by the ideal order |
| MRR@10 | mean of `1 / rank` of the first document with rel at least 2 |
| Success@1 and @3 | share with a rel at least 2 document in the first 1 or 3 results |
| Latency | p50, p95 and p99 in milliseconds, cold (first call) and warm; stage by stage (plan, candidates, passages, re-rank, fusion) |

Statistics: paired bootstrap with 10,000 resamples over queries gives a 95% confidence interval for the difference between two systems. A claimed improvement needs a lower bound above zero on the primary metric. A "no regression" claim needs a lower bound at or above -0.01.

## 5. Systems compared

| Id | System |
|---|---|
| S0 | Baseline: the code at the commit before Phase 0 (tagged `semantic-baseline`) |
| S1 | Phase 0: query plan, relaxed form, `bm25_relaxed` pool |
| S2 | S1 plus cross-encoder, small multilingual model, depth 30, 3 passages per document |
| S3 | S1 plus cross-encoder, English small model (to measure the cost of ignoring Polish) |
| S4 | S1 plus cross-encoder, large multilingual model (opt-in; only if the latency benchmark allows it) |
| S5 | S2 plus the late pool (Phase 2) |
| S6 | S5 with a late re-score instead of the cross-encoder |

Ablations on dev: depth 10, 30, 50, 100; passages per document 1, 3; with and without priors in fusion (pure model order against fused order); fusion weight of `rerank` 3, 5, 8; window 128, 256, 384.

## 6. Harness

1. **Command.** `ai-worklog memory eval --set gold --systems S0,S1,S2 --json`. The harness calls the retrieval code in-process (no JVM start per query) and calls the service for stage 3.
2. **Frozen corpus.** The memory grows while the test runs. The harness takes `--as-of-sequence N` (default: the highest turn sequence when the gold set was frozen) and ignores newer documents, so two runs on different days compare fairly. Documents are filtered by their sequence number or by `doc_hash` recorded in the gold labels.
3. **Determinism.** Each system runs twice; any difference in order fails the run (FR-009).
4. **Storage.** Metrics, per-query results and the configuration go to `eval_runs` in `semantic.db`. The text summary goes to the verification file of the phase (no query text).
5. **Comparison.** `--compare RUN` prints the per-class differences with the bootstrap intervals and applies the gates of section 8.
6. **Isolation.** The harness never writes to the existing databases. It may write passages and cache rows to `semantic.db`; it can run with `--no-cache` to measure cold scoring.

## 7. Other test groups

| Group | Tests |
|---|---|
| Query plan (Phase 0, no model) | Stop-word removal in English and Polish; prefix terms for words of 5 or more letters; diacritic-free Polish matches indexed diacritics; explicit operators, phrases, parentheses and column filters are not rewritten; ticket-only queries keep the ticket path; strict-then-relaxed switching at `relax_min_results` |
| Passages | Structural split of a turn, a chunk and a capsule; window and stride; context prefix; best 3 per document; hash change replaces passages; excluded documents have none |
| Fusion | Weights as configured; deterministic tie-break; rounding to four decimals; pins never cut (I1, I3, I12); `rerank` signal appears in `--signals` |
| Client and fallback | Service absent, slow, busy, wrong token, wrong contract: lexical result, correct reason code; circuit opens for 30 s; service-off result equals S1 (I10); `--rerank on` exits 5 when the service fails |
| Service | Contract tests with a stub model: request validation, size limits, deadline cut, queue full returns 429, health and models endpoints, no write to the existing databases (file hashes before and after) |
| Security | Socket mode 0600 and directory 0700; token required; `Origin` refused; a test that fails if the service opens any outbound socket; model checksum mismatch refuses to load; no query text in logs |
| Regression | Every existing suite: journal 25, retrieval 31, query 68, chatlog 100, reconciliation 33, shadow validation 44, worklog 42, memory lock 34 (2026-10-31); `sweep-memory` ends with `failures=0`; `record_turn.py` unaffected |
| Model tests (opt-in) | `RUN_MODEL_TESTS=1`: real model on 20 fixture pairs gives the expected order; same order on a second run; device recorded |

## 8. Gates (proposed targets, confirmed in the decision log after the baseline)

| Gate | Condition | Measured by |
|---|---|---|
| G0 baseline | S0 run stored for the dev and test splits; targets confirmed or changed by the owner | `memory eval --systems S0` |
| G1 end of Phase 0 | Zero-result rate on Q, P and T at most 5%; Recall@50 on Q at least 0.85; K and X nDCG@10 within -0.01 of S0 (lower bound); all existing suites pass | S1 against S0 |
| G2 end of Phase 1 | nDCG@10 on Q at least 0.65 and MRR@10 at least 0.60 (test split); improvement over S1 with a lower bound above zero; the English and Polish subsets each improve; p95 added latency at most 1.2 s warm at depth 30; I1 to I12 pass; S2 with the service off equals S1 | S2 against S1 |
| G3 end of Phase 2 | Recall@50 on P up by at least 0.10 absolute over S2; no regression of G2; storage within 5 GB; the encoding job finishes unattended; memory of the service at most 4 GB | S5 against S2 |

## 9. Latency benchmark (task T022, before the model choice)

1. Machine state: idle, power connected, same Python and package versions as production, three warm-up calls discarded.
2. Matrix: model (small multilingual, English small, large multilingual if licence allows) by device (`cpu`, `mps`) by depth (10, 30, 50) by passage length (128, 256, 384 tokens) by batch size (8, 16, 32).
3. Per cell: 20 runs; record p50, p95, resident memory and peak memory.
4. The result replaces the estimates in [evidence.md](evidence.md) section 7. A model whose p95 at depth 30 and 256 tokens exceeds 1.2 s is not a default.

## 10. Reporting

After each gate a verification file `docs/semantic-retrieval/verification-phase-N.md` records: the commit, the commands, the run ids, the metric tables per class and language with confidence intervals, the latency tables, the test counts, and what was not verified. No query text, no document text.

## Summary (STE)

We cannot know if a change helps unless we measure it. This plan builds two test sets. A small synthetic set runs with the normal tests and needs no model. A larger real set of at least 150 questions stays on this computer and is not put in git. Each question has graded labels. The plan measures recall, ranking quality and speed for each version of the system. A change must beat the old version with a confidence interval and must not hurt the keyword searches that work today.

**Remaining 20% (not STE100):** metric and system names; the words "nDCG", "MRR", "bootstrap", "kappa", "pooling", "stride", "p95" and "cold and warm"; the tables and the JSON; some sentences over 20 words.

---

Created using Anthropic Claude. Internal only until a person has reviewed and verified it.
