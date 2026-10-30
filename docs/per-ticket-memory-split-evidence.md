# Measured Evidence: Per-Ticket Memory Split

Status: proposal awaiting review.

Governing specification: [per-ticket-memory-split-specification.md](per-ticket-memory-split-specification.md).

All values measured on 2026-10-30 in workspace `/Users/twojda/workspace` (WS).

| # | Fact | Value | Source / command |
|---|---|---|---|
| E1 | `prompt.log` entries, 2026-09-11 to 2026-10-30 | 3,870 | `grep -c '^--- PROMPT LOG ENTRY' WS/prompt.log` |
| E2 | Entries where the user text was only a mode switch | 269 (7%) | `grep -cE '^USER: MODE: ' WS/prompt.log` |
| E3 | `prompt.log` size | 7.4 MB | `ls -la WS/prompt.log` |
| E4 | Global resume budget | 22,000 bytes | `lib/Config.groovy`: `RESUME_BUDGET_BYTES = 22000` |
| E5 | Recent turns in the global resume | 12 (`RESUME_RECENT_TURNS`); header showed `Turns: 15` and `Content bytes: 21493` at 18:34:48Z | `lib/Config.groovy`; `WS/worklog-chat/RESUME.md` header |
| E6 | Per-ticket capsules | 54 files | `WS/worklog-chat/ticket-state/` |
| E7 | `journal.db` size | 192 MB | `du -h data/journal.db` |
| E8 | Journal write method | `BEGIN IMMEDIATE`; up to 16 attempts; sleep `10 ms × attempt` on busy | `lib/JournalStore.groovy` |
| E9 | `prompt.log` append method | plain `open("a")`, no lock, after `record-event` succeeds | `repos/ai-vault/skills/worklog-chat-memory/scripts/record_turn.py` |
| E10 | Global resume writer | `ResumeDigest.write` from `07_sweep_memory.groovy`, full rewrite | `07_sweep_memory.groovy` line 59 |
| E11 | Turn payload files left in shared scratch | 3,044 | `WS/tmp/journal-turns/` |
| E12 | Current rule on parallel agents | "Multiple agent instances write simultaneously \| Not supported. One agent session per workspace at a time" | `repos/ai-vault/skills/CROSS_SKILL_INTEGRATION.md` §5.4 |
| E13 | Target parallelism | 10 active tickets | `repos/ai-worklog-scaleup/docs/specs/001-ticket-scaleup/spec.md` SC-001 |

## Derived values

- Global resume space per ticket at 10 active tickets: 22,000 ÷ 10 ≈ 2,200 bytes (from E4, E13).
- Share of turns that carried no work content other than a mode switch: 7% (E2 ÷ E1).

## Not measured

- Actual interleaving in `prompt.log` under parallel writers: not reproduced (`NOT_ENOUGH_INFO`). The risk comes from E9 and E13, not from an observed failure.
- Lost `RESUME.md` updates from parallel sweeps: not observed; the risk comes from E10.

## Summary (STE)

The global resume has 22,000 bytes and 12 recent turns for all work. The journal already retries when it is busy. The log append and the resume rewrite have no lock. With 10 tickets, each ticket gets about 2,200 bytes of resume. Mixed log entries were not seen, but the design allows them.

**Remaining 20% (not STE100):** file, constant and command names; the words "resume", "journal", "capsule", "interleaving" and "sweep"; the table.

---

Created using Anthropic Claude. Internal only until a person has reviewed and verified it.
