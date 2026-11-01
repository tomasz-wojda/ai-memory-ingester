# Verification: Phase 1 (interim)

Status: built and checked on 2026-11-01, **with a stub model only**. Gate G2 (a real model, the gold set, the benchmark) is open. Part of [the specification](specification.md).

---

## 1. What was built

| Task | Result |
|---|---|
| T024 | Python service `service/ai_memory_service/` (config, protocol, store, backends, encoders, MaxSim, server, doctor) and the launcher `service/ai-memory-service`. Standard library only. |
| T025 | `CrossEncoderBackend`: loads only from a local folder, only `safetensors`, checksum required, `trust_remote_code` off, offline variables set. |
| T026 | `lib/PassageBuilder.groovy`: turn sections, state headings, windows of 900 characters with stride 675, context prefix, best 3 passages per document, ids that match the service |
| T027 | `lib/SemanticClient.groovy`: Unix socket, bearer token, circuit of 30 s per endpoint, reason codes |
| T028 | `lib/RerankStage.groovy` and `MemoryRetrieval`: depth, MaxP, `rerank` signal with weight 5, state-capsule pin, result fields `rerank_score`, `rerank_rank`, `best_passage` |
| T029 | Score cache in `semantic.db`; passage upsert and delete |
| T030 | `--rerank`, `--rerank-depth`, `--rerank-budget-ms`, `--explain` output, exit code 5, `AI_MEMORY_RERANK=0` |
| T031 | Framework: `ai-worklog memory serve|status|stop|doctor|reindex|eval` |
| T032 | Fallback and switch tests |
| T034 | `worklog-chat-memory` skill 1.5.0 documents questions, the flags, the service and the fallback |
| extra | `service/benchmark.py` (T022 harness) and `ai-memory-service checksum` (T023 tool) |

## 2. Tests

| Suite | Result |
|---|---|
| `tests/service/` (Python `unittest`) | 46 run. Default Python 3.15 release candidate: 41 pass, 5 skipped on purpose (4 need numpy, 1 needs a real model and `RUN_MODEL_TESTS=1`). Python 3.14.7 with numpy: 45 pass, 1 skipped |
| `tests/test_semantic_suite.groovy` (new) | 74 pass under both Python interpreters: passages, client, re-rank on the stub service, invariants, fallback, kill switches, late pool and late re-score, `reindex`, command line, evaluation systems S2, S5 and S6 |
| journal 25, retrieval 31, query 68, chatlog 100, reconciliation 33, shadow validation 44 (4 min 30 s), worklog 42, memory lock 36, eval 27, query plan 80 | all pass |
| Framework, Groovy | 497 pass (8 new for `MemoryCommands`) |
| Framework, pytest | 335 pass, 190 skipped (unchanged) |
| `repos/ai-vault` `validate-skills.sh` | passes |

What the tests prove: the contract (token, `Origin`, limits, errors, deadline cut between batches, queue full returns 429, cache), the passage ids, the pin and the invariants I1, I8, I10 and I12, the exit code 5, the kill switches, and that the service holds no network socket (`lsof`) and leaves neighbouring database files byte-identical.

## 3. Invariants

| ID | Result |
|---|---|
| I1 state capsule first for a ticket facet | Tested after re-rank (query "FIX-101 monitoring") |
| I2, I3 stale label and newer evidence | Not touched: the ticket-only path is not re-ranked (D-26); the existing retrieval suite passes |
| I4 deterministic | Tested twice with the service on |
| I5 complete rows, I6, I7, I9 | Unchanged code paths; existing suites pass |
| I8 explicit query unchanged | Tested: no `rerank_score` on an operator query |
| I10 service off equals lexical | Tested |
| I11 excluded documents have no passages | Orphans are removed by `reindex` (tested); no hook inside `egest` |
| I12 model never removes a lexical result | Tested on the ticket query |

## 4. Not verified (waiting for decisions)

- **Any real model.** No model is chosen, licensed or downloaded (Q1, D-08, D-21, D-22). `CrossEncoderBackend` has never scored a pair. The stub score is term overlap, so **no quality number exists** for the re-ranker.
- **Latency of a real model** (T022). The harness works; the stub takes 0.1 ms.
- **Gate G2**: nDCG and MRR on the real gold set (T005, T011).
- **Python environment with hashed requirements** (T021, Q2). The service runs on the default Python 3.15 release candidate only because it uses the standard library.
- **`launchd` job** for the service. Not built.
- **A real `ai-worklog memory serve` run with a real model**, and the `memory` commands through a real framework build with `--daemon`. The framework tests use fake launchers; the real `ai-worklog memory status` printed "not running" correctly.
- **The full audit workflow of the `security-audit` skill.** A guidance-mode review was done and found six small problems, all fixed ([security-review.md](security-review.md)).

## 5. Differences from the specification

1. Passage windows are cut in characters, not model tokens (the spec's fallback).
2. Re-ranking applies to `query` with free text that is not an explicit query (D-26).
3. The circuit is per endpoint (D-27).
4. The service has two more endpoints, `/v1/passages/missing` and `/v1/passages/docs`, used by `reindex`.
5. The `vectors` table has a `scale` column; vectors are 8-bit only.
6. `serve --init` writes the configuration and runs the doctor without starting (D-29).
7. A deletion hook inside `egest` and a deletion path for `MemoryExclusion` are not built; `reindex` removes orphans.

## 6. Found and fixed on the way

- The sweep hook (`AI_MEMORY_SWEEP_INDEX=1`) first started `reindex` without the sweep's data folder, so a test indexed real documents into a temporary stub service (read-only on your databases; the temporary database was thrown away). Fixed by passing `--data-dir`; the generated-data check now shows `orphans=0`. The three large databases show a changed modification time from that read; `journal --integrity` still says `ok` for 28,197 observations.
- A Unix socket path longer than 100 characters made the first test run fail; `serve` now refuses it with a clear message.

## Summary (STE)

The model service, the passage builder, the client and the re-ranking stage are built, and all tests pass. But only a stub model has run. No real model is chosen or licensed, so we cannot say how good the re-ranking is, and the gate for Phase 1 stays open. The old search is unchanged when the service is off.

**Remaining 20% (not STE100):** file, command, flag and table names; the words "stub", "circuit", "passage", "MaxP", "invariant", "checksum", "socket" and "venv"; the tables; some sentences over 20 words.

---

Created using Anthropic Claude. Internal only until a person has reviewed and verified it.
