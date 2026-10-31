# Decision Log: Semantic Retrieval Upgrade

Status: proposal. Each row is **Proposed** until the owner approves it. Part of [the specification](specification.md).

---

## 1. Decisions

| ID | Decision | Options considered | Reason | Status |
|---|---|---|---|---|
| D-01 | Fix the first stage (query plan, relaxed form) and build the gold set before adding any model | Add the re-ranker first | All 5 natural-language questions in the baseline returned zero results ([evidence](evidence.md) section 2). A re-ranker cannot rerank an empty set, and without a gold set no gain can be proven | Proposed |
| D-02 | Two-stage design: FTS5 and the existing pools generate candidates; a re-ranker scores passages; the model score is fused with the existing signals | Replace the fusion with the model order; keep lexical only | Keeps authority, freshness and pins (invariants I1 to I3, I12). The model can promote a result but cannot remove a pinned one | Proposed |
| D-03 | Cross-encoder first (Phase 1), late interaction second (Phase 2) | ColBERT first; both at once | A cross-encoder needs no new index and "drops cleanly on top of FTS5 results", as the owner's summary says. Late interaction adds recall for paraphrases and moves cost offline, but needs encoding and storage | Proposed |
| D-04 | Default re-ranker is a small multilingual model; large models are opt-in | Large multilingual by default; small English only | Estimated cost of a large model at depth 50 is 15 to 29 s on this laptop ([evidence](evidence.md) section 7). The ticket state is 70% Polish and queries are often Polish, so English-only is the fallback for comparison (S3), not the default. Final choice after the benchmark T022 | Proposed |
| D-05 | One local memory service process with a language-neutral HTTP contract | Load the model in every CLI call; run everything in the JVM | Model load takes seconds and gigabytes. A JVM per call cannot pay it | Proposed |
| D-06 | Python implementation of the service first; in-JVM ONNX Runtime kept as a swap-in behind the same contract | JVM only; Python only without a swap path | The ranking libraries are Python. ONNX Runtime for Java 1.31.1 is on Maven Central and MIT, so the swap is possible. The sentence-transformers page reports ONNX at roughly break-even speed [summary], so the swap is for deployment simplicity, not speed | Proposed |
| D-07 | Dedicated virtual environment with a pinned, supported Python; not the default 3.15 release candidate | Use the default `python3`; use Homebrew 3.14 | torch 2.15 and onnxruntime 1.31 require Python 3.11 or newer; the default is a release candidate; wheel files for macOS arm64 on 3.15 are unverified. RAGatouille lists Python only up to 3.11 | Proposed |
| D-08 | No model is downloaded before its licence is read and recorded; non-commercial licences are excluded | Download first and check later | The model-card fetch failed, so every model licence is unverified. Weights are third-party material (IP Legal flag) | Proposed |
| D-09 | Derived data goes to a new `data/semantic.db`; the existing databases and schema are not changed | Add tables or columns to `convo.db` and `worklogs.db` | Locked decision 14 of the worklog-aware upgrade. A separate file can be deleted and rebuilt | Proposed |
| D-10 | Passages: structural split, then windows of 256 tokens with stride 192, best 3 per document, MaxP | Whole documents; fixed windows only; 512-token windows | Cross-encoders read at most a few hundred tokens; turns can be 680 KB. Structure first keeps user and assistant text apart. Window size is an ablation on the dev split | Proposed |
| D-11 | Artifacts and dross are not indexed semantically by default | Index everything | 15.7 MB of long raw evidence; low value per token; raises cost and privacy scope. Option Q5 | Proposed |
| D-12 | Relaxed form uses stop-word removal, `OR`, and prefix terms cut to `max(4, length - 3)` letters | Add a Polish and English stemmer or lemmatizer; wildcard every term | A prefix cut works for both languages with no dictionary and no new dependency. A real lemmatizer is a Phase 3 option if the gold set shows it is needed | Proposed |
| D-13 | Time-boxed ("anytime") scoring with a deadline and a fallback to the lexical order | Wait for the model; fail the query | The system must work when the service is off or slow (G4) | Proposed |
| D-14 | Score cache in `semantic.db`, 14 days | No cache; in-memory cache | Agents repeat queries. A disk cache survives service restarts | Proposed |
| D-15 | Gold set stays out of git; synthetic fixtures are committed | Commit the gold set | The real queries name real tickets and may hold personal data | Proposed |
| D-16 | Evaluate on a frozen corpus (`--as-of-sequence`) and split dev and test by ticket | Evaluate on live data; split by query | The memory grows daily; splitting by query leaks tickets between dev and test | Proposed |
| D-17 | The semantic indexer has its own lock `_semantic.sqlite` and never takes the `_journal` memory lock | Reuse `_journal` | `record_turn.py` must never wait for an index build | Proposed |
| D-18 | Commands live in the framework group `ai-worklog memory ...`, as the dashboard did | A separate script outside the framework | The owner asked for a memory service integrated into the Groovy-based framework | Proposed |
| D-19 | RAGatouille is a candidate in the Phase 2 bake-off, not the default | RAGatouille by default (named in the owner's summary) | Its README lists Python 3.9 to 3.11 only and the latest release is 0.0.9 ("11 Feb", year not shown). sentence-transformers 6.2.0 documents a `MultiVectorEncoder` with MaxSim and PyLate (MIT) provides PLAID indexing and `rank.rerank`. The bake-off T041 decides | Proposed |
| D-20 | One late-interaction model is chosen only after a 128-dimension variant is compared with a large multi-vector model | Take the large multi-vector model because it is multilingual | Storage for 12.4 million token vectors is about 3.2 GB at 128 dimensions in half precision and about 25 GB at 1024 dimensions ([evidence](evidence.md) section 7) | Proposed |

## 2. Alternatives not chosen (and when to revisit)

| Alternative | Why not now | Revisit when |
|---|---|---|
| Dense bi-encoder with a vector index (`sqlite-vec` 0.1.9 or an in-memory matrix) | One vector per passage loses token-level matching; a good recall helper but not a ranking gain over a cross-encoder | Phase 2 shows the multi-vector index is too large; a single-vector pool is the cheaper fallback |
| Learned sparse retrieval | New index type with little gain over relaxed FTS5 plus prefix at this size | Gold set shows lexical recall below target after Phase 0 |
| LLM re-ranker (a 0.6 billion parameter reranker or larger) | Heavier than a small cross-encoder; licence unverified | A benchmark shows the budget allows it |
| Query rewriting with a generative model | Adds a model that reads text (injection surface S9) and latency | Phase 3, with its own review |
| Polish lemmatizer | New dictionary dependency; the prefix cut may be enough | Polish recall on the gold set is below the English recall by more than 0.10 |
| Fine-tuning a cross-encoder on the gold set | Needs more labels than 150 and a hold-out discipline | At least 500 labelled queries exist |
| Replace SQLite FTS5 | Works, is fast and keeps the current invariants | Never needed for this corpus size |

## 3. Open questions for the owner

| ID | Question | Recommended answer |
|---|---|---|
| Q1 | May the licences of the default models be accepted after they are read and recorded? | Yes, for Apache-2.0 or MIT; no for non-commercial; send anything else to IP Legal |
| Q2 | Dedicated Python virtual environment with a pinned Python, or the in-JVM route? | Virtual environment first (D-06, D-07) |
| Q3 | Confirm the targets in SC-002 to SC-007 after the baseline run? | Yes, at gate G0, with the baseline numbers in view |
| Q4 | Who labels the gold set, and how many hours does the owner give? | Assistant generates state-derived items and pools; owner writes 30 queries and reviews 20% (about 2 hours) |
| Q5 | May artifacts be indexed semantically? | Not by default; revisit after Phase 1 |
| Q6 | Is a one-time encoding job of 2 to 4 hours (large model, estimate) or minutes (small model) acceptable in Phase 2? | Yes, unattended, started overnight, resumable |
| Q7 | Is Privacy Legal approval in place for personal data of people other than the owner, if the memory holds any? | Confirm before Phase 2 |
| Q8 | Should `--rerank auto` be the default after gate G2? | Yes, with `AI_MEMORY_RERANK=0` as the kill switch |

## Summary (STE)

This log lists 20 decisions and 8 open questions. The main choices are: fix the first search stage first, measure with a test set, re-rank with a small multilingual model, keep the old signals in the final order, run the model in a separate service, and keep all new data in a separate database. The owner has not approved any of them yet.

**Remaining 20% (not STE100):** decision, model, library and table names; the words "FTS5", "cross-encoder", "bi-encoder", "MaxP", "ONNX", "lemmatizer", "PLAID" and "dimension"; the tables; some sentences over 20 words.

---

Created using Anthropic Claude. Internal only until a person has reviewed and verified it.
