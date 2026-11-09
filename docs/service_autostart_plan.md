# Plan: start the service from `sweep-memory` (R49 option D) and write its model config (R50)

Status: decided by the owner on 2026-11-09 ("sweep-memory with a flag"). Executed in the same session. Any deviation from this plan returns to PLAN mode.

Parent documents: [rerank_enhacement_plan_tranche2.md](rerank_enhacement_plan_tranche2.md) section 1.2 (R49 memo), [rerank_enhacement.md](rerank_enhacement.md) (R49, R50).

## 1. Facts

- Nothing starts the service today. `sweep-memory` only runs `reindex` when `AI_MEMORY_SWEEP_INDEX=1` and the socket already exists.
- Without `.ai-worklog/memory-service.json` the service has no model (`models` is empty). That file does not exist in the workspace now.
- Measured on 2026-11-06: the socket appears 0.1 to 0.3 s after the start; the model is ready about 4 s after the start; the service holds about 1.1 to 1.3 GB of memory while it runs.
- The launcher `service/ai-memory-service` uses `AI_MEMORY_SERVICE_PYTHON` (default `python3`). The virtual environment with the packages is `~/.local/share/ai-memory-service/venv`.
- The model folders and `models.manifest.json` are in `~/.local/share/ai-memory-service/models/`. The default model is `mmarco-mMiniLMv2-L12-H384-v1` (decision D-04). R44 is closed by the owner (2026-11-09), so R50 is free.

## 2. Design

New class `lib/ServiceAutostart.groovy` and a step in `07_sweep_memory.groovy`. The step runs only when `AI_MEMORY_SERVICE_AUTOSTART=1` and the run is not a dry run. Without the flag nothing changes and the output line says `SERVICE_AUTOSTART off`.

1. If `service/ai-memory-service status` says the service runs: note `running`.
2. Else, if `.ai-worklog/memory-service.json` does not exist: write it from the manifest entry of the default model (R50): `rerank_model`, and `models.<name>` with `path`, `sha256` (the folder checksum), `max_tokens` 512 (as in the benchmarks), `device` `auto`, `licence`. If the model folder or the manifest entry is missing: note `skipped (model not installed)` and stop. An existing configuration file is never changed.
3. Else start `service/ai-memory-service --workspace-root <root> serve` in the background (`nohup`, output to `.ai-worklog/service-autostart.log`), with `AI_MEMORY_SERVICE_PYTHON` set to the virtual environment's Python when it exists. Do not wait: note `started (the model loads in about 4 s)`.
4. Any error gives a note `skipped (<reason>)`. The sweep never fails because of the service.

The output line is `SERVICE_AUTOSTART<TAB><note>`. `AI_MEMORY_SERVICE_MODEL` selects another model name from the manifest.

The log is not inside `.ai-worklog/run/`: the service refuses to start when its run folder holds other files (found in the first real test).

## 3. Limits

- The service starts when a session starts a sweep, not at login. A query in the first seconds after the start falls back to the first-stage order.
- Memory: about 1.3 GB while the service runs. `service/ai-memory-service stop` stops it. No stop is automatic.
- The configuration file is written once. To change the model, edit the file or delete it.

## 4. Regression scope

New unit tests (config builder, manifest reading, skip cases, flag off). All engine suites, the wrapper test and the Python service tests must pass. A manual run of `sweep-memory` with the flag in a temporary workspace shows the service running (`status`) and answering one re-rank request.

## IMPLEMENTATION CHECKLIST

1. [x] Write `lib/ServiceAutostart.groovy` (config builder, manifest read, status, start).
2. [x] Add the step and the output line to `07_sweep_memory.groovy`; register the class in `run.groovy` and the test class lists.
3. [x] Add unit tests; run the suites.
4. [x] Test the real start in a temporary workspace (flag on, flag off, model missing).
5. [ ] Rebuild the jar; run the wrapper test.
6. [ ] Update the documents (R49, R50, decision log), render the handoff, record the turn.

Any deviation from this plan returns to PLAN mode.

---

Created using Anthropic Claude. Keep this internal until a person has reviewed and verified it.
