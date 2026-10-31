# Implementation Checklist: Per-Ticket Memory Split

Status: executed on 2026-10-31. Differences are in section 10 of the specification.

Governing specification: [per-ticket-memory-split-specification.md](per-ticket-memory-split-specification.md).

Supporting measurements: [per-ticket-memory-split-evidence.md](per-ticket-memory-split-evidence.md).

---

## Preconditions

1. The specification is approved, and `MODE: EXECUTE` is given for this repository.
2. `git status` in `repos/ai-memory-ingester` and `repos/ai-vault` is recorded; foreign changes are not touched.
3. A copy of `worklog-chat/RESUME.md` and the tail of `prompt.log` (last 20 entries) is kept in the session scratch folder for comparison.
4. The precompiled jar builds before the change (`dist/ai-memory-ingester.jar`).

## Implementation

1. Add the new constants to `lib/Config.groovy` (spec §4), including `RESUME_MODE = 'index'`.
2. Add `ResumeDigest.writeTicket` and the index form of `ResumeDigest.write` (spec §5); keep the `'global'` path.
3. Extend `lib/TicketStateCapsule.groovy` frontmatter with `phase`, `next_action`, resume path.
4. Pass changed ticket keys from `06_ingest_worklogs.groovy` and `lib/WorklogEmitter.groovy` to the digest step.
5. Add the memory lock around digest writes in `07_sweep_memory.groovy` (spec §6).
6. Add the ticket keys to the output line of `08_record_event.groovy`; no journal change.
7. Add `resume --ticket KEY` to `run.groovy`.
8. Rebuild the precompiled jar.
9. In `repos/ai-vault`, add the lock to `skills/worklog-chat-memory/scripts/record_turn.py` with `AI_MEMORY_LOCK=0` as the off switch.
10. Add `tests/test_memory_lock_suite.groovy` and extend the worklog and retrieval suites (spec §8).

## Regression (every existing command)

1. `ai-memory-ingester record-event` with a test payload on a copy of `journal.db`: row inserted, idempotency kept.
2. `ai-memory-ingester sweep-memory --workspace-root <copy>`: `SWEEP_MEMORY` line has `failures=0`.
3. `query`, `recent`, `resume`, `context --budget 8000`, `ticket KD-8130`, `chatlog`, `worklogs --changed`, `journal`: same exit codes as before; output format unchanged except the index form of `RESUME.md`.
4. `reconcile-transcripts`, `project-journal`, `reconcile-project`: exit 0 on the copy.
5. Dataset and archive commands: run each `--help` and one read-only call.
6. `RESUME_MODE = 'global'`: `RESUME.md` matches the format of the saved copy.
7. `record_turn.py` with the lock: journal row and `prompt.log` entry both written; with a forced busy lock: clear error, no `prompt.log` append.
8. Any test that runs for more than 60 seconds is recorded as skipped with the reason.

## Completion

1. Commit handoff for `repos/ai-memory-ingester` and `repos/ai-vault`, with attributed paths only.
2. Update `.rules` and `CROSS_SKILL_INTEGRATION.md` §5.4 through a separate ai-vault plan.

## Summary (STE)

Do the work only after approval. First, save copies of the current files. Then change the configuration, the digest, the capsule, the sweep, the record step and the runner. Then test every existing command on a copy of the data. The last step is the commit handoff.

**Remaining 20% (not STE100):** file, command and constant names; the words "digest", "capsule", "sweep", "payload" and "idempotency".

---

Created using Anthropic Claude. Internal only until a person has reviewed and verified it.
