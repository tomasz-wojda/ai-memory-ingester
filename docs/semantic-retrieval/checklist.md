# Checklist: Semantic Retrieval Upgrade

Status: proposal. Use the first part to review the specification. Use the other parts only after the owner approves it in PLAN and gives `MODE: EXECUTE`. Part of [the specification](specification.md).

---

## 1. Specification quality (review before approval)

### Content
- [x] Problem is stated with measured facts and sources ([evidence](evidence.md))
- [x] Goals and non-goals are separate and bounded
- [x] User stories have priorities and acceptance scenarios
- [x] Requirements are numbered and testable (FR-001 to FR-020)
- [x] Success criteria are measurable (SC-001 to SC-010)
- [x] Risks have a mitigation each
- [x] Unverified facts are marked and tracked as tasks (model licences, torch wheels, PyLate version)
- [x] Existing locked decisions and invariants are carried over (N2, N3, I1 to I12)

### Open before approval
- [ ] Owner has read the 22 decisions in the [decision log](decision-log.md) and approved or changed each
- [ ] Owner has answered Q1 to Q9
- [ ] Targets of SC-002 to SC-007 are confirmed after the baseline run (gate G0)
- [ ] Model licences are read and recorded in `security-privacy.md` section 5
- [ ] No [NEEDS CLARIFICATION] marker remains (none were used; open items are Q1 to Q9)

## 2. Preconditions for EXECUTE

1. The specification is approved in PLAN and `MODE: EXECUTE` is given for `repos/ai-memory-ingester` (and `repos/ai-worklog-framework` and `repos/ai-vault` for their tasks).
2. `git status` of each repository is recorded before work starts. Foreign changes are not touched.
3. The baseline commit is tagged `semantic-baseline` (T001).
4. The precompiled jar builds before the change, and the old jar is kept as a copy for rollback.
5. Real data is not copied into scratch folders. Tests use the fixture corpus; the real memory is used read-only by the `eval` command.

## 3. Implementation (tick as the tasks of [tasks.md](tasks.md) finish)

Phase 0
- [x] Metrics library and fixture corpus (T002, T003)
- [x] Stop-word lists and query planner (T006, T007)
- [x] Relaxed pool and fusion weight (T008)
- [x] Flags `--relax`, `--explain`; kill switch (T009)
- [x] `eval` command with frozen corpus (T010)
- [ ] Gold set built, labelled, reviewed; kappa at least 0.6 (T004 tooling done; T005 needs the owner)
- [ ] Baseline run and confirmed targets (T011)
- [ ] Gate G1 and `verification-phase-0.md` (T012)

Phase 1
- [ ] Model source path approved (T019)
- [ ] Licences recorded under the licence policy (T020)
- [x] Python environment with hashes (T021): numpy only; machine-learning packages wait for the model choice
- [ ] Latency benchmark and default model chosen (T022)
- [ ] Models downloaded once with manifest (T023)
- [x] Service skeleton with contract tests (T024)
- [~] Real cross-encoder loader (T025): built; real inference not verified
- [x] Passage builder and `semantic.db` schema (T026)
- [x] Client with circuit and reason codes (T027)
- [x] Stages 2 to 4 and invariants (T028)
- [x] Cache and deletion path (T029)
- [x] CLI flags, `--explain`, exit code 5 (T030)
- [x] Framework commands `memory serve|status|stop|doctor|reindex|eval` (T031)
- [x] Fallback tests (T032)
- [ ] Gate G2 and `verification-phase-1.md` (T033)
- [x] Skill documentation updated and validated (T034)

Phase 2
- [ ] Decisions Q5, Q6, Q7 recorded (T040)
- [ ] Bake-off result (T041)
- [x] Vector store and encoding job (T042), stub encoder
- [x] Late search (T043), exact scan in plain Python
- [x] Late pool (T044)
- [x] `reindex` and orphan removal (T045)
- [ ] Gate G3 and `verification-phase-2.md` (T046)
- [x] Security review of built code (T047): guidance mode plus the skill's quick-profile audit (partial pass, run incomplete for lack of a sandbox), see security-review.md
- [ ] Default switch decided (T048)

## 4. Regression (every phase; one command and one recorded result each)

1. `groovy tests/test_journal_suite.groovy`: 25 pass.
2. `groovy tests/test_retrieval_suite.groovy`: 31 pass (plus new checks, none removed).
3. `groovy tests/test_query_suite.groovy`: 68 pass.
4. `groovy tests/test_chatlog_suite.groovy`: 100 pass.
5. `groovy tests/test_reconciliation_suite.groovy`: 33 pass.
6. `groovy tests/test_shadow_validation_suite.groovy`: 44 pass (about 5 minutes).
7. `groovy tests/test_worklog_suite.groovy`: 42 pass.
8. `groovy tests/test_memory_lock_suite.groovy`: 34 pass.
9. New suites of this feature pass: eval, query plan, passages, fusion, client, service contract, security.
10. `ai-memory-ingester sweep-memory`: `failures=0`.
11. `ai-memory-ingester query`, `ticket`, `recent`, `resume`, `context`, `journal`, `worklogs --changed`, `reconcile-transcripts --dry-run`, `project-journal --dry-run`: same exit codes as before; output format unchanged except the new fields.
12. `record_turn.py` records a turn with the service stopped and with it running.
13. With `AI_MEMORY_RERANK=0` and `AI_MEMORY_RELAX=0`, the output equals the baseline output for the fixture queries.
14. Any test that runs longer than 60 seconds is recorded as such with the reason (R-12 of the vault rules).

## 5. Completion

1. Commit handoff for each changed repository with attributed paths only (`commit_handoff.py`).
2. The jar `dist/ai-memory-ingester.jar` is rebuilt and verified on generated data before any real turn uses it.
3. `verification-phase-N.md` exists for each finished phase, without query or document text.
4. The decision log shows each decision as Approved or Rejected.
5. `data/eval/` is ignored by git (checked with `git check-ignore`).

## Summary (STE)

Use the first part to review the plan. It shows what is ready and what the owner must still decide. Use the later parts when the work starts. They list every step, every regression test and every result that must be recorded.

**Remaining 20% (not STE100):** file, test and command names; the words "kappa", "gate", "jar", "handoff", "regression" and "fixture"; the lists; some sentences over 20 words.

---

Created using Anthropic Claude. Internal only until a person has reviewed and verified it.
