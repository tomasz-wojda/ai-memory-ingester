# Three-Branch Retrieval Comparison

## Purpose

This document defines a repeatable way to compare three memory sources:

1. a fixed, structured worklog;
2. FTS5 retrieval from materialized conversation turns;
3. direct context recovered from the primary `prompt.log`.

The worked case is `SCTASK1544083`. The objective is not to decide that one
source is universally best. It is to identify where information is lost or
distorted:

- in the source itself;
- while parsing or ingesting;
- while indexing;
- while ranking;
- while assembling a context budget;
- while synthesizing an answer.

The benchmark answers two different questions:

- **Budget-normalized retrieval:** What can an agent learn from each branch
  within a 22,000-byte context budget?
- **Source-ceiling audit:** What is the maximum information available if the
  complete source corpus is inspected?

These must remain separate. A complete 42 KB worklog cannot be compared fairly
with 20 short snippets unless input size and selection policy are recorded.

## Decision questions

Each branch must answer the same questions without consulting either of the
other branches:

1. What was requested and which systems and applications were in scope?
2. Which architecture and security decisions were locked?
3. Which implementation and rollout milestones occurred, in chronological
   order?
4. What is the latest completed state?
5. Which blockers and remaining actions are genuinely current?
6. Which repositories, commits, jobs, manifests, credentials, and live
   resources support the answer?
7. Which claims conflict, and which source resolves each conflict?

## Source roles and authority boundaries

| Source | Intended role | Strength | Boundary |
|---|---|---|---|
| Fixed worklog | Curated ticket history and state | Broad coverage and human-readable chronology | Can retain stale status and contradictory historical sections |
| FTS5 turns | Searchable atomic conversation evidence | Exact turn path, hash, source lines, and fast filtering | Ranking and context selection can omit decisive turns |
| Direct `prompt.log` | Append-only journal of record | Closest representation of the original conversation | Large, weakly structured, and expensive to parse repeatedly |
| Repository history | Current code-state adjudication | Direct proof of committed repository state | Does not prove Jenkins, ArgoCD, ServiceNow, or Artifactory state |
| Recorded live checks | Point-in-time operational adjudication | Direct evidence at the recorded time | Must not be treated as current without a new read-only verification |
| Model summary | Navigation aid | Compresses many facts | Secondary evidence; never authoritative by itself |

Repository and recorded live evidence are used only after each branch answer is
frozen. They adjudicate conflicts; they are not branch inputs.

## Benchmark snapshot

Snapshot: `2026-09-14 08:43 CEST`

| Item | Measured value |
|---|---:|
| Primary worklog | `worklog/2026-07-01_SCTASK1544083.log` |
| Worklog source bytes | 42,373 |
| Worklog logical lines | 667 newline-delimited lines; the editor reports 668 |
| Worklog source mtime | `2026-07-20T18:13:37Z` |
| State capsule bytes | 9,952 |
| Indexed worklog documents | 1 state capsule and 16 chunks |
| Primary journal | `prompt.log` |
| Primary journal bytes | 2,650,660 |
| Primary journal mtime | `2026-09-14T06:38:17Z` |
| `convo.db` size | 45,899,776 bytes |
| All indexed chat turns | 2,633 |
| SCTASK1544083-primary turns | 64 |
| Operational turns after exclusions | 62 |
| Operational date span | 2026-06-30 through 2026-07-13 |

The database and journal are intentionally not synchronized during this
benchmark. Synchronizing would ingest the comparison prompts and contaminate
the result being measured.

## Leakage and exclusion policy

The following indexed turns are excluded from scored operational evidence:

- `turn-002885.md`: request to perform a worklog-versus-conversation
  comparison;
- `turn-002886.md`: summary produced by that comparison.

Turn `002886` is especially important: it summarizes the worklog, earlier
turns, transcript, and repository history. Counting it as an independently
retrieved operational fact would be circular.

All current comparison prompts after `prompt.log` line 37209 are excluded from
the direct-journal branch. The resulting direct source set contains 62 eligible
operational entries.

For another ticket:

1. identify the first retrieval/comparison prompt;
2. list every derivative comparison or memory-quality turn;
3. record excluded paths, hashes, and source-line boundaries;
4. apply the same exclusion to every branch;
5. never let a generated evaluation summary satisfy freshness.

## Reproducibility prerequisites

Set paths without embedding machine-specific locations in commands:

```bash
export MEMORY_WORKSPACE_ROOT="/path/to/workspace"
export MEMORY_REPO="${MEMORY_WORKSPACE_ROOT}/repos/ai-memory-ingester"
export MEMORY_DATA="${MEMORY_REPO}/data"
export MEMORY_TREE="${MEMORY_WORKSPACE_ROOT}/worklog-chat"
export TICKET="SCTASK1544083"
export FACET="sctask1544083"
```

All benchmark commands are read-only. Do not run `sweep-memory`, ingestion,
database synchronization, or digest generation while a snapshot is being
scored.

## Branch 1: fixed worklog retrieval

### Budget-normalized input

Use the state capsule first, followed by the newest ticket chunks in descending
chunk sequence until the next complete document would exceed 22,000 bytes.

For SCTASK1544083 this selected:

1. `ticket-state/sctask1544083.md` — 9,952 bytes;
2. `other/0016-...sync-manifests...md` — 2,843 bytes;
3. `other/0015-...applicationmap...md` — 2,426 bytes;
4. `other/0014-...all-9-apps...md` — 1,921 bytes;
5. `other/0013-...multi-app-onboarding...md` — 4,684 bytes.

Total: 5 documents and 21,826 bytes.

Retrieve the authoritative inventory:

```bash
sqlite3 -json "${MEMORY_DATA}/worklogs.db" <<SQL
SELECT source_archive, file_path, file_name,
       length(CAST(content AS BLOB)) AS bytes,
       content
FROM documents
WHERE (source_archive = 'worklog:state'
       AND file_name = '${FACET}.md')
   OR (source_archive = 'worklog:chunks'
       AND file_path LIKE '${FACET}/%')
ORDER BY source_archive DESC, file_path;
SQL
```

### Source ceiling

Read the complete fixed worklog:

```bash
cat "${MEMORY_WORKSPACE_ROOT}/worklog/2026-07-01_${TICKET}.log"
```

Record:

- full byte and line count;
- all dated sections;
- duplicated and superseded status;
- latest operational timestamp;
- unresolved assertions requiring repository or live verification.

### Observed quality

The normalized branch recovers the original request, architecture, early
eFaktura execution, multi-app targets, nine-app config, ApplicationMap
migration, manifest publication, and 18 generated jobs.

Its strongest property is breadth. Its main defect is temporal state:

- the source says the BSMS config and JIRA `ApplicationMap.json` are still
  uncommitted;
- repository history proves `e96a6f0` and `585ef9c` exist;
- older `PAUSED`, deferred PROD, and pre-TLS statements remain in the same
  status material as later completion evidence.

The worklog therefore describes history better than current repository truth.

## Branch 2: FTS5 top-20 retrieval

### Raw current behavior

The unfiltered ticket-facet query returns 64 turns. Its first two results are
the excluded comparison turns because they are short and contain no repeated
duplicate-source metadata.

Run the scored operational query using a bound FTS expression:

```bash
sqlite3 -json "${MEMORY_DATA}/convo.db" <<SQL
PRAGMA trusted_schema=ON;
.parameter init
.parameter set @query 'file_path:${FACET}'
SELECT d.id, d.file_path, d.file_name,
       bm25(documents_fts) AS rank,
       substr(d.content, instr(d.content, char(10) || '---') + 5, 1200) AS snippet
FROM documents_fts
JOIN documents d ON d.id = documents_fts.rowid
WHERE documents_fts MATCH @query
  AND d.source_archive = 'chat:turns'
  AND d.file_name NOT IN ('turn-002885.md', 'turn-002886.md')
ORDER BY rank, d.id
LIMIT 20;
SQL
```

The scored SCTASK1544083 result contains:

- 20 snippets;
- 8,569 snippet bytes;
- dates from 2026-07-01 through 2026-07-13;
- four obvious administrative or low-value results;
- three results containing status later superseded.

### Complete-turn expansion

Snippets are discovery aids, not sufficient evidence. Open the complete turn
when a result contributes to an answer:

```bash
cat "${MEMORY_TREE}/turns/<file_path-from-query>"
```

The inspected complete turns included:

- `000922`: initial repository push;
- `000926`: Job DSL branch-shadowing fix;
- `000964`: verified-TLS PREPROD success;
- `000973`: eFaktura manifest upload;
- `001048`: all nine PROD permission checks;
- `001049`: final folder naming;
- `001051`: 18 generated jobs.

### Observed ranking quality

The query is a facet filter, not a semantic question. Every eligible document
contains the same path token, so BM25 length normalization strongly influences
the order.

Measured effects:

- the 18 historical files in the original top 20 contained 180 repeated
  `also_seen_in` lines;
- the 62 operational turn files contain 620 repeated `also_seen_in` lines;
- those 62 materialized files total 886,518 bytes;
- their original `prompt.log` regions total only 54,395 bytes;
- short derivative comparison turns therefore outranked primary operational
  evidence.

The unbiased top 20 found useful seed, SYNC-gate, TLS, permission, naming, and
18-job evidence. It omitted:

- `turn-001050`: nine-app config merge and 18-target readiness;
- `turn-001052`: eight-app ApplicationMap migration;
- `turn-001054`: eight uploaded manifests and JIRA enablement for all nine
  apps.

This is ranking and selection loss, not source or ingestion loss.

## Branch 3: direct prompt.log retrieval

### Eligibility and ordering

Parse only the primary workspace journal. Do not consult `convo.db`, worklog
files, `RESUME.md`, or repository history while producing the branch answer.

Selection rules:

1. segment complete prompt-response entries using journal boundaries;
2. keep entries whose primary ticket is `SCTASK1544083`;
3. exclude entries beginning at line 37210 or later;
4. order eligible entries by source byte offset descending;
5. add only complete entries while cumulative UTF-8 bytes remain at or below
   22,000.

For the measured snapshot:

- 68 primary matches existed in the live journal;
- 62 were eligible operational entries;
- newest-first packing selected 24 complete entries;
- selected context size was 21,871 bytes;
- selected source-line span was 16452–17442;
- parsing through the Groovy source path took approximately 3.97 seconds,
  including runtime startup.

The full 62-entry source ceiling was 54,395 bytes.

### Observed quality

The newest-first budget captured all three decisive July 13 entries omitted by
FTS5:

- nine-app config and 18-target readiness;
- ApplicationMap migration;
- all eight manifest uploads.

It also captured:

- root-CA creation and verified TLS;
- eFaktura JIRA end-to-end success;
- PROD target discovery and permission checks;
- the final 18-job seed run.

Its main budget weakness is the opposite of the worklog:

- excellent latest-state coverage;
- weak early-ticket context;
- missing original architecture, initial scaffold, plugin failure, script
  approval, and detailed RBAC progression.

Direct parsing was also much slower than SQLite retrieval in this snapshot:

- worklog SQLite retrieval: approximately 0.18 seconds;
- FTS5 top-20 query: approximately 0.17 seconds;
- direct Groovy journal parsing: approximately 3.97 seconds.

## Common isolated answer template

Each branch answer must use this structure:

1. **Scope:** systems, environments, applications, and acceptance criteria.
2. **Locked decisions:** architecture, security, credentials, hierarchy, and
   rollout gates.
3. **Timeline:** dated milestones in chronological order.
4. **Latest state:** completed deliverables at the newest operational event.
5. **Current remaining work:** only items not contradicted by later evidence.
6. **Evidence:** exact source path plus line range, turn hash, or source lines.
7. **Conflicts:** both claims, their dates, authority, and resolution.
8. **Unknowns:** statements requiring repository or live-system verification.

Do not allow one branch to fill omissions from another before its answer is
frozen.

## Fact ledger

Weights represent importance to a useful ticket answer:

- 3: required to understand current delivery state;
- 2: important architecture or progression detail;
- 1: supporting metadata.

Recovery values are:

- full = 1;
- partial = 0.5;
- missing or wrong = 0.

| ID | Weight | Adjudicated fact | Fixed worklog | FTS5 top 20 | Direct prompt.log |
|---|---:|---|---:|---:|---:|
| F01 | 3 | JIRA-driven ArgoCD rollout, eFaktura first, then nine apps across PREPROD and PROD | Full | Partial | Full |
| F02 | 2 | Target is server01; server08/Manhattan is reference only | Full | Partial | Partial |
| F03 | 3 | Config-driven 18-job hierarchy with boolean `SYNC=false` gate | Full | Full | Full |
| F04 | 2 | Roles are additive/manual and final assignees remain separate work | Full | Partial | Partial |
| F05 | 3 | Environment tokens plus pinned root-CA TLS | Full | Partial | Full |
| F06 | 2 | Initial scaffold and first repository push | Partial | Full | Missing |
| F07 | 2 | Plugin, script-approval, and DSL branch failures were resolved | Full | Partial | Missing |
| F08 | 3 | PREPROD RBAC failure progressed to green E2E | Full | Partial | Partial |
| F09 | 2 | Verified-TLS build completed cleanly | Partial | Full | Full |
| F10 | 2 | eFaktura SYNC manifest became live | Full | Full | Full |
| F11 | 3 | eFaktura JIRA-triggered PREPROD E2E succeeded | Missing in budget | Missing | Full |
| F12 | 3 | All nine PROD targets and permissions were verified | Full | Full | Full |
| F13 | 2 | Nine Jenkins folder names and ZaiksWeb2 correction | Full | Full | Full |
| F14 | 3 | Nine-app config and 18-target readiness validation completed | Full | Missing | Full |
| F15 | 3 | All 18 synchronization jobs were generated | Full | Full | Full |
| F16 | 3 | Eight existing ApplicationMap entries were migrated in place | Full | Missing | Full |
| F17 | 3 | Eight additional manifests were uploaded and verified | Full | Missing | Full |
| F18 | 3 | Final config commits exist as `e96a6f0` and `585ef9c` | Missing/wrong | Missing | Missing/wrong |
| F19 | 2 | Per-app roles remain unverified; config-push action is resolved | Partial | Partial | Partial |
| F20 | 1 | Current ServiceNow status requires re-verification | Partial | Missing | Missing |
| F21 | 2 | JIRA E2E for eight non-eFaktura apps is not evidenced | Full | Full | Full |

Total eligible weight: 52.

Weighted budget coverage:

- fixed worklog: 42.5 / 52 = 81.7%, rounded to 82;
- FTS5 top 20: 27.5 / 52 = 52.9%, rounded to 53;
- direct prompt.log: 39.5 / 52 = 76.0%, rounded to 76.

The source-ceiling audit is materially closer: all three sources expose most
operational facts through July 13. None independently contains the later
repository correction for F18.

## Assessment dimensions and formulas

### Factual coverage

```text
coverage = 100 × recovered fact weight / eligible fact weight
```

This measures what the selected context actually exposes, not what exists
somewhere else in the source corpus.

### Precision

```text
retrieval precision = useful non-meta input items / all input items
claim precision = supported substantive claims / all substantive claims
precision score = average(retrieval precision, claim precision)
```

Measured branch adjudication:

- fixed worklog: all 5 documents were relevant; 23 of 25 substantive assertions
  were supported or correctly qualified;
- FTS5: 16 of 20 results were useful; 18 of 22 substantive assertions were
  supported or correctly qualified;
- direct prompt.log: all 24 entries were ticket-operational; 11 of 12
  substantive assertions were supported or correctly qualified.

### Freshness

Freshness combines event recency and latest-state fact coverage:

```text
freshness = average(event recency score, latest-state coverage)
```

Comparison and memory-quality turns cannot establish operational freshness.

For this case:

- worklog and direct prompt.log reach the July 13 12:14 manifest upload;
- FTS5 top 20 ends at the 11:26 seed run and misses three later decisive turns;
- both worklog and prompt.log still misstate final repository push status.

### Temporal correctness

```text
temporal correctness =
  100 × correctly classified current/historical/superseded status items
        / all status-bearing items
```

A dated statement can be factually true historically and still be wrong when
presented as current.

### Authority

Use the following native-source scale:

- 100: currently verified repository or live-system evidence;
- 80: structured authoritative worklog evidence;
- 70: atomic turn or direct journal evidence;
- 50: derivative model summary;
- 0: unsupported assertion.

External adjudication does not retroactively raise a branch's native authority
score.

### Traceability

```text
traceability =
  100 × claims with exact path and line/hash provenance / substantive claims
```

All three measured branches support exact provenance:

- worklog line range or chunk path;
- FTS turn path, hash, and `source_lines`;
- direct prompt-log line range.

### Contradictions

```text
contradiction score =
  100 × known conflicts detected and correctly resolved / eligible known conflicts
```

The adjudication set includes:

1. server01 target versus server08 reference;
2. RBAC blocked versus resolved;
3. plugin/seed blocked versus resolved;
4. manifests staged versus uploaded;
5. config uncommitted versus repository commits.

### Overall score

```text
overall =
  coverage × 0.25 +
  precision × 0.20 +
  freshness × 0.15 +
  temporal correctness × 0.15 +
  authority × 0.10 +
  traceability × 0.10 +
  contradictions × 0.05
```

Supporting diagnostics such as latency, bytes, duplication, and parser
anomalies are reported separately and do not alter the weighted score.

## Representation-parity audit

Eight decisive journal entries were mapped to their materialized turns:

- 000964;
- 000973;
- 001048;
- 001049;
- 001050;
- 001051;
- 001052;
- 001054.

For all eight:

- normalized user text matched;
- normalized assistant text matched;
- turn hash matched;
- `source_lines` resolved to the original journal entry;
- SCTASK1544083 remained the primary ticket.

Observed metadata and structure defects:

- turn `001052` also contains the false-positive ticket `UTF-8`;
- the phrase `(not committed)` was split so `COMMITTED)` appears as a detail
  heading, although the text itself was preserved;
- duplicate-source processing produced repeated `also_seen_in` keys rather
  than one consolidated key.

Conclusion: core conversational content survived ingestion. The dominant loss
in this case occurs after ingestion, through metadata bloat, ranking, and
context selection.

## Repository adjudication

The branches all end with, or fail to correct, a claim that two configuration
changes still require commit and push.

Read-only repository history shows:

- `TVN-gitadmins-bsms_pipelines`
  - `e96a6f09690bf1f0ccfb991a0a688aebcf32d1e6`
  - `2026-07-13T11:23:58+02:00`
  - `feat(config): onboard 8 BSMS apps to ArgoCD sync (PREPROD+PROD)`
  - present at `origin/master`.
- `TVN-gitadmins-jira_integration`
  - `585ef9c9bc3c86c4f96da5bc0769c5df98779dfb`
  - `2026-07-13T11:40:58+02:00`
  - `feat(appmap): migrate 8 apps to ArgoCD sync jobs`.

This proves the repository state, but not whether Jenkins roles were applied or
whether every application completed a later JIRA-triggered end-to-end test.

## Hybrid retrieval diagnostic

The hybrid `ticket SCTASK1544083 --limit 20` result is not one of the three
scored pure branches, but it demonstrates the intended upgrade direction.

Its measured composition was:

- 1 state capsule;
- 1 newer-evidence turn;
- 12 authoritative worklog chunks;
- 6 recent turns.

It is stronger than raw path-only BM25 because it retrieves late worklog chunks
and recent turns `001050`–`001054`. Its current defect is that comparison turn
`002886` is classified as newer operational evidence, causing the state capsule
to be marked stale through September even though that turn is an evaluation
summary.

## Gap classification

| Finding | Failure layer | Evidence |
|---|---|---|
| Worklog says final configs are uncommitted | Source staleness | Contradicted by `e96a6f0` and `585ef9c` |
| FTS top 20 misses turns 001050, 001052, and 001054 | Ranking/context selection | All three turns exist and pass core parity |
| Comparison turns rank first | Ranking leakage | Short derivative documents outrank bloated operational turns |
| 620 repeated `also_seen_in` lines | Ingestion/materialization metadata | 62 turn files total 886,518 bytes versus 54,395 raw bytes |
| `UTF-8` appears as a ticket | Parsing/facet classification | Turn 001052 metadata |
| `(not committed)` is split into a heading | Parsing/representation | Turn 001052 body/details boundary |
| Direct prompt context misses early design and failures | Context assembly | Newest-first 22 KB selection |
| Worklog budget misses July 7 E2E detail | Context assembly | State plus latest-chunk budget excludes chunk 0011 |
| September comparison marks capsule stale | Freshness classification | Turn 002886 is derivative evaluation evidence |
| All branches miss final commit truth | Cross-source verification | Repository history is outside branch inputs |

## Interpretation

### Fixed worklog

Best for comprehensive history and architecture. It provides the strongest
single-document explanation but requires explicit temporal interpretation and
external current-state verification.

### FTS5 top 20

Best for locating exact atomic evidence quickly. The materialized data is
substantially more complete than the top-20 result suggests; current
path-facet BM25 ordering is not a dependable ticket-summary strategy.

### Direct prompt.log

Best journal-level evidence for the latest conversation when parsed directly.
It exposes decisive late turns that FTS ranking missed, but repeated whole-log
parsing is slow and a newest-first budget loses early context.

### Upgrade position

The ingestion upgrade has solved ticket discoverability and largely preserved
turn bodies. The remaining measured weaknesses are:

1. source freshness in worklogs;
2. duplicate metadata expansion;
3. false-positive ticket/section parsing;
4. path-only BM25 ranking;
5. comparison/meta leakage into freshness;
6. selection policies that do not balance current state with foundational
   decisions;
7. lack of repository/live adjudication in normal answer assembly.

The hybrid retriever is closer to the desired behavior than any pure branch,
but it still needs trustworthy meta exclusion and cleaner current-state
adjudication.

## Reusing this benchmark

For another ticket:

1. freeze source and database metadata;
2. discover the real ticket facet;
3. identify and exclude comparison/meta turns;
4. build an adjudicated weighted fact ledger;
5. retrieve each branch independently;
6. enforce the same byte budget;
7. retain complete items only;
8. answer the same seven questions;
9. score using the formulas in this document;
10. inspect each complete turn used by the answer;
11. run representation parity on decisive facts;
12. inspect all source corpora for the source ceiling;
13. adjudicate only afterward with repository and read-only live evidence;
14. classify each miss by failure layer;
15. preserve commands, paths, exclusions, and raw counts with the result.

## Final comparison

| Dimension | Fixed worklog | FTS5 top 20 | Direct prompt.log |
|---|---|---|---|
| Input/result count | 5 documents from 17 indexed ticket documents | 20 snippets from 62 eligible turns | 24 entries from 62 eligible entries |
| Budget input bytes | 21,826 | 8,569 snippet bytes | 21,871 |
| Source ceiling | 42,373 bytes; 667 logical lines | 62 turns; 886,518 materialized bytes | 62 entries; 54,395 raw bytes |
| Retrieval latency | About 0.18 s through direct SQLite | About 0.17 s through direct SQLite | About 3.97 s including Groovy startup and parsing |
| Factual coverage | **82/100** — broad early and late ticket coverage; misses budgeted July 7 E2E and final commits | **53/100** — seed/TLS/permissions/jobs recovered; final three milestones omitted | **76/100** — strong July 2–13 delivery coverage; early design and failure history omitted |
| Precision | **96/100** — all selected documents relevant; two current-state assertions fail adjudication | **81/100** — 16/20 useful results; administrative and superseded results consume rank | **96/100** — all entries operational; final push status requires correction |
| Freshness | **91/100** — reaches July 13 12:14 but misstates repository completion | **58/100** — selected set ends before three decisive July 13 turns | **91/100** — reaches July 13 12:14 but retains stale push status |
| Temporal correctness | **67/100** — old paused/deferred/current sections are mixed and final push state is stale | **65/100** — several superseded blockers can appear current without cross-turn synthesis | **75/100** — newest-first sequence resolves most progression but not repository commits |
| Authority | **80/100** — structured authoritative ticket source, not current repository proof | **70/100** — atomic materialized conversation evidence | **70/100** — original append-only conversation journal |
| Traceability | **100/100** — exact source/chunk paths and line ranges | **100/100** — path, hash, timestamp, and source lines | **100/100** — complete source-line ranges |
| Contradictions | **80/100** — resolves 4/5 known progress conflicts; misses final commit correction | **60/100** — resolves 3/5; omits decisive later evidence | **40/100** — resolves 2/5 within the newest-first budget |
| Overall weighted score | **85.4/100** | **67.9/100** | **82.1/100** |
| Strongest use | Comprehensive ticket history and architecture | Fast discovery of exact atomic evidence | Latest conversational progression and forensic journal recovery |
| Primary weakness | Stale and mixed temporal state | Length-biased ranking and decisive-turn omission | Slow parsing and loss of early context under recency packing |
| Confidence | High for source contents; medium for current state | High for measured ranking; medium for answer completeness | High for selected entries; medium for current external state |
