import heapq
import threading

from .store import dequantize

try:
    import numpy as np
except Exception:  # noqa: BLE001
    np = None

USE_NUMPY = True
BLOCK_TOKENS = 200_000


def maxsim(query_vectors, passage_vectors):
    total = 0.0
    for q in query_vectors:
        best = max((sum(a * b for a, b in zip(q, p)) for p in passage_vectors), default=0.0)
        total += best
    return total


def numpy_active():
    return np is not None and USE_NUMPY


class VectorIndex:
    """All token vectors of one model in memory as one half-precision matrix (float32 per block while scoring), passages sorted by id."""

    def __init__(self, db, model_id):
        self.version = db.vector_version(model_id)
        self.model_id = model_id
        meta = []
        blocks = []
        offsets = []
        position = 0
        dimension = 0
        for row in db.iter_vectors(model_id):
            matrix = np.asarray(dequantize(row["matrix"], row["scale"], row["token_count"], row["dimension"]), dtype=np.float32)
            dimension = row["dimension"]
            meta.append((row["passage_id"], row["origin_db"], row["source_archive"], row["file_path"]))
            offsets.append(position)
            position += matrix.shape[0]
            blocks.append(matrix)
        self.meta = meta
        self.offsets = np.asarray(offsets, dtype=np.int64)
        self.tokens = (np.concatenate(blocks) if blocks else np.zeros((0, dimension or 1), dtype=np.float32)).astype(np.float16)
        self.archives = np.asarray([m[2] for m in meta], dtype=object)
        self.databases = np.asarray([m[1] for m in meta], dtype=object)
        self.paths = [m[3] for m in meta]

    def scores(self, query_vectors):
        query = np.asarray(query_vectors, dtype=np.float32)
        count = len(self.meta)
        if count == 0:
            return np.zeros(0, dtype=np.float64)
        per_query = np.zeros((query.shape[0], count), dtype=np.float32)
        start = 0
        while start < count:
            end = start + 1
            while end < count and int(self.offsets[end]) - int(self.offsets[start]) < BLOCK_TOKENS:
                end += 1
            low = int(self.offsets[start])
            high = int(self.offsets[end]) if end < count else self.tokens.shape[0]
            sims = query @ self.tokens[low:high].astype(np.float32).T
            local = self.offsets[start:end] - low
            per_query[:, start:end] = np.maximum.reduceat(sims, local, axis=1)
            start = end
        return per_query.sum(axis=0, dtype=np.float64)

    def mask(self, archives, origin_db, path_contains, docs):
        keep = np.ones(len(self.meta), dtype=bool)
        if archives:
            keep &= np.isin(self.archives, list(archives))
        if origin_db:
            keep &= self.databases == origin_db
        if path_contains:
            keep &= np.asarray([path_contains in path for path in self.paths], dtype=bool)
        if docs:
            wanted = {(d["origin_db"], d["source_archive"], d["file_path"]) for d in docs}
            keep &= np.asarray([(m[1], m[2], m[3]) in wanted for m in self.meta], dtype=bool)
        return keep


_indexes = {}
_lock = threading.Lock()


def index_for(db, model_id):
    key = (id(db), model_id)
    with _lock:
        cached = _indexes.get(key)
        if cached is not None and cached.version == db.vector_version(model_id):
            return cached
        built = VectorIndex(db, model_id)
        _indexes[key] = built
        return built


def search(db, encoder, query_text, top_k, archives=None, origin_db=None, path_contains=None, docs=None):
    query_vectors = encoder.encode_query(query_text)
    if not query_vectors:
        return []
    if numpy_active():
        return search_numpy(db, encoder, query_vectors, top_k, archives, origin_db, path_contains, docs)
    return search_python(db, encoder, query_vectors, top_k, archives, origin_db, path_contains, docs)


def search_numpy(db, encoder, query_vectors, top_k, archives, origin_db, path_contains, docs):
    index = index_for(db, encoder.model_id)
    scores = index.scores(query_vectors)
    keep = index.mask(archives, origin_db, path_contains, docs)
    candidates = np.nonzero(keep)[0]
    if candidates.size == 0:
        return []
    rounded = np.round(scores[candidates], 4)
    order = np.argsort(-rounded, kind="stable")[:top_k]
    chosen = [int(candidates[i]) for i in order]
    texts = db.passage_texts([index.meta[i][0] for i in chosen])
    result = []
    for position, i in zip(order, chosen):
        pid, origin, archive, path = index.meta[i]
        result.append({"passage_id": pid, "score": float(rounded[position]), "origin_db": origin, "source_archive": archive,
                       "file_path": path, "text": texts.get(pid, "")})
    return result


def search_python(db, encoder, query_vectors, top_k, archives, origin_db, path_contains, docs):
    wanted = {(d["origin_db"], d["source_archive"], d["file_path"]) for d in docs} if docs else None
    heap = []
    sequence = 0
    for row in db.iter_vectors(encoder.model_id, archives, origin_db, path_contains):
        if wanted is not None and (row["origin_db"], row["source_archive"], row["file_path"]) not in wanted:
            continue
        matrix = dequantize(row["matrix"], row["scale"], row["token_count"], row["dimension"])
        score = round(maxsim(query_vectors, matrix), 4)
        sequence += 1
        item = (score, -sequence, row)
        if len(heap) < top_k:
            heapq.heappush(heap, item)
        elif item[:2] > heap[0][:2]:
            heapq.heapreplace(heap, item)
    ordered = sorted(heap, key=lambda item: (-item[0], -item[1]))
    return [{"passage_id": row["passage_id"], "score": score, "origin_db": row["origin_db"], "source_archive": row["source_archive"],
             "file_path": row["file_path"], "text": row["text"]} for score, _, row in ordered]
