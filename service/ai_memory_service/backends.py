import os
import re
import unicodedata
from pathlib import Path

from .integrity import tree_sha256, weight_problems


class ModelUnavailable(Exception):
    pass


def fold(text):
    lowered = (text or "").lower().replace("ł", "l")
    decomposed = unicodedata.normalize("NFD", lowered)
    return "".join(c for c in decomposed if not unicodedata.combining(c))


def words(text):
    return re.findall(r"[a-z0-9_]+", fold(text))


class StubBackend:
    """Deterministic term-overlap scorer. For tests and development only; never a production model."""

    name = "stub"

    def __init__(self, entry=None):
        self.entry = entry or {}

    def load(self):
        return self

    def info(self):
        return {"revision": "stub-1", "device": "cpu", "max_tokens": int(self.entry.get("max_tokens", 256))}

    def score(self, query, texts):
        wanted = set(words(query))
        scores = []
        for text in texts:
            found = set(words(text))
            scores.append(round(len(wanted & found) / len(wanted), 4) if wanted else 0.0)
        return scores


class CrossEncoderBackend:
    """A sentence-transformers CrossEncoder loaded from a local, checksum-verified folder.

    Loads only safetensors weights, never runs remote code, never touches the network.
    Real inference is not covered by the default tests (set RUN_MODEL_TESTS=1 with a cached model).
    """

    def __init__(self, name, entry, model_cache, max_mb=4096):
        self.name = name
        self.entry = entry
        self.max_mb = max_mb
        self.model_cache = Path(model_cache).expanduser()
        self.model = None
        self.device = "cpu"

    def model_path(self):
        path = Path(str(self.entry.get("path", ""))).expanduser()
        return path if path.is_absolute() else self.model_cache / path

    def verify(self):
        path = self.model_path()
        if not path.is_dir():
            raise ModelUnavailable(f"model folder not found: {path}")
        problems = weight_problems(path)
        if problems:
            raise ModelUnavailable("; ".join(problems))
        size_mb = sum(p.stat().st_size for p in path.rglob("*") if p.is_file()) / (1024 * 1024)
        if size_mb > self.max_mb:
            raise ModelUnavailable(f"the model folder is {int(size_mb)} MB, above the limit of {self.max_mb} MB")
        expected = self.entry.get("sha256")
        if not expected:
            raise ModelUnavailable("the model entry has no sha256 checksum")
        actual = tree_sha256(path)
        if actual != expected:
            raise ModelUnavailable("checksum mismatch for the model folder")
        return path

    def load(self):
        path = self.verify()
        os.environ.setdefault("HF_HUB_OFFLINE", "1")
        os.environ.setdefault("TRANSFORMERS_OFFLINE", "1")
        try:
            from sentence_transformers import CrossEncoder  # noqa: WPS433
        except Exception as error:
            raise ModelUnavailable(f"sentence_transformers is not usable: {error}") from error
        self.device = self.resolve_device()
        try:
            self.model = CrossEncoder(str(path), device=self.device, max_length=int(self.entry.get("max_tokens", 256)),
                                      trust_remote_code=False)
        except Exception as error:
            raise ModelUnavailable(f"model could not be loaded: {error}") from error
        return self

    def resolve_device(self):
        wanted = self.entry.get("device", "auto")
        if wanted in ("cpu", "mps"):
            return wanted
        try:
            import torch  # noqa: WPS433
            return "mps" if torch.backends.mps.is_available() else "cpu"
        except Exception:
            return "cpu"

    def info(self):
        return {"revision": str(self.entry.get("revision", "unknown")), "device": self.device,
                "max_tokens": int(self.entry.get("max_tokens", 256))}

    def score(self, query, texts):
        if self.model is None:
            raise ModelUnavailable("model is not loaded")
        pairs = [(query, text) for text in texts]
        return [round(float(value), 4) for value in self.model.predict(pairs, show_progress_bar=False)]


def build_backend(name, config, allow_stub):
    if name == "stub":
        if not allow_stub:
            raise ModelUnavailable("the stub model is for tests; start the service with --stub")
        return StubBackend(config.get("models", {}).get("stub"))
    entry = config.get("models", {}).get(name)
    if entry is None:
        raise KeyError(name)
    return CrossEncoderBackend(name, entry, config["model_cache"], int(config.get("max_rss_mb", 4096)))
