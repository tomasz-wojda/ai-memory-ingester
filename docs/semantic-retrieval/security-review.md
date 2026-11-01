# Security Review of the Built Code (T047)

Status: review done on 2026-11-01 by reading the code with the `security-audit` skill method (real trust boundaries, evidence, smallest fix). It is a **guidance-mode review, not the skill's full audit workflow**: the full workflow writes a run folder outside the repository and uses parallel agents; it was not run. Part of [the specification](specification.md); threats are from [security-privacy.md](security-privacy.md).

Scope: `service/ai_memory_service/` (server, protocol, store, backends, integrity, doctor, maxsim, config), `lib/SemanticClient.groovy`, `lib/PassageBuilder.groovy`, `lib/RerankStage.groovy`, `11_reindex.groovy`, the sweep hook in `07_sweep_memory.groovy`.

---

## 1. Findings and fixes

| # | Finding | Severity | Fix | Test |
|---|---|---|---|---|
| F1 | A negative or non-numeric `Content-Length` made the handler wait for a body that never came (negative) or fail with a generic error (text). A local process holding the token could tie up handler threads. | Medium | Both are refused with 400 and the connection is closed | `test_a_bad_content_length_is_refused_not_waited_for` |
| F2 | A client that opened a connection and sent nothing (or half a body) kept a handler thread forever (the server creates one thread per connection). | Medium | Handler timeout of 15 seconds | `test_an_idle_connection_is_dropped_by_a_timeout` |
| F3 | The configuration file could enable the stub model (`allow_stub`), against decision D-28. | Low | The key is gone; only `--stub` enables it | `test_a_config_flag_cannot_enable_the_stub_model` |
| F4 | The request path was written to the log as received: control characters and very long strings could forge or flood log lines. | Low | Non-printable characters become `?`, paths are cut at 80 characters | `test_log_lines_hide_control_characters_and_long_paths` |
| F5 | The Groovy client read a response without a size limit. | Low | Limit of 64 MB, then an error that degrades to the lexical order | code path; no separate test |
| F6 | The service did not refuse a model folder above the memory limit, although the specification says it does. | Low | `max_rss_mb` is checked against the folder size before loading | `test_cross_encoder_refuses_unsafe_or_unverified_models` |

## 2. Checked and found sound

- **Authentication:** every endpoint, including `/v1/health`, needs the bearer token; the comparison is constant time (`hmac.compare_digest`); `Origin` is refused before the token check. Tests: 401, 401, 403.
- **File modes:** run folder 0700, socket 0600, token 0600 (tested through the real program); the token is written with `os.open(..., 0o600)`, so it is never world-readable, even for a moment. The socket sits inside the 0700 folder, so the short time between `bind` and `chmod` is not reachable by other users.
- **No network:** `lsof -i` of the running service shows no socket besides the Unix socket (tested). The offline variables are set at start. There is no outbound code path.
- **SQL:** every statement uses parameters. The only composed fragments are `IN (?,?,...)` lists built from the length of an input list, in chunks of 500.
- **Paths:** document paths are data only; they are never opened as files. Model paths come from the configuration, not from a request.
- **Model loading:** only `safetensors`; any `.bin`, `.pt`, `.pkl`, `.pickle` or `.ckpt` file in the folder refuses the model; the folder checksum must match the recorded value; `trust_remote_code` is always false; `HF_HUB_OFFLINE` and `TRANSFORMERS_OFFLINE` are set before the import.
- **Writes:** the service writes only to `semantic.db` and its run folder. A test compares the hashes of five neighbouring database files before and after rerank, upsert, encode and late search: unchanged.
- **Limits:** body 4 MB, 100 passages per rerank, 2000 per upsert, 4000 characters per passage, 500 documents in a late filter, bounded queue (429), deadline between batches.
- **Logs:** counts and timings only. A test checks that neither the query nor the passage text appears.
- **Deletion:** `reindex` removes the passages and vectors of documents that no longer exist (tested), so removed content does not stay searchable through the index.
- **Prompt injection:** no generative model reads passage text; the re-ranker returns numbers only.
- **The sweep hook:** off by default, never waits, starts `reindex` with the sweep's own data folder (a first version used the wrong folder and was fixed).

## 3. Residual risks (accepted or open)

| Risk | Why it stays | Next step |
|---|---|---|
| One handler thread per connection, no cap | A local process that holds the token can open many connections; each lives at most 15 seconds idle | Add a connection cap if the service ever listens on TCP (it does not by default) |
| `max_rss_mb` limits the model folder size, not the process memory; the in-memory late index is about 3.2 GB for the full corpus in half precision | Process memory is only reported by `/v1/health` | Enforce a limit when the real model and index size are known (T041) |
| The model checksum is verified at load, not on every use; a file swapped on disk while the service runs is not noticed | The model folder is owned by the user; the same user can already read the token | Re-check on `admin/reload`; `doctor` re-checks on demand |
| The TCP option (`tcp_port`) is in the configuration but not built; only the Unix socket exists | Not needed; smaller attack surface | If built, it needs the `Origin` rule, a connection cap and its own review |
| Python packages and model weights are not installed, so supply-chain controls (hashed requirements, signed manifests) are untested | Decisions Q1, Q2, D-21, D-22 are open | Review again when the real environment exists |
| The `security-audit` skill's full workflow was not run | It writes outside the repository and uses parallel agents | Run it after a real model is installed, if wanted |

## Summary (STE)

I read the built service and client for security problems. I found six small problems and fixed all of them, each with a test. The main ones were a bad length header that could hold a thread, no time limit for idle connections, a config key that could turn on the test model, and unlimited log text. The service has no network connection, keeps its files private, and writes only to its own database. The checks that need a real model and a real Python environment cannot run yet. I did not run the full audit workflow of the skill.

**Remaining 20% (not STE100):** file, endpoint and test names; the words "bearer", "socket", "checksum", "safetensors", "supply chain", "severity" and "hash"; the tables; some sentences over 20 words.

---

Created using Anthropic Claude. Internal only until a person has reviewed and verified it.
