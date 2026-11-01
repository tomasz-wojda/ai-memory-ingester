# Technical Specification: Semantic Retrieval Upgrade

Status: proposal for review. Not approved. Phase 0 and the model-neutral parts of Phases 1 and 2 are built and tested with a stub model (see the verification files); the real model, the licences, the benchmark and the gates are open.

Companion documents: [evidence](evidence.md), [evaluation](evaluation.md), [service contract](service-contract.md), [security and privacy](security-privacy.md), [decision log](decision-log.md), [tasks](tasks.md), [checklist](checklist.md).

Origin: the owner asked for "much much better retrieval from memory" and named a two-stage design: SQLite FTS5 as the first stage, then re-ranking with a cross-encoder or ColBERT (late interaction), served by a dedicated memory service inside the Groovy-based AI Worklog Framework.

---

## 1. Purpose

Make memory retrieval find the right turn, worklog chunk or ticket state for a question written in plain English or Polish, rank it in the first results, and keep the existing guarantees about authority, freshness and complete rows.

## 2. Problem

Measured facts are in [evidence.md](evidence.md). In short:

1. **The first stage fails on questions.** Bare terms are ANDed. All five natural-language questions and phrases in the baseline returned zero results, while the same ideas typed as `OR` or prefix queries returned ten.
2. **No word-form matching.** There is no stemming or lemmatization. Polish inflection and English forms ("pinned" and "pin") do not match. The tokenizer also does not fold the Polish letter ł, so "bylo" does not find "było" (verified in SQLite on 2026-11-01).
3. **Ranking inside the candidate set is lexical.** BM25 rank, recency, authority and impact are fused with fixed weights. Nothing measures whether a passage answers the question.
4. **Whole documents, no passages.** A turn can be 680 KB and an artifact 1.4 MB. Relevance is judged on the whole text.
5. **No way to measure.** There is no labelled query set, so no change can be shown to help.
6. **Language.** The ticket state is mostly Polish; most turns are English; queries are often Polish without diacritics.

A re-ranker alone fixes only problem 3. The specification therefore has four parts: a measurement harness, a better first stage, a re-ranking stage, and an optional late-interaction pool.

## 3. Goals and non-goals

Goals:
- G1. Natural-language questions return candidates (recall) in both languages.
- G2. The best answer is in the top 10 more often (precision at the top).
- G3. The existing invariants of the memory system hold (section 8).
- G4. Retrieval works when the semantic service is off, slow or broken. It then behaves like today plus the first-stage improvements.
- G5. Every change is measured on a labelled query set before it is switched on.
- G6. Everything stays on the local machine.

Non-goals:
- N1. No change to the journal, `record-event`, the sweep exit codes or the `prompt.log` flow.
- N2. No AI-written summary as persisted state (locked decision 7 of the worklog-aware upgrade).
- N3. No change to the existing SQLite schema of `convo.db`, `worklogs.db`, `artifacts.db` and `dross.db` (locked decision 14).
- N4. No cloud model, no network call at query time, no telemetry.
- N5. No generative answer. The system returns evidence, not a written reply.
- N6. No training or fine-tuning in this specification (listed as a later option in the decision log).

## 4. Locked decisions (proposed; each needs the owner's approval in [decision-log.md](decision-log.md))

1. **Fix the first stage before adding a model.** Phase 0 adds query planning (relaxed OR queries, stop-word removal, prefix terms) and a labelled evaluation harness.
2. **Two-stage pipeline.** FTS5 and the existing pools produce candidates. A re-ranker scores passages of those candidates. The final order fuses the model score with the existing signals. The model never replaces authority and freshness rules.
3. **Re-ranking is optional and time-boxed.** A deadline applies to every call. When the deadline or the service fails, the lexical order is returned and the output says so.
4. **Cross-encoder first, late interaction second.** Phase 1 ships the cross-encoder because it needs no index. Phase 2 adds a multi-vector pool, because it adds recall for paraphrases and moves cost offline.
5. **One local service process** holds the models in memory. The Groovy code is a thin client. The contract is language-neutral, so a Python implementation can be replaced by an in-JVM ONNX Runtime implementation without a change to callers.
6. **Derived data lives outside the existing databases.** A new `data/semantic.db` holds passages, scores and (Phase 2) vectors. Deleting it loses nothing that cannot be rebuilt.
7. **A small multilingual model is the default.** Large models are opt-in until the benchmark proves they fit the latency budget (see [evidence.md](evidence.md) section 7).
8. **No model is downloaded before its licence is checked** and recorded in [security-privacy.md](security-privacy.md). **MIT is preferred.** Apache-2.0, BSD and ISC are acceptable after IP Legal confirms; non-commercial, copyleft, gated and unreadable licences are excluded. The models must be usable on the company laptop, so their source path must be approved too (D-21, D-22).

## 5. User stories

### US1 (P1): A question returns evidence

The owner types "what did we decide about the traefik attribute" or the Polish equivalent without diacritics. The system returns relevant turns, worklog chunks and the ticket state, not zero results.

Acceptance:
1. On the labelled question set, the zero-result rate is at most 5% (baseline: 5 of 5 in the small sample of [evidence.md](evidence.md) section 2; the real baseline comes from the gold set).
2. Recall@50 of the candidate set is at least 0.85.
3. Keyword-style queries that work today do not get worse (nDCG@10 within 0.01 of the baseline).

### US2 (P1): The best answer comes first

For the same questions, the relevant item is in the first results.

Acceptance:
1. nDCG@10 is at least 0.65 and MRR@10 at least 0.60 on the question set after Phase 1 (proposed targets; they are confirmed after the baseline run in task T011).
2. The gain holds for the English subset and the Polish subset separately.
3. The ticket state capsule is still returned when a ticket facet is present (invariant I1).

### US3 (P1): The system keeps working without the service

The semantic service is stopped, missing or too slow.

Acceptance:
1. The query finishes with a lexical result. The output carries `degraded: true` and a reason code.
2. A stopped service adds at most 300 ms to a query (connect timeout 150 ms).
3. The sweep and `record-event` never wait for the service.

### US4 (P2): A paraphrase finds the right turn

The question uses other words than the document. The late-interaction pool brings the document into the candidate set.

Acceptance:
1. On the paraphrase subset of the gold set, Recall@50 rises by at least 0.10 absolute over Phase 1.
2. The extra pool is switched off by one flag and one environment variable.

### US5 (P2): The owner can see why a result ranked

`--explain` shows the query plan, the pools, the rank in each pool, the model score and the final fusion terms.

Acceptance: for any result, the final rank can be reproduced by hand from the printed terms.

### US6 (P3): Quality is tracked over time

An `eval` command runs the gold set and stores the metrics. A regression of more than 0.02 nDCG@10 fails the run.

## 6. Architecture

```
query text
   |
   v
[0 Query plan]        language-neutral: ticket key, facets, phrases, stop words,
   |                  strict form, relaxed form, prefix terms
   v
[1 Candidates]        existing pools: exact, bm25 (strict), recency, authority, impact
   |                  + bm25_relaxed (OR form, ranked by BM25)         (Phase 0)
   |                  + late (multi-vector top K)                      (Phase 2)
   |                  fused by RRF with deterministic quotas, K = 100 at most
   v
[2 Passages]          split each candidate into passages, keep the best 3 per document
   |
   v
[3 Re-rank]  <---->   memory service: cross-encoder scores (or MaxSim)    deadline 1.2 s
   |
   v
[4 Fusion]            RRF over model rank + prior fused rank + authority + freshness,
   |                  pins and invariants applied
   v
[5 Neighbors + labels + packing]   unchanged
   |
   v
results (+ rerank_score, signals, degraded, query_plan with --explain)
```

### 6.1 Stage 0: query plan

Input: the raw query. Output: a `QueryPlan` (fields in [service-contract.md](service-contract.md) section 6).

Rules:
1. Parse a bare ticket key first, as today (`parseQuery`). A ticket-only query keeps the ticket path.
2. Keep phrases in quotes and explicit operators as typed. A query with an explicit operator, parentheses or a column filter is **not** rewritten (invariant: multi-ticket OR queries keep the full boolean expression).
3. For all other queries build two forms:
   - **strict**: the current form (terms ANDed), unchanged.
   - **relaxed**: stop words removed, remaining terms joined with `OR`, each term of five or more letters also given as a prefix term cut to `max(4, length - 3)` letters (a light stemmer that works for English and Polish forms). Terms of fewer than four letters stay whole.
4. Stop-word lists: English and Polish, kept in `lib/StopWords.groovy`, with Polish words listed both with and without diacritics.
5. Fold case and diacritics the same way the index does (`unicode61 remove_diacritics 2`), so a query typed without diacritics matches indexed text with them.
6. The relaxed form is used only when the strict form returns fewer than `relax_min_results` rows or when `--relax on` is given. **The first release uses 1** (zero rows only, decision D-23), so queries that work today are unchanged; the default may widen toward 10 after the gold set shows a gain. The result records which form ran.
7. **Polish ł.** For a query guessed as Polish (a Polish letter or more Polish than English stop words), each word with one or two letters `l` also gets variants with `ł` ("bylo" gives "było"), because the index keeps ł. Decision D-24.
8. No model is needed in this stage. It is deterministic and testable alone.

### 6.2 Stage 1: candidates

1. All current pools stay. `POOL_LIMIT` stays 50 for the existing pools.
2. New pool `bm25_relaxed`: the relaxed form, ranked by BM25, limit 50. Its RRF weight is 2 (below the strict `bm25` weight 3).
3. New pool `late` (Phase 2): the top 50 passages by MaxSim, mapped to their documents. RRF weight 3.
4. The fused list is cut to `rerank_depth` rows (default 30, maximum 100) before stage 2. Cutting is deterministic: fused score, then the existing tie-break order.
5. **Pins** are never cut: the ticket state capsule when a ticket facet is present, and items of the `newer-evidence` pool of a stale ticket.

### 6.3 Stage 2: passages

Documents are long and a cross-encoder reads at most 512 tokens per pair. Each candidate becomes passages.

1. **Structural split first.** A turn is split at its `## User`, `## Assistant` and `## Details` sections. A worklog chunk is already one section. A state capsule is split at its `## ` headings.
2. **Window second.** A section longer than the window is cut into windows of 256 tokens with a stride of 192 (25% overlap). The window uses the model tokenizer; the character fallback is 900 characters with a stride of 675.
3. **Context prefix.** Each passage gets a one-line prefix: `ticket`, `date`, `mode` and the section name. The prefix is part of the scored text and costs about 20 tokens.
4. **Selection.** For each document, a cheap lexical overlap score (query terms present in the passage, with the folding of section 6.1) picks the top 3 passages. Only these go to the model. A document with a single short passage sends that passage.
5. **Cap.** At most 100 passages per call (depth 30 times up to 3 passages, cut to 100 in candidate order).
6. **Document score** is the maximum passage score (MaxP). The best passage is returned as the snippet of the result, replacing the 64-token display window when re-ranking ran.
7. Passages are derived. They are stored in `semantic.db` (table `passages`) with the content hash of their document, so a changed document invalidates its passages (see [section 9](#9-data-model-and-index-lifecycle)).

### 6.4 Stage 3: re-rank

1. The client sends `{query, passages[], model, deadline_ms}` to the service ([service-contract.md](service-contract.md) section 3).
2. The service scores the passages with the cross-encoder in batches. It checks the deadline between batches ("anytime" scoring). Passages are sent in descending prior order, so a cut at the deadline loses the least likely candidates. Unscored passages get no score; the response lists them.
3. The response carries `scored`, `skipped` and `elapsed_ms` per call and the model identifier and version.
4. If fewer than `rerank_min_scored` passages (default 10) were scored, the client treats the call as degraded and keeps the lexical order.
5. A score cache in `semantic.db` (key: model id, normalized query hash, passage hash) avoids scoring the same pair again. Entries expire after 14 days.

### 6.5 Stage 4: fusion

The model score is one signal, not the final order.

1. Rank the scored documents by model score (ties by prior rank, then stable id). This gives the `rerank` signal.
2. Fuse with the same RRF code (`SignalFusion.fuse`), adding `rerank` to the pools with weight 5. The existing weights stay: exact 4, bm25 3, bm25_relaxed 2, late 3, recency 2, authority 2, impact 1. Weights are configuration, tuned only on the gold set.
3. Apply the invariants of section 8.
4. Round the model score to four decimals before it is used, so equal inputs give equal orders across devices.
5. The result keeps `fused_score`, `signals` and gains `rerank_score` and `rerank_rank`.

### 6.6 Stage 5: unchanged

Neighbor expansion, freshness labels and complete-row packing run after fusion. Packing still never emits a snippet-only row.

## 7. Memory service

Details and the wire format are in [service-contract.md](service-contract.md). Decisions here:

1. **Process.** One local process, `ai-memory-service`, bound to a Unix domain socket under `.ai-worklog/run/` (mode 0600) and optionally `127.0.0.1` on a fixed port. No other interface.
2. **Why a service.** Loading a model takes seconds and uses gigabytes. A JVM that starts for every `query` call cannot pay that cost. A warm service pays it once.
3. **Why Python first.** The cross-encoder, ColBERT and PLAID libraries are Python. The Groovy side stays thin and the contract is neutral. The in-JVM alternative (ONNX Runtime for Java, 1.31.1 on Maven Central) is kept open as a swap-in: same contract, no Python process, at the cost of exporting the models and a Java tokenizer.
4. **Python environment.** A dedicated virtual environment under `~/.local/share/ai-memory-service/venv` with a pinned supported Python (not the 3.15 release candidate that is the default today). Packages are pinned by version and hash. No network access at run time (`HF_HUB_OFFLINE=1`).
5. **Lifecycle.** The framework command group `ai-worklog memory` manages it: `serve`, `status`, `stop`, `doctor`, `reindex`, `eval`. A `launchd` job can keep it running, like the dashboard.
6. **Concurrency.** One model worker thread. Requests queue with a bounded queue (default 4). A full queue returns `429` at once, and the client degrades.
7. **Resource limits.** Target resident memory at most 4 GB with the default small model. The service refuses to load a model whose size exceeds a configured limit.
8. **Failure handling.** Model load failure, out-of-memory and a hung batch end the call with a reason code; the service restarts the worker. The service never writes to `convo.db`, `worklogs.db`, `artifacts.db`, `dross.db` or `journal.db`.

## 8. Invariants (carried over; each has an existing or a new test)

| ID | Invariant | Test |
|---|---|---|
| I1 | With a ticket facet, the state capsule is in the results, before historical turns | existing: exact ticket precedence |
| I2 | A stale capsule keeps its stale label | existing: stale worklog labeled |
| I3 | Newer evidence of a stale ticket comes before the capsule | existing: newer evidence precedes authoritative when stale |
| I4 | Fusion is deterministic for equal input | existing: deterministic fusion; new: with the model on, run twice |
| I5 | Packing keeps complete rows, never a snippet only | existing: packing never snippet-only |
| I6 | Retrieval-meta turns are excluded unless the query is about retrieval | existing behavior; new test with the model on |
| I7 | `journal.db` is never searched | existing |
| I8 | An explicit boolean expression is not rewritten | existing: multi-ticket OR query preserves full boolean expression |
| I9 | A direct compatibility call (`search`) keeps the legacy behavior | existing: direct compatibility bypass |
| I10 | With the service off, results equal the Phase 0 lexical result | new |
| I11 | Excluded documents (`MemoryExclusion`) have no passages and no vectors | new |
| I12 | The model score never removes a pinned item | new |

## 9. Data model and index lifecycle

Full table definitions are in [service-contract.md](service-contract.md) section 7.

1. New database `data/semantic.db` (SQLite, WAL). Tables: `passages`, `score_cache`, `index_manifest`, `vectors` (Phase 2), `eval_runs`.
2. A passage row holds: passage id, `origin_db`, `source_archive`, `file_path`, document content hash, section, window start and end (in characters), token count, text, model-neutral.
3. **Which archives are indexed.** Default: `chat:turns`, `worklog:chunks`, `worklog:state`. `chat:artifacts` and `chat:dross` are off by default (raw evidence and forensic data; 15.7 MB of long documents). A setting turns them on.
4. **When passages are built.** Lazily at query time for candidates (Phase 1), and in batch by `ai-worklog memory reindex` (Phase 2 needs all passages). The sweep calls an incremental update only when the service is up and never waits for it.
5. **Change detection.** A document's content hash is stored with its passages. A new hash replaces the passages of that document in one transaction.
6. **Deletion.** `egest`, archive purge and `MemoryExclusion` matches remove the passages, cached scores and vectors of the affected documents. `reindex --verify` lists orphans.
7. **Vectors (Phase 2).** One row per passage with the token matrix as a blob: `tokens × dimension × bytes`. A 128-dimension model needs about 3.2 GB in half precision for the 12.4 million tokens estimated in [evidence.md](evidence.md); 8-bit needs about 1.6 GB. The service memory-maps the matrix for full-scan MaxSim (exact search over the whole corpus) and uses it directly for candidate re-scoring.
8. **Locks.** The semantic indexer uses its own lock file `.ai-worklog/locks/_semantic.sqlite`. It never takes the `_journal` memory lock, so `record_turn.py` is never blocked by indexing.
9. **Versioning.** `index_manifest` stores the model id, model revision, tokenizer, dimension and passage parameters. A mismatch makes the service refuse the index and report `index_stale`; the fix is `reindex`.

## 10. Phases and exit gates

| Phase | Content | Exit gate (measured by [evaluation.md](evaluation.md)) |
|---|---|---|
| 0 | Gold set and `eval` command; query plan with the relaxed form and stop words; `bm25_relaxed` pool | Zero-result rate at most 5%; Recall@50 at least 0.85 on questions; keyword nDCG@10 not worse than 0.01; all existing suites pass |
| 1 | Service skeleton and contract; passages; cross-encoder re-rank with deadline; fusion; fallback; `--explain` | nDCG@10 at least 0.65 and MRR@10 at least 0.60 on questions; p95 added latency at most 1.2 s warm at depth 30; I1 to I12 pass; service-off equals Phase 0 |
| 2 | Multi-vector index; `late` pool; MaxSim re-score; reindex and verify | Paraphrase Recall@50 up by at least 0.10 absolute; storage within the budget; encoding job finishes unattended; no regression of Phase 1 gates |
| 3 | Options: score-model tuning of weights on the gold set; query expansion through a local model; fine-tuning | Only after Phase 2 and with their own gates |

A phase starts only when the previous gate is recorded as passed with the command and result.

## 11. Interfaces

CLI flags, environment variables, output fields, the HTTP contract and the framework commands are in [service-contract.md](service-contract.md). Summary of new flags: `--rerank off|auto|on`, `--rerank-depth N`, `--rerank-budget-ms N`, `--relax auto|on|off`, `--explain`. Kill switches: `AI_MEMORY_RERANK=0` and `AI_MEMORY_RELAX=0`. Existing flags and the output format stay valid; new fields are added only.

## 12. Functional requirements

- **FR-001**: The system MUST build a query plan with a strict and a relaxed form for queries that carry no explicit operator.
- **FR-002**: The system MUST NOT rewrite a query that has an explicit operator, parentheses, a phrase or a column filter.
- **FR-003**: The system MUST use the relaxed form when the strict form returns fewer than `relax_min_results` rows, and MUST report which form ran.
- **FR-004**: The system MUST match English and Polish word forms through prefix terms, and MUST match text typed without diacritics.
- **FR-005**: The system MUST provide a labelled query set and an `eval` command that reports Recall@K, nDCG@10 and MRR@10 per query class and language.
- **FR-006**: The system MUST split candidates into passages and score passages, not whole documents, when re-ranking.
- **FR-007**: The system MUST apply a deadline to every re-rank call and MUST return the lexical order when the deadline is missed.
- **FR-008**: The system MUST keep every invariant I1 to I12.
- **FR-009**: The system MUST return the same order for the same input, the same model revision and the same device (scores rounded to four decimals, tie-break by stable id).
- **FR-010**: The system MUST work with the service stopped, and the output MUST show `degraded` with a reason code.
- **FR-011**: The service MUST bind to a Unix socket with mode 0600 or to `127.0.0.1`, and MUST NOT accept other interfaces.
- **FR-012**: The service MUST NOT make any network request at run time.
- **FR-013**: The service MUST NOT write to the existing databases.
- **FR-014**: Models MUST be loaded only from a local, checksum-verified cache, with `trust_remote_code` off.
- **FR-015**: The system MUST store derived data only in `semantic.db` and MUST delete it for excluded or removed documents.
- **FR-016**: `--explain` MUST print the query plan, pool ranks, model score and fusion terms for each result.
- **FR-017**: `ai-worklog memory serve|status|stop|doctor|reindex|eval` MUST exist with the exit codes of [service-contract.md](service-contract.md).
- **FR-018**: The sweep and `record-event` MUST NOT wait for the service.
- **FR-019**: Every new behavior MUST have a kill switch (flag and environment variable).
- **FR-020**: Every summary written for this feature MUST follow `docs/standards/ste100-summaries.md` of ai-worklog-scaleup.

## 13. Success criteria

- **SC-001**: Zero-result rate on the question set is at most 5%.
- **SC-002**: Recall@50 on questions is at least 0.85 after Phase 0.
- **SC-003**: nDCG@10 is at least 0.65 and MRR@10 at least 0.60 after Phase 1.
- **SC-004**: Keyword-style queries do not lose more than 0.01 nDCG@10.
- **SC-005**: Warm p95 added latency of the re-rank stage is at most 1.2 s at depth 30. Total CLI p95 with re-ranking is at most 2.5 s including JVM start.
- **SC-006**: With the service off, the added latency is at most 300 ms.
- **SC-007**: Service resident memory is at most 4 GB with the default model; `semantic.db` is at most 5 GB after Phase 2.
- **SC-008**: Two runs of the same query give the same order.
- **SC-009**: No network connection is opened by the service (checked by a socket test).
- **SC-010**: All existing suites pass unchanged: journal 25, retrieval 31, query 68, chatlog 100, reconciliation 33, shadow validation 44, worklog 42, memory lock 34 (counts of 2026-10-31).

Targets in SC-002 to SC-007 are proposals. Task T011 measures the baseline, and the owner confirms or changes the targets in the decision log before Phase 1 starts.

## 14. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Large model too slow on the laptop | Re-ranking misses the deadline and adds no value | Small default model; anytime scoring; depth 30; benchmark gate in T022 |
| Model licence is not usable on the company laptop | Work blocked or a legal problem | Licence policy with MIT first (D-21); check before download (D-08); excluded classes in `security-privacy.md` 5.1 |
| No MIT multilingual cross-encoder exists | The default cannot be both MIT and Polish-capable | Decision tree in [decision-log.md](decision-log.md) section 4: Apache-2.0 after IP Legal, or an MIT English-only reranker plus Phase 0 and Phase 2 for Polish |
| The model host is blocked on the company network | Models cannot be fetched | Approved source path (D-22): internal artifact repository, offline transfer or an opened host; checked in task T019 |
| Python wheels missing for the interpreter | Service cannot start | Pinned supported Python in a dedicated venv; ONNX Runtime route kept open |
| Gold set too small or biased | Wrong decisions | At least 150 queries from real tickets, two labellers or a labelled-and-reviewed process, per-class reporting (evaluation.md) |
| Re-ranker demotes authoritative or fresh state | Wrong answers with confidence | Fusion with priors; pins; invariants I1 to I3 and I12 in tests |
| Passage split cuts the answer | Lower recall | Structural split first; overlap; best 3 passages per document; tested on the gold set |
| Index grows too large | Disk or memory pressure | 128-dimension models only; 8-bit or compressed vectors; artifacts off by default |
| Extra process breaks the 24/7 setup | Memory service down | Graceful fallback; `doctor`; `launchd` restart; no dependency from the sweep |
| Sensitive text leaves the machine | Privacy breach | No network at run time; offline flags; socket test; security-privacy.md |
| Score drift between devices (CPU vs GPU) | Order changes between runs | Rounding; fixed device per run; determinism test |
| Overfitting weights to the gold set | Poor real-world result | Keep weights simple; hold out 30% of the queries |

## 15. Open questions for the owner

Each has a recommended answer in [decision-log.md](decision-log.md).
1. Accept the licences of the default models after the check (Q1)?
2. Approve a dedicated Python virtual environment and a pinned Python version, or prefer the in-JVM route (Q2)?
3. Confirm the targets of SC-002 to SC-007 after the baseline run (Q3)?
4. Who labels the gold set, and how many hours does the owner give (Q4)?
5. May artifacts be indexed for semantic search (Q5)?
6. Is a 2 to 4 hour one-time encoding job acceptable for Phase 2 (Q6)?
7. Which licences does IP Legal accept besides MIT, and which source path is approved for the model files (Q1, Q9)?

## Summary (STE)

Today the search needs every query word to appear in a document. Plain-language questions return nothing. This plan first fixes that and builds a test set. Then a small local model re-reads the best candidate passages and puts the most useful ones first. A separate service keeps the model ready. If the service stops, the old search still works. Authority and freshness rules stay in force. An optional index of token vectors adds recall for questions that use other words. No data leaves the computer.

**Remaining 20% (not STE100):** file, command, flag, table and model names; the words "FTS5", "BM25", "RRF", "cross-encoder", "ColBERT", "MaxSim", "passage", "tokenizer", "WAL", "ONNX", "venv" and "launchd"; the tables and the diagram; some sentences over 20 words.

---

Created using Anthropic Claude. Internal only until a person has reviewed and verified it.
