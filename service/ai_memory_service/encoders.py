import hashlib
import math

from .backends import ModelUnavailable, words


class StubEncoder:
    """Deterministic hashed token vectors for tests: texts that share words give high MaxSim."""

    model_id = "stub-encoder"
    dimension = 16

    def vector(self, word):
        raw = hashlib.sha256(word.encode("utf-8")).digest()
        values = [(raw[i] - 127.5) / 127.5 for i in range(self.dimension)]
        norm = math.sqrt(sum(v * v for v in values)) or 1.0
        return [v / norm for v in values]

    def encode_query(self, text):
        return [self.vector(w) for w in words(text)]

    def encode_passage(self, text):
        tokens = words(text)
        return [self.vector(w) for w in tokens[:512]] or [[0.0] * self.dimension]


def build_encoder(config, allow_stub):
    name = config.get("late_model")
    if name == "stub" or (name is None and allow_stub):
        if not allow_stub:
            raise ModelUnavailable("the stub encoder is for tests; start the service with --stub")
        return StubEncoder()
    raise ModelUnavailable("no late-interaction model is configured (decision D-19 and D-20 are open)")
