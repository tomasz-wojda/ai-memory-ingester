# Semantic Retrieval Upgrade: Documents

Status: proposal for review (2026-10-31). Not approved. Nothing is built.

Goal: much better retrieval from memory. Questions in plain English or Polish return the right turn, worklog chunk or ticket state, with the best answer in the first results. The design keeps SQLite FTS5 as the first stage, adds a re-ranking stage (cross-encoder first, ColBERT-style late interaction second) and runs the models in a dedicated local memory service inside the Groovy-based AI Worklog Framework.

## Read in this order

| # | Document | Purpose |
|---|---|---|
| 1 | [evidence.md](evidence.md) | Measured baseline, corpus size, machine, library facts, cost arithmetic; what is unverified |
| 2 | [specification.md](specification.md) | The full technical specification: stories, architecture, invariants, requirements, phases, risks |
| 3 | [service-contract.md](service-contract.md) | HTTP contract, configuration, CLI flags, output fields, data tables, framework commands |
| 4 | [evaluation.md](evaluation.md) | Gold set, metrics, systems compared, gates, latency benchmark, test groups |
| 5 | [security-privacy.md](security-privacy.md) | Trust boundaries, threats, privacy, model licences (all unverified), supply chain |
| 6 | [decision-log.md](decision-log.md) | 22 decisions, a licence decision tree, alternatives not chosen, 9 open questions for the owner |
| 7 | [tasks.md](tasks.md) | 41 tasks: 37 in 3 phases and 4 later options, with dependencies and owner steps |
| 8 | [checklist.md](checklist.md) | Spec review list, preconditions, implementation list, regression list |

## The main findings

1. **The first stage fails on questions.** Bare terms are ANDed. All five natural-language questions and phrases in the baseline returned zero results (three English, two Polish); the same ideas as `OR` or prefix queries returned ten. A re-ranker cannot fix an empty candidate set, so Phase 0 fixes the first stage and builds a measurement set before any model is added.
2. **A large cross-encoder is too slow on this laptop** at depth 50 (estimated 15 to 29 s). The default is a small multilingual model, a small depth and a time-boxed scoring loop. The benchmark in task T022 decides.
3. **Late interaction moves cost offline** but needs storage: about 3.2 GB for 12.4 million token vectors at 128 dimensions in half precision, about 25 GB at 1024 dimensions.
4. **The text is mostly English, the ticket state mostly Polish** (70.6% of state capsules contain Polish letters; queries are often Polish without diacritics). The model must be multilingual, or the authority layer is retrieved worst.
5. **Every model licence is unverified, and the model host is unreachable from this machine** (all 9 model cards and all 15 API calls timed out). The licence policy is MIT first, then Apache-2.0, BSD and ISC after IP Legal confirms; non-commercial, copyleft and gated licences are excluded. No model is downloaded before its licence is read and recorded, and the files come only from an approved path (D-21, D-22).
6. **The existing guarantees stay**: authority, freshness, pins, complete rows, determinism, `journal.db` never searched, no schema change. The model score is one signal in the fusion, not the final order.

## What the owner decides next

Approve or change the 22 decisions, answer questions Q1 to Q9 in the decision log, get the model licences read and the source path approved, and give `MODE: PLAN` to review the plan and `MODE: EXECUTE` to start. Phase 0 needs no model and no Python and can start first.

## Summary (STE)

This folder holds the full plan to make memory search much better. First we fix the search stage that returns nothing for questions and build a test set. Then a small local model re-reads the best passages and puts the most useful first. A separate service keeps the model ready, and the old search still works when the service is off. The owner must still approve the plan, read the model licences and label some test questions.

**Remaining 20% (not STE100):** file and model names; the words "FTS5", "cross-encoder", "ColBERT", "late interaction", "re-ranker", "passage" and "Phase"; the tables; some sentences over 20 words.

---

Created using Anthropic Claude. Internal only until a person has reviewed and verified it.
