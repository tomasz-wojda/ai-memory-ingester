import hmac
import http.server
import json
import os
import queue
import resource
import socket
import socketserver
import sys
import threading
import time

from . import CONTRACT_VERSION
from . import maxsim
from .backends import ModelUnavailable, build_backend
from .config import db_path
from .encoders import build_encoder
from .protocol import (MAX_DOCS_PER_CALL, MAX_QUERY_CHARS, ProtocolError, check_version, query_hash, require_dict, text_hash, validate_delete,
                       validate_rerank, validate_upsert)
from .store import SemanticDb


class Busy(Exception):
    pass


class ModelWorker(threading.Thread):
    """One thread runs every model call, one at a time, behind a bounded queue."""

    def __init__(self, size):
        super().__init__(daemon=True)
        self.jobs = queue.Queue(maxsize=size)
        self.start()

    def run(self):
        while True:
            job = self.jobs.get()
            if job is None:
                return
            function, done, box = job
            try:
                box["value"] = function()
            except BaseException as error:  # noqa: BLE001
                box["error"] = error
            finally:
                done.set()

    def submit(self, function, wait_seconds):
        done = threading.Event()
        box = {}
        try:
            self.jobs.put_nowait((function, done, box))
        except queue.Full:
            raise Busy()
        if not done.wait(wait_seconds):
            raise ProtocolError(504, "deadline_exceeded", "The model call did not finish in time")
        if "error" in box:
            raise box["error"]
        return box["value"]


WARM_WORDS = ("service", "memory", "ticket", "deploy", "cluster", "config", "release", "pipeline", "metric", "token", "session",
              "worklog", "manifest", "rollout", "traefik", "kibana", "alert", "storage", "network", "secret", "account", "policy",
              "backup", "gateway", "certificate", "namespace", "controller", "dashboard", "retention", "latency")


def synthetic_passage(words, seed):
    return " ".join(WARM_WORDS[(seed + index) % len(WARM_WORDS)] for index in range(words))


class App:
    def __init__(self, config, token, allow_stub=False):
        self.config = config
        self.token = token
        self.allow_stub = bool(allow_stub)
        self.started = time.time()
        self.db = SemanticDb(db_path(config))
        self.worker = ModelWorker(int(config["queue_size"]))
        self.backends = {}
        self.encoder = None
        self.lock = threading.Lock()
        self.last_purge = 0.0
        self.failures = {}
        self.gates = {}
        self.generation = 0
        self.failed_load_seconds = 30.0
        self.warmed = False
        self.warm_ms = None
        self.purge_if_due()

    def warm(self):
        name = self.config.get("rerank_model")
        models = self.config.get("models", {})
        if not name or not (name in models or (name == "stub" and self.allow_stub)):
            return False
        started = time.time()
        try:
            backend = self.backend(name)
            backend.score("warm up", ["warm up"])
            for words in self.config["warm_shapes"]:
                backend.score("warm up", [synthetic_passage(words, seed) for seed in range(self.config["warm_batch"])])
        except (ProtocolError, Exception):  # noqa: BLE001
            return False
        self.warm_ms = int((time.time() - started) * 1000)
        self.warmed = True
        return True

    def purge_if_due(self):
        now = time.time()
        if now - self.last_purge < 3600:
            return 0
        self.last_purge = now
        return self.db.purge_cache(self.config["score_cache_days"])

    def close(self):
        self.worker.jobs.put(None)
        self.db.close()

    def refuse_if_recently_failed(self, key):
        failure = self.failures.get(key)
        if failure and time.time() - failure[0] < self.failed_load_seconds:
            raise ProtocolError(503, "model_unavailable", failure[1])
        self.failures.pop(key, None)

    def remember_failure(self, key, error):
        self.failures[key] = (time.time(), str(error))
        raise ProtocolError(503, "model_unavailable", str(error))

    def backend(self, name):
        key = ("backend", name)
        with self.lock:
            if name in self.backends:
                return self.backends[name]
            self.refuse_if_recently_failed(key)
            gate = self.gates.setdefault(key, threading.Lock())
            generation = self.generation
        with gate:
            with self.lock:
                if name in self.backends:
                    return self.backends[name]
                self.refuse_if_recently_failed(key)
            try:
                backend = build_backend(name, self.config, self.allow_stub)
                backend.load()
            except KeyError:
                raise ProtocolError(404, "unknown_model", f"model {name} is not configured")
            except ModelUnavailable as error:
                with self.lock:
                    self.remember_failure(key, error)
            with self.lock:
                if generation == self.generation:
                    self.backends[name] = backend
            return backend

    def late_encoder(self):
        key = ("encoder", "")
        with self.lock:
            if self.encoder is not None:
                return self.encoder
            self.refuse_if_recently_failed(key)
            gate = self.gates.setdefault(key, threading.Lock())
            generation = self.generation
        with gate:
            with self.lock:
                if self.encoder is not None:
                    return self.encoder
                self.refuse_if_recently_failed(key)
            try:
                encoder = build_encoder(self.config, self.allow_stub)
            except ModelUnavailable as error:
                with self.lock:
                    self.remember_failure(key, error)
            with self.lock:
                if generation == self.generation:
                    self.encoder = encoder
            return encoder

    def reload(self):
        with self.lock:
            self.backends = {}
            self.encoder = None
            self.failures = {}
            self.generation += 1

    def rss_mb(self):
        usage = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
        return round(usage / (1024 * 1024) if sys.platform == "darwin" else usage / 1024, 1)

    def health(self):
        return {"status": "ok", "contract_version": CONTRACT_VERSION, "uptime_s": int(time.time() - self.started),
                "rss_mb": self.rss_mb(), "queue": {"size": self.config["queue_size"], "waiting": self.worker.jobs.qsize()},
                "warmed": self.warmed, "warm_ms": self.warm_ms,
                "models": [{"name": name, "loaded": True, "device": backend.info()["device"]} for name, backend in self.backends.items()]}

    def models(self):
        names = list(self.config.get("models", {}).keys()) + (["stub"] if self.allow_stub else [])
        result = []
        for name in names:
            entry = self.config.get("models", {}).get(name, {})
            result.append({"name": name, "revision": str(entry.get("revision", "stub-1" if name == "stub" else "unknown")),
                           "max_tokens": int(entry.get("max_tokens", 256)), "licence": entry.get("licence", "unverified"),
                           "checksum": "recorded" if entry.get("sha256") else "missing", "loaded": name in self.backends})
        return {"contract_version": CONTRACT_VERSION, "models": result}

    def rerank(self, body):
        self.purge_if_due()
        request = validate_rerank(body, self.config)
        backend = self.backend(request["model"])
        info = backend.info()
        started = time.time()
        deadline = started + request["deadline_ms"] / 1000.0
        passages = request["passages"]
        qhash = query_hash(request["query"])
        hashes = {item["id"]: text_hash(item["text"]) for item in passages}
        cached = self.db.cache_get(request["model"], qhash, list(hashes.values()))
        results = {}
        for item in passages:
            value = cached.get(hashes[item["id"]])
            if value is not None:
                results[item["id"]] = {"id": item["id"], "score": round(float(value), 4), "status": "scored"}
        pending = [item for item in passages if item["id"] not in results]
        batch = max(1, int(self.config["batch_size"]))

        def work():
            computed = {}
            for start in range(0, len(pending), batch):
                if time.time() >= deadline:
                    break
                chunk = pending[start:start + batch]
                scores = backend.score(request["query"], [item["text"] for item in chunk])
                for item, score in zip(chunk, scores):
                    computed[item["id"]] = score
            return computed

        if pending:
            try:
                computed = self.worker.submit(work, max(0.05, deadline - time.time()) + 2.0)
            except Busy:
                raise ProtocolError(429, "busy", "The model queue is full")
            fresh = {}
            for item in pending:
                if item["id"] in computed:
                    score = round(float(computed[item["id"]]), 4)
                    results[item["id"]] = {"id": item["id"], "score": score, "status": "scored"}
                    fresh[hashes[item["id"]]] = score
            if fresh:
                self.db.cache_put(request["model"], qhash, fresh)
        scored = sum(1 for item in results.values() if item["status"] == "scored")
        if scored == 0:
            raise ProtocolError(504, "deadline_exceeded", "No passage was scored before the deadline")
        ordered = [results.get(item["id"], {"id": item["id"], "score": None, "status": "skipped_deadline"}) for item in passages]
        return {"contract_version": CONTRACT_VERSION, "model": request["model"], "model_revision": info["revision"],
                "device": info["device"], "scored": scored, "skipped": len(passages) - scored,
                "elapsed_ms": int((time.time() - started) * 1000), "results": ordered}

    def upsert_passages(self, body):
        documents = validate_upsert(body, self.config)
        return {"contract_version": CONTRACT_VERSION, "documents": len(documents), "passages": self.db.upsert_documents(documents)}

    def missing_passages(self, body):
        require_dict(body)
        check_version(body, self.config["contract_version"])
        documents = body.get("documents")
        if not isinstance(documents, list):
            raise ProtocolError(400, "bad_request", "documents must be a list")
        if len(documents) > MAX_DOCS_PER_CALL:
            raise ProtocolError(400, "bad_request", f"at most {MAX_DOCS_PER_CALL} documents per call")
        for document in documents:
            if not isinstance(document, dict) or not all(isinstance(document.get(f), str) for f in ("origin_db", "source_archive", "file_path", "content_hash")):
                raise ProtocolError(400, "bad_request", "each document needs origin_db, source_archive, file_path and content_hash")
        return {"contract_version": CONTRACT_VERSION, "missing": self.db.missing_documents(documents)}

    def list_documents(self):
        return {"contract_version": CONTRACT_VERSION, "documents": self.db.all_documents()}

    def delete_passages(self, body):
        docs = validate_delete(body, self.config)
        return {"contract_version": CONTRACT_VERSION, "removed": self.db.delete_documents(docs)}

    def encode_query(self, body):
        require_dict(body)
        check_version(body, self.config["contract_version"])
        encoder = self.late_encoder()
        text = body.get("query")
        if not isinstance(text, str) or not text.strip():
            raise ProtocolError(400, "bad_request", "query is required")
        if len(text) > MAX_QUERY_CHARS:
            raise ProtocolError(400, "bad_request", f"query is longer than {MAX_QUERY_CHARS} characters")
        vectors = self.submit(lambda: encoder.encode_query(text))
        return {"contract_version": CONTRACT_VERSION, "model": encoder.model_id, "dimension": encoder.dimension, "tokens": len(vectors),
                "vectors": [[round(v, 4) for v in row] for row in vectors]}

    def submit(self, function, wait_seconds=60.0):
        try:
            return self.worker.submit(function, wait_seconds)
        except Busy:
            raise ProtocolError(429, "busy", "The model queue is full")

    def index_upsert(self, body):
        require_dict(body)
        check_version(body, self.config["contract_version"])
        encoder = self.late_encoder()
        batch = body.get("batch", 64)
        if not isinstance(batch, int) or batch < 1 or batch > 1000:
            raise ProtocolError(400, "bad_request", "batch must be between 1 and 1000")

        def work():
            rows = self.db.passages_without_vectors(encoder.model_id, batch)
            items = [(pid, encoder.encode_passage(text)) for pid, text in rows]
            if items:
                self.db.put_vectors(encoder.model_id, encoder.dimension, items)
            return len(items)

        encoded = self.submit(work)
        remaining = self.db.count_passages_without_vectors(encoder.model_id)
        self.db.set_manifest({"late_model_id": encoder.model_id, "dimension": encoder.dimension, "contract_version": CONTRACT_VERSION})
        return {"contract_version": CONTRACT_VERSION, "encoded": encoded, "remaining": remaining, "model": encoder.model_id}

    def search_late(self, body):
        require_dict(body)
        check_version(body, self.config["contract_version"])
        encoder = self.late_encoder()
        manifest = self.db.manifest()
        if manifest.get("late_model_id") not in (None, encoder.model_id):
            raise ProtocolError(409, "index_stale", "The index was built with another model; run reindex")
        text = body.get("query")
        if not isinstance(text, str) or not text.strip():
            raise ProtocolError(400, "bad_request", "query is required")
        if len(text) > MAX_QUERY_CHARS:
            raise ProtocolError(400, "bad_request", f"query is longer than {MAX_QUERY_CHARS} characters")
        top_k = body.get("top_k", 50)
        if not isinstance(top_k, int) or top_k < 1 or top_k > 500:
            raise ProtocolError(400, "bad_request", "top_k must be between 1 and 500")
        filters = body.get("filters") or {}
        if not isinstance(filters, dict):
            raise ProtocolError(400, "bad_request", "filters must be an object")
        for name in ("source_archive", "origin_db", "ticket"):
            if filters.get(name) is not None and not isinstance(filters[name], str):
                raise ProtocolError(400, "bad_request", f"filters.{name} must be text")
        started = time.time()
        docs = filters.get("docs")
        if docs is not None:
            if not isinstance(docs, list) or len(docs) > 500 or not all(
                    isinstance(d, dict) and all(isinstance(d.get(f), str) for f in ("origin_db", "source_archive", "file_path")) for d in docs):
                raise ProtocolError(400, "bad_request", "filters.docs must be a list of at most 500 documents")
        hits = self.submit(lambda: maxsim.search(self.db, encoder, text, top_k, filters.get("source_archive") and [filters["source_archive"]],
                                                 filters.get("origin_db"), filters.get("ticket"), docs))
        unindexed = self.db.count_passages_without_vectors(encoder.model_id)
        return {"contract_version": CONTRACT_VERSION, "model": encoder.model_id, "elapsed_ms": int((time.time() - started) * 1000), "results": hits,
                "complete": unindexed == 0, "unindexed": unindexed}

    def index_status(self):
        encoder = None
        try:
            encoder = self.late_encoder()
        except ProtocolError:
            pass
        counts = self.db.counts(encoder.model_id if encoder else None)
        manifest = self.db.manifest()
        stale = bool(encoder and manifest.get("late_model_id") not in (None, encoder.model_id))
        unindexed = self.db.count_passages_without_vectors(encoder.model_id) if encoder else None
        return {"contract_version": CONTRACT_VERSION, "counts": counts, "manifest": manifest, "stale": stale,
                "complete": unindexed == 0 if encoder else False, "unindexed": unindexed,
                "bytes_on_disk": self.db.file_size(), "late_model": encoder.model_id if encoder else None}


BUSY_REPLY = json.dumps({"error": "busy", "message": "Too many open connections"}).encode("utf-8")


class UnixHTTPServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    address_family = socket.AF_UNIX
    daemon_threads = True

    def __init__(self, address, handler, max_connections=32):
        super().__init__(address, handler)
        self.connection_slots = threading.BoundedSemaphore(max_connections)
        self.refusal_slots = threading.BoundedSemaphore(8)

    def refuse(self, request):
        try:
            request.settimeout(1.0)
            request.sendall(b"HTTP/1.1 429 Too Many Requests\r\nContent-Type: application/json\r\nConnection: close\r\n"
                            b"Content-Length: " + str(len(BUSY_REPLY)).encode() + b"\r\n\r\n" + BUSY_REPLY)
            request.shutdown(socket.SHUT_WR)
            deadline = time.time() + 1.0
            while time.time() < deadline:
                if not request.recv(65536):
                    break
        except OSError:
            pass
        finally:
            self.shutdown_request(request)
            self.refusal_slots.release()

    def process_request(self, request, client_address):
        if not self.connection_slots.acquire(blocking=False):
            if self.refusal_slots.acquire(blocking=False):
                threading.Thread(target=self.refuse, args=(request,), daemon=True).start()
            else:
                self.shutdown_request(request)
            return
        super().process_request(request, client_address)

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.connection_slots.release()

    def server_bind(self):
        socketserver.TCPServer.server_bind(self)
        self.server_name = "localhost"
        self.server_port = 0

    def get_request(self):
        request, _ = self.socket.accept()
        return request, ("local", 0)


ROUTES = {
    ("GET", "/v1/health"): lambda app, body: app.health(),
    ("GET", "/v1/models"): lambda app, body: app.models(),
    ("GET", "/v1/index/status"): lambda app, body: app.index_status(),
    ("POST", "/v1/rerank"): lambda app, body: app.rerank(body),
    ("POST", "/v1/passages/upsert"): lambda app, body: app.upsert_passages(body),
    ("POST", "/v1/passages/delete"): lambda app, body: app.delete_passages(body),
    ("POST", "/v1/passages/missing"): lambda app, body: app.missing_passages(body),
    ("GET", "/v1/passages/docs"): lambda app, body: app.list_documents(),
    ("POST", "/v1/encode/query"): lambda app, body: app.encode_query(body),
    ("POST", "/v1/index/upsert"): lambda app, body: app.index_upsert(body),
    ("POST", "/v1/search/late"): lambda app, body: app.search_late(body),
    ("POST", "/v1/admin/reload"): lambda app, body: (app.reload() or {"contract_version": CONTRACT_VERSION, "reloaded": True}),
}


def make_handler(app):
    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        server_version = "ai-memory-service/1"
        timeout = 15

        def log_message(self, format, *args):
            return

        def address_string(self):
            return "local"

        def respond(self, status, payload, started, note=""):
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            shown = "".join(c if c.isprintable() else "?" for c in self.path)[:80]
            print(f"{time.strftime('%Y-%m-%dT%H:%M:%S')} {self.command} {shown} {status} {int((time.time() - started) * 1000)}ms {note}".strip(),
                  file=sys.stderr, flush=True)

        def handle_any(self):
            started = time.time()
            try:
                if self.headers.get("Origin") is not None:
                    self.close_connection = True
                    raise ProtocolError(403, "forbidden", "Requests with an Origin header are refused")
                supplied = (self.headers.get("Authorization") or "")
                expected = f"Bearer {app.token}"
                if not hmac.compare_digest(supplied.encode("utf-8"), expected.encode("utf-8")):
                    self.close_connection = True
                    raise ProtocolError(401, "unauthorized", "Missing or wrong token")
                try:
                    length = int(self.headers.get("Content-Length") or 0)
                except ValueError:
                    self.close_connection = True
                    raise ProtocolError(400, "bad_request", "Content-Length is not a number")
                if length < 0:
                    self.close_connection = True
                    raise ProtocolError(400, "bad_request", "Content-Length is negative")
                if length > app.config["max_body_bytes"]:
                    self.close_connection = True
                    raise ProtocolError(400, "bad_request", "The body is too large")
                raw = self.read_body(length, app.config["body_deadline_s"]) if length else b""
                route = ROUTES.get((self.command, self.path.split("?")[0]))
                if route is None:
                    raise ProtocolError(404, "not_found", "Unknown endpoint")
                body = None
                if self.command == "POST":
                    try:
                        body = json.loads(raw.decode("utf-8") or "{}")
                    except (ValueError, UnicodeDecodeError):
                        raise ProtocolError(400, "bad_request", "The body is not valid JSON")
                payload = route(app, body)
                self.respond(200, payload, started)
            except ProtocolError as error:
                self.respond(error.status, {"error": error.code, "message": error.message}, started, error.code)
            except Exception as error:  # noqa: BLE001
                self.respond(500, {"error": "internal_error", "message": type(error).__name__}, started, "internal_error")

        def read_body(self, length, deadline_seconds):
            deadline = time.time() + deadline_seconds
            chunks = []
            remaining = length
            while remaining > 0:
                left = deadline - time.time()
                if left <= 0:
                    self.close_connection = True
                    raise ProtocolError(408, "request_timeout", "The request body arrived too slowly")
                self.connection.settimeout(min(self.timeout, left))
                try:
                    chunk = self.rfile.read1(min(remaining, 65536))
                except (socket.timeout, TimeoutError):
                    self.close_connection = True
                    raise ProtocolError(408, "request_timeout", "The request body arrived too slowly")
                if not chunk:
                    self.close_connection = True
                    raise ProtocolError(400, "bad_request", "The request body is shorter than Content-Length")
                chunks.append(chunk)
                remaining -= len(chunk)
            return b"".join(chunks)

        do_GET = handle_any
        do_POST = handle_any

    return Handler
