# Evidence: Semantic Retrieval Upgrade

Status: measured on 2026-10-31. Part of [the specification](specification.md).

Every fact has a source. A fact marked **unverified** has not been checked here and must be checked before the work that depends on it. Numbers marked **estimate** are computed from stated assumptions, not measured.

---

## 1. How retrieval works today

| # | Fact | Source |
|---|---|---|
| 1 | First stage is SQLite FTS5 only: a contentless table `documents_fts` with `tokenize='unicode61 remove_diacritics 2'` and `prefix='2 3 4'`. There is no stemming, no lemmatization and no vector index. | `lib/MemoryEngine.groovy` lines 121-136 |
| 2 | Bare query terms are ANDed. The help text says so: "Bare terms are ANDed." | `ai-memory-ingester query --help` |
| 3 | `normalizeQuery` joins tokens with a space, so FTS5 applies implicit AND. Operators `AND`, `OR`, `NOT`, phrases, `prefix*` and column filters are accepted only when the caller types them. | `lib/MemoryEngine.groovy` `normalizeQuery`, `tokenizeQuery` |
| 4 | Five candidate pools are fused with reciprocal rank fusion (`RRF_K` = 60) and fixed weights: exact 4, bm25 3, recency 2, authority 2, impact 1. Each pool holds at most `POOL_LIMIT` = 50 rows. | `lib/SignalFusion.groovy`, `lib/MemoryRetrieval.groovy` |
| 5 | After fusion, `NeighborExpansion` adds the adjacent turn (radius 1), freshness labels are applied, and `ContextPacker` packs complete rows into a byte budget (22000 bytes for ticket context). | `lib/MemoryRetrieval.groovy`, `lib/NeighborExpansion.groovy`, `lib/ContextPacker.groovy` |
| 6 | Whole documents are indexed. There is no passage split. A snippet window of 64 tokens is cut only for display. | `lib/MemoryEngine.groovy` `searchInternal`, `generateSnippet` |
| 7 | The search body of a turn is its `## User`, `## Assistant` and `## Details` sections; the front matter is removed before indexing. | `lib/MemoryEngine.groovy` `extractSearchBody` |
| 8 | Existing retrieval tests check these invariants: body relevance beats a path-only match, exact ticket precedence, stale worklog label, newer evidence before a stale capsule, deterministic fusion, neighbor expansion, complete-row packing (never snippet-only), `journal.db` excluded from search, multi-ticket OR queries keep the boolean expression. | `tests/test_retrieval_suite.groovy` (31 checks) |
| 9 | Locked decisions of the earlier upgrade stay in force: extractive state capsules, never an AI summary as authoritative state; deterministic candidate pools; the existing SQLite schema is preserved. | `docs/worklog-aware-memory-upgrade-specification.md` section 2 |

## 2. Measured baseline (real data, read-only, 2026-10-31)

Command: `ai-memory-ingester query "<text>" --limit 10` through the launcher, JVM start included. One run each. Only counts and times are recorded here, not content.

| Query (kind) | Results | Time |
|---|---|---|
| one keyword | 10 | 1.51 s (first call) |
| two keywords | 10 | 0.86 s |
| ticket key only | 10 | 0.88 s |
| English question, 8 words ("what did we decide about ...") | **0** | 0.69 s |
| English question, 6 words ("why was the ... version pinned") | **0** | 0.69 s |
| Polish question, 6 words, typed without diacritics | **0** | 0.69 s |
| Polish phrase, 3 words, typed without diacritics | **0** | 0.69 s |
| English phrase, 5 words | **0** | 0.68 s |
| same ideas as explicit `OR` queries | 10 | 0.9 to 1.0 s |
| same ideas with prefix terms (`usuwani* OR uzytkownik*`) | 10 | 0.73 s |

Reading:
- **Every natural-language question returns nothing.** A question word such as "what" or "did" must appear in the same document as all other words. This is a recall failure in the first stage. A re-ranker cannot fix an empty candidate set.
- Inflection also fails: "pinned" does not match "pin", and a Polish form does not match another form of the same word. Typed prefixes and `OR` give results.
- Latency of 0.7 to 1.0 s is mostly JVM start. The search itself is small.

### 2b. Tokenizer check (SQLite FTS5, `unicode61 remove_diacritics 2`, 2026-11-01)

Text indexed: "złożyliśmy decyzję o użytkownikach było".

| Query | Matches | Reading |
|---|---|---|
| `zlozylismy` (ASCII) | 0 | ł is not folded |
| `złozylismy` (ł kept, rest ASCII) | 1 | a variant with ł matches |
| `złożyliśmy` (typed in full) | 1 | |
| `uzytkownikach` (ASCII) | 1 | ż, ś, ń, ó are folded |
| `bylo` / `było` | 0 / 1 | the most common case |
| `zloz*` / `zł*` | 0 / 1 | prefix terms behave the same |

Reading: a Polish query typed without diacritics misses every word that holds ł. The query planner adds ł variants for Polish queries (decision D-24).

## 3. Corpus size (2026-10-31)

| Store | Archive | Documents | Average size | Largest | Total |
|---|---|---|---|---|---|
| `convo.db` | `chat:turns` | 9,748 | 4,227 B | 680,946 B | 41.2 MB |
| `worklogs.db` | `worklog:chunks` | 612 | 3,330 B | 40,712 B | 2.04 MB |
| `worklogs.db` | `worklog:state` | 51 | 5,810 B | 24,780 B | 0.30 MB |
| `artifacts.db` | `chat:artifacts` | 841 | 18,639 B | 1,449,016 B | 15.7 MB |
| `dross.db` | `chat:dross` | 11 | 12,611 B | 51,264 B | 0.14 MB |
| `journal.db` | canonical observations | 28,186 | n/a | n/a | 197.6 MB file (not searched) |

- No turn document is compressed (`is_compressed` = 0 for all 9,748).
- Total searchable text: about 59.4 MB. Without artifacts and dross: about 43.5 MB.
- **Estimate:** at about 3.5 characters per token (mixed English, Polish and code), the searchable text is about 17 million tokens, and turns plus worklogs plus state about 12.4 million tokens. Measure with the real tokenizer before sizing an index.

Language mix (documents that contain at least one Polish letter, so a lower bound; Polish typed without diacritics is not counted):

| Store | Documents with Polish letters |
|---|---|
| turns | 373 of 9,748 (3.8%) |
| worklog chunks | 84 of 612 (13.7%) |
| worklog state capsules | 36 of 51 (70.6%) |
| artifacts | 64 of 841 (7.6%) |

Reading: the text is mostly English. The authoritative layer (state capsules, Jira titles) is mostly Polish. Queries from the owner are often Polish without diacritics. A model must handle both languages, or the authority layer is retrieved worst.

## 4. Machine

| Item | Value | Source |
|---|---|---|
| CPU | Apple M4, arm64 | `uname -m`, `sysctl` |
| Memory | 24 GB (25,769,803,776 bytes) | `sysctl hw.memsize` |
| Free disk | 260 GB | `df -h /` |
| Java | OpenJDK 27 | `java -version` |
| Default `python3` | 3.15.0rc1 (a release candidate) | `python3 --version` |
| Other Python | Homebrew `python@3.14` in the Cellar; system `/usr/bin/python3` is 3.9.6 | `ls /opt/homebrew/Cellar`, `/usr/bin/python3 --version` |
| Installed ML packages | none: no `torch`, `sentence_transformers` or `onnxruntime`; no `~/.cache/huggingface` | import tests, `pip3 list`, `ls` |
| Package managers | `uv`, `pyenv` and `conda` are not installed | `which` |

## 5. Library facts (fetched 2026-10-31 by a read-only fetch agent; quotes verified by the agent unless marked [summary])

| Library | Fact |
|---|---|
| sentence-transformers | 6.2.0, released Oct 21, 2026, `Python >=3.10`, Apache-2.0, extras `onnx` and `openvino`. The page documents a `MultiVectorEncoder` with `encode_query`, `encode_document` and a MaxSim `similarity`. Backends for `CrossEncoder`: PyTorch (default), ONNX, OpenVINO. [summary] ONNX gives roughly break-even speed (0.9 to 1.1x) in the measured table; half precision gives 2 to 3x on larger rerankers. |
| torch | 2.15.0, released Oct 28, 2026, `Python >=3.11`, classifiers list 3.14 and 3.15. macOS arm64 wheels for 3.14 and 3.15 are **unverified** (the file list was cut off); [summary] names `cp314` and `cp315` `macosx_14_0_arm64` wheels. |
| onnxruntime (Python) | 1.31.1, released Oct 26, 2026, `Python >=3.11`, MIT. The file list includes `cp314 macosx_14_0_arm64`. |
| onnxruntime (Java) | 1.31.1 on Maven Central, MIT. A Java process can run ONNX models without Python. |
| RAGatouille | 0.0.9 is the latest release (shown as "11 Feb", year not shown). The README badge lists Python 3.9, 3.10, 3.11. Apache-2.0. It persists indexes on disk. Examples use `colbert-ir/colbertv2.0`. No maintenance warning is shown. "Windows is not supported." The PyPI page did not load. |
| PyLate | "Built on top of Sentence Transformers", MIT. README shows `indexes.PLAID(index_folder=...)` and a `rank.rerank` function. Also lists WARP and TACHIOM backends. PyPI page did not load, so version and Python support are **unverified**. |
| FlagEmbedding | 1.4.2, released Aug 24, 2026, MIT. Describes BGE-M3 as dense, sparse and multi-vector (ColBERT), 8192 tokens. Class names `BGEM3FlagModel` and `FlagReranker` are not stated on the page. |
| sqlite-vec | 0.1.9 stable (Mar 31, 2026), MIT or Apache-2.0; the PyPI description is a placeholder ("TODO readme"). |

Reading: the Python default on this machine (3.15 release candidate) is a risk for ML wheels. RAGatouille lists Python up to 3.11 only. A dedicated virtual environment with a pinned supported Python is needed, or the JVM route through ONNX Runtime.

## 6. Model facts: unverified

On 2026-10-31 and again on 2026-11-01 the host `huggingface.co` did not answer from this machine: 9 of 9 model-card pages, then 15 of 15 JSON API calls and one raw README timed out (`ETIMEDOUT`). The cause (network, proxy or the host) is unknown. A company laptop may meet the same block.

The fetch of all nine Hugging Face model cards timed out twice (`ETIMEDOUT`). The following are **not verified here**. Check each licence, size, language list and `trust_remote_code` need on the model card before any download:

- `BAAI/bge-reranker-v2-m3` (multilingual cross-encoder)
- `BAAI/bge-m3` (dense, sparse and multi-vector)
- `cross-encoder/ms-marco-MiniLM-L-6-v2` (English cross-encoder, small)
- `cross-encoder/mmarco-mMiniLMv2-L12-H384-v1` (multilingual cross-encoder, small)
- `jinaai/jina-colbert-v2` and `jinaai/jina-reranker-v2-base-multilingual` (the licence may be non-commercial: check)
- `colbert-ir/colbertv2.0`, `answerdotai/answerai-colbert-small-v1` (English)
- `Qwen/Qwen3-Reranker-0.6B`

## 7. Cost arithmetic (estimates; the real numbers come from the benchmark in task T022)

Assumptions: `FLOPs ≈ 2 × parameters × tokens`; an Apple M4 sustains an unmeasured 1 to 2 TFLOPS on half precision for transformer inference.

| Case | Parameters | Tokens | Work | Time at 1 to 2 TFLOPS |
|---|---|---|---|---|
| Cross-encoder, 50 pairs of 512 tokens | 568 million (class "large", unverified) | 25,600 | about 29 TFLOP | about 15 to 29 s: too slow |
| Cross-encoder, 30 pairs of 256 tokens | 568 million | 7,680 | about 8.7 TFLOP | about 4 to 9 s: still slow |
| Cross-encoder, 50 pairs of 256 tokens | 21 million non-embedding (class "small multilingual") | 12,800 | about 0.5 TFLOP | under 1 s |
| Offline encoding of 12.4 million tokens | 568 million | 12.4 million | about 14 PFLOP | about 2 to 4 hours, one time |
| Offline encoding of 12.4 million tokens | 33 million (class "small ColBERT", English) | 12.4 million | about 0.8 PFLOP | about 7 to 14 minutes, one time |

### Measured on 2026-11-03 (task T022)

The benchmark (`service/benchmark.py`) ran on this machine (Apple M4, 24 GB), batch 16, medians of 5 to 12 runs. The limit of the engine is 1.2 s for 30 passages.

**Correction (2026-11-03).** The benchmark parameter `--tokens` counts words, not model tokens. An earlier run used 256 words, which is about twice the real engine load. The engine sends windows of about 1000 characters: about 246 model tokens for `mmarco` and about 308 for `polish-reranker-roberta-v3`. The table below uses 128 words, close to that load. The earlier 256-word table was replaced.

Median time in ms at depth 10 / 30 / 50:

| Model (weights) | MPS | CPU | Per passage (MPS / CPU) | Peak memory (MPS / CPU) |
|---|---|---|---|---|
| `ms-marco-MiniLM-L-6-v2` (91 MB, English) | 24 / 70 / 120 | 71 / 226 / 377 | 2.3 / 7.5 ms | 582 / 679 MB |
| `ms-marco-MiniLM-L-12-v2` (133 MB, English) | 44 / 131 / 222 | 142 / 434 / 738 | 4.4 / 14.5 ms | 579 / 708 MB |
| `mmarco-mMiniLMv2-L12-H384-v1` (471 MB, multilingual) | 58 / 171 / 287 | 190 / 597 / 1002 | 5.7 / 19.9 ms | 1142 / 1300 MB |
| `BAAI/bge-reranker-v2-m3` (2.27 GB, multilingual) | 584 / 1816 / 3113 | 1486 / 4467 / 7497 | 60.5 / 149 ms | 1024 / 2454 MB |
| `sdadas/polish-reranker-roberta-v3` (1.77 GB, Polish only) | 776 / 2356 / 3949 | 1911 / 5589 / 9343 | 78.5 / 187 ms | 710 / 2153 MB |

- Inside the 1.2 s limit at depth 30: the three small models on MPS, and on the CPU the two English models and `mmarco` (597 ms). `mmarco` does not need MPS.
- The two large models do not fit at depth 30. At 60 to 80 ms per passage on MPS they fit about 15 to 20 passages; on the CPU they fit about 6 to 8.
- The estimate above (15 to 29 s for 50 pairs of 512 tokens) was not tested at that length. At 128 words the large model needs 3.1 s on MPS and 7.5 s on the CPU for 50 passages.
**Load time (2026-11-03).** Measured with `CrossEncoderBackend` (`verify`, `load`, then one dummy score), one run per cell, files in the OS cache. A first run after a restart reads the disk and is slower. Times in seconds.

| Model | Folder size | Checksum | Load (MPS / CPU) | First score (MPS / CPU) | Total (MPS / CPU) |
|---|---|---|---|---|---|
| `ms-marco-MiniLM-L-6-v2` | 88 MB | 0.04 | 3.2 / 2.1 | 0.81 / 0.01 | 4.0 / 2.2 |
| `ms-marco-MiniLM-L-12-v2` | 129 MB | 0.05 | 2.2 / 2.2 | 0.09 / 0.02 | 2.4 / 2.2 |
| `mmarco-mMiniLMv2-L12-H384-v1` | 486 MB | 0.19 | 3.3 / 3.2 | 0.09 / 0.02 | 3.6 / 3.4 |
| `BAAI/bge-reranker-v2-m3` | 2.1 GB | 0.87 | 4.9 / 3.6 | 0.34 / 0.13 | 6.1 / 4.7 |
| `sdadas/polish-reranker-roberta-v3` | 1.7 GB | 0.67 | 3.5 / 2.9 | 0.18 / 0.13 | 4.4 / 3.7 |

- Load time depends little on size: 2 to 5 s for all five. The fixed cost of importing the libraries is a large part of it. The checksum check takes under 1 s.
- The first score of the first model in a process includes the GPU set-up, which explains the 0.81 s of L-6 on MPS.
- The service loads the default model at start, so a query does not pay this cost. A model that is not the default loads on its first use. That takes 3 to 6 s, above the 1.2 s budget, so that first query degrades to `deadline_exceeded`.

- **Sanity probe** (not a quality measurement: 8 made-up paraphrase items per set, so one item is 0.125; a random order gives MRR 0.52). The right passage shares almost no words with the question, and each distractor shares a key word:

| Model | English | Polish | Polish, ASCII question and passages with diacritics | All ASCII |
|---|---|---|---|---|
| `ms-marco-MiniLM-L-6-v2` | 0.46 | 0.51 | 0.57 | 0.51 |
| `ms-marco-MiniLM-L-12-v2` | 0.40 | 0.53 | not run | 0.59 |
| `mmarco-mMiniLMv2-L12-H384-v1` | 0.82 | 0.84 | 0.73 | 0.50 |
| `BAAI/bge-reranker-v2-m3` | 0.81 | 0.84 | 0.82 | 0.64 |
| `sdadas/polish-reranker-roberta-v3` | 0.69 | 0.91 | 0.91 | 0.91 |

  The English-only models are near random on the harder probe. The two multilingual models handle Polish with diacritics as well as English. Text typed without diacritics on both sides hurts the small model most. So the passages must keep their diacritics when they go to the re-ranker. A first, easier probe was discarded because even the English-only model scored 10 of 10 on Polish.
- **Real inference through the service and the engine** (task T025): the real-model test passes for all five models. In an end-to-end run, `ai-memory-ingester query ... --rerank on` scored 32 passages with `mmarco` in 223 ms. The first run showed a cold-start fault: the model loaded on the first request, which exceeded the 1.2 s deadline. The service now loads the default re-rank model when it starts.

Multi-vector storage is `tokens × dimension × bytes per value`:

| Variant | Size for 12.4 million token vectors |
|---|---|
| 128 dimensions, half precision | about 3.2 GB |
| 128 dimensions, 8-bit | about 1.6 GB |
| 1024 dimensions, half precision (the dimension of a large multi-vector model is **unverified**) | about 25 GB |
| 128 dimensions with 2-bit residual compression (PLAID style) | a few hundred MB (estimate) |

Reading:
1. A large cross-encoder does not fit an interactive budget on this laptop at depth 50. The default must be a small model, a small depth, or a time-boxed ("anytime") scoring loop.
2. Late interaction moves the cost offline. Query time is one short query encoding plus MaxSim over the candidate matrices. The price is storage and a one-time encoding job.
3. A 1024-dimension multi-vector index of the full corpus is too large without compression. A 128-dimension model fits.

## 8. Method

The work follows the mapped skills in `repos/ai-worklog-scaleup/docs/resources/skills/`:
- **spec-kit** templates: user stories with priorities, functional requirements, success criteria, plan, tasks, checklist.
- **research** (matt-pocock-skills): primary sources first; facts and unknowns are separated (sections 5 and 6).
- **rea** method: stage the investigation, pin every fact to a source, mark what is unverified.
- **security-audit**: the threat review in [security-privacy.md](security-privacy.md).
- **verification-before-completion**: no gate is marked passed without a recorded command and result ([evaluation.md](evaluation.md)).
- **writing-plans** and **to-tickets**: the task list with blocking edges in [tasks.md](tasks.md).

## Summary (STE)

Today the search finds only documents that contain every word of the query. Questions in plain language return no results. Inflected words do not match. These are first-stage problems, so a re-ranker alone cannot help. The text is mostly English, but the ticket state is mostly Polish. A large re-ranking model is too slow on this laptop at depth 50. A late-interaction index moves the work offline but needs disk space. The licence of every model is still unchecked.

**Remaining 20% (not STE100):** file, command, model and library names; the words "FTS5", "RRF", "cross-encoder", "multi-vector", "MaxSim", "tokenizer", "TFLOP" and "PLAID"; the tables and the arithmetic; some sentences over 20 words.

---

Created using Anthropic Claude. Internal only until a person has reviewed and verified it.
