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

## 3. Storage estimate for a real model

Unchanged from [evidence.md](evidence.md) section 7: 12.4 million token vectors are about 1.6 GB at 128 dimensions in 8 bits. The built store keeps the matrix as one blob per passage in SQLite and scans all rows in pure Python; for 12.4 million tokens that scan is far too slow for interactive use. **A real index needs numpy or a compiled MaxSim and a memory-mapped matrix**, which are not built because the model and library choice (D-19, D-20) is open.

## 4. Not verified (waiting for decisions)

- **Any real late-interaction model**, its dimension, its storage and its encoding time (T041, Q6).
- **Paraphrase recall** (the gate: +0.10 absolute on class P).
- **Speed of the full scan** on the real corpus.
- **Candidate re-scoring** (system S6): not built.
- **The unattended encoding run** on the real memory.
- **Gate G3.**

## Summary (STE)

The token-vector store, the late search, the late pool and the `reindex` command are built and tested with a stub encoder. No real encoder is chosen, so we do not know the real recall gain, the disk size or the speed. The scan is written in plain Python and is too slow for the real corpus, so a faster version is needed after the model is chosen.

**Remaining 20% (not STE100):** file, command and table names; the words "stub", "encoder", "MaxSim", "numpy", "quantization", "blob" and "manifest"; the tables; some sentences over 20 words.

---

Created using Anthropic Claude. Internal only until a person has reviewed and verified it.
