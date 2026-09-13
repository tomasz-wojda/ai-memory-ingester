# Measured Evidence: Worklog-Aware Memory Upgrade

Companion to the [upgrade specification](worklog-aware-memory-upgrade-specification.md) and [implementation checklist](worklog-aware-memory-upgrade-checklist.md).

Status: planning baseline measured on 2026-09-13.

---

## 1. Purpose

This document records the production observations that motivate the worklog-aware memory upgrade. The acceptance criteria in the specification are derived from these measurements rather than from an assumed retrieval model.

## 2. KD-6999 source inventory

The conversation database contains 94 complete KD-6999 turn documents. Together they occupy 148,646 bytes. The largest individual turn is 6,823 bytes, and their global sequence values span 1221 through 2366.

The separate authoritative worklog is `worklog/2026-07-29_KD-6999.log`. It contains 2,451 lines and 159,293 bytes.

No KD-6999 document exists in `artifacts.db`. The worklog itself is not indexed as a complete document or as section documents. Thirty-two turns mention the worklog filename, but references to a source do not provide the source content.

## 3. Twenty-result ticket retrieval test

The production query used the ticket facet `file_path:kd6999` against the `worklog-chat` dataset with a limit of twenty.

The result set contained:

- Fourteen execute-mode turns.
- Three turns without a mode.
- Two plan-mode turns.
- One innovate-mode turn.
- Two high-impact turns.
- Eighteen turns with no recorded impact.

All twenty previews ended within YAML front matter. None exposed the user request or assistant response, so the preview layer provided no evidence for selecting which full documents to open.

Because the query contained only a path facet, BM25 had no semantic content term to rank. Shorter documents were favoured, and reciprocal-rank fusion preserved that ordering. The result set therefore represented compact ticket-tagged turns rather than the most current or authoritative ticket information.

## 4. Complete-turn inspection

Representative complete turns recovered useful operational facts:

- The UAT and PROD scheduling work was in `TVN-eks_konto-eks_konto`.
- Separate UAT and PROD scheduling branches existed.
- A revised PROD plan introduced deterministic latency and query-plan gates after measured `countDocuments` timeouts.
- Jenkins PR 128 addressed overlapping retention scrapes.
- Jenkins build 8 produced image 24-8.
- EKS PRs 223 and 224 participated in the deployment sequence.
- Least-privilege credential and image-update branches were pushed.

These facts were valid historical evidence, but they did not establish the final ticket state. Several were superseded by later delivery.

## 5. Authoritative worklog comparison

The worklog supplied facts absent from the first twenty turn results:

- The ticket monitors physical DocumentDB collections `accessTokens`, `refreshTokens`, and `sessions`.
- Reads must use DocumentDB reader endpoints and secondary-preferred behaviour.
- Option A, a dedicated exporter sending OTLP metrics with `system="konto-retention"`, was locked as the solution.
- UAT passed using image 24-9 and the 08:00 every-four-hours Europe/Warsaw schedule.
- The worklog recorded measured collection counts, query durations, health checks, and scrape status.
- Controlled PROD reader queries completed without throttling or a required kill operation.
- PR 227 was open and approved.
- PROD was not deployed.
- Promotion of the required commits from `develop` to `master` was the current deployment gate.
- The next action was to merge, promote, discover the kontoprod application, sync kontoprod only, and perform targeted runtime verification.

The turn-only answer was therefore partly stale: it highlighted image 24-8 and PRs 223 and 224, while the authoritative current state had advanced to image 24-9 and PR 227.

## 6. What the current system does well

Turn materialization is lossless at the document level. The complete user and assistant text is stored, and selected turns can be opened directly without querying SQLite again.

Ticket facets successfully identify a broad conversation set. KD-6999 retrieval found 94 related turns across multiple sessions and dates.

The append-only journal remains a complete recovery source for conversational history, and deterministic sequence ordering provides reliable recency.

## 7. What the current system does not solve

A ticket facet is a filter, not a relevance signal. Applying BM25 to the facet alone rewards document shape instead of ticket importance.

Impact labels detect change-oriented entries but do not identify the authoritative current state.

The preview implementation spends its budget on front matter and prevents evidence-based document selection.

Conversation turns preserve historical progression but cannot reliably distinguish a superseded statement from the current ticket truth.

The authoritative worklog is outside the database, so an agent must already know to discover and read it.

The incremental chatlog sweep materializes files and refreshes the digest, but database synchronization is not represented as one guaranteed session-start operation.

## 8. Derived requirements

The upgrade must preserve the journal and turn corpus while adding worklogs as a distinct authoritative source.

Worklogs must be divided into coherent, lossless sections rather than indexed as one 159 KB document, because whole-file BM25 would suffer severe length dilution.

Ticket retrieval must reserve capacity for current state, authoritative sections, recent turns, high-impact turns, and semantic matches instead of producing one undifferentiated rank.

Bare ticket keys must be normalized before FTS parsing so hyphens cannot become FTS operators.

Previews must be generated from document bodies and semantic match locations rather than from YAML front matter.

Current-state resolution must be extractive and provenance-preserving. Generated state may select and order authoritative text but must not invent a summary that can become a new unsupported source of truth.

The session-start path must synchronize materialized files, databases, datasets, and the resume digest through one command.

## 9. Production acceptance case

KD-6999 is the primary production acceptance case because it contains long research, multiple decisions, implementation reversals, UAT delivery, PROD preparation, superseded image versions, several pull requests, and a clear current-status section.

A successful twenty-result retrieval must expose the ticket purpose, selected architecture, physical collection names, reader-only requirement, UAT result, current image, current PR, deployment status, current blocker, and next action.

Current worklog facts must outrank stale turns without deleting or hiding those historical turns.
