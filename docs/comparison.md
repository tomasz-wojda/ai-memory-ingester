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

## Mandatory benchmark output

Every three-branch benchmark run must produce a complete measured result. A
narrative answer without the following fields is incomplete:

1. frozen query, snapshot timestamp, journal cutoff, exclusions, byte budget,
   and branch-specific ordering policy;
2. successful wall-clock retrieval time for every branch, including process
   and language-runtime startup;
3. failed setup attempts listed separately and excluded from successful
   retrieval timing;
4. total matches, selected items, selected UTF-8 bytes, date span, and source
   ceiling for every branch;
5. isolated answers produced before cross-branch adjudication;
6. a stable weighted fact ledger;
7. numeric factual coverage, precision, freshness, temporal correctness,
   authority, traceability, contradiction, and weighted overall scores;
8. disclosed denominators for every score;
9. retrieval and synthesis defects classified by failure layer;
10. a populated timing-and-score summary table as the final benchmark section.

The user-facing answer must repeat the branch timings and score summary. Values
that were not measured must be reported as `Not measured`; they must never be
estimated to make a table appear complete.

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

## SCTASK1544083 baseline comparison

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

## MongoDB metrics benchmark

### Frozen inputs

Snapshot: `2026-09-14 09:18 CEST`

| Item | Frozen value |
|---|---|
| Query intent | Case-insensitive `mongo* AND metric*` |
| Direct-journal equivalent | Entry contains both a `mongo...` token and a `metric...` token |
| Journal cutoff | Complete entries ending at or before `prompt.log` line 37286 |
| Excluded journal range | Line 37287 onward, containing this benchmark and its derivative prompts |
| Branch budget | 22,000 UTF-8 bytes |
| Indexed-worklog order | BM25 ascending, document ID ascending, limit 20 |
| Conversation FTS order | BM25 ascending, document ID ascending, limit 20 |
| Direct-journal order | Source byte offset descending; complete entries only |
| `worklogs.db` | 6,938,624 bytes; mtime `2026-09-14T05:02:34.851856Z` |
| `convo.db` | 45,899,776 bytes; mtime `2026-09-14T05:02:33.075371Z` |
| Primary `prompt.log` | 2,657,760 bytes at snapshot |
| Application code or database changes | None |

The scored indexed branches use 1,050-character snippets so their top 20 fit
the byte budget. Complete documents and neighboring turns are inspected only
after each isolated answer is frozen and therefore cannot improve its score.

An earlier exploratory direct-journal attempt failed in 1.34 seconds because
`MEMORY_WORKSPACE_ROOT` was not exported. It changed no data and is excluded
from successful retrieval time. The measured benchmark run itself had no
failed setup attempts.

### Retrieval measurements

| Branch | Total matches | Selected | Selected bytes | Query source ceiling | Successful wall time |
|---|---:|---:|---:|---:|---:|
| Indexed worklogs | 27 | 20 snippets | 20,755 | 200,845 bytes across matching documents | 0.02 s |
| Conversation FTS5 | 48 | 20 snippets | 17,209 | 532,027 bytes across matching turns | 0.02 s |
| Direct `prompt.log` | 51 | 20 complete entries | 21,772 | 115,765 bytes across matching entries | 3.75 s |

The two primary worklog sources contain 177,754 bytes across 2,813 logical
lines. They are larger than the indexed query ceiling because not every section
contains both query terms.

### Isolated branch findings

#### Indexed worklogs

The ranked snippets recover the KD-6999 and KD-7289 ticket scopes, the
DocumentDB reader-safety design, the dedicated exporter and OTLP architecture,
the stable document metric name, UAT-first rollout, and historical collector
routing work.

Quality defects:

- both ticket state capsules explicitly report `freshness: stale`;
- an unrelated KD-6541 New Relic metrics chunk consumes one top-20 position;
- one additional action-log result is marginal;
- the snippets omit the final six-name metric contract, image 24-9 cadence,
  September PROD deployment, collector repair, and Grafana verification;
- authoritative and historical chunks conflict over whether KD-6999 Phase 1
  remains blocked or is closed.

#### Conversation FTS5

The top 20 recover both exporters, four stable KD-7289 metrics, UAT live
measurements, dedicated-secret progression, image 24-9, the four-hour schedule,
60-second scrape, 10-second batch, and the September diagnosis that PROD
metrics were being dropped.

Quality defects:

- six results are only partial plumbing or planning evidence;
- the result set presents the September 3 missing PROD allowlist and wrong
  datasource as the latest state;
- the actual September 4 collector repair and successful Grafana query do not
  match both lexical terms and therefore never enter the 48-result query
  ceiling;
- metric naming and COUNT_MODE history require chronology-aware synthesis.

#### Direct prompt.log

The 20 newest complete matching entries recover the metric rename, both ticket
scopes, UAT measurements, credential hardening, PROD diagnosis, and current
scrape/count cadence. Nineteen entries are useful and one is marginal.

Quality defects:

- source-offset ordering is deterministic but not strictly chronological
  because later journal appends can contain earlier timestamps;
- the selector omits the final collector repair because that entry contains
  `konto_mongo` but not a `metric...` token;
- it omits the successful Prometheus query because that entry says `metrics`
  but does not contain a `mongo...` token;
- the isolated answer consequently repeats a blocker that had already been
  resolved in adjacent journal entries.

### Adjudicated fact ledger

Weights use the existing 3/2/1 importance scale. `Full` receives the complete
weight, `Partial` receives half, and `Missing` receives zero. Recorded live and
repository statements are historical evidence unless reverified during this
benchmark.

| ID | Weight | Adjudicated fact | Indexed worklogs | FTS5 top 20 | Direct prompt.log |
|---|---:|---|---:|---:|---:|
| M01 | 3 | KD-6999 monitors accessTokens, refreshTokens, and sessions for long-retention reporting | Full | Full | Full |
| M02 | 3 | KD-7289 monitors `event_message` in total-only mode | Full | Full | Full |
| M03 | 2 | Both use the dedicated exporter image and related deployments | Full | Full | Full |
| M04 | 3 | Final contract has six names; the document gauge no longer ends in `_total` | Partial | Partial | Full |
| M05 | 3 | OAuth uses total/active/expired; KD-7289 emits no active, expired, or status split | Full | Partial | Full |
| M06 | 3 | Queries use reader endpoints, secondary preference, bounds, and least privilege | Full | Full | Full |
| M07 | 2 | UAT credentials progressed from shared application access to dedicated secrets | Partial | Full | Full |
| M08 | 3 | UAT scrape route uses collector allowlisting and the Konto UAT datasource | Partial | Full | Full |
| M09 | 2 | Direct retention OTLP requires authentication and `system=konto-retention` | Full | Full | Full |
| M10 | 3 | KD-6999 UAT exposed 15 healthy series | Missing | Full | Full |
| M11 | 3 | KD-7289 UAT reported about 132,615 documents in 0.094–0.096 seconds | Partial | Full | Full |
| M12 | 3 | Image 24-9 uses `/livez` and a four-hour Warsaw schedule | Missing | Full | Full |
| M13 | 2 | Prometheus scrapes every 60 seconds and collector batching is 10 seconds | Partial | Full | Full |
| M14 | 3 | PROD exporter was recorded healthy on image 24-9 | Missing | Partial | Partial |
| M15 | 3 | PROD collector replacement made all six allowlist entries live | Missing | Missing | Missing |
| M16 | 3 | Prometheus Konto then returned documents, active, expired, duration, and success series | Missing | Missing | Missing |
| M17 | 2 | Retention datasource remained empty because direct authenticated OTLP was disabled | Missing | Full | Full |
| M18 | 1 | UAT2 collector restoration remained unresolved in the scored evidence | Missing | Full | Full |
| M19 | 2 | KD-7289 dedicated credential hardening remained incomplete | Missing | Full | Full |
| M20 | 2 | September PROD count totals and per-cycle performance were recorded | Missing | Missing | Missing |
| M21 | 1 | Both indexed ticket capsules explicitly identify their own staleness | Full | Missing | Missing |

Total eligible weight: 52.

Coverage calculations:

- indexed worklogs: 22.5 / 52 = 43.3%, rounded to 43;
- FTS5 top 20: 37.5 / 52 = 72.1%, rounded to 72;
- direct prompt.log: 41.5 / 52 = 79.8%, rounded to 80.

### Score calculations

#### Precision

- Indexed worklogs: 18/20 clearly useful results and 27/28 supported or
  correctly qualified substantive claims; average 93.
- FTS5: 14/20 clearly useful results and 28/30 supported or correctly
  qualified substantive claims; average 82.
- Direct prompt.log: 19/20 useful entries and 29/30 supported or correctly
  qualified substantive claims; average 96.

#### Freshness

Latest-state facts M14–M20 carry 16 weight.

- Indexed worklogs receive event-recency 50 and 0/16 latest-state fact weight:
  `(50 + 0) / 2 = 25`.
- FTS5 and direct prompt.log each receive event-recency 100 and 6.5/16 =
  40.6 latest-state coverage: `(100 + 41) / 2 = 70.5`, rounded to 71.

The high recency component does not mean the isolated answer is current: both
ranked conversational branches miss the adjacent September 4 repair.

#### Temporal correctness

- Indexed worklogs correctly classify 6 of 8 status-bearing claims: 75.
- FTS5 correctly classifies 7 of 11 status-bearing claims: 64.
- Direct prompt.log correctly classifies 8 of 11 status-bearing claims: 73.

#### Authority

- Indexed worklogs: 80, using the existing structured-worklog authority tier.
- FTS5: 70, using the atomic-turn authority tier.
- Direct prompt.log: 70, using the raw-journal authority tier.

#### Traceability

All substantive claims in all three branch answers retain an exact chunk path,
turn path and source lines, or direct journal range. Each branch scores 100.

#### Contradictions

Six eligible conflicts are used:

1. `_total` gauge versus the renamed document gauge;
2. proposed status mode versus live total-only KD-7289 mode;
3. UAT pipeline broken versus restored;
4. shared application credentials versus dedicated credentials;
5. allowlist present in Git versus absent live;
6. PROD blocked versus collector repaired and metrics queryable.

- Indexed worklogs resolve 2/6: 33.
- FTS5 top 20 resolves 2/6: 33.
- Direct prompt.log resolves 4/6: 67.

#### Weighted totals

Using the unchanged weights:

- indexed worklogs:
  `43×0.25 + 93×0.20 + 25×0.15 + 75×0.15 + 80×0.10 + 100×0.10 + 33×0.05 = 64.0`;
- FTS5 top 20:
  `72×0.25 + 82×0.20 + 71×0.15 + 64×0.15 + 70×0.10 + 100×0.10 + 33×0.05 = 73.3`;
- direct prompt.log:
  `80×0.25 + 96×0.20 + 71×0.15 + 73×0.15 + 70×0.10 + 100×0.10 + 67×0.05 = 81.2`.

### Source-ceiling and neighbor adjudication

After all isolated answers were frozen, adjacent complete turns established the
latest recorded state:

- `turn-002396.md`: the September 4 replacement of
  `OpenTelemetryCollector/otel-cluster-collector` succeeded and all six
  allowlist entries became live;
- `turn-002397.md`: direct Grafana API queries found documents, active,
  expired, duration, and success metrics, each with three collection series and
  `system=konto`, in Prometheus Konto;
- the Retention datasource remained empty because direct authenticated OTLP
  was disabled;
- `turn-002394.md`: the collector scraped every 60 seconds, batched every 10
  seconds, and republished values calculated on the four-hour Warsaw schedule.

These facts receive no branch score credit because the frozen query and
selection did not retrieve the first two turns. This proves a lexical-query and
context-expansion defect rather than absent source memory.

### Failure-layer classification

| Finding | Failure layer |
|---|---|
| KD-6999 and KD-7289 state capsules are stale | Source/state freshness |
| Unrelated KD-6541 chunk enters worklog top 20 | Ranking precision |
| Worklog snippets cut off decisive status details | Context assembly |
| BM25 promotes planning and credential plumbing over final repair | Ranking |
| Final repair turn lacks `metric...`; successful query turn lacks `mongo...` | Query formulation |
| Direct source-offset order differs from event chronology | Context ordering |
| Scored answers present repaired PROD routing as still blocked | Temporal synthesis |
| Neighbor expansion recovers the omitted correction | Retrieval orchestration |

## MongoDB metrics comparison

| Dimension | Indexed worklogs | FTS5 top 20 | Direct prompt.log |
|---|---|---|---|
| Result/selected count | 27 matches / 20 snippets | 48 matches / 20 snippets | 51 matches / 20 complete entries |
| Selected bytes | 20,755 | 17,209 | 21,772 |
| Retrieval time | **0.02 s** including SQLite startup | **0.02 s** including SQLite startup | **3.75 s** including Groovy startup and parsing |
| Source ceiling | 27 matching documents / 200,845 bytes | 48 matching turns / 532,027 bytes | 51 matching entries / 115,765 bytes |
| Factual coverage | **43/100** — strong design, weak late delivery state | **72/100** — broad operational evidence; repair omitted | **80/100** — strongest selected fact coverage; repair still omitted |
| Precision | **93/100** — 18/20 useful; one unrelated and one marginal result | **82/100** — 14/20 clearly useful; plumbing consumes rank | **96/100** — 19/20 useful complete entries |
| Freshness | **25/100** — stale capsules and no latest-state facts | **71/100** — recent cadence evidence but stale blocker conclusion | **71/100** — recent cadence evidence but lexical selection misses repair |
| Temporal correctness | **75/100** — explicit stale labels help, conflicting status remains | **64/100** — 7/11 status items correctly classified | **73/100** — resolves more progression but misses final repair |
| Authority | **80/100** — structured worklog evidence | **70/100** — atomic conversation-turn evidence | **70/100** — primary append-only journal evidence |
| Traceability | **100/100** — exact indexed chunk paths | **100/100** — exact turn paths and source lines | **100/100** — exact complete journal ranges |
| Contradictions | **33/100** — resolves 2/6 known conflicts | **33/100** — resolves 2/6; final correction absent | **67/100** — resolves 4/6; final correction split across nonmatching entries |
| Overall weighted score | **64.0/100** | **73.3/100** | **81.2/100** |
| Strongest use | Architecture, safety constraints, and ticket scope | Fast operational evidence discovery | Latest broad narrative from complete raw entries |
| Primary weakness | Stale state and truncated ranked snippets | BM25 and conjunction query omit decisive repair | Slow startup and entry-level conjunction misses adjacent evidence |
| Confidence | High for measured input; medium for operational truth | High for measured ranking; medium for latest-state answer | High for selected entries; medium for latest-state answer |

## Trace in Konto benchmark (Task C, re-ranker variant)

This section adds a third task and a different set of branches. It compares
direct `prompt.log`, plain FTS5, and FTS5 followed by the cross-encoder
re-ranker described in `semantic-retrieval/`. It has no worklog branch and no
manual adjudication. The result is automated fact coverage only. The seven
dimension score defined above was not computed.

### Frozen inputs

Snapshot: `2026-11-03`

| Item | Frozen value |
|---|---|
| Query | `Trace in Konto`, run verbatim |
| Ticket mapping | KD-6945 (request tracing and trace correlation across Konto frontend and backend). Chosen from the memory search by the benchmark author |
| Ledger source | `ticket-state/kd6945.md`, authoritative through `2026-10-30T20:08Z` |
| FTS5 turn cutoff | Turn number 9979 or lower (`2026-10-30T20:10Z`) |
| Journal cutoff | Entries before `2026-10-30 22:10` local time; `Z` timestamps shifted by 2 hours |
| Journal selection | Entries whose parsed ticket keys contain `KD-6945`: 545 eligible |
| Journal parser | `lib/ChatLogParser.groovy` (6,111 entries in the full journal) |
| Branch budget | 22,000 UTF-8 bytes |
| FTS5 snippet size | 1,050 characters, 20 results, archive filter `chat:turns` |
| Re-ranker | `mmarco-mMiniLMv2-L12-H384-v1` on MPS, depth 30, budget 1,200 ms, no degraded runs |
| Journal order | Timestamp descending, then byte offset descending |

`prompt.log` is no longer in the September line order, so line-number cutoffs
are invalid. Task C, and the re-runs of tasks A and B in the same session,
use timestamp cutoffs. Tasks A and B reproduced their earlier automated
results exactly on a second run.

The journal branch is reported with two packing rules:

- **strict:** add complete entries newest first and stop at the first entry that
  would exceed the budget;
- **greedy fill:** skip an entry that does not fit and continue. On the MongoDB
  task this rule reproduces the September byte count exactly (20 entries,
  21,772 bytes), so it is probably the original rule.

### Fact ledger

Weights follow the definition above (3, 2 or 1). All 20 facts were drafted from
one source document. The strict set holds 17 facts and 39 weight. A fact counts
as recovered when its strict anchor patterns all match the selected text.

| ID | Weight | Fact | Strict anchor |
|---|---:|---|---|
| C01 | 3 | Goal: trace requests and correlate Traefik, application error logs and datalog across frontend and microservices | Not in strict set |
| C02 | 3 | Backend decision: PULS, with Grafana Tempo for traces and Loki via OTLP for logs (D2, 2026-10-24) | `tempo`, `loki` |
| C03 | 3 | PULS is the target platform; Konto is leaving Datadog and New Relic (D9, 2026-10-30) | `datadog`, `new relic` |
| C04 | 3 | Sampling is 1.0 (100%) in all environments for now, replacing the 20% start (D3, 2026-10-27) | `1.0` or `100%`, `sampl` |
| C05 | 3 | 97.3% of player `traceparent` headers carry flag 00 and are not recorded; 0 of 15 such IDs exist in Tempo | `flag 00` |
| C06 | 3 | PROD auto-instrumentation was delivered by KD-6540 (26 apps) | `kd-6540` |
| C07 | 3 | Konto traces are in Tempo for 24 services, but none from Traefik; no Konto application logs in Loki (2026-10-27) | `24 services` |
| C08 | 2 | D1 open: log field names `trace.id` and `span.id` against `trace_id` and `span_id` | `trace.id` |
| C09 | 2 | D4 open: reuse `sso_device_id` or introduce `konto_device_id` | `konto_device_id` |
| C10 | 2 | D8 resolved: browser tracing through PULS; browser intake (OTLP/HTTP with CORS) still open | `cors` |
| C11 | 2 | KD-7159: Traefik logs go to a separate Kinesis stream on UAT only; not live on kontoprod | `kd-7159` |
| C12 | 2 | KD-7531: `social.provider` attribute is present on PROD spans in PULS Tempo | `social.provider` |
| C13 | 2 | Web `device_id` is live end to end (KD-6195, webcomponent #278) | `#278` or `kd-6195` |
| C14 | 2 | Quick-win plan (bunyan trace fields, Rollouts `OTEL_SERVICE_NAME`, Traefik tracing); rollout STAGE, UAT, PROD; not approved | `otel_service_name` |
| C15 | 2 | Design direction: W3C `trace_id`, business IDs as separate fields | `w3c` |
| C16 | 2 | D6 open: meaning of "datalog"; Datadog browser-logs ship in PROD frontends | `browser-logs` |
| C17 | 1 | Ticket metadata: Jira status "Do podjęcia", 7 hours logged | Not in strict set |
| C18 | 3 | Current phase is RESEARCH; Traefik tracing, log and trace correlation, error logs and frontend tracing remain open | Not in strict set |
| C19 | 2 | D7 open: GDPR and consent sign-off for `device_id` | `gdpr` |
| C20 | 1 | PULS documents recommend 5-20% PROD sampling | `5-20%` |

Anchor check, run before scoring: all 20 fact patterns match the capsule, and
the 17 strict anchors match at most 1.5% of a random sample of 3,000 unrelated
turns. C07 appears only in the capsule and in none of the 501 KD-6945 turns,
so no turn-based or journal branch can recover it. The best possible turn-based
coverage on this ledger is about 92%.

### Retrieval measurements

| Branch | Selected | Selected bytes | Wall time |
|---|---|---:|---:|
| Direct `prompt.log`, strict packing | 3 entries of 545 eligible | 16,830 | 4.7 s |
| Direct `prompt.log`, greedy fill | 7 entries | 21,933 | 4.7 s |
| FTS5 | 20 snippets | 10,787 | 1.1 s |
| FTS5 + re-ranker, engine order | 20 snippets | 17,252 | 1.7 s |
| FTS5 + re-ranker, model-only order | 20 snippets | 15,071 | 1.7 s |

Wall time includes runtime start-up: about 1 s of Java start-up for the FTS5
queries, and the Groovy parse of the 8 MB journal for `prompt.log`. The
re-ranker adds about 0.6 s with the service already warm. The September
timings in this document came from the `sqlite3` command line and are not
comparable with the FTS5 times here.

### Weighted coverage

| Branch | cov@5 | cov@10 | cov@all | Facts recovered |
|---|---:|---:|---:|---|
| `prompt.log`, strict packing | 0.0 | 0.0 | **0.0** | none |
| `prompt.log`, greedy fill | 12.8 | 12.8 | **12.8** | C03, C16 |
| FTS5 | 0.0 | 0.0 | **5.1** | C14 |
| FTS5 + re-ranker, engine order | 7.7 | 12.8 | **33.3** | C02, C04, C05, C14, C15 |
| FTS5 + re-ranker, model-only order | 20.5 | 20.5 | **20.5** | C02, C05, C15 |

Coverage uses `100 × recovered strict weight / 39`. `cov@k` counts only the
first `k` selected items in each branch's own order.

### Findings

- **Re-ranking changes the selected set for this query.** On tasks A and B the
  20 FTS5 results already fit the budget, so re-ranking only reordered them.
  Here plain FTS5 fills the top 20 with short administrative turns, for example
  turns that only say the agent is waiting for a Tempo lookup. The re-ranker
  pulls substantive turns into the top 20 and raises coverage from 5.1 to 33.3.
  Part of the gain is more text: the re-ranked set holds 17,252 bytes against
  10,787.
- **The short query is the main weakness of plain FTS5.** Three words match
  many low-information turns, and BM25 length normalization favors them.
- **Recency packing fails on a long ticket.** KD-6945 has 545 eligible entries,
  and the newest are large (the largest is 17 KB). A 22,000-byte newest-first
  window holds 3 to 7 entries, all from the last two days before the freeze,
  and they do not contain the decision facts. This suits a short ticket and
  fails a ticket with a long tail of status updates.
- **The two journal packing rules differ by 12.8 points** on this task and by
  zero on tasks A and B. The choice of rule matters when entries are large.
- **Model-only order is not better than the engine order** here (20.5 against
  33.3 at all selected), because the set differs. The blend with recency,
  authority and impact signals kept two extra facts in the set.

### Failure-layer classification

| Finding | Failure layer |
|---|---|
| Plain FTS5 top 20 is dominated by short administrative turns | Ranking |
| Three-word query matches many low-information turns | Query formulation |
| Large newest entries consume the journal budget | Context assembly |
| Journal order by recency selects the last days of the ticket only | Context ordering |
| C07 exists only in the state capsule | Source coverage |

### Limits

- One query and one ticket. A difference of one fact is worth 5 to 8 points.
- The ledger was drafted by the benchmark author from one source and has not
  been adjudicated by the owner. The ticket mapping of the query is also the
  author's choice.
- Anchors are regular expressions, not human grading. Facts C01, C17 and C18
  are in the full ledger but not in the strict set.
- The re-ranked and plain FTS5 sets differ in size, so coverage per byte was
  not compared.
- The harness lives in `data/eval/ledger-run/` (`three_way.py`, `task_c.py`,
  `model_bench.py`, `dump_journal.groovy`), which is git-ignored. Results are in
  `three-way-run4.json` in the same folder.

Created using Anthropic Claude. Keep this internal until a person has reviewed
and verified it.

## Summary of KD-8130 benchmark (Task D, re-ranker variant)

This section adds a fourth task with the same branches and method as the
Trace in Konto section: direct `prompt.log`, plain FTS5, and FTS5 followed by
the cross-encoder re-ranker. The result is automated fact coverage only. The
query is a ticket-key question, which is the most common way a person asks for
the state of a ticket.

### Frozen inputs

Snapshot: `2026-11-03`

| Item | Frozen value |
|---|---|
| Query | `summary of KD-8130`, run verbatim |
| Ledger sources | `worklog/2026-10-30_KD-8130.log` (ACTION LOG through `2026-11-03 12:50` CET) and `ticket-state/kd8130.md` |
| FTS5 turn cutoff | Turn number 10467 or lower (all indexed turns at the snapshot; none concern this benchmark) |
| Journal cutoff | Entries before `2026-11-03T11:50Z` (the Jira comment 2105960 entry itself is excluded) |
| Journal parser | `lib/ChatLogParser.groovy` |
| Branch budget | 22,000 UTF-8 bytes |
| FTS5 snippet size | 1,050 characters, 20 results, archive filter `chat:turns` |
| Re-ranker | `mmarco-mMiniLMv2-L12-H384-v1` on MPS, depth 30, budget 1,200 ms, no degraded runs |

Time handling: the harness shifts `Z` timestamps by a fixed 2 hours and
compares them with a cutoff in the same frame. Poland has used CET (UTC+1)
since `2026-10-25`, so cutoffs for tasks C and D are accurate to about one
hour for entries without a `Z` suffix.

The journal branch is reported with three selection rules:

- **strict:** entries whose parsed ticket keys contain `KD-8130`, newest first,
  stop at the first entry that would exceed the budget (25 eligible entries);
- **greedy fill:** the same entries, skipping an entry that does not fit;
- **text match, greedy:** any entry whose text mentions `KD-8130` or `KD8130`
  (42 eligible entries). Seventeen entries mention the ticket without carrying
  the parsed ticket key.

### Fact ledger

All 20 facts were drafted by the benchmark author from the worklog. The strict
set holds 17 facts and 40 weight. A fact counts as recovered when its strict
anchor patterns all match the selected text.

| ID | Weight | Fact | Strict anchor |
|---|---:|---|---|
| D01 | 3 | Goal: prepare the environment to run `deleteInactiveUsersFromEmailList.js` (aaa-api, KD-7033), which deletes users listed in a CSV and writes a result report | script name |
| D02 | 3 | Scale: 425,539 hard-bounced accounts first, then 1,117,120 dropped | `425 539` |
| D03 | 3 | Acceptance criteria: runs in aaa-api, is not interrupted, result saved on the share and passed on, audit line in the infosec Kibana | Not in strict set |
| D04 | 3 | D1 resolved: option A, detached exec in the existing eap pod, flagged `karpenter.sh/do-not-disrupt` | `do-not-disrupt` |
| D05 | 3 | KD-7033 PR #732 blocks the run; prod release 2026-11-03; no script run on release day | `#732` |
| D06 | 3 | UAT dry-run (G1) over 3,949,912 lines: 3,939,946 would be deleted, 6,959 recent logins, 3,007 recent creations, 0 errors | `3 939 946` |
| D07 | 3 | G1 needed four attempts: query timeout, two OOMKills, then resume with `--skipLines`; result split over three files | `skiplines` |
| D08 | 3 | OOM cause: about 20 CronJobs exec into the same eap pod, bursts on even minutes against a 1 Gi limit | `even minute` |
| D09 | 3 | UAT force run (G2) completed on 2026-11-03 after five attempts: 3,939,947 deleted, 0 errors | `3 939 947` |
| D10 | 2 | First force attempt failed with `Timeout - table: aaa_users_bylaws - method: del` at batch size 3000; batch size 500 used afterwards | `aaa_users_bylaws` |
| D11 | 2 | UAT eap resources aligned with PROD through GitOps (limits 1536Mi, commit `10a6c0cd1`) | `10a6c0cd1` or `1536mi` |
| D12 | 2 | Audit lines (`userAudit`) go through fluent-bit to Firehose; they are emitted before commit, so they do not prove a deletion | `useraudit` |
| D13 | 2 | The result file goes to Bartkowiak, Andrzej, who notifies the other teams; transfer channel (D4) open | `bartkowiak` |
| D14 | 2 | D6 resolved: PROD starts on a PO request and is scheduled then | Not in strict set |
| D15 | 2 | Rerun semantics: resume with `--skipLines`, idempotent; SIGTERM is ignored, so stopping needs SIGKILL | `sigterm` |
| D16 | 2 | Input path `/net/rodo/rc/kontotvn/exported`; never place the input in `new_to_import` | `new_to_import` |
| D17 | 1 | Jira comment 2105960 posted with the UAT summary | `2105960` |
| D18 | 2 | ArgoCD application is auto-synced from an ApplicationSet, so a manual pause is reverted; freeze means no commits to the overlay | `applicationset` |
| D19 | 1 | Monitoring uses a local script `tmp/kd8130-monitor.sh`; no dedicated alert exists | `kd8130-monitor` |
| D20 | 3 | Current state: UAT G0 to G2 complete; PROD run (G3) waits for a date and a PO request | Not in strict set |

Anchor check, run before scoring: all 20 fact patterns match the worklog, and
the strict anchors match at most 0.3% of a random sample of 3,000 unrelated
turns. D02 appears in no turn, so no turn-based branch can recover it. The best
possible turn-based coverage is about 92.5%.

The state capsule `ticket-state/kd8130.md` matches only 8 of the 20 fact
patterns. It still describes the ticket as in PLAN with G1 and G2 pending and
has none of the UAT results (D06 to D12 and later). The capsule is stale
relative to the ACTION LOG in the same worklog.

### Retrieval measurements

| Branch | Selected | Selected bytes | Wall time |
|---|---|---:|---:|
| Direct `prompt.log`, strict packing | 6 entries of 25 eligible | 19,813 | 4.6 s |
| Direct `prompt.log`, greedy fill | 8 entries | 21,426 | 4.6 s |
| Direct `prompt.log`, text match, greedy | 8 entries of 42 eligible | 21,486 | 4.6 s |
| FTS5 | 20 snippets | 16,850 | 1.3 s |
| FTS5 + re-ranker, engine order | 20 snippets | 16,563 | 2.1 s |
| FTS5 + re-ranker, model-only order | 20 snippets | 16,152 | 2.1 s |

Timings follow the same caveats as in the Trace in Konto section.

### Weighted coverage

| Branch | cov@5 | cov@10 | cov@all | Facts recovered |
|---|---:|---:|---:|---|
| `prompt.log`, strict packing | 5.0 | 5.0 | **5.0** | D16 |
| `prompt.log`, greedy fill | 5.0 | 5.0 | **5.0** | D16 |
| `prompt.log`, text match, greedy | 42.5 | 47.5 | **47.5** | D01, D04, D07, D08, D10, D11, D12, D19 |
| FTS5 | 17.5 | 17.5 | **50.0** | D01, D04, D06, D07, D09, D10, D11, D17 |
| FTS5 + re-ranker, engine order | 17.5 | 32.5 | **57.5** | D01, D04, D06, D07, D08, D09, D10, D11, D17 |
| FTS5 + re-ranker, model-only order | 10.0 | 40.0 | **55.0** | D01, D06, D07, D08, D09, D10, D11, D12, D17 |

Coverage uses `100 × recovered strict weight / 40`.

### Findings

- **The parsed ticket key is a poor selector for the journal branch.** Matching
  only entries with a parsed `KD-8130` key gives 5.0. Matching entries that
  mention the ticket gives 47.5. Seventeen entries mention the ticket without
  a parsed key, including entries from 2026-11-03, and the text-match variant
  recovers the UAT run facts that the keyed selection misses. The same
  text-match variant gives the same result on tasks A and C, so only this task
  is affected.
- **Plain FTS5 does well on a ticket-key question.** It recovers 8 of 17 facts
  (50.0). I did not test why; the ticket key may match the turn paths and text
  of this ticket well.
- **The re-ranker adds 7.5 points and moves useful turns up.** The engine order
  gives 57.5 against 50.0, and cov@10 rises from 17.5 to 32.5. The model-only
  order reaches 40.0 at the top 10 but 55.0 over all 20. This is smaller than
  the gain on Trace in Konto because plain FTS5 already starts well.
- **The three best branches are close.** FTS5 + re-ranker (57.5), FTS5 (50.0)
  and the journal with text matching (47.5) differ by about one to two facts.
  They recover different facts: the journal recovers the audit and monitoring
  facts (D12, D19), FTS5 the totals (D06, D09, D17).
- **No branch recovers D02, D05, D13, D15 or D18**, and D16 and D19 are
  recovered only by one journal variant each. Several of these are decision and
  hand-over facts. I did not check where they sit in the turns.
- **The capsule is stale for a live ticket.** A branch that reads only the
  state capsule would recover 8 of 20 facts and report the ticket as pending.

### Failure-layer classification

| Finding | Failure layer |
|---|---|
| Some journal entries that mention the ticket carry no parsed ticket key | Parsing and ingestion |
| Ticket-key selection misses entries that only mention the ticket | Retrieval orchestration |
| State capsule lags the ACTION LOG | Source and state freshness |
| 20 snippets of 1,050 characters cannot hold decisions and hand-over | Context assembly |
| Plain FTS5 reaches the first facts at top 20 but not top 10 | Ranking |

### Limits

- One query and one ticket. A difference of one fact is worth 2.5 to 7.5 points.
- The ledger was drafted by the benchmark author from one worklog and has not
  been adjudicated by the owner. The facts and weights are the author's choice.
- Anchors are regular expressions, not human grading. Facts D03, D14 and D20
  are in the full ledger but not in the strict set.
- The FTS5 branch filters to `chat:turns`. A real ticket question also reaches
  worklog and capsule documents, which this task did not measure.
- Results are in `three-way-run6.json` in `data/eval/ledger-run/` (git-ignored),
  with `task_d.py` and the harness files named in the Trace in Konto section.

Created using Anthropic Claude. Keep this internal until a person has reviewed
and verified it.

## Score summary for tasks C and D

This section gives the timing-and-score summary for the Trace in Konto and
Summary of KD-8130 benchmarks in the format of the MongoDB metrics comparison,
with four branches: direct `prompt.log`, FTS5 top 20 over chat turns, indexed
worklogs, and FTS5 over all sources followed by the `mmarco` re-ranker.

Dimensions that need written answers and manual adjudication (freshness,
temporal correctness, contradictions, claim precision) were not measured and are
reported as `Not measured`. The overall weighted score defined above is
therefore not computed. A partial score is given instead.

### Method for the measured dimensions

- **Factual coverage:** weighted strict-fact coverage over all selected items:
  `100 × recovered strict weight / strict weight` (39 for task C, 40 for task D).
  Facts outside the strict set are not counted.
- **Precision:** retrieval precision only: useful items divided by selected
  items. An item is useful when it satisfies the anchors of at least one ledger
  fact. Claim precision needs written answers and was not measured.
- **Authority:** the tier scale defined above: 80 for structured worklog
  evidence and 70 for turn or journal evidence. The mixed all-source branch uses
  the mean over its items, with model-reasoning artifacts at 50.
- **Traceability:** 100 at item level, because every selected item carries a
  document path or journal line range. Claim-level provenance was not measured.
- **Partial score:**
  `(0.25 × coverage + 0.20 × precision + 0.10 × authority + 0.10 × traceability) / 0.65`.
  This renormalizes the four measured weights. It is not comparable with the
  overall scores of the SCTASK1544083 and MongoDB sections.
- **Journal variant:** task C uses greedy fill. Task D uses text matching with
  greedy fill. The parsed-ticket-key selection scores 5.0 on task D.
- **Indexed worklogs:** the engine restricted to `worklog:chunks`, top 20,
  1,050-character snippets, no re-ranker. The state capsule is not included.
- **FTS5 all + re-ranker:** no archive filter, `mmarco` on MPS, depth 30,
  budget 1,200 ms, no degraded runs; first 20 results. Chat turns obey the turn
  cutoff. Worklog and artifact documents have no time cutoff.

### Trace in Konto comparison (task C)

| Dimension | Direct prompt.log | FTS5 top 20 | Indexed worklogs | FTS5 all + mmarco |
|---|---|---|---|---|
| Result/selected count | 545 eligible / 7 complete entries | 20 snippets | 20 snippets | 20 snippets: 9 turns, 9 artifacts, 2 chunks |
| Selected bytes | 21,933 | 10,787 | 21,038 | 21,022 |
| Retrieval time | **4.7 s** including Groovy start-up and parsing | **1.1 s** including Java start-up | **0.9 s** including Java start-up | **1.7 s** including Java start-up and re-ranking |
| Factual coverage | **13/100**, no decision facts in the newest entries | **5/100**, short administrative turns dominate | **82/100**, the ledger was drafted from this source | **46/100**, nine artifacts take half the window |
| Precision (retrieval only) | **14/100**, 1 of 7 useful | **5/100**, 1 of 20 useful | **60/100**, 12 of 20 useful | **30/100**, 6 of 20 useful |
| Freshness | Not measured | Not measured | Not measured | Not measured |
| Temporal correctness | Not measured | Not measured | Not measured | Not measured |
| Authority | **70** | **70** | **80** | **62**, item mean |
| Traceability | **100** at item level | **100** at item level | **100** at item level | **100** at item level |
| Contradictions | Not measured | Not measured | Not measured | Not measured |
| Overall weighted score | Not computed | Not computed | Not computed | Not computed |
| Partial score (4 of 7 dimensions) | **35.5** | **29.7** | **77.7** | **51.9** |
| Strongest use | Not a fit for this query | None measured | Facts from a curated ticket record | Broad discovery across sources |
| Primary weakness | Large recent entries fill the budget | Short administrative turns win the ranking | Built from the same source as the ledger | Model-reasoning artifacts consume rank |
| Confidence | Medium: one query, one ledger | Medium: one query, one ledger | Low: ledger bias toward this source | Medium: one query, mixed sources |

### Summary of KD-8130 comparison (task D)

| Dimension | Direct prompt.log (text match) | FTS5 top 20 | Indexed worklogs | FTS5 all + mmarco |
|---|---|---|---|---|
| Result/selected count | 42 eligible / 8 complete entries | 20 snippets | 20 snippets | 20 snippets: 15 turns, 5 chunks |
| Selected bytes | 21,486 | 16,850 | 17,967 | 15,125 |
| Retrieval time | **4.8 s** including Groovy start-up and parsing | **1.4 s** including Java start-up | **1.2 s** including Java start-up | **1.5 s** including Java start-up and re-ranking |
| Factual coverage | **48/100**, audit and monitoring facts; misses totals | **50/100**, totals found; misses decisions | **48/100**, decisions and plan sections | **65/100**, turns plus chunks |
| Precision (retrieval only) | **75/100**, 6 of 8 useful | **55/100**, 11 of 20 useful | **40/100**, 8 of 20 useful | **50/100**, 10 of 20 useful |
| Freshness | Not measured | Not measured | Not measured | Not measured |
| Temporal correctness | Not measured | Not measured | Not measured | Not measured |
| Authority | **70** | **70** | **80** | **72.5**, item mean |
| Traceability | **100** at item level | **100** at item level | **100** at item level | **100** at item level |
| Contradictions | Not measured | Not measured | Not measured | Not measured |
| Overall weighted score | Not computed | Not computed | Not computed | Not computed |
| Partial score (4 of 7 dimensions) | **67.5** | **62.3** | **58.3** | **66.9** |
| Strongest use | Audit and monitoring facts from raw entries | Totals and ticket scope | Decisions and plan sections | Best coverage by combining turns and chunks |
| Primary weakness | The parsed ticket key misses 17 entries | Misses decisions and hand-over | Static sections, not the latest results | Half of the 20 results are not useful |
| Confidence | Medium: depends on the selection rule | Medium: one query, one ledger | Low: ledger drafted from this worklog | Medium: one query, mixed sources |

### Reading the two tables

- The indexed worklog branch leads on task C and trails on task D. Both
  ledgers were drafted from the worklog or capsule, so this branch has a
  built-in advantage that the table does not remove.
- FTS5 over all sources with the re-ranker has the highest coverage on task D
  (65) and a middle result on task C (46). On task C, nine of its 20 results
  are model-reasoning artifacts. The source mix matters more than the model on
  that task.
- The earlier chat-turn-only re-ranker run scored 33.3 on task C and 57.5 on
  task D, so removing the archive filter lowers task C and raises task D.
- The direct journal branch is the slowest on both tasks and depends on how
  entries are selected.
- Results are in `three-way-run8.json` in `data/eval/ledger-run/`
  (git-ignored).

Created using Anthropic Claude. Keep this internal until a person has reviewed
and verified it.

## Re-measurement on a frozen snapshot (2026-11-04)

This section re-measures the four tasks on a frozen copy of the databases and records two findings about the benchmark itself. The numbers below supersede the values for tasks C and D in the earlier sections, which could not be reproduced. The earlier sections stay unchanged.

### Setup

- **Snapshot:** `data/eval/snapshots/20261104` (git-ignored): `convo.db` 12,630 turns, `worklogs.db` 680 documents, `artifacts.db` 1,012 documents, copied with the SQLite backup API. The harness and the engine read it through `BENCH_DATA_DIR` and `--data-dir`. The 12 golden queries give identical output on the live data and on the snapshot. `prompt.log` is not part of the snapshot. The freeze cutoffs of each task still apply to it.
- **Determinism:** two runs of `three_way.py` (weight 12 and weight 5, 5 repeats, budget 1.2 s) and of `model_bench.py` on the snapshot gave 0 differences in every coverage field.
- **Default weight:** the engine default is now 12 (D-34). The "weight 5" column uses `--rerank-weight 5`.
- **Method of the scores:** as in the score summary for tasks C and D: weighted strict-fact coverage over all selected items, retrieval precision (items that satisfy a ledger fact), authority tier, traceability 100, and the partial score `(0.25 × coverage + 0.20 × precision + 0.10 × authority + 0.10 × traceability) / 0.65`. Freshness, temporal correctness and contradictions were not measured.

### Findings about the benchmark

1. **Why the numbers moved between 2026-11-03 and 2026-11-04.** Each archive is replaced as a whole when it is ingested (one `ingestion_manifest` row per archive: `worklog:chunks` at 08:37, `worklog:state` at 08:50 and `chat:turns` at 09:17 on 2026-11-04), so document IDs change, and `chat:turns` grew from 10,159 to 12,630 documents during the day. This explains the drift of tasks C and D. Tasks A and B reproduce.
2. **Candidate crowding.** The harness removes turns after a task's freeze point only at the end, after the re-rank has already used its 30-document window. In the morning run, tasks C and D had 3 and 1 post-freeze turns in the window. On the snapshot they have 17 and 16, because this session's own discussion of "Trace in Konto" and "KD-8130" is now in the corpus. Tasks A and B already had 11 to 27 of 30 slots taken by later turns in both runs. The benchmark therefore loses coverage as the corpus grows, and a result measured on a less crowded corpus does not carry over. A per-task corpus cut at the freeze point is needed (amendment A1 in the tranche 2 plan).

### Mean coverage at the top 10 (10 queries, chat turns, engine order)

| System | This morning (live data) | Snapshot, 2026-11-04 |
|---|---:|---:|
| lexical (no re-rank) | 33.2 | 33.7 |
| mmarco, weight 5 | 40.3 | 36.4 |
| mmarco, weight 12 (default) | 44.0 | 36.5 |

On the snapshot, weight 12 against weight 5: +0.11, interval [-1.53, 1.86], 1 win, 1 loss, 8 ties; per task A 0.0, B +1.55, C -5.1, D 0.0. Weight 12 against lexical: +2.77, interval [-6.43, 10.97], 5 wins, 3 losses, 2 ties. **The gain of weight 12 found on the morning data is not reproduced on this snapshot.** See D-35.

### SCTASK1544083 (task A)

| Dimension | Direct prompt.log | FTS5 top 20 | Indexed worklogs | FTS5 all + mmarco, weight 12 | FTS5 all + mmarco, weight 5 |
|---|---|---|---|---|---|
| Selected / bytes | 18 / 21,813 | 20 / 15,369 | 20 / 20,802 | 20 / 21,056 | 20 / 21,056 |
| Whole-command time | 6.96 s (journal parse) | cold 0.91 s, cached 0.91 s | cold 0.75 s, cached 0.74 s | cold 1.76 s, cached 1.04 s | cold 2.54 s, cached 1.19 s |
| Factual coverage, top 10 / all | 41.7 / **63.9** | 33.3 / **61.1** | 86.1 / **86.1** | 63.9 / **72.2** | 63.9 / **72.2** |
| Precision (retrieval only) | 83 (15 of 18) | 60 (12 of 20) | 75 (15 of 20) | 40 (8 of 20) | 40 (8 of 20) |
| Authority | 70.0 | 70.0 | 80.0 | 60.5 | 60.5 |
| Partial score (4 of 7 dimensions) | **76.3** | **68.1** | **83.9** | **64.8** | **64.8** |
| Freshness, temporal correctness, contradictions | Not measured | Not measured | Not measured | Not measured | Not measured |

### MongoDB metrics (task B)

| Dimension | Direct prompt.log | FTS5 top 20 | Indexed worklogs | FTS5 all + mmarco, weight 12 | FTS5 all + mmarco, weight 5 |
|---|---|---|---|---|---|
| Selected / bytes | 20 / 21,772 | 20 / 17,965 | 20 / 20,084 | 20 / 21,070 | 20 / 21,068 |
| Whole-command time | 6.97 s (journal parse) | cold 0.98 s, cached 0.87 s | cold 0.74 s, cached 0.73 s | cold 1.91 s, cached 1.06 s | cold 1.9 s, cached 1.13 s |
| Factual coverage, top 10 / all | 79.2 / **93.8** | 64.6 / **77.1** | 50.0 / **66.7** | 47.9 / **64.6** | 56.2 / **64.6** |
| Precision (retrieval only) | 95 (19 of 20) | 85 (17 of 20) | 85 (17 of 20) | 50 (10 of 20) | 50 (10 of 20) |
| Authority | 70.0 | 70.0 | 80.0 | 57.5 | 57.5 |
| Partial score (4 of 7 dimensions) | **91.5** | **82.0** | **79.5** | **64.5** | **64.5** |
| Freshness, temporal correctness, contradictions | Not measured | Not measured | Not measured | Not measured | Not measured |

### Trace in Konto (KD-6945) (task C)

| Dimension | Direct prompt.log | FTS5 top 20 | Indexed worklogs | FTS5 all + mmarco, weight 12 | FTS5 all + mmarco, weight 5 |
|---|---|---|---|---|---|
| Selected / bytes | 7 / 21,933 | 20 / 13,254 | 20 / 21,038 | 20 / 18,960 | 20 / 20,330 |
| Whole-command time | 6.97 s (journal parse) | cold 0.88 s, cached 0.85 s | cold 0.67 s, cached 0.65 s | cold 1.65 s, cached 1.01 s | cold 1.73 s, cached 0.96 s |
| Factual coverage, top 10 / all | 12.8 / **12.8** | 5.1 / **12.8** | 69.2 / **82.1** | 17.9 / **46.2** | 41.0 / **46.2** |
| Precision (retrieval only) | 14 (1 of 7) | 10 (2 of 20) | 60 (12 of 20) | 20 (4 of 20) | 25 (5 of 20) |
| Authority | 70.0 | 70.0 | 80.0 | 59.0 | 57.0 |
| Partial score (4 of 7 dimensions) | **35.4** | **34.2** | **77.7** | **48.4** | **49.6** |
| Freshness, temporal correctness, contradictions | Not measured | Not measured | Not measured | Not measured | Not measured |

### Summary of KD-8130 (task D)

| Dimension | Direct prompt.log | FTS5 top 20 | Indexed worklogs | FTS5 all + mmarco, weight 12 | FTS5 all + mmarco, weight 5 |
|---|---|---|---|---|---|
| Selected / bytes | 8 / 21,486 | 20 / 16,850 | 20 / 17,967 | 20 / 15,264 | 20 / 15,264 |
| Whole-command time | 7.0 s (journal parse) | cold 1.19 s, cached 1.13 s | cold 0.81 s, cached 0.87 s | cold 1.36 s, cached 1.13 s | cold 1.27 s, cached 1.06 s |
| Factual coverage, top 10 / all | 47.5 / **47.5** | 17.5 / **50.0** | 47.5 / **47.5** | 40.0 / **57.5** | 45.0 / **57.5** |
| Precision (retrieval only) | 75 (6 of 8) | 55 (11 of 20) | 40 (8 of 20) | 55 (11 of 20) | 55 (11 of 20) |
| Authority | 70.0 | 70.0 | 80.0 | 73.0 | 73.0 |
| Partial score (4 of 7 dimensions) | **67.5** | **62.3** | **58.3** | **65.7** | **65.7** |
| Freshness, temporal correctness, contradictions | Not measured | Not measured | Not measured | Not measured | Not measured |

### Latency, cold and cached (whole command, weight 12, 4 tasks)

The service caches scores per model, query and passage (14 days). Each harness run starts with an empty cache, so the first call is uncached and the repeats are cache hits.

| Branch | Cold (first call) | Cached p50 | Degraded runs |
|---|---:|---:|---:|
| FTS5 | 0.88 to 1.19 s | 0.85 to 1.13 s | 0 of 20 |
| FTS5 + mmarco (chat turns) | 1.73 to 2.12 s | 0.91 to 1.20 s | 0 of 20 |
| Indexed worklogs | 0.67 to 0.81 s | 0.65 to 0.87 s | 0 of 20 |
| FTS5 all sources + mmarco | 1.36 to 1.91 s | 1.01 to 1.13 s | 0 of 20 |

The re-ranker adds about 0.8 to 1.0 s to a cold whole-command time, which includes about 0.7 s of Java start-up. No run was degraded at the 1.2 s budget.

### Reading this section

- No branch wins on every task. The all-source re-ranker leads on task A and task D, and the indexed worklogs lead on task C (with the usual caveat: the ledgers were drafted from the worklog or capsule). Direct `prompt.log` is best on task B and is the slowest.
- On the all-source branch, weight 12 gives a lower top-10 coverage than weight 5 on tasks B, C and D (47.9 against 56.2, 17.9 against 41.0, 40.0 against 45.0) and the same on task A. Over all 20 results the coverage is identical for all four tasks.
- Results are in `three-way-t2-w12-a.json`, `three-way-t2-w5-a.json` and `model-bench-t2-a.json` in `data/eval/ledger-run/` (git-ignored).

## Frozen per-task corpus (2026-11-05)

Each task has its own copy of snapshot `20261104` (chat turns after the cutoff and artifacts after the freeze instant deleted). Details, tables and decision rules: section 12 of [rerank_enhacement.md](rerank_enhacement.md).

| System (mean cov@10, chat turns) | Unfrozen | Frozen |
|---|---|---|
| lexical | 33.7 | 33.6 |
| mmarco, weight 12 | 36.5 | 43.3 |
| mmarco, weight 5 | 36.4 | 41.3 |

Frozen per task, chat-turn branch, cov@10 (fts5, then fts5 + re-rank at weight 12):

| Task | fts5 | fts5 + rerank, weight 12 | fts5 + rerank, weight 5 |
|---|---|---|---|
| A | 33.3 | 55.6 | 55.6 |
| B | 75.0 | 75.0 | 75.0 |
| C | 0.0 | 28.2 | 12.8 |
| D | 17.5 | 45.0 | 32.5 |

- The earlier statement that weight 12 is lower than weight 5 on the all-source branch for tasks B, C and D does not hold on frozen corpora: weight 12 gives 60.4, 17.9 and 45.0 against 52.1, 17.9 and 40.0, and 86.1 on A for both.
- Weight 12 stays the recommended default (D-35, rule (a) met). No neighbor mode wins; `adjacent` stays.
- Indexed worklogs and all-source branches are not fully frozen (worklog documents carry no date).


Created using Anthropic Claude. Keep this internal until a person has reviewed and verified it. The tables contain internal ticket content: share them only with people allowed to see it.
