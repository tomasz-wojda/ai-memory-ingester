import os
import shutil
import stat
import sys
from pathlib import Path

from .config import db_path, resolve, socket_path, token_path
from .integrity import tree_sha256, weight_problems

OFFLINE_VARIABLES = ("HF_HUB_OFFLINE", "TRANSFORMERS_OFFLINE", "HF_HUB_DISABLE_TELEMETRY", "DO_NOT_TRACK")


def check(name, ok, detail):
    return {"check": name, "ok": bool(ok), "detail": detail}


def mode_of(path):
    return stat.S_IMODE(os.stat(path).st_mode)


def run_doctor(config):
    results = []
    version = sys.version_info
    results.append(check("python version", version >= (3, 11) and version.releaselevel == "final",
                         f"{version.major}.{version.minor}.{version.micro} {version.releaselevel}; a final release of 3.11 or newer is needed"))
    for module in ("sentence_transformers", "torch"):
        try:
            __import__(module)
            results.append(check(f"package {module}", True, "importable"))
        except Exception as error:  # noqa: BLE001
            results.append(check(f"package {module}", False, f"not importable ({type(error).__name__}); needed only for a real model"))
    for name, entry in (config.get("models") or {}).items():
        path = Path(str(entry.get("path", ""))).expanduser()
        path = path if path.is_absolute() else Path(config["model_cache"]).expanduser() / path
        if not path.is_dir():
            results.append(check(f"model {name}", False, f"folder not found: {path}"))
            continue
        problems = weight_problems(path)
        sha = entry.get("sha256")
        actual = tree_sha256(path) if sha else None
        ok = not problems and bool(sha) and actual == sha
        detail = "; ".join(problems) if problems else ("checksum matches" if ok else ("no checksum recorded" if not sha else "checksum mismatch"))
        results.append(check(f"model {name}", ok, detail))
        results.append(check(f"model {name} licence recorded", entry.get("licence") not in (None, "", "unverified"),
                             f"licence: {entry.get('licence', 'unverified')}"))
    sock = socket_path(config)
    run_dir = sock.parent
    if run_dir.exists():
        results.append(check("run directory mode 0700", mode_of(run_dir) == 0o700, oct(mode_of(run_dir))))
    else:
        results.append(check("run directory mode 0700", True, "not created yet"))
    for label, path in (("socket mode 0600", sock), ("token mode 0600", token_path(config))):
        if path.exists():
            results.append(check(label, mode_of(path) == 0o600, oct(mode_of(path))))
    results.append(check("offline variables", all(os.environ.get(v) in ("1", "true", "True") for v in OFFLINE_VARIABLES),
                         "set " + ", ".join(f"{v}=1" for v in OFFLINE_VARIABLES) + " for the service"))
    free = shutil.disk_usage(db_path(config).parent if db_path(config).parent.exists() else Path(config["workspace"])).free
    results.append(check("free disk above 10 GB", free > 10 * 1024 ** 3, f"{free // 1024 ** 3} GB free"))
    return results
