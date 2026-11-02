import array
import sqlite3
import threading
import time
from pathlib import Path

from .protocol import passage_id

SCHEMA = """
CREATE TABLE IF NOT EXISTS index_manifest (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS passages (
  passage_id TEXT PRIMARY KEY,
  origin_db TEXT NOT NULL,
  source_archive TEXT NOT NULL,
  file_path TEXT NOT NULL,
  doc_hash TEXT NOT NULL,
  section TEXT NOT NULL,
  char_start INTEGER NOT NULL,
  char_end INTEGER NOT NULL,
  token_count INTEGER NOT NULL,
  text TEXT NOT NULL,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS passages_doc ON passages(origin_db, source_archive, file_path);
CREATE TABLE IF NOT EXISTS score_cache (
  model_id TEXT NOT NULL,
  query_hash TEXT NOT NULL,
  passage_hash TEXT NOT NULL,
  score REAL NOT NULL,
  created_at REAL NOT NULL,
  PRIMARY KEY (model_id, query_hash, passage_hash)
);
CREATE TABLE IF NOT EXISTS vectors (
  passage_id TEXT PRIMARY KEY REFERENCES passages(passage_id) ON DELETE CASCADE,
  model_id TEXT NOT NULL,
  dimension INTEGER NOT NULL,
  dtype TEXT NOT NULL,
  scale REAL NOT NULL,
  token_count INTEGER NOT NULL,
  matrix BLOB NOT NULL
);
CREATE TABLE IF NOT EXISTS eval_runs (
  run_id TEXT PRIMARY KEY,
  started_at TEXT NOT NULL,
  git_head TEXT,
  config_json TEXT NOT NULL,
  metrics_json TEXT NOT NULL,
  per_query_json TEXT NOT NULL
);
"""


def quantize(matrix):
    peak = max((abs(value) for row in matrix for value in row), default=0.0)
    scale = peak / 127.0 if peak > 0 else 1.0
    data = array.array("b")
    for row in matrix:
        data.extend(max(-127, min(127, int(round(value / scale)))) for value in row)
    return scale, data.tobytes()


def dequantize(blob, scale, token_count, dimension):
    data = array.array("b")
    data.frombytes(blob)
    return [[data[i * dimension + j] * scale for j in range(dimension)] for i in range(token_count)]


class SemanticDb:
    def __init__(self, path):
        self.path = Path(path)
        self.lock = threading.RLock()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.connection = sqlite3.connect(str(self.path), check_same_thread=False, isolation_level=None)
        self.connection.execute("PRAGMA journal_mode=WAL")
        self.connection.execute("PRAGMA foreign_keys=ON")
        self.connection.execute("PRAGMA busy_timeout=5000")
        self.connection.executescript(SCHEMA)

    def close(self):
        with self.lock:
            self.connection.close()

    def manifest(self):
        with self.lock:
            return dict(self.connection.execute("SELECT key, value FROM index_manifest").fetchall())

    def set_manifest(self, values):
        with self.lock:
            for key, value in values.items():
                self.connection.execute("INSERT OR REPLACE INTO index_manifest(key, value) VALUES (?, ?)", (key, str(value)))

    def upsert_documents(self, documents):
        stored = 0
        with self.lock:
            self.connection.execute("BEGIN IMMEDIATE")
            try:
                for document in documents:
                    keys = (document["origin_db"], document["source_archive"], document["file_path"])
                    self.connection.execute(
                        "DELETE FROM passages WHERE origin_db = ? AND source_archive = ? AND file_path = ?", keys)
                    for passage in document["passages"]:
                        pid = passage_id(*keys, passage["section"], passage["start"], passage["end"])
                        self.connection.execute(
                            "INSERT OR REPLACE INTO passages VALUES (?,?,?,?,?,?,?,?,?,?,datetime('now'))",
                            (pid, *keys, document["content_hash"], str(passage["section"]), int(passage["start"]),
                             int(passage["end"]), int(passage["tokens"]), passage["text"]))
                        stored += 1
                self.connection.execute("COMMIT")
            except Exception:
                self.connection.execute("ROLLBACK")
                raise
        return stored

    def delete_documents(self, docs):
        removed = 0
        with self.lock:
            self.connection.execute("BEGIN IMMEDIATE")
            try:
                for doc in docs:
                    keys = (doc["origin_db"], doc["source_archive"], doc["file_path"])
                    ids = [row[0] for row in self.connection.execute(
                        "SELECT passage_id FROM passages WHERE origin_db = ? AND source_archive = ? AND file_path = ?", keys)]
                    for pid in ids:
                        self.connection.execute("DELETE FROM vectors WHERE passage_id = ?", (pid,))
                    removed += self.connection.execute(
                        "DELETE FROM passages WHERE origin_db = ? AND source_archive = ? AND file_path = ?", keys).rowcount
                self.connection.execute("COMMIT")
            except Exception:
                self.connection.execute("ROLLBACK")
                raise
        return removed

    def cache_get(self, model_id, query_hash, passage_hashes):
        if not passage_hashes:
            return {}
        found = {}
        with self.lock:
            for start in range(0, len(passage_hashes), 500):
                chunk = passage_hashes[start:start + 500]
                marks = ",".join("?" * len(chunk))
                rows = self.connection.execute(
                    f"SELECT passage_hash, score FROM score_cache WHERE model_id = ? AND query_hash = ? AND passage_hash IN ({marks})",
                    (model_id, query_hash, *chunk)).fetchall()
                found.update(dict(rows))
        return found

    def cache_put(self, model_id, query_hash, scores):
        now = time.time()
        with self.lock:
            self.connection.execute("BEGIN IMMEDIATE")
            try:
                for passage_hash, score in scores.items():
                    self.connection.execute("INSERT OR REPLACE INTO score_cache VALUES (?,?,?,?,?)", (model_id, query_hash, passage_hash, score, now))
                self.connection.execute("COMMIT")
            except Exception:
                self.connection.execute("ROLLBACK")
                raise

    def purge_cache(self, days):
        cutoff = time.time() - days * 86400
        with self.lock:
            return self.connection.execute("DELETE FROM score_cache WHERE created_at < ?", (cutoff,)).rowcount

    def passages_without_vectors(self, model_id, limit):
        with self.lock:
            return self.connection.execute(
                "SELECT p.passage_id, p.text FROM passages p LEFT JOIN vectors v ON v.passage_id = p.passage_id AND v.model_id = ? "
                "WHERE v.passage_id IS NULL ORDER BY p.passage_id LIMIT ?", (model_id, limit)).fetchall()

    def count_passages_without_vectors(self, model_id):
        with self.lock:
            return self.connection.execute(
                "SELECT count(*) FROM passages p LEFT JOIN vectors v ON v.passage_id = p.passage_id AND v.model_id = ? "
                "WHERE v.passage_id IS NULL", (model_id,)).fetchone()[0]

    def put_vectors(self, model_id, dimension, items):
        with self.lock:
            self.connection.execute("BEGIN IMMEDIATE")
            try:
                for pid, matrix in items:
                    scale, blob = quantize(matrix)
                    self.connection.execute(
                        "INSERT OR REPLACE INTO vectors VALUES (?,?,?,?,?,?,?)",
                        (pid, model_id, dimension, "int8", scale, len(matrix), blob))
                self.connection.execute("COMMIT")
            except Exception:
                self.connection.execute("ROLLBACK")
                raise

    def vector_version(self, model_id):
        with self.lock:
            row = self.connection.execute("SELECT count(*), coalesce(max(rowid), 0) FROM vectors WHERE model_id = ?", (model_id,)).fetchone()
        return (row[0], row[1])

    def passage_texts(self, ids):
        found = {}
        with self.lock:
            for start in range(0, len(ids), 500):
                chunk = ids[start:start + 500]
                marks = ",".join("?" * len(chunk))
                for pid, text in self.connection.execute(f"SELECT passage_id, text FROM passages WHERE passage_id IN ({marks})", chunk):
                    found[pid] = text
        return found

    def iter_vectors(self, model_id, archives=None, origin_db=None, path_contains=None):
        query = ("SELECT v.passage_id, v.dimension, v.scale, v.token_count, v.matrix, p.origin_db, p.source_archive, p.file_path, p.text "
                 "FROM vectors v JOIN passages p ON p.passage_id = v.passage_id WHERE v.model_id = ?")
        params = [model_id]
        if archives:
            query += " AND p.source_archive IN (%s)" % ",".join("?" * len(archives))
            params.extend(archives)
        if origin_db:
            query += " AND p.origin_db = ?"
            params.append(origin_db)
        if path_contains:
            query += " AND p.file_path LIKE ?"
            params.append(f"%{path_contains}%")
        query += " ORDER BY p.passage_id"
        with self.lock:
            rows = self.connection.execute(query, params).fetchall()
        for row in rows:
            yield {"passage_id": row[0], "dimension": row[1], "scale": row[2], "token_count": row[3], "matrix": row[4],
                   "origin_db": row[5], "source_archive": row[6], "file_path": row[7], "text": row[8]}

    def counts(self, model_id=None):
        with self.lock:
            passages = self.connection.execute("SELECT count(*) FROM passages").fetchone()[0]
            cache = self.connection.execute("SELECT count(*) FROM score_cache").fetchone()[0]
            vectors = self.connection.execute(
                "SELECT count(*) FROM vectors" + (" WHERE model_id = ?" if model_id else ""),
                (model_id,) if model_id else ()).fetchone()[0]
        return {"passages": passages, "score_cache": cache, "vectors": vectors}

    def missing_documents(self, documents):
        missing = []
        with self.lock:
            for index, document in enumerate(documents):
                row = self.connection.execute(
                    "SELECT 1 FROM passages WHERE origin_db = ? AND source_archive = ? AND file_path = ? AND doc_hash = ? LIMIT 1",
                    (document["origin_db"], document["source_archive"], document["file_path"], document["content_hash"])).fetchone()
                if row is None:
                    missing.append(index)
        return missing

    def all_documents(self):
        with self.lock:
            rows = self.connection.execute(
                "SELECT DISTINCT origin_db, source_archive, file_path, doc_hash FROM passages ORDER BY origin_db, source_archive, file_path").fetchall()
        return [{"origin_db": r[0], "source_archive": r[1], "file_path": r[2], "content_hash": r[3]} for r in rows]

    def orphans(self, live_docs):
        live = set(live_docs)
        with self.lock:
            rows = self.connection.execute(
                "SELECT DISTINCT origin_db, source_archive, file_path, doc_hash FROM passages").fetchall()
        return [row for row in rows if (row[0], row[1], row[2], row[3]) not in live]

    def file_size(self):
        return self.path.stat().st_size if self.path.exists() else 0
