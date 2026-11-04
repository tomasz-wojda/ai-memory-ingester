import json
import os
from pathlib import Path

DEFAULTS = {
    "contract_version": 1,
    "socket": ".ai-worklog/run/memory-service.sock",
    "token_file": ".ai-worklog/run/memory-service.token",
    "tcp_port": 0,
    "db_path": "repos/ai-memory-ingester/data/semantic.db",
    "model_cache": "~/.local/share/ai-memory-service/models",
    "rerank_model": "small-multilingual",
    "models": {},
    "late_model": None,
    "max_rss_mb": 4096,
    "queue_size": 4,
    "batch_size": 16,
    "default_deadline_ms": 1200,
    "rerank_depth": 30,
    "rerank_min_scored": 10,
    "relax_min_results": 10,
    "score_cache_days": 14,
    "warm_shapes": [80, 160, 200],
    "warm_batch": 16,
    "index_archives": ["chat:turns", "worklog:chunks", "worklog:state"],
    "max_body_bytes": 4 * 1024 * 1024,
    "max_passages": 100,
    "max_passage_chars": 4000,
    "max_connections": 32,
    "body_deadline_s": 30,
}


LIMITS = {
    "max_rss_mb": (256, 1048576),
    "queue_size": (1, 64),
    "batch_size": (1, 256),
    "default_deadline_ms": (50, 60000),
    "rerank_depth": (1, 100),
    "rerank_min_scored": (1, 100),
    "relax_min_results": (1, 100),
    "score_cache_days": (1, 3650),
    "max_body_bytes": (1024, 64 * 1024 * 1024),
    "max_passages": (1, 1000),
    "max_passage_chars": (100, 100000),
    "max_connections": (1, 1024),
    "body_deadline_s": (1, 600),
    "warm_batch": (1, 256),
}

WARM_SHAPE_LIMITS = (1, 400)
WARM_SHAPE_COUNT = 5


def clamp_limits(config):
    for key, (low, high) in LIMITS.items():
        value = config.get(key)
        if isinstance(value, bool) or not isinstance(value, int):
            config[key] = DEFAULTS[key]
        else:
            config[key] = max(low, min(high, value))
    shapes = config.get("warm_shapes")
    if not isinstance(shapes, list) or any(isinstance(item, bool) or not isinstance(item, int) for item in shapes):
        config["warm_shapes"] = list(DEFAULTS["warm_shapes"])
    else:
        low, high = WARM_SHAPE_LIMITS
        config["warm_shapes"] = [max(low, min(high, item)) for item in shapes][:WARM_SHAPE_COUNT]
    return config


def workspace_root(explicit=None):
    value = explicit or os.environ.get("MEMORY_WORKSPACE_ROOT")
    if value:
        return Path(value).expanduser().resolve()
    here = Path(__file__).resolve()
    return here.parents[4] if len(here.parents) > 4 else Path.cwd()


def config_path(workspace):
    override = os.environ.get("AI_MEMORY_SERVICE_CONFIG")
    if override:
        return Path(override).expanduser()
    return workspace / ".ai-worklog" / "memory-service.json"


def load_config(workspace, overrides=None):
    config = json.loads(json.dumps(DEFAULTS))
    path = config_path(workspace)
    if path.is_file():
        config.update(json.loads(path.read_text(encoding="utf-8")))
    if overrides:
        config.update({key: value for key, value in overrides.items() if value is not None})
    socket_override = os.environ.get("AI_MEMORY_SERVICE_SOCKET")
    if socket_override:
        config["socket"] = socket_override
    config["workspace"] = str(workspace)
    return clamp_limits(config)


def resolve(workspace, value):
    path = Path(str(value)).expanduser()
    return path if path.is_absolute() else Path(workspace) / path


def socket_path(config):
    return resolve(config["workspace"], config["socket"])


def token_path(config):
    return resolve(config["workspace"], config["token_file"])


def db_path(config):
    return resolve(config["workspace"], config["db_path"])
