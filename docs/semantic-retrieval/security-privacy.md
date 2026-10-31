# Security, Privacy and Licence Review: Semantic Retrieval Upgrade

Status: design-time review in guidance mode (the `security-audit` skill method: real trust boundaries, evidence, smallest fix). It is not a full audit; the full audit runs on the built code (task T047). Part of [the specification](specification.md).

---

## 1. What is protected

| Asset | Where | Why it matters |
|---|---|---|
| Conversation turns, worklog chunks, state capsules | `convo.db`, `worklogs.db`, `artifacts.db`, `dross.db`, `worklog-chat/` | Internal and confidential business information; may name WBD staff, vendors and tickets (personal data) |
| The journal | `journal.db` | Append-only record of every turn; never searched, never touched by this feature |
| Derived data: passages, cached scores, vectors | `data/semantic.db` | A vector can leak content. It is derived personal data when the source holds personal data |
| Model files | `~/.local/share/ai-memory-service/models` | Code-like input: a poisoned model or loader can run code |
| Service token and socket | `.ai-worklog/run/` | Gate to the service |
| Credentials | `**/credentials`, `**/*.properties`, `**/cookie` | Never read by this feature |

## 2. Trust boundaries

1. **Owner session or script to the service.** Both run as the same user on one machine. The socket and token stop other local users and other processes of the same user that do not hold the token.
2. **Service to model files and Python packages.** Supply-chain boundary: the packages and weights come from the internet once, then are frozen.
3. **Service to the existing databases.** The service holds no write path to them (FR-013). Only the Groovy side reads them and sends passage text.
4. **Machine to the network.** The only crossing is the one-time download of packages and models, done by the owner with the network on and the service off.

## 3. Threats and controls

Severity uses: High = could expose or alter memory content or run code; Medium = could degrade results or leak metadata; Low = nuisance.

| # | Threat | Sev. | Control | Test |
|---|---|---|---|---|
| S1 | Another local process calls the service and reads or scores text | Medium | Unix socket mode 0600 in a 0700 directory; bearer token mode 0600; TCP off by default and loopback only | Socket and token mode test; call without a token returns 401 |
| S2 | A web page in the owner's browser calls the TCP port (DNS rebinding, CSRF) | Medium | TCP off by default; when on, requests with an `Origin` header get 403; token required | Request with `Origin` returns 403 |
| S3 | Poisoned package or model runs code on load (pickle, `trust_remote_code`) | High | `trust_remote_code` always off; only `safetensors` weights accepted; packages pinned by version and hash; models verified by SHA-256 against a recorded manifest; `doctor` re-checks | Loader refuses a model with a wrong checksum or a `.bin` pickle |
| S4 | The service sends text out (telemetry, model download at run time) | High | `HF_HUB_OFFLINE=1`, `TRANSFORMERS_OFFLINE=1`, `HF_HUB_DISABLE_TELEMETRY=1`, `DO_NOT_TRACK=1`; no outbound code path; a test fails on any outbound socket | Socket-opening test under a blocked network |
| S5 | Query or passage text appears in logs | Medium | Logs hold counts and times only | Log scan test after a run |
| S6 | Oversized or malformed input exhausts memory or CPU | Medium | Body limit 4 MB; 100 passages; 4,000 characters each; bounded queue with `429`; per-call deadline; resident memory limit with worker restart | Contract tests for each limit |
| S7 | A stale or foreign `semantic.db` is trusted | Medium | `index_manifest` pins model id, revision and parameters; mismatch returns `index_stale` | Manifest mismatch test |
| S8 | Removed or excluded content stays searchable through passages, cache or vectors | High | Deletion path for `egest`, purge and `MemoryExclusion`; `reindex --verify` finds orphans; I11 | Exclusion test; orphan test |
| S9 | Prompt injection in memory text steers a later model | Low in this design | The re-ranker only returns numbers. No generative model reads passage text in Phases 0 to 2. A later query-expansion model (Phase 3) needs its own review | Design rule; review at Phase 3 |
| S10 | The service writes into the memory databases | High | No write handle to them; contract test compares file hashes before and after | Hash test |
| S11 | Token or socket left behind after a crash | Low | `serve` replaces a stale socket after checking that no process answers; token rewritten on every start | Crash-restart test |
| S12 | Gold set with real tickets committed to git | Medium | `data/eval/` ignored by git; only metrics without text are committed; synthetic fixtures are public | Ignore-rule check in the checklist |

## 4. Privacy

1. **Scope.** The memory contains internal business information and, in places, personal information of WBD personnel, vendors or consumers. Processing stays on the owner's machine. The licensed AI tool policy allows internal business content; personal information needs Privacy Legal approval.
2. **Derived data.** Passages, cached scores and vectors are derived from the memory. They follow the same retention and deletion as the source (S8).
3. **No data leaves the machine** at query or index time (S4).
4. **Gold set.** It may contain personal data. It is not committed, and only the owner and the sessions the owner starts may read it.
5. **If the memory is ever shared** with another person or system, the derived index must not travel with it unless the same approval covers it.
6. **Flag for the owner.** If the memory holds personal data of people other than the owner, confirm Privacy Legal approval is in place before Phase 2 (full-corpus vectors). This document does not decide that.

## 5. Licences and third-party material (flag for IP Legal)

Model weights and packages are third-party material. Open licences usually allow internal use, but the licence of each file must be read and recorded. **All model licences below are unverified**, because the fetch of every model card timed out on 2026-10-31. Package licences are from package pages fetched the same day.

| Item | Licence | Status |
|---|---|---|
| sentence-transformers 6.2.0 | Apache-2.0 | Verified (PyPI page) |
| torch 2.15.0 | Apache-2.0 and others (BSD-3-Clause) | Verified (PyPI page) |
| onnxruntime 1.31.1 (Python and Java) | MIT | Verified |
| FlagEmbedding 1.4.2 | MIT | Verified |
| PyLate | MIT | Verified (GitHub page); version unverified |
| RAGatouille 0.0.9 | Apache-2.0 | Verified (GitHub page); lists Python up to 3.11 |
| sqlite-vec 0.1.9 | MIT or Apache-2.0 | Verified |
| `BAAI/bge-reranker-v2-m3` | not checked | **Unverified: check** |
| `BAAI/bge-m3` | not checked | **Unverified: check** |
| `cross-encoder/ms-marco-MiniLM-L-6-v2` | not checked | **Unverified: check** |
| `cross-encoder/mmarco-mMiniLMv2-L12-H384-v1` | not checked | **Unverified: check** |
| `jinaai/jina-colbert-v2`, `jinaai/jina-reranker-v2-base-multilingual` | not checked; may be non-commercial | **Unverified: do not use before checking; exclude if non-commercial** |
| `colbert-ir/colbertv2.0`, `answerdotai/answerai-colbert-small-v1` | not checked | **Unverified: check** |
| `Qwen/Qwen3-Reranker-0.6B` | not checked | **Unverified: check** |

Rules:
1. No model is downloaded before its licence, size, languages and `trust_remote_code` need are read on the model card and written into this table with the date and the card revision.
2. A model with a non-commercial or research-only licence is excluded.
3. Training data of a model is not reviewed here; the owner decides if that matters for internal use.
4. The weights are not redistributed. They live in the user's home directory, not in a repository.
5. The mapped skills used for this method keep their MIT notices in `repos/ai-worklog-scaleup/docs/resources/skills/`. No file of those skills was edited for this work.

## 6. Supply chain procedure

1. Create the virtual environment from a pinned, supported Python (not the 3.15 release candidate that is the default today).
2. Install from a requirements file with `--require-hashes`. Record the hashes in the repository.
3. Download each model once on a network-connected machine state, with the service stopped. Record the revision and the SHA-256 of every file in `models.manifest.json`.
4. Set the offline variables for the service and for `doctor`.
5. `memory doctor` verifies: Python version, installed versions and hashes, model checksums, socket modes, offline flags, free disk. It is read-only.
6. Updates are a deliberate act: a new manifest, a new run of the evaluation, a decision-log entry.

## 7. Review checklist for the built code (task T047)

- [ ] No outbound socket in a network-blocked run
- [ ] Socket 0600, directory 0700, token 0600, token required
- [ ] `Origin` refused
- [ ] `trust_remote_code` is never true; only `safetensors` loads
- [ ] Hashes and checksums verified by `doctor`
- [ ] No text in logs
- [ ] Input limits enforced
- [ ] No write to existing databases (hash test)
- [ ] Deletion path removes passages, cache and vectors of excluded documents
- [ ] Gold set path ignored by git

## Summary (STE)

The service holds no write path to the memory, keeps no text in its logs, and sends nothing out. A private socket and a token protect it. Models load only from a local folder with checked hashes, and code from a model is never run. Passages and vectors count as copies of personal data, so they are deleted when the source is deleted. Every model licence is still unchecked, and no model is downloaded until the owner has read it.

**Remaining 20% (not STE100):** the words "socket", "token", "telemetry", "pickle", "safetensors", "checksum", "trust boundary", "supply chain" and "licence" terms; package, model and file names; the tables; some sentences over 20 words.

---

Created using Anthropic Claude. Internal only until a person has reviewed and verified it. Third-party model licences need review by IP Legal before use.
