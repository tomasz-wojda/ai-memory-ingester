# Technical Specification: Per-Ticket Memory Split

Status: proposal awaiting review. Not approved for implementation.

Companion documents: [measured evidence](per-ticket-memory-split-evidence.md) and [implementation checklist](per-ticket-memory-split-checklist.md).

Origin: `repos/ai-worklog-scaleup` (spec `docs/specs/001-ticket-scaleup/`, decision D-14 in `docs/research/decision-log.md`).

---

## 1. Purpose

Let up to 10 agent sessions (Claude, Cursor and background workers) work on different tickets at the same time without lost memory writes, and give each session a small, ticket-focused resume at start.

## 2. Problem

- All sessions write to the same files: `prompt.log`, `worklog-chat/RESUME.md` and `journal.db`.
- `RESUME.md` is one global digest with a fixed budget (`Config.RESUME_BUDGET_BYTES = 22000`) and `Config.RESUME_RECENT_TURNS = 12` recent turns. With 10 active tickets, each ticket gets about 2 KB, and a ticket that waits one day drops out of the digest.
- `RESUME.md` is fully rewritten by `ResumeDigest.write` during `sweep-memory`. Two sweeps at the same time can overwrite each other.
- `prompt.log` is appended by `record_turn.py` with a plain `open("a")`, without a lock. Large entries from parallel writers can interleave.
- `journal.db` writes already use `BEGIN IMMEDIATE` with up to 16 retries and a growing sleep (`lib/JournalStore.groovy`). The journal itself is safe; the steps around it are not.
- Every session reads the full global digest at start, then searches for its ticket. This costs tokens and time.
- The workspace rules state: "Multiple agent instances write simultaneously | Not supported" (`repos/ai-vault/skills/CROSS_SKILL_INTEGRATION.md` §5.4).

## 3. Locked decisions (proposed)

1. The per-ticket capsule `worklog-chat/ticket-state/<KEY>.md` becomes the primary resume source for a session that works on `<KEY>`.
2. `journal.db` stays the single record of all turns. The existing `BEGIN IMMEDIATE` retry stays.
3. `record_turn.py` takes a named lock before `record-event` and releases it after the `prompt.log` append, so the journal row and its `prompt.log` entry are written as one unit.
4. The global `RESUME.md` becomes an index: one block per active ticket (key, phase, next action, capsule path, freshness), plus recent turns that have no ticket.
5. A per-ticket resume file `worklog-chat/ticket-resume/<KEY>.md` holds the recent turns of that ticket within its own budget.

## 4. Affected code

| File | Change |
|---|---|
| `lib/Config.groovy` | Add `TICKET_RESUME_DIR = 'ticket-resume'`, `TICKET_RESUME_BUDGET_BYTES = 12000`, `TICKET_RESUME_RECENT_TURNS = 12`, `MEMORY_LOCK_PATH = '.ai-worklog/locks/_journal.sqlite'` |
| `lib/ResumeDigest.groovy` | New `writeTicket(outputRoot, ticketKey, budget, store)`; `write` renders the index form of `RESUME.md` |
| `lib/TicketStateCapsule.groovy` | Add `phase`, `next_action` and the resume path to the capsule frontmatter |
| `lib/WorklogEmitter.groovy` | Emit only for changed tickets (already change-based); call `ResumeDigest.writeTicket` for each changed ticket |
| `06_ingest_worklogs.groovy` | Pass the changed ticket set to the digest step |
| `07_sweep_memory.groovy` | Take the memory lock around digest writes; refresh per-ticket resumes for changed tickets only |
| `08_record_event.groovy` | No change to the journal write; return the ticket keys of the event in its output line |
| `repos/ai-vault/skills/worklog-chat-memory/scripts/record_turn.py` | Take the memory lock; run `record-event`; append `prompt.log`; release |
| `run.groovy` | New command `resume --ticket KEY` that prints the per-ticket resume |

## 5. Per-ticket resume format

File `worklog-chat/ticket-resume/<KEY>.md`:

- Frontmatter: `ticket`, `generated_at`, `budget_bytes`, `content_bytes`, `turns`, `capsule` (path), `state_file` (path), `freshness`.
- Sections, in order: `Current state` (from the capsule), `Next action`, `Open decisions`, `Recent turns` (newest first, within the budget), `Sources`.

`RESUME.md` (index form):

- Header as today (`Generated`, `Turns`, `Content bytes`, `Budget bytes`).
- `Active tickets`: one block per ticket with lifecycle `active` or `waiting_decision` in its state file.
- `Recent turns without a ticket`: the rest of the budget.

## 6. Lock design

- Lock file: `<workspace>/.ai-worklog/locks/_journal.sqlite` (same name as in `ai-worklog-scaleup/docs/specs/001-ticket-scaleup/contracts/lock-and-queue.md`).
- Method: open SQLite, `PRAGMA busy_timeout = 2000`, `BEGIN EXCLUSIVE`; on `SQLITE_BUSY` retry once, then fail with a clear message and do not append `prompt.log`.
- The OS releases the lock if the holder process stops (pattern from `repos/rea/scripts/run-exclusive-command.mjs`).
- Holders: `record_turn.py` (record plus append) and `07_sweep_memory.groovy` (digest writes). Holding time target: under 500 ms.

## 7. Rule and skill changes (ai-vault, separate plan)

- `.rules` §1: session start reads `worklog-chat/ticket-resume/<KEY>.md` for the session's ticket and `RESUME.md` only as an index.
- `skills/worklog-chat-memory/SKILL.md`: document `resume --ticket KEY` and the lock.
- `skills/CROSS_SKILL_INTEGRATION.md` §5.4: change "Not supported" to the supported model with locks.

## 8. Tests

- `tests/test_worklog_suite.groovy`: per-ticket resume within budget; index form of `RESUME.md`.
- New `tests/test_memory_lock_suite.groovy`: two writers append 100 turns each in parallel; journal rows = `prompt.log` entries = 200; no interleaved entry.
- `tests/test_retrieval_suite.groovy`: `resume --ticket KEY` returns the ticket file.
- Regression: every existing `ai-memory-ingester` command (see checklist).

## 9. Rollback

- Keep the global digest code path behind `Config.RESUME_MODE = 'index' | 'global'`; set `'global'` to restore today's `RESUME.md`.
- Per-ticket resume files are derived data; delete `worklog-chat/ticket-resume/` to remove them.
- `record_turn.py` lock can be disabled with `AI_MEMORY_LOCK=0`.

## 10. Summary (STE)

Today, all sessions share one resume file and one log file without a lock. With ten sessions, writes can mix or get lost, and each ticket gets too little space in the resume. This proposal gives each ticket its own resume file. The global resume becomes a short index. One lock keeps the journal record and the log entry together. The journal stays the single record.

**Remaining 20% (not STE100):** file, class, constant and command names; the words "digest", "capsule", "journal", "frontmatter", "rollback" and "interleave"; the tables.

---

Created using Anthropic Claude. Internal only until a person has reviewed and verified it.
