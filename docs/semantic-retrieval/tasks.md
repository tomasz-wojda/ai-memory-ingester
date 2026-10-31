# Tasks: Semantic Retrieval Upgrade

Status: proposal. No task is started. Part of [the specification](specification.md).

Legend: `[P]` can run in parallel with other `[P]` tasks of the same phase. `needs: T..` lists blocking tasks. `OWNER` marks a step that needs the owner. Repositories: `ING` = `repos/ai-memory-ingester`, `FWK` = `repos/ai-worklog-framework`, `VAULT` = `repos/ai-vault`.

Each task finishes with its tests and, at phase end, a gate from [evaluation.md](evaluation.md). A plan change after approval returns to PLAN.

---

## Phase 0: measure and fix the first stage

- [ ] T001 [ING] Tag the baseline commit `semantic-baseline`; record the suite counts (journal 25, retrieval 31, query 68, chatlog 100, reconciliation 33, shadow validation 44, worklog 42, memory lock 34).
- [x] T002 [P] [ING] `lib/EvalMetrics.groovy`: Recall@K, nDCG@10, MRR@10, Success@K, zero-result rate, paired bootstrap; `tests/test_eval_suite.groovy` with hand-computed cases. _Done 2026-11-01: 27 checks._
- [x] T003 [P] [ING] Fixture corpus `tests/fixtures/semantic/` (43 documents, English and Polish; 24 queries covering classes K, Q, P, T, X as `queries.jsonl`) and the builder `tests/SemanticFixtureBuilder.groovy`. _Done 2026-11-01. A stub scorer is not needed in Phase 0 (no model); it comes with T024._
- [x] T004 [ING] Gold set tooling: `lib/GoldSet.groovy` (load, validate, candidates from state decisions, blockers and next actions) and the `eval --gold-candidates`, `--validate` and `--pool-export` commands. _Done 2026-11-01. On the real workspace it made 238 unlabelled candidates from 16 state files (git-ignored). The capsule-source link to a positive document is not built; the pool export is the way to find positives._ needs: T002
- [ ] T005 OWNER [ING] Build the gold set (section 3 of the evaluation plan): 30 owner-written queries, review of 20%, kappa at least 0.6; freeze at a sequence number. needs: T004
- [x] T006 [P] [ING] `lib/StopWords.groovy` (English and Polish, matched on the folded form) and `lib/TextFold.groovy`; tests. _Done 2026-11-01._
- [x] T007 [ING] `lib/QueryPlanner.groovy`: strict and relaxed forms, prefix terms, Polish ł variants, explicit-operator bypass; `tests/test_query_plan_suite.groovy` (80 checks with the integration tests). _Done 2026-11-01._ needs: T006
- [x] T008 [ING] `MemoryRetrieval`: plan, pool `bm25_relaxed`, relaxed form only when the strict query returns zero rows (D-23), weight in `SignalFusion`; the ticket path (`TicketRetrieval.semanticCandidates`) has the same fallback. _Done 2026-11-01. Existing suites pass; five of them needed the three new classes added to their class-loading list._ needs: T007
- [x] T009 [ING] Flags `--relax auto|on|off`, `--explain` (plan part), `AI_MEMORY_RELAX=0`; help text. _Done 2026-11-01. JSON stays an array of rows: rows carry `signals.bm25_relaxed`, and with `--explain` the plan goes to stderr._ needs: T008
- [x] T010 [ING] `eval` command (`10_eval.groovy`, `lib/EvalRunner.groovy`, `lib/SemanticStore.groovy`) with `--as-of-sequence`, `--systems S0,S1`, paired bootstrap comparison, run storage in `semantic.db`. _Done 2026-11-01. `--compare RUN` against a stored run is not built._ needs: T002
- [ ] T011 OWNER [ING] Run S0 on the gold set; confirm or change the targets of SC-002 to SC-007 (gate G0, questions Q3, Q4). _Waiting for T005: the tooling is ready (`eval --gold-candidates`, `--pool-export`, `--run`)._ needs: T005, T010
- [ ] T012 [ING] Gate G1: run S1 against S0 on the real gold set; full regression; rebuild the jar. _Partly done: the interim record is in `verification-phase-0.md` (fixture numbers, real-data before and after, all suites, jar rebuilt). The gate itself needs the labelled gold set (T005, T011)._ needs: T008, T009, T011

## Phase 1: cross-encoder re-ranking service

- [ ] T019 OWNER [ING] Model source path: check from the company laptop whether the model host and the package index are reachable; ask for the approved path (artifact repository, offline transfer or an opened host). Question Q9, decision D-22. Starts at once, in parallel with Phase 0.
- [ ] T020 OWNER [ING] Licence check by the policy of `security-privacy.md` 5.1 (MIT first): read the model cards of the candidate models, store each licence text, fill the table in section 5.3 (licence, size, languages, `trust_remote_code`, revision, date), and send the Apache-2.0 candidates to IP Legal in one request. Questions Q1 and decisions D-08, D-21. Starts at once, in parallel with Phase 0. needs: T019 for the source
- [ ] T021 [ING] Python environment: choose and install a supported Python in a dedicated venv; requirements file with hashes; a script that checks the interpreter and wheel availability. needs: T020 for the package list
- [ ] T022 [ING] Latency benchmark (evaluation plan section 9) on `cpu` and `mps`; replace the estimates in `evidence.md` section 7; choose the default model (decision D-04). OWNER approves. needs: T021, T020
- [ ] T023 [ING] Download the chosen models once, offline flags set after; write `models.manifest.json` with revisions and SHA-256. needs: T022
- [ ] T024 [ING] Service skeleton in Python: Unix socket, token, limits, logging without text, `/v1/health`, `/v1/models`, `/v1/rerank` with a stub model; contract tests (`tests/service/`). needs: T021
- [ ] T025 [ING] Real cross-encoder loader: `safetensors` only, `trust_remote_code` off, checksum check, batching, deadline between batches, device selection, rounding to four decimals. needs: T023, T024
- [ ] T026 [P] [ING] `lib/PassageBuilder.groovy` and the `semantic.db` schema (`passages`, `index_manifest`, `score_cache`); structural split, windows, prefix, best 3 per document; tests with the fixture corpus. needs: T003
- [ ] T027 [P] [ING] `lib/SemanticClient.groovy`: timeouts, circuit of 30 s, reason codes, token and socket discovery; tests against a stub server. needs: T024
- [ ] T028 [ING] Stages 2 to 4 in `MemoryRetrieval`: depth, passage selection, MaxP, `rerank` signal with weight 5, pins, invariants I1 to I12 as tests. needs: T026, T027, T008
- [ ] T029 [ING] Score cache with 14-day expiry; `passages/upsert` and `passages/delete` endpoints; deletion on `egest` and exclusion. needs: T026, T028
- [ ] T030 [ING] CLI flags `--rerank`, `--rerank-depth`, `--rerank-budget-ms`; full `--explain`; JSON fields; exit code 5; kill switch `AI_MEMORY_RERANK=0`. needs: T028
- [ ] T031 [FWK] Commands `memory serve|status|stop|doctor` in `Main.groovy` and `shared/command-contract.json`, tests, `launchd` plist for the service; `doctor` checks of the security plan. needs: T024
- [ ] T032 [ING] Fallback tests: service absent, slow, busy, bad token, bad contract; circuit; service-off equals S1; `--rerank on` exits 5. needs: T028, T030
- [ ] T033 [ING] Gate G2: run S2 (and S3, and S4 if the benchmark allowed it) against S1 on the test split; determinism run; latency tables; write `verification-phase-1.md`; full regression; rebuild the jar. needs: T029, T030, T031, T032
- [ ] T034 [VAULT] Update `skills/worklog-chat-memory/SKILL.md` (new flags, service, fallback), `CROSS_SKILL_INTEGRATION.md`, version bump per `VERSIONING.md`; `scripts/validate-skills.sh` passes. needs: T033

## Phase 2: late interaction (multi-vector)

- [ ] T040 OWNER [ING] Decide Q5 (artifacts), Q6 (encoding time) and Q7 (privacy approval) before this phase starts.
- [ ] T041 [ING] Bake-off on a dev subset: `sentence-transformers` `MultiVectorEncoder`, PyLate, `bge-m3` multi-vector through FlagEmbedding, and RAGatouille. Criteria: licence, Python support, recall on class P, storage per token, encoding speed, determinism. Write the result and decide (D-19, D-20). needs: T033, T020 for the licences
- [ ] T042 [ING] `vectors` table and `/v1/index/upsert`: resumable encoding job, half precision or 8-bit, progress in `index_status`. needs: T041
- [ ] T043 [ING] `/v1/search/late`: full-scan MaxSim over a memory-mapped matrix and candidate re-scoring; memory limit check. needs: T042
- [ ] T044 [ING] `late` pool in `MemoryRetrieval` (weight 3); `signals.late`; kill switch; tests. needs: T043
- [ ] T045 [ING] `memory reindex [--archives] [--verify] [--rebuild]`; incremental hook in the sweep that never waits for the service; orphan removal; invariant I11 tests. needs: T042
- [ ] T046 [ING] Gate G3: run S5 and S6 against S2; storage and memory limits; unattended encoding run; write `verification-phase-2.md`; full regression. needs: T044, T045
- [ ] T047 [ING] Security review of the built code: full audit mode of the `security-audit` skill on the service and client, with the checklist of `security-privacy.md` section 7. needs: T046
- [ ] T048 OWNER [ING] Decide Q8: switch `--rerank auto` on by default. needs: T046, T047

## Phase 3: options (not planned; each needs its own plan)

- [ ] T060 Tune fusion weights on the dev split with a simple search; report on the test split.
- [ ] T061 Polish lemmatizer if the Polish gap on the gold set exceeds 0.10.
- [ ] T062 Local query expansion model (new review of threat S9).
- [ ] T063 Fine-tune the cross-encoder when at least 500 labelled queries exist.

## Dependencies (summary)

```
T001 -> T012          T002 -> T004 -> T005 -> T011 -> T012
T006 -> T007 -> T008 -> T009 -> T012      T010 -> T011
T019 -> T020 -> T021 -> T022 -> T023 -> T025 -> T033
T024 -> T025, T027, T031        T026 + T027 + T008 -> T028 -> T029, T030 -> T032 -> T033 -> T034
T033 -> T041 -> T042 -> T043 -> T044 -> T046 -> T047 -> T048      T042 -> T045 -> T046
```

Critical path to the first measurable gain (Phase 0): T006, T007, T008, then T002, T004, T005, T010, T011, T012. Phase 0 needs no model and no Python, and it fixes the zero-result problem, so it can start at once.

## Summary (STE)

The plan has three phases. Phase 0 needs no model: it builds the test set and fixes the first search stage. Phase 1 adds the model service and the re-ranking. Phase 2 adds the token-vector index. Each phase ends with a measured gate. Some steps need the owner: labelling a few queries, reading the model licences, and choosing the default model.

**Remaining 20% (not STE100):** task, file, command and repository names; the words "gate", "stub", "venv", "sweep", "MaxSim", "benchmark" and "kappa"; the diagram; some sentences over 20 words.

---

Created using Anthropic Claude. Internal only until a person has reviewed and verified it.
