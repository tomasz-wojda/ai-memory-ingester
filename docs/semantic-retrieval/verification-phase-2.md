# Verification: Phase 2 (interim)

Status: built and checked on 2026-11-01, **with a stub encoder only**. Gate G3 is open. Part of [the specification](specification.md).

---

## 1. What was built

| Task | Result |
|---|---|
| T042 | `vectors` table (8-bit, one scale per passage matrix) and `/v1/index/upsert`: encodes passages without vectors in batches, resumable |
| T043 | `/v1/search/late`: exact MaxSim over all stored vectors, filters by archive, database and ticket path; the manifest refuses an index built with another model (`index_stale`) |
| T044 | `late` pool in `MemoryRetrieval` (weight 3), `signals.late`, `--late`, `AI_MEMORY_LATE=0` |
| T045 | `reindex` command (`11_reindex.groovy`): incremental by content hash, orphan removal, `--verify`, `--rebuild`, `--no-encode`; sweep hook `AI_MEMORY_SWEEP_INDEX=1` |
| eval | Systems S2 (relaxed plus re-rank) and S5 (S2 plus the late pool) in `eval` |

## 2. Tests

All in `tests/test_semantic_suite.groovy` and `tests/service/` (see verification-phase-1.md for counts): late round trip (upsert, encode in batches, search, filters, status), stale index refused, no late model without a decision, `reindex --verify` clean after indexing, an orphan found and removed, a changed document skipped or replaced, `--rebuild`, the late pool contributes rows, switches turn it off, explicit queries get no late pool, quantization round trip, deterministic search order.

The stub encoder hashes words into 16-dimension vectors, so a passage that shares words with the query scores high. It tests the plumbing, not paraphrase recall.

## 2b. Faster scan and candidate re-scoring (added the same day)

- **Accelerated MaxSim:** with numpy installed the service keeps all vectors in one half-precision matrix in memory and scans it in blocks; the plain-Python scan stays as the fallback. Both give the same order and scores within 0.002 (tested on 40 passages with filters, with a block size of 8 tokens, and after new vectors arrive: the in-memory index follows the vector count).
- **Measured on synthetic data** (2.0 million token vectors, 128 dimensions, 32 query tokens): numpy 198 ms; plain Python about 194 s (computed from 2.4 ms per passage), so about 1000 times faster. **Estimated** for the full corpus (12.4 million tokens): about 1.2 s and 3.2 GB of memory in half precision. Real vectors and a real encoder may differ.
- **Tested with numpy 2.5.4 on Python 3.14.7** in a throw-away virtual environment, and without numpy on the default Python 3.15 release candidate. The service tests (46) and the semantic suite (74) pass under both.
- **Candidate re-scoring (system S6):** `--rerank late` asks the service to scan only the vectors of the candidate documents (`filters.docs`); the best passage of each document is its score, fused as the `rerank` signal with the same state-capsule pin. Tested with the stub encoder.

## 3. Storage estimate for a real model

Unchanged from [evidence.md](evidence.md) section 7: 12.4 million token vectors are about 1.6 GB at 128 dimensions in 8 bits. The store keeps one 8-bit blob per passage in SQLite. The plain-Python scan is too slow for the real corpus (about 194 s for 20,000 passages); the numpy scan measured 198 ms per 2 million token vectors on synthetic data. The in-memory half-precision matrix needs about 3.2 GB for the full corpus (estimate), close to the 4 GB limit; an 8-bit or memory-mapped matrix is not built because the real model decides the dimension (D-19, D-20).

## 4. Not verified (waiting for decisions)

- **Any real late-interaction model**, its dimension, its storage and its encoding time (T041, Q6).
- **Paraphrase recall** (the gate: +0.10 absolute on class P).
- **Speed of the scan on real vectors** (the measured numbers are synthetic).
- **Quality of candidate re-scoring** (S6) with a real encoder.
- **The unattended encoding run** on the real memory.
- **Gate G3.**

## Summary (STE)

The token-vector store, the late search, the late pool and the `reindex` command are built and tested with a stub encoder. No real encoder is chosen, so we do not know the real recall gain, the disk size or the speed. The scan is written in plain Python and is too slow for the real corpus, so a faster version is needed after the model is chosen.

**Remaining 20% (not STE100):** file, command and table names; the words "stub", "encoder", "MaxSim", "numpy", "quantization", "blob" and "manifest"; the tables; some sentences over 20 words.

---

Created using Anthropic Claude. Internal only until a person has reviewed and verified it.
