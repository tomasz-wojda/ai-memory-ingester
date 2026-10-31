# Verification: Phase 0 (interim)

Status: built and checked on 2026-11-01. This is an **interim** record. Gate G1 needs the labelled gold set (task T005, owner) and the baseline run (task T011, owner). Part of [the specification](specification.md).

---

## 1. What was built

| Task | Result |
|---|---|
| T002 | `lib/EvalMetrics.groovy`: Recall@K, nDCG@10, MRR@10, Success@K, zero-result rate, paired bootstrap |
| T003 | `tests/fixtures/semantic/` (43 documents, 24 queries) and `tests/SemanticFixtureBuilder.groovy` |
| T004 | `lib/GoldSet.groovy`; commands `eval --gold-candidates`, `--validate`, `--pool-export` |
| T006 | `lib/StopWords.groovy`, `lib/TextFold.groovy` |
| T007 | `lib/QueryPlanner.groovy` |
| T008 | `MemoryRetrieval`: pool `bm25_relaxed`, relaxed query only when the strict query returns zero rows; the same fallback in `TicketRetrieval.semanticCandidates`; weight 2 in `SignalFusion` |
| T009 | `--relax auto|on|off`, `--explain`, `AI_MEMORY_RELAX=0` |
| T010 | `eval` command: `10_eval.groovy`, `lib/EvalRunner.groovy`, `lib/SemanticStore.groovy`, runs stored in `data/semantic.db` |
| extra | `RESUME.md` index shows the phase (from `phase`, then verification state, then `governance_mode`) instead of "unknown" |
| extra | `.gitignore`: `data/eval/` and `data/semantic.db*` |

## 2. Real memory, before and after (read-only, jar rebuilt, one run each)

Command: `ai-memory-ingester query "<text>" --limit 10`, with `--relax off` for "before".

| Query | Before (`--relax off`) | After (default) |
|---|---|---|
| what did we decide about the bunyan pin | 0 | 10 |
| why was the bunyan version pinned | 0 | 10 |
| jak rozwiazalismy problem z traefik | 0 | 10 |
| decyzja dotyczaca usuwania uzytkownikow | 0 | 10 |
| script removing users eap pod | 0 | 10 |
| bunyan pin (keyword) | 10 | 10 (same rows) |
| bunyan AND pin* (explicit) | 10 | 10 (not rewritten) |

The top results of the first query were the KD-6945 bunyan worklog entries and related turns. Relevance beyond "contains the content words" is **not measured**; that needs the gold set.

## 3. Fixture set, S0 against S1 (a logic check, not a quality claim for the real memory)

Command: `eval --set tests/fixtures/semantic/queries.jsonl --systems S0,S1` (24 queries, 43 documents).

| Class | Zero-result rate S0 | Zero-result rate S1 | nDCG@10 S0 | nDCG@10 S1 | Recall@50 S0 | Recall@50 S1 |
|---|---|---|---|---|---|---|
| Q (questions) | 0.88 | 0.00 | 0.098 | 0.715 | 0.06 | 0.94 |
| P (paraphrases) | 1.00 | 0.00 | 0.000 | 0.473 | 0.00 | 0.50 |
| T (ticket) | 0.00 | 0.00 | 0.549 | 0.701 | 0.88 | 1.00 |
| K (keywords) | 0.00 | 0.00 | 0.637 | 0.637 | 0.63 | 0.63 |
| X (explicit) | 0.00 | 0.00 | 0.814 | 0.814 | 0.88 | 0.88 |
| All | 0.42 | 0.00 | 0.393 | 0.683 | 0.44 | 0.82 |

Reading: classes K and X do not change (no regression). Question and paraphrase queries gain because they return candidates at all. Paraphrases with no shared words stay unsolved by lexical search (query p2); that is the job of Phase 2.

## 4. Tests

| Suite | Result |
|---|---|
| journal | 25 pass |
| retrieval | 31 pass |
| query | 68 pass |
| chatlog | 100 pass |
| reconciliation | 33 pass |
| shadow validation | 44 pass (4 min 36 s) |
| worklog | 42 pass |
| memory lock | 36 pass (2 new: phase from the mode and from the verification state) |
| eval (new) | 27 pass |
| query plan (new) | 80 pass: text folding, stop words, planner, retrieval on the fixture, evaluation, gold set, store and 7 command-line checks (1 minute) |

Command-line checks with the rebuilt jar on generated data: `sweep-memory` ends with `failures=0` and the index shows `Phase: execute`; 15 other commands exit 0 (`query`, `ticket`, `recent`, `context`, `journal`, `worklogs --changed --dry-run`, `reconcile-transcripts --dry-run`, `project-journal --dry-run`, `dbs`, `datasets`, `archs`, `eval --help`). `AI_MEMORY_RELAX=0` and `--relax off` give the old behavior (tested as a subprocess). A bad `--relax` value exits with 2.

## 5. Differences from the specification

1. **D-23.** The relaxed query runs only on zero rows (`relax_min_results` = 1), not below 10.
2. **D-24.** Polish ł variants only for queries guessed as Polish; `pod` and `go` are not stop words.
3. **Prefix rule example.** The rule `max(4, length - 3)` gives `trae*` for "traefik" (the contract example now matches).
4. **Five existing suites** (chatlog, query, retrieval, shadow validation, worklog) got the three new classes added to their class-loading list, because `MemoryRetrieval` now depends on them. No check was changed.
5. **Not built:** `eval --compare RUN` against a stored run, the capsule-source link in the gold-candidate tool, `--explain` in `ticket` and `context` output (the ticket path has the relaxed fallback but no plan print).

## 6. Fixes found on the way

1. **Ticket-only queries through `searchTopic`.** The folded facet (`fix103`) was folded again to `noticket`, so the API returned unrelated turns. The CLI avoided it by routing bare keys elsewhere. `TicketRetrieval.normalizeTicket` now leaves a non-key value as it is (the guard `applyFreshnessLabels` already used). Regression test added.
2. **Tokenizer gap.** The FTS5 tokenizer does not fold ł (evidence section 2b); handled in the query planner.

## 7. Not verified

- **Real retrieval quality** (nDCG, MRR, recall on real questions). Needs the gold set (T005) and the baseline run (T011).
- **The effect on a real gold set of the zero-row trigger.** The trigger may be too narrow; the gold set decides if it should widen.
- **Latency of the relaxed query on the real corpus.** The CLI runs were about 1 s including JVM start, with no timing table.
- **`eval --run` on the real memory.** Only `--gold-candidates` and `--validate` ran on real data (238 candidates from 16 state files, unlabelled).

## Summary (STE)

Phase 0 is built. Questions that returned nothing now return results, and the keyword and explicit searches that worked before return the same results. All old test suites pass, and the new ones pass. The jar is rebuilt and works on the real memory and on generated data. We cannot yet say how good the new results are, because the owner has not labelled the test questions.

**Remaining 20% (not STE100):** file, command, flag and task names; the words "jar", "tokenizer", "fixture", "nDCG", "MRR", "recall" and "gate"; the tables; some sentences over 20 words.

---

Created using Anthropic Claude. Internal only until a person has reviewed and verified it.
