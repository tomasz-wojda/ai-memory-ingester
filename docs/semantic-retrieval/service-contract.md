# Contract: Memory Service, CLI and Data

Status: proposal. Part of [the specification](specification.md). Version of this contract: `1`.

All JSON is UTF-8. Field names are lower snake case. New fields may be added in a minor version; a client ignores unknown fields. A change that removes or renames a field raises the major version (the path prefix `/v1`).

---

## 1. Transport and security

| Item | Rule |
|---|---|
| Primary transport | Unix domain socket `.ai-worklog/run/memory-service.sock` under the workspace, file mode `0600`, directory mode `0700` |
| Optional transport | TCP `127.0.0.1:8777` (configurable). No other interface. Off by default. |
| Protocol | HTTP/1.1 with JSON bodies |
| Auth | A random token in `.ai-worklog/run/memory-service.token` (mode `0600`), sent as `Authorization: Bearer <token>`. The token is created at `serve` start and removed at stop. Never logged. |
| Origin checks | A request with an `Origin` header is refused (`403`). The client is not a browser. |
| Size limits | Request body at most 4 MB; at most 100 passages per call; each passage at most 4,000 characters |
| Network | The service opens no outbound connection. Environment: `HF_HUB_OFFLINE=1`, `TRANSFORMERS_OFFLINE=1`, `HF_HUB_DISABLE_TELEMETRY=1`, `DO_NOT_TRACK=1` |
| Logging | One line per call: time, endpoint, status, counts, elapsed. Query and passage text are never logged. |

## 2. Configuration

File `.ai-worklog/memory-service.json` (created by `ai-worklog memory serve --init`). Defaults shown.

```json
{
  "contract_version": 1,
  "socket": ".ai-worklog/run/memory-service.sock",
  "tcp_port": 0,
  "python": "~/.local/share/ai-memory-service/venv/bin/python",
  "model_cache": "~/.local/share/ai-memory-service/models",
  "rerank_model": "small-multilingual",
  "models": {
    "small-multilingual": { "path": "models/<checksum-verified-folder>", "sha256": "<manifest sha256>", "max_tokens": 256, "device": "auto" }
  },
  "max_rss_mb": 4096,
  "queue_size": 4,
  "batch_size": 16,
  "default_deadline_ms": 1200,
  "rerank_depth": 30,
  "rerank_min_scored": 10,
  "relax_min_results": 10,
  "score_cache_days": 14,
  "index_archives": ["chat:turns", "worklog:chunks", "worklog:state"]
}
```

Rules: a model entry needs a checksum recorded in [security-privacy.md](security-privacy.md); the service refuses a model whose checksum differs. `device` is `auto`, `cpu` or `mps`; the chosen device is part of every response.

## 3. `POST /v1/rerank`

Scores passages for one query.

Request:

```json
{
  "contract_version": 1,
  "query": "what did we decide about the traefik attribute",
  "query_language": "en",
  "model": "small-multilingual",
  "deadline_ms": 1200,
  "passages": [
    { "id": "p-91a2", "doc": "convo.db:chat:turns:kd6945/d20261029/execute/impact-high/turn-000123.md", "text": "ticket=KD-6945 date=2026-10-29 section=Assistant\n..." }
  ]
}
```

Response `200`:

```json
{
  "contract_version": 1,
  "model": "small-multilingual",
  "model_revision": "<revision>",
  "device": "mps",
  "scored": 28,
  "skipped": 2,
  "elapsed_ms": 640,
  "results": [
    { "id": "p-91a2", "score": 0.9132, "status": "scored" },
    { "id": "p-77c0", "score": null, "status": "skipped_deadline" }
  ]
}
```

Rules:
1. `results` keeps the order of the request. A score is a float in the model's own range, rounded to four decimals; callers must not compare scores of different models.
2. The service scores in request order and checks the deadline between batches. Passages it did not reach have `status` `skipped_deadline` and `score` `null`.
3. `status` values: `scored`, `skipped_deadline`, `skipped_too_long` (the text was cut to `max_tokens`; the score is still returned with `status` `scored_truncated`), `error`.

Errors (JSON body `{"error": "<code>", "message": "<text>"}`):

| HTTP | `error` | Meaning |
|---|---|---|
| 400 | `bad_request` | Invalid JSON, missing field, too many or too long passages |
| 401 | `unauthorized` | Missing or wrong token |
| 403 | `forbidden` | `Origin` header present |
| 404 | `unknown_model` | Model name not configured |
| 409 | `contract_mismatch` | `contract_version` not supported |
| 429 | `busy` | Queue full; retry later or degrade |
| 503 | `model_unavailable` | Model not loaded, load failed or checksum mismatch |
| 504 | `deadline_exceeded` | No passage was scored before the deadline |

## 4. Other endpoints

| Method and path | Purpose | Phase |
|---|---|---|
| `GET /v1/health` | `{status, uptime_s, rss_mb, models:[{name, loaded, device}], queue, contract_version}` | 1 |
| `GET /v1/models` | Configured models with revision, size, max tokens, licence id and checksum status | 1 |
| `POST /v1/passages/upsert` | Store passages for documents: `{documents:[{origin_db, source_archive, file_path, content_hash, passages:[{section, start, end, tokens, text}]}]}`; replaces the old passages of each document in one transaction | 1 |
| `POST /v1/passages/delete` | `{docs:[{origin_db, source_archive, file_path}]}` removes passages and vectors; at most 2000 docs per call | 1 |
| `POST /v1/passages/missing` | `{documents:[{origin_db, source_archive, file_path, content_hash}]}` returns `{missing:[indexes]}` of documents that are new or changed; at most 2000 documents per call; used by `reindex` | 1 |
| `GET /v1/passages/docs` | Every indexed document with its hash; used by `reindex` to find orphans | 1 |
| `POST /v1/encode/query` | Query token vectors (debug, `--explain`) | 2 |
| `POST /v1/index/upsert` | Encode and store vectors for stored passages: `{batch: N}`; resumable | 2 |
| `POST /v1/search/late` | `{query, top_k, filters:{origin_db, source_archive, ticket, docs:[{origin_db, source_archive, file_path}]}}` returns passages with MaxSim scores; `docs` limits the scan to candidate documents (at most 500) | 2 |
| `GET /v1/index/status` | Counts, model id and revision, dimension, stale count, bytes on disk | 2 |
| `POST /v1/admin/reload` | Reload configuration and models | 1 |

The service writes only to `data/semantic.db` and its own run directory.

## 5. Client behavior (Groovy `SemanticClient`)

1. Connect timeout 150 ms; read timeout is the call deadline plus 200 ms.
2. On any error or `deadline_exceeded`, return no scores. The caller keeps the lexical order and sets `degraded` with a reason code.
3. A failed call opens a circuit for 30 seconds: the next queries skip the call at once (no 150 ms cost) and report `service_circuit_open`.
4. The client never retries a request that timed out.
5. `AI_MEMORY_RERANK=0` or `--rerank off` skips the client entirely.

Reason codes for `degraded_reason`: `rerank_off`, `service_down`, `service_circuit_open`, `service_busy`, `deadline_exceeded`, `too_few_scored`, `model_unavailable`, `contract_mismatch`, `unauthorized`, `index_stale`.

## 6. CLI, environment and output

### 6.1 Flags (added to `query`, `ticket`, `context` and `resume`; existing flags are unchanged)

| Flag | Values | Default | Meaning |
|---|---|---|---|
| `--rerank` | `off`, `auto`, `on`, `late` | `auto` | `auto`: use the service when it answers; `on`: fail with exit 5 when it does not; `off`: never; `late`: re-score the candidates with the late-interaction index (exit 5 when it cannot) |
| `--rerank-depth N` | 1 to 100 | 30 | Candidates sent to stage 2 |
| `--rerank-budget-ms N` | 100 to 10000 | 1200 | Deadline of the call |
| `--relax` | `auto`, `on`, `off` | `auto` | Relaxed first-stage query (section 6.3 of the specification) |
| `--late` | `auto`, `on`, `off` | `auto` | Late-interaction pool from the service (Phase 2) |
| `--explain` | none | off | Print the query plan, the re-rank and late status, and the model score of each row |

Environment: `AI_MEMORY_RERANK=0` (off), `AI_MEMORY_LATE=0` (off), `AI_MEMORY_RELAX=0` (off), `AI_MEMORY_SWEEP_INDEX=1` (the sweep starts `reindex` in the background when the service runs), `AI_MEMORY_SERVICE_SOCKET` (override), `AI_MEMORY_SERVICE_CONFIG` (override).

### 6.2 `QueryPlan`

```json
{
  "raw": "what did we decide about the traefik attribute",
  "ticket": null,
  "phrases": [],
  "explicit_operators": false,
  "language_guess": "en",
  "strict": "what did we decide about the traefik attribute",
  "relaxed": "decide OR deci* OR traefik OR trae* OR attribute OR attrib*",
  "stop_words_removed": ["what", "did", "we", "about", "the"],
  "form_used": "relaxed",
  "reason": "strict_returned_0"
}
```

`form_used` is `strict`, `relaxed` or `explicit` (the query was left as typed).

### 6.3 Output fields (added; none removed)

JSON rows gain: `rerank_score` (float or null), `rerank_rank` (int or null), `rerank_model`, `best_passage` (text), `signals.rerank`, `signals.bm25_relaxed`, `signals.late`. The result envelope gains: `degraded` (bool), `degraded_reason`, `query_plan` (with `--explain`), `rerank` (`{model, device, scored, skipped, elapsed_ms, depth}`), and the pool sizes with `--signals`.

Text output prints one extra line when degraded: `rerank: skipped (service_down); lexical order`.

Exit codes: unchanged, plus `5` when `--rerank on` was requested and the service did not answer in time.

## 7. Data model: `data/semantic.db`

SQLite, WAL, one writer (the service or `reindex`), created on first use.

```sql
CREATE TABLE index_manifest (
  key TEXT PRIMARY KEY, value TEXT NOT NULL
);  -- model_id, model_revision, tokenizer, dimension, window_tokens, stride_tokens, contract_version, built_at

CREATE TABLE passages (
  passage_id   TEXT PRIMARY KEY,            -- hash of doc + section + start + end
  origin_db    TEXT NOT NULL,
  source_archive TEXT NOT NULL,
  file_path    TEXT NOT NULL,
  doc_hash     TEXT NOT NULL,               -- content hash of the source document
  section      TEXT NOT NULL,               -- user, assistant, details, chunk, state-heading
  char_start   INTEGER NOT NULL,
  char_end     INTEGER NOT NULL,
  token_count  INTEGER NOT NULL,
  text         TEXT NOT NULL,
  created_at   TEXT NOT NULL
);
CREATE INDEX passages_doc ON passages(origin_db, source_archive, file_path);

CREATE TABLE score_cache (
  model_id     TEXT NOT NULL,
  query_hash   TEXT NOT NULL,               -- hash of the normalized query
  passage_hash TEXT NOT NULL,               -- hash of the scored text
  score        REAL NOT NULL,
  created_at   TEXT NOT NULL,
  PRIMARY KEY (model_id, query_hash, passage_hash)
);

CREATE TABLE vectors (                        -- Phase 2
  passage_id   TEXT PRIMARY KEY REFERENCES passages(passage_id) ON DELETE CASCADE,
  model_id     TEXT NOT NULL,
  dimension    INTEGER NOT NULL,
  dtype        TEXT NOT NULL,               -- int8 (float16 is not built)
  scale        REAL NOT NULL,               -- dequantization factor of the whole matrix
  token_count  INTEGER NOT NULL,
  matrix       BLOB NOT NULL                -- token_count x dimension values
);

CREATE TABLE eval_runs (
  run_id TEXT PRIMARY KEY, started_at TEXT NOT NULL, git_head TEXT, config_json TEXT NOT NULL,
  metrics_json TEXT NOT NULL, per_query_json TEXT NOT NULL
);
```

Rules: every row of `passages` has a live source document with the same `doc_hash`; `reindex --verify` reports and removes orphans. No row stores personal data that is not already in the source database, and the whole file is deletable.

## 8. Framework commands

Group `ai-worklog memory` (added to `shared/command-contract.json` of the framework, as `dashboard` was).

| Command | Does | Exit codes |
|---|---|---|
| `memory serve [--init] [--daemon] [--tcp PORT]` | Start the service; `--init` writes the configuration and checks the environment | 0 started, 1 failed, 2 usage |
| `memory status` | Show address, process id, uptime, memory, models, queue, index counts | 0 running, 1 not running |
| `memory stop` | Stop the service | 0 stopped or not running |
| `memory doctor` | Check Python version, packages and hashes, model checksums, socket mode, disk, offline flags; read-only | 0 all pass, 1 a check failed |
| `memory reindex [--archives A,B] [--verify] [--rebuild] [--no-encode]` | Build or update passages and vectors; resumable; removes orphans. `--verify` changes nothing and exits 1 when work or orphans exist. The same command is `ai-memory-ingester reindex` | 0 done, 1 failed or problems found, 3 service not running |
| `memory eval [--set gold] [--compare RUN] [--json]` | Run the gold set; store the run; compare with an earlier run | 0 passed gates, 1 a gate failed, 2 usage |

## 8b. Service program

`service/ai-memory-service` (uses `AI_MEMORY_SERVICE_PYTHON`, default `python3`): `serve [--stub]`, `status`, `stop`, `doctor`, `checksum PATH`. Global options: `--workspace-root`, `--db`. `serve` refuses a socket path longer than 100 characters (exit 2) and a second running instance (exit 1). The stub model and the stub encoder exist for tests and answer only with `--stub`.

## 9. Compatibility

1. Old callers of `query`, `ticket`, `recent`, `resume` and `context` see the same output plus new fields.
2. A client with contract version 1 against a newer minor service works. A major mismatch returns `409` and the client degrades.
3. `AI_MEMORY_RERANK=0` makes the system behave like Phase 0 (first-stage improvements only). `AI_MEMORY_RELAX=0` as well makes it behave like today.

## Summary (STE)

The service has one main call: it receives a question and a list of passages and returns a score for each passage. It listens on a private socket on this computer and needs a token. It has a time limit. If it is late or down, the caller keeps the old order. New flags let the owner turn each part on or off. All derived data lives in one separate database that can be deleted.

**Remaining 20% (not STE100):** field, endpoint, flag, table and environment names; the words "socket", "token", "deadline", "circuit", "passage", "WAL" and "checksum"; the JSON, SQL and tables; some sentences over 20 words.

---

Created using Anthropic Claude. Internal only until a person has reviewed and verified it.
