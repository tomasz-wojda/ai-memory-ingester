# Plan: build test queries from real session starts

Status: proposal, written on 2026-11-10 after the owner's idea: the reason for memory (and for `prompt.log` today) is the start of a new conversation, for example in Cursor, when the agent needs context. So mine the Cursor sessions whose first user message was followed by a `prompt.log` read (tail or grep), and build test queries from them. The feasibility check and the miner are done; nothing else is built. Any deviation from this plan returns to PLAN mode.

Parent documents: [five_way_real_test_plan.md](five_way_real_test_plan.md) (the test this feeds), [comparison.md](comparison.md) (scoring), [semantic-retrieval/tasks.md](semantic-retrieval/tasks.md) (T005, the gold set).

## 1. Does the idea make sense? Yes, with five reasons and four cautions

Reasons:
1. It uses the real use case. Memory exists so that a new conversation can recover what matters. A first user message plus the retrieval that followed it is that moment, recorded.
2. The queries are not drafted by a model, and they are not all summary-type. The benchmark so far used queries that ask for a topic summary and ledgers drafted by a model (sections 13 to 17 of `rerank_enhacement.md`). Real first messages are narrow, recent, vague, Polish and English in the real proportions.
3. There is a real baseline. For each session we know what the agent actually did to find context (a `tail -n`, a `grep` pattern, an engine query). That is the "current practice" arm of the test, replayed instead of imagined (method 1 in the five-way plan).
4. We can measure the need after the fact. What the agent used in its first plan, answer and tool calls shows which earlier facts the request depended on. That gives a ledger without asking the owner to invent questions.
5. It costs little. 148 Cursor sessions exist for this workspace (63.7 MB). The miner needs no model.

Cautions (details in sections 6 and 9):
1. Most of the reads are the mandatory session-start read of the rule, not a search that the question caused. Only a minority show a query-driven search.
2. The transcripts store the tool calls but not their results. We cannot see what the agent read; we replay the call.
3. The memory must be tested as it was at the session start (an "as-of" snapshot), otherwise later facts leak into the answer.
4. Some first messages need no memory at all. They are useful only as "do no harm" controls.

## 2. What the data says (checked 2026-11-10, `bench/gold_mine.py`)

| Measure | Value |
|---|---|
| Cursor session files for this workspace | 148 (the first user message found in all of them; 147 dated through `convo.db`) |
| Sessions with a `prompt.log` read in the first 15 tool calls after the first user message | 71 |
| Forms of that read (a session can have several) | `Read` with an offset 48 (the tail through the Read tool), `Read` from the start or whole 21, `Grep` tool 16, shell `tail` 7, shell `grep` 4 |
| Candidates: a `prompt.log` read, a first message of 60 or more characters, not a continuation ("yes", "continue") | 57 |
| Candidates by month | August 23, September 27, October 6, November 1 |
| Candidates with a ticket key in the first message | 12 |
| Candidates with a query-driven search (a grep pattern or an engine query) | 19 (16 with a grep pattern, 7 with an engine query, some with both) |
| Candidates with only the session-start read | 38 |
| Candidates that also read `RESUME.md` / ran `sweep-memory` | 24 / 25 |
| Claude Code sessions for this workspace | too few to use (4 files) |

The candidate list, with the verbatim first messages, is in `repos/ai-memory-ingester/data/eval/real/mined/sessions.tsv` (git-ignored; it holds the owner's own words and may hold host names or paths).

## 3. Candidate classes

- **Class A, query-driven (19 sessions):** the agent searched after the first message: a `Grep` or shell `grep` pattern on `prompt.log`, or an `ai-memory-ingester query` phrase. The pattern or phrase is the agent's own retrieval query. These are the strongest test queries and the strongest baselines.
- **Class B, session start only (38 sessions):** the agent read the tail of `prompt.log` (and often `RESUME.md`), as the rule says, whatever the question was. The retrieval need is implicit. The test query is the first message; the ledger comes from what the session used.
- **Controls (3 to 5 sessions from the non-candidates and from class B):** first messages that need no memory (for example "import the following sessions"). A method must not make these worse (no extra noise, no extra time).

## 4. From session to test query

For each selected session (`id` = the transcript id):
1. **Phrase:** the first user message, verbatim (the text inside `<user_query>`). Long messages (over 300 characters; 15 of the 71) keep their full text for the record and also get a one-sentence retrieval phrase written by the owner, because a 3,000-character message is not a search query. The test runs on the owner's phrase when it exists, on the verbatim message otherwise.
2. **Agent practice (method 1, replayed):** the exact `tail -n N` or `grep` pattern that the agent used, run on the as-of `prompt.log`. This is "method 1a, as done". The identifier rule of the five-way plan stays as "method 1b". Where the agent only used the `Read` tool at an offset, 1a is the same range (offset to the end) at the as-of date.
3. **Type and language:** set by the owner or inferred (ticket key present, recent, old, narrow, topic, cross) and recorded in `queries.tsv` with `source=session`, `session` id and `started_at`.

## 5. As-of snapshot (time travel)

A test of a session at its start must see only what existed then.
- Chat turns and artifacts: keep those with a timestamp at or before `started_at` (the time-based cut of `bench/task_corpus.py`, section 12.6 of `rerank_enhacement.md`); undated items stay only below a fallback number, as there.
- `prompt.log`: keep entries whose `TIMESTAMP` is at or before `started_at` (the file is not in time order; the cut is by entry, not by line), and remove the entries of the session itself and of every later session.
- Worklogs and state capsules cannot be frozen (they carry no per-document date). Default for as-of tests: leave the worklog archives out and say so in the report. This lowers realism (the agent also saw the worklog and `RESUME.md`), but it removes a certain leak. Option for the owner (question 2 of section 11): include them with a leak flag.
- Many sessions share one snapshot when their starts are close; build one snapshot per distinct day to save disk (about 150 MB each, git-ignored).
- The engine, the jar and the services are today's version. We test today's retrieval on the historical data, not the engine of that day.

## 6. Reference facts (the retrospective ledger)

1. **Draft from the session, blind to the five methods:** a model reads the first assistant turns and tool calls of the session (what it planned, quoted, edited, cited; which tickets, paths, commands, decisions it used) and lists the earlier facts that the request depended on, with the earlier turn or file that the agent drew each from. The draft uses the session transcript only.
2. **Owner adjudication:** per fact: needed, not needed, or wrong; weights 3, 2, 1; mark the latest-state facts. This is a short review (as R02). A session where the agent used no earlier fact becomes a control.
3. **Source check:** each kept fact must be present in the as-of memory (some turn before `started_at`); otherwise it is marked "not in memory" and left out of the coverage denominator (it measures the data, not the method).
4. The scoring then follows section 6 of the five-way plan (the seven dimensions, two tiers, blind labels).

## 7. Selection

Target 30 queries plus 3 to 5 controls (T005 asks for 30). Stratify: class A about 10, class B about 20; spread over the months (August to November); at least 5 with a ticket key; at least 40% Polish if the sample allows. Exclude sessions whose first message is about the memory or retrieval work itself (importing sessions, benchmarks, STE100 rules): they would make the test circular. Pilot first with 6.

## 8. How it plugs into the five-way test

The five-way plan stays as it is, with these changes for session-based queries:
- A new Phase 0 (mining and ledger drafting, sections 2 to 6 of this plan).
- Phase 1 builds one as-of snapshot per distinct day instead of one snapshot; `queries.tsv` gets the columns `source`, `session`, `started_at`.
- Method 1 has the two arms of section 4 (1a as done, 1b identifier rule).
- Phase 2 is unchanged. In addition the report shows, for class A, whether the agent's own query would have scored better than the first message as a phrase.
Owner-typed phrases (like q001) still work and go into the same `queries.tsv` with `source=owner`.

## 9. Privacy

`sessions.tsv`, the as-of snapshots and the drafted ledgers hold internal conversation text; first messages may contain host names, IP addresses, paths and ticket content. They stay in the git-ignored folder and are not shared. A first message that holds a credential is excluded from the test set and not copied anywhere. Only numbers and conclusions go into `docs/`.

## 10. Limits and biases

- Selection bias: sessions where the protocol was followed. The 77 sessions without a `prompt.log` read are not candidates; they show cases where memory was not used. We could add their share to the cost analysis.
- Class B dominates (38 of 57). For them the retrieval need is implicit, and the ledger is a judgement about what the session used.
- The first 15 tool calls are an arbitrary window; a later search is not seen. The window is a parameter of the miner.
- The tool results are not in the transcripts; the replay can differ from what the agent saw (the log has grown since).
- The protocol changed between August and November (the memory tools came late). Early sessions show only the `prompt.log` practice; the engine arm there is "what today's engine would have given".
- Many first messages point to other tabs or sessions ("based on the conversation in a tab named ..."): a strong memory need, and a hard one.
- 148 sessions come from this workspace only; other workspace folders in `~/.cursor/projects` are not included.
- The count of 30 is small. The pilot shows the effort.

## 11. Open questions for the owner

1. Do you accept class B sessions (the mandatory read) as candidates, with a ledger from what the session used?
2. As-of tests without the worklog archives (default), or with them and a leak flag?
3. Do you want to write a one-sentence retrieval phrase for the long first messages, or may I draft it and you correct it?
4. 30 queries and 3 to 5 controls: right numbers? Pilot of 6 first?
5. Should I also mine the other workspace folders in `~/.cursor/projects` (framework, env-setup, scripts)?
6. Is a window of 15 tool calls right, or should the window be "until the first assistant text answer"?

## IMPLEMENTATION CHECKLIST

1. [x] Feasibility check and the miner `bench/gold_mine.py` (output `data/eval/real/mined/sessions.tsv`).
2. [ ] Owner: answer section 11; mark sessions to exclude (circular or sensitive).
3. [ ] Select the sessions (section 7) and write the selection into `queries.tsv` (`source`, `session`, `started_at`).
4. [ ] Build the as-of snapshot builder (section 5): time cut of turns and artifacts, `prompt.log` cut by entry, the session and later ones removed; one snapshot per distinct day.
5. [ ] Draft the ledgers from the session transcripts (section 6), blind to the five methods; owner adjudication.
6. [ ] Extend `bench/gold_collect.py`: per-session snapshot, method 1a replay and 1b, new `queries.tsv` columns.
7. [ ] Pilot with 6 sessions (Phase 1 and Phase 2); report the effort and fix the rules.
8. [ ] Run the full selection; score; report in `docs/` with the decision log entry.
9. [ ] `py_compile`, `git status` (nothing under `data/eval/real/` tracked), commit handoff, record the turn.

Any deviation from this plan returns to PLAN mode.

---

Created using Anthropic Claude. Keep this internal until a person has reviewed and verified it. This plan is a proposal and should stay on internal versions until a human has reviewed it. It refers to internal conversation data: share it only with people allowed to see it.
