import json
import os
import shutil
import stat
import subprocess
import sys
import threading
import time
import unittest
from pathlib import Path

from helpers import ROOT, ServiceFixture, raw_request, short_temp

from ai_memory_service import backends

PASSAGES = [
    {"id": "p1", "text": "the nginx ingress timeout is raised to 120 seconds"},
    {"id": "p2", "text": "lunch plans for friday"},
    {"id": "p3", "text": "nginx notes only"},
]


class ServerTest(unittest.TestCase):
    def setUp(self):
        self.fx = ServiceFixture()

    def tearDown(self):
        self.fx.close()

    def rerank(self, **extra):
        body = {"contract_version": 1, "query": "nginx timeout", "model": "stub", "passages": PASSAGES}
        body.update(extra)
        return self.fx.request("POST", "/v1/rerank", body)

    def test_token_and_origin_are_enforced(self):
        self.assertEqual(self.fx.request("GET", "/v1/health", token=None)[0], 401)
        self.assertEqual(self.fx.request("GET", "/v1/health", token="wrong")[0], 401)
        status, body = self.fx.request("GET", "/v1/health")
        self.assertEqual((status, body["status"], body["contract_version"]), (200, "ok", 1))
        self.assertEqual(self.fx.request("GET", "/v1/health", headers={"Origin": "http://evil.example"})[0], 403)

    def test_bad_requests_get_clear_errors(self):
        self.assertEqual(self.fx.request("GET", "/v1/nothing")[0], 404)
        status, body = self.fx.request("POST", "/v1/rerank", {"contract_version": 2, "query": "q", "passages": PASSAGES})
        self.assertEqual((status, body["error"]), (409, "contract_mismatch"))
        self.assertEqual(self.rerank(passages=[])[0], 400)
        self.assertEqual(self.rerank(model="missing")[1]["error"], "unknown_model")

    def test_rerank_scores_in_request_order_with_metadata(self):
        status, body = self.rerank()
        self.assertEqual(status, 200)
        self.assertEqual([r["id"] for r in body["results"]], ["p1", "p2", "p3"])
        self.assertEqual([r["score"] for r in body["results"]], [1.0, 0.0, 0.5])
        self.assertEqual((body["scored"], body["skipped"], body["model"], body["device"]), (3, 0, "stub", "cpu"))
        self.assertIn("model_revision", body)

    def test_the_stub_model_is_refused_unless_enabled(self):
        fx = ServiceFixture(allow_stub=False)
        try:
            status, body = fx.request("POST", "/v1/rerank", {"query": "q", "model": "stub", "passages": PASSAGES})
            self.assertEqual((status, body["error"]), (503, "model_unavailable"))
        finally:
            fx.close()

    def test_scores_are_cached_and_served_without_the_model(self):
        self.assertEqual(self.rerank()[0], 200)
        self.assertEqual(self.fx.app.db.counts()["score_cache"], 3)

        def explode(query, texts):
            raise AssertionError("the model must not be called for cached scores")

        self.fx.app.backends["stub"].score = explode
        status, body = self.rerank()
        self.assertEqual((status, body["scored"]), (200, 3))

    def test_deadline_stops_scoring_between_batches(self):
        class Slow:
            name = "slow"

            def info(self):
                return {"revision": "s", "device": "cpu", "max_tokens": 256}

            def score(self, query, texts):
                time.sleep(0.25)
                return [0.5 for _ in texts]

        self.fx.app.backends["slow"] = Slow()
        self.fx.app.config["batch_size"] = 1
        passages = [{"id": f"p{i}", "text": f"text {i}"} for i in range(6)]
        status, body = self.fx.request("POST", "/v1/rerank", {"query": "q", "model": "slow", "passages": passages, "deadline_ms": 300})
        self.assertEqual(status, 200)
        statuses = [r["status"] for r in body["results"]]
        self.assertIn("skipped_deadline", statuses)
        self.assertIn("scored", statuses)
        self.assertEqual(body["scored"] + body["skipped"], 6)
        self.assertTrue(all(r["score"] is None for r in body["results"] if r["status"] == "skipped_deadline"))

    def test_a_full_queue_returns_429(self):
        gate = threading.Event()

        class Blocking:
            name = "blocking"

            def info(self):
                return {"revision": "b", "device": "cpu", "max_tokens": 256}

            def score(self, query, texts):
                gate.wait(5)
                return [0.1 for _ in texts]

        self.fx.app.backends["blocking"] = Blocking()
        results = []

        def call(index):
            results.append(self.fx.request("POST", "/v1/rerank", {"query": f"q{index}", "model": "blocking",
                                                                 "passages": [{"id": "a", "text": f"t{index}"}], "deadline_ms": 4000})[0])

        threads = [threading.Thread(target=call, args=(i,)) for i in range(6)]
        for thread in threads:
            thread.start()
            time.sleep(0.05)
        time.sleep(0.3)
        gate.set()
        for thread in threads:
            thread.join()
        self.assertIn(429, results)
        self.assertIn(200, results)

    def test_passages_upsert_and_delete(self):
        document = {"origin_db": "convo.db", "source_archive": "chat:turns", "file_path": "x.md", "content_hash": "h",
                    "passages": [{"section": "user", "start": 0, "end": 5, "tokens": 1, "text": "hello"}]}
        status, body = self.fx.request("POST", "/v1/passages/upsert", {"documents": [document]})
        self.assertEqual((status, body["passages"]), (200, 1))
        status, body = self.fx.request("POST", "/v1/passages/delete", {"docs": [{"origin_db": "convo.db", "source_archive": "chat:turns", "file_path": "x.md"}]})
        self.assertEqual((status, body["removed"]), (200, 1))
        self.assertEqual(self.fx.request("POST", "/v1/passages/upsert", {"documents": [{"origin_db": "x"}]})[0], 400)

    def test_missing_and_docs_endpoints_support_incremental_indexing(self):
        document = {"origin_db": "convo.db", "source_archive": "chat:turns", "file_path": "x.md", "content_hash": "h1",
                    "passages": [{"section": "user", "start": 0, "end": 5, "tokens": 1, "text": "hello"}]}
        self.fx.request("POST", "/v1/passages/upsert", {"documents": [document]})
        keys = [{k: document[k] for k in ("origin_db", "source_archive", "file_path", "content_hash")},
                {**{k: document[k] for k in ("origin_db", "source_archive", "file_path")}, "content_hash": "changed"},
                {"origin_db": "convo.db", "source_archive": "chat:turns", "file_path": "new.md", "content_hash": "h"}]
        status, body = self.fx.request("POST", "/v1/passages/missing", {"documents": keys})
        self.assertEqual((status, body["missing"]), (200, [1, 2]))
        status, body = self.fx.request("GET", "/v1/passages/docs")
        self.assertEqual((status, [d["file_path"] for d in body["documents"]]), (200, ["x.md"]))
        self.assertEqual(self.fx.request("POST", "/v1/passages/missing", {"documents": [{"origin_db": "x"}]})[0], 400)

    def test_the_service_touches_no_file_besides_its_own_database(self):
        import hashlib
        neighbours = []
        for name in ("convo.db", "worklogs.db", "artifacts.db", "dross.db", "journal.db"):
            path = self.fx.directory / name
            path.write_bytes(name.encode() * 100)
            neighbours.append((path, hashlib.sha256(path.read_bytes()).hexdigest()))
        self.rerank()
        self.fx.request("POST", "/v1/passages/upsert", {"documents": [{"origin_db": "convo.db", "source_archive": "chat:turns", "file_path": "x.md",
                         "content_hash": "h", "passages": [{"section": "user", "start": 0, "end": 5, "tokens": 1, "text": "hello"}]}]})
        self.fx.request("POST", "/v1/index/upsert", {"batch": 10})
        self.fx.request("POST", "/v1/search/late", {"query": "hello", "top_k": 3})
        for path, digest in neighbours:
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), digest, path.name)

    def send_raw(self, head_lines, body=b""):
        import socket as socketlib
        request = ("\r\n".join(head_lines) + "\r\n\r\n").encode() + body
        with socketlib.socket(socketlib.AF_UNIX, socketlib.SOCK_STREAM) as sock:
            sock.settimeout(5)
            sock.connect(str(self.fx.sock))
            sock.sendall(request)
            data = b""
            while True:
                chunk = sock.recv(65536)
                if not chunk:
                    break
                data += chunk
        return int(data.split(b" ", 2)[1])

    def test_a_bad_content_length_is_refused_not_waited_for(self):
        base = ["POST /v1/rerank HTTP/1.1", "Host: local", "Authorization: Bearer test-token", "Connection: close"]
        self.assertEqual(self.send_raw(base + ["Content-Length: abc"]), 400)
        self.assertEqual(self.send_raw(base + ["Content-Length: -5"]), 400)
        self.assertEqual(self.send_raw(base + [f"Content-Length: {5 * 1024 * 1024}"]), 400)

    def test_a_refused_request_closes_the_connection(self):
        import socket as socketlib
        follow_up = b"GET /v1/health HTTP/1.1\r\nHost: local\r\nAuthorization: Bearer test-token\r\n\r\n"
        for extra in ("Authorization: Bearer wrong", "Authorization: Bearer test-token\r\nOrigin: http://evil.example"):
            head = f"POST /v1/rerank HTTP/1.1\r\nHost: local\r\n{extra}\r\nContent-Length: {len(follow_up)}\r\n\r\n".encode()
            with socketlib.socket(socketlib.AF_UNIX, socketlib.SOCK_STREAM) as sock:
                sock.settimeout(3)
                sock.connect(str(self.fx.sock))
                sock.sendall(head + follow_up)
                data = b""
                while True:
                    chunk = sock.recv(65536)
                    if not chunk:
                        break
                    data += chunk
            self.assertEqual(data.count(b"HTTP/1."), 1, extra)

    def test_an_idle_connection_is_dropped_by_a_timeout(self):
        from ai_memory_service import server as server_module
        handler = server_module.make_handler(self.fx.app)
        self.assertGreater(handler.timeout, 0)
        self.assertLessEqual(handler.timeout, 30)

    def test_a_config_flag_cannot_enable_the_stub_model(self):
        fx = ServiceFixture(allow_stub=False, overrides={"allow_stub": True})
        try:
            status, body = fx.request("POST", "/v1/rerank", {"query": "q", "model": "stub", "passages": PASSAGES})
            self.assertEqual((status, body["error"]), (503, "model_unavailable"))
        finally:
            fx.close()

    def test_log_lines_hide_control_characters_and_long_paths(self):
        import io
        import contextlib
        captured = io.StringIO()
        with contextlib.redirect_stderr(captured):
            self.fx.request("GET", "/v1/" + "a" * 300 + "%0d%0aFAKE")
        line = captured.getvalue().strip().splitlines()[-1]
        self.assertLess(len(line), 160)
        self.assertEqual(len(captured.getvalue().strip().splitlines()), 1)

    def test_candidate_documents_can_be_scored_alone(self):
        documents = [{"origin_db": "convo.db", "source_archive": "chat:turns", "file_path": name, "content_hash": "h",
                      "passages": [{"section": "user", "start": 0, "end": 20, "tokens": 4, "text": text}]}
                     for name, text in (("a.md", "nginx timeout raised"), ("b.md", "nginx timeout also here"), ("c.md", "nothing related"))]
        self.fx.request("POST", "/v1/passages/upsert", {"documents": documents})
        self.fx.request("POST", "/v1/index/upsert", {"batch": 10})
        only = [{"origin_db": "convo.db", "source_archive": "chat:turns", "file_path": "b.md"}, {"origin_db": "convo.db", "source_archive": "chat:turns", "file_path": "c.md"}]
        status, body = self.fx.request("POST", "/v1/search/late", {"query": "nginx timeout", "top_k": 10, "filters": {"docs": only}})
        self.assertEqual((status, [r["file_path"] for r in body["results"]]), (200, ["b.md", "c.md"]))
        self.assertEqual(self.fx.request("POST", "/v1/search/late", {"query": "x", "top_k": 5, "filters": {"docs": "no"}})[0], 400)

    def test_models_listing_marks_licences_unverified(self):
        status, body = self.fx.request("GET", "/v1/models")
        self.assertEqual(status, 200)
        self.assertEqual(body["models"][0]["name"], "stub")
        self.assertEqual(body["models"][0]["licence"], "unverified")

    def test_late_interaction_round_trip(self):
        documents = [
            {"origin_db": "convo.db", "source_archive": "chat:turns", "file_path": "a.md", "content_hash": "h",
             "passages": [{"section": "user", "start": 0, "end": 40, "tokens": 8, "text": "the nginx timeout is raised to 120 seconds"}]},
            {"origin_db": "convo.db", "source_archive": "chat:turns", "file_path": "b.md", "content_hash": "h",
             "passages": [{"section": "user", "start": 0, "end": 30, "tokens": 5, "text": "backup retention is thirty days"}]},
        ]
        self.assertEqual(self.fx.request("POST", "/v1/passages/upsert", {"documents": documents})[0], 200)
        status, body = self.fx.request("POST", "/v1/index/upsert", {"batch": 1})
        self.assertEqual((status, body["encoded"], body["remaining"]), (200, 1, 1))
        status, body = self.fx.request("POST", "/v1/index/upsert", {"batch": 10})
        self.assertEqual((body["encoded"], body["remaining"]), (1, 0))
        status, body = self.fx.request("POST", "/v1/search/late", {"query": "nginx timeout", "top_k": 5})
        self.assertEqual(status, 200)
        self.assertEqual(body["results"][0]["file_path"], "a.md")
        status, body = self.fx.request("POST", "/v1/search/late", {"query": "nginx timeout", "top_k": 5, "filters": {"ticket": "b.md"}})
        self.assertEqual([r["file_path"] for r in body["results"]], ["b.md"])
        status, body = self.fx.request("GET", "/v1/index/status")
        self.assertEqual((body["counts"]["vectors"], body["stale"]), (2, False))
        self.assertEqual(self.fx.request("POST", "/v1/search/late", {"query": "x", "top_k": 0})[0], 400)
        status, body = self.fx.request("POST", "/v1/encode/query", {"query": "nginx timeout"})
        self.assertEqual((status, body["tokens"]), (200, 2))

    def test_late_search_rejects_badly_typed_filters(self):
        for filters in ("x", {"source_archive": 5}, {"origin_db": ["a"]}, {"ticket": 7}):
            status, body = self.fx.request("POST", "/v1/search/late", {"query": "nginx", "top_k": 3, "filters": filters})
            self.assertEqual((status, body["error"]), (400, "bad_request"), str(filters))

    def test_upsert_with_a_bad_number_is_a_400_not_a_500(self):
        document = {"origin_db": "convo.db", "source_archive": "chat:turns", "file_path": "a.md", "content_hash": "h",
                    "passages": [{"section": "user", "start": "zero", "end": 5, "tokens": 1, "text": "hello"}]}
        self.assertEqual(self.fx.request("POST", "/v1/passages/upsert", {"documents": [document]})[0], 400)

    def test_delete_and_missing_lists_are_capped(self):
        from ai_memory_service.protocol import MAX_DOCS_PER_CALL
        docs = [{"origin_db": "convo.db", "source_archive": "chat:turns", "file_path": f"{i}.md"} for i in range(MAX_DOCS_PER_CALL + 1)]
        self.assertEqual(self.fx.request("POST", "/v1/passages/delete", {"docs": docs})[0], 400)
        documents = [dict(doc, content_hash="h") for doc in docs]
        self.assertEqual(self.fx.request("POST", "/v1/passages/missing", {"documents": documents})[0], 400)

    def test_old_cached_scores_are_purged_when_due(self):
        self.assertEqual(self.rerank()[0], 200)
        self.assertEqual(self.fx.app.db.counts()["score_cache"], 3)
        self.fx.app.db.connection.execute("UPDATE score_cache SET created_at = 0")
        self.fx.app.last_purge = 0.0
        self.assertEqual(self.fx.app.purge_if_due(), 3)
        self.assertEqual(self.fx.app.db.counts()["score_cache"], 0)
        self.assertEqual(self.fx.app.purge_if_due(), 0)

    def test_a_stale_index_is_refused(self):
        self.fx.app.db.set_manifest({"late_model_id": "another-model"})
        status, body = self.fx.request("POST", "/v1/search/late", {"query": "nginx", "top_k": 3})
        self.assertEqual((status, body["error"]), (409, "index_stale"))

    def test_no_late_model_without_a_decision(self):
        fx = ServiceFixture(allow_stub=False)
        try:
            status, body = fx.request("POST", "/v1/search/late", {"query": "nginx", "top_k": 3})
            self.assertEqual((status, body["error"]), (503, "model_unavailable"))
        finally:
            fx.close()

    def test_logs_hold_no_text(self):
        import io
        import contextlib
        captured = io.StringIO()
        with contextlib.redirect_stderr(captured):
            self.rerank(query="secret-query-text")
        self.assertIn("/v1/rerank 200", captured.getvalue())
        self.assertNotIn("secret-query-text", captured.getvalue())
        self.assertNotIn("nginx ingress", captured.getvalue())


class BackendTest(unittest.TestCase):
    def test_cross_encoder_refuses_unsafe_or_unverified_models(self):
        directory = Path(short_temp())
        try:
            entry = {"path": str(directory), "sha256": "wrong"}
            backend = backends.CrossEncoderBackend("m", entry, directory)
            (directory / "model.safetensors").write_bytes(b"w")
            with self.assertRaises(backends.ModelUnavailable) as caught:
                backend.verify()
            self.assertIn("checksum mismatch", str(caught.exception))
            (directory / "pytorch_model.bin").write_bytes(b"p")
            with self.assertRaises(backends.ModelUnavailable) as caught:
                backend.verify()
            self.assertIn("unsafe", str(caught.exception))
            with self.assertRaises(backends.ModelUnavailable):
                backends.CrossEncoderBackend("m", {"path": str(directory / "nope")}, directory).verify()
            (directory / "pytorch_model.bin").unlink()
            (directory / "big.bin.txt").write_bytes(b"x" * (2 * 1024 * 1024))
            with self.assertRaises(backends.ModelUnavailable) as caught:
                backends.CrossEncoderBackend("m", {"path": str(directory), "sha256": "x"}, directory, max_mb=1).verify()
            self.assertIn("above the limit", str(caught.exception))
            (directory / "big.bin.txt").unlink()
            (directory / "pytorch_model.bin").write_bytes(b"p")
            with self.assertRaises(backends.ModelUnavailable) as caught:
                backends.CrossEncoderBackend("m", {"path": str(directory)}, directory).verify()
        finally:
            shutil.rmtree(directory, ignore_errors=True)

    def test_cross_encoder_without_packages_is_unavailable(self):
        from ai_memory_service.integrity import tree_sha256
        directory = Path(short_temp())
        try:
            (directory / "model.safetensors").write_bytes(b"w")
            entry = {"path": str(directory), "sha256": tree_sha256(directory)}
            try:
                import sentence_transformers  # noqa: F401
                self.skipTest("sentence_transformers is installed here")
            except Exception:
                pass
            with self.assertRaises(backends.ModelUnavailable) as caught:
                backends.CrossEncoderBackend("m", entry, directory).load()
            self.assertIn("sentence_transformers", str(caught.exception))
        finally:
            shutil.rmtree(directory, ignore_errors=True)

    def test_stub_backend_needs_permission(self):
        with self.assertRaises(backends.ModelUnavailable):
            backends.build_backend("stub", {"models": {}, "model_cache": "/tmp"}, False)
        self.assertEqual(backends.build_backend("stub", {"models": {}, "model_cache": "/tmp"}, True).name, "stub")
        with self.assertRaises(KeyError):
            backends.build_backend("missing", {"models": {}, "model_cache": "/tmp"}, True)

    @unittest.skipUnless(os.environ.get("RUN_MODEL_TESTS") == "1", "set RUN_MODEL_TESTS=1 with a cached model")
    def test_real_model_scores_a_relevant_passage_higher(self):
        entry = json.loads(os.environ["AI_MEMORY_TEST_MODEL_ENTRY"])
        backend = backends.CrossEncoderBackend("real", entry, entry.get("model_cache", "/tmp")).load()
        scores = backend.score("nginx timeout", ["the nginx timeout is 120 seconds", "lunch plans"])
        self.assertGreater(scores[0], scores[1])
        self.assertEqual(scores, backend.score("nginx timeout", ["the nginx timeout is 120 seconds", "lunch plans"]))


class CommandLineTest(unittest.TestCase):
    def test_serve_status_stop_and_file_modes(self):
        directory = Path(short_temp())
        launcher = ROOT / "service" / "ai-memory-service"
        env = {**os.environ, "AI_MEMORY_SERVICE_SOCKET": ""}
        env.pop("AI_MEMORY_SERVICE_SOCKET")
        base = [str(launcher), "--workspace-root", str(directory), "--db", str(directory / "semantic.db")]
        process = subprocess.Popen(base + ["serve", "--stub"], env=env, stderr=subprocess.PIPE, text=True)
        try:
            sock = directory / ".ai-worklog" / "run" / "memory-service.sock"
            for _ in range(100):
                if sock.exists():
                    break
                time.sleep(0.05)
            self.assertTrue(sock.exists())
            self.assertEqual(stat.S_IMODE(os.stat(sock).st_mode), 0o600)
            self.assertEqual(stat.S_IMODE(os.stat(sock.parent).st_mode), 0o700)
            self.assertEqual(stat.S_IMODE(os.stat(sock.parent / "memory-service.token").st_mode), 0o600)
            status = subprocess.run(base + ["status"], env=env, capture_output=True, text=True)
            self.assertEqual(status.returncode, 0, status.stdout + status.stderr)
            self.assertTrue(json.loads(status.stdout)["running"])
            network = subprocess.run(["lsof", "-nP", "-a", "-p", str(process.pid), "-i"], capture_output=True, text=True)
            self.assertEqual(network.stdout.strip(), "", "the service must hold no network socket")
            second = subprocess.run(base + ["serve", "--stub"], env=env, capture_output=True, text=True)
            self.assertEqual(second.returncode, 1)
            reaper = threading.Thread(target=process.wait, daemon=True)
            reaper.start()
            stop = subprocess.run(base + ["stop"], env=env, capture_output=True, text=True)
            self.assertEqual(stop.returncode, 0, stop.stdout + stop.stderr)
            reaper.join(timeout=10)
            self.assertFalse(sock.exists())
            self.assertFalse((sock.parent / "memory-service.token").exists())
            self.assertEqual(subprocess.run(base + ["status"], env=env, capture_output=True, text=True).returncode, 1)
        finally:
            if process.poll() is None:
                process.kill()
            shutil.rmtree(directory, ignore_errors=True)

    def test_stop_never_signals_a_process_that_is_not_the_service(self):
        directory = Path(short_temp())
        env = {key: value for key, value in os.environ.items() if key != "AI_MEMORY_SERVICE_SOCKET"}
        run_dir = directory / ".ai-worklog" / "run"
        run_dir.mkdir(parents=True)
        bystander = subprocess.Popen(["sleep", "30"])
        try:
            (run_dir / "memory-service.pid").write_text(str(bystander.pid))
            stop = subprocess.run([str(ROOT / "service" / "ai-memory-service"), "--workspace-root", str(directory), "stop"], env=env, capture_output=True, text=True)
            self.assertEqual(stop.returncode, 0, stop.stdout + stop.stderr)
            self.assertIn("did not belong", stop.stdout)
            self.assertIsNone(bystander.poll())
            self.assertFalse((run_dir / "memory-service.pid").exists())
        finally:
            bystander.kill()
            bystander.wait()
            shutil.rmtree(directory, ignore_errors=True)

    def test_serve_refuses_to_change_a_shared_directory(self):
        directory = Path(short_temp())
        shared = directory / "shared"
        shared.mkdir()
        (shared / "other-file.txt").write_text("not ours")
        os.chmod(shared, 0o755)
        env = {**os.environ, "AI_MEMORY_SERVICE_SOCKET": str(shared / "s.sock")}
        try:
            result = subprocess.run([str(ROOT / "service" / "ai-memory-service"), "--workspace-root", str(directory), "--db", str(directory / "semantic.db"), "serve", "--stub"],
                                    env=env, capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 2, result.stderr)
            self.assertIn("other files", result.stderr)
            self.assertEqual(stat.S_IMODE(os.stat(shared).st_mode), 0o755)
            self.assertFalse((shared / "memory-service.token").exists())
        finally:
            shutil.rmtree(directory, ignore_errors=True)

    def test_a_too_long_socket_path_is_refused_with_a_clear_message(self):
        directory = Path(short_temp())
        try:
            long_dir = directory / ("d" * 90)
            launcher = ROOT / "service" / "ai-memory-service"
            result = subprocess.run([str(launcher), "--workspace-root", str(long_dir), "serve", "--stub"], capture_output=True, text=True)
            self.assertEqual(result.returncode, 2)
            self.assertIn("too long for a Unix socket", result.stderr)
        finally:
            shutil.rmtree(directory, ignore_errors=True)

    def test_doctor_reports_checks_and_fails_without_a_real_setup(self):
        directory = Path(short_temp())
        try:
            launcher = ROOT / "service" / "ai-memory-service"
            result = subprocess.run([str(launcher), "--workspace-root", str(directory), "doctor"], capture_output=True, text=True,
                                    env={k: v for k, v in os.environ.items() if k not in ("HF_HUB_OFFLINE", "TRANSFORMERS_OFFLINE")})
            self.assertEqual(result.returncode, 1)
            self.assertIn("PASS\trun directory mode 0700", result.stdout)
            self.assertIn("FAIL\toffline variables", result.stdout)
            self.assertIn("python version", result.stdout)
        finally:
            shutil.rmtree(directory, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
