import shutil
import sys
import unittest
from pathlib import Path

from helpers import ROOT, short_temp  # noqa: F401

from ai_memory_service import protocol
from ai_memory_service.backends import StubBackend, fold, words
from ai_memory_service.config import DEFAULTS
from ai_memory_service.encoders import StubEncoder
from ai_memory_service.integrity import tree_sha256, weight_problems
from ai_memory_service.maxsim import maxsim, search
from ai_memory_service.store import SemanticDb, dequantize, quantize


class ProtocolTest(unittest.TestCase):
    def config(self):
        return dict(DEFAULTS)

    def test_rerank_validation_accepts_a_good_request(self):
        request = protocol.validate_rerank({"query": "q", "passages": [{"id": "a", "text": "t"}]}, self.config())
        self.assertEqual(request["model"], "small-multilingual")
        self.assertEqual(request["deadline_ms"], 1200)

    def test_rerank_validation_rejects_bad_requests(self):
        cases = [
            {"passages": [{"id": "a", "text": "t"}]},
            {"query": "q", "passages": []},
            {"query": "q", "passages": [{"id": "a"}]},
            {"query": "q", "passages": [{"id": "a", "text": "t"}, {"id": "a", "text": "u"}]},
            {"query": "q", "passages": [{"id": "a", "text": "x" * 4001}]},
            {"query": "q", "passages": [{"id": str(i), "text": "t"} for i in range(101)]},
            {"query": "q", "passages": [{"id": "a", "text": "t"}], "deadline_ms": 5},
            "not an object",
        ]
        for body in cases:
            with self.assertRaises(protocol.ProtocolError) as caught:
                protocol.validate_rerank(body, self.config())
            self.assertEqual(caught.exception.status, 400)

    def test_contract_mismatch_is_409(self):
        with self.assertRaises(protocol.ProtocolError) as caught:
            protocol.validate_rerank({"contract_version": 2, "query": "q", "passages": [{"id": "a", "text": "t"}]}, self.config())
        self.assertEqual(caught.exception.status, 409)

    def test_hashes_are_stable_and_query_is_normalized(self):
        self.assertEqual(protocol.query_hash("  What  IS this "), protocol.query_hash("what is this"))
        self.assertNotEqual(protocol.text_hash("a"), protocol.text_hash("b"))
        self.assertEqual(protocol.passage_id("a", "b", "c", "d", 1, 2), protocol.passage_id("a", "b", "c", "d", 1, 2))

    def test_upsert_validation(self):
        good = {"documents": [{"origin_db": "convo.db", "source_archive": "chat:turns", "file_path": "x.md", "content_hash": "h",
                               "passages": [{"section": "user", "start": 0, "end": 5, "tokens": 2, "text": "hello"}]}]}
        self.assertEqual(len(protocol.validate_upsert(good, self.config())), 1)
        with self.assertRaises(protocol.ProtocolError):
            protocol.validate_upsert({"documents": [{"origin_db": "x"}]}, self.config())


class TextTest(unittest.TestCase):
    def test_fold_matches_the_index_and_the_stub_scores_overlap(self):
        self.assertEqual(fold("Użytkowników Łódź"), "uzytkownikow lodz")
        self.assertEqual(words("It's the nginx timeout, 120s"), ["it", "s", "the", "nginx", "timeout", "120s"])
        scores = StubBackend().score("nginx timeout", ["nginx timeout is 120 seconds", "unrelated words", "nginx only"])
        self.assertEqual(scores, [1.0, 0.0, 0.5])


class StoreTest(unittest.TestCase):
    def setUp(self):
        self.directory = Path(short_temp())
        self.db = SemanticDb(self.directory / "semantic.db")

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.directory, ignore_errors=True)

    def document(self, path="a.md", content_hash="h1", texts=("first passage", "second passage")):
        return {"origin_db": "convo.db", "source_archive": "chat:turns", "file_path": path, "content_hash": content_hash,
                "passages": [{"section": "user", "start": i * 100, "end": i * 100 + 50, "tokens": 3, "text": text} for i, text in enumerate(texts)]}

    def test_upsert_replaces_the_passages_of_a_document(self):
        self.assertEqual(self.db.upsert_documents([self.document()]), 2)
        self.assertEqual(self.db.counts()["passages"], 2)
        self.db.upsert_documents([self.document(content_hash="h2", texts=("only one",))])
        self.assertEqual(self.db.counts()["passages"], 1)

    def test_delete_removes_passages_and_vectors(self):
        self.db.upsert_documents([self.document(), self.document("b.md")])
        encoder = StubEncoder()
        for pid, text in self.db.passages_without_vectors(encoder.model_id, 10):
            self.db.put_vectors(encoder.model_id, encoder.dimension, [(pid, encoder.encode_passage(text))])
        self.assertEqual(self.db.counts()["vectors"], 4)
        removed = self.db.delete_documents([{"origin_db": "convo.db", "source_archive": "chat:turns", "file_path": "a.md"}])
        self.assertEqual(removed, 2)
        counts = self.db.counts()
        self.assertEqual((counts["passages"], counts["vectors"]), (2, 2))

    def test_score_cache_round_trip_and_expiry(self):
        self.db.cache_put("m", "q", {"h1": 0.5, "h2": 0.25})
        self.assertEqual(self.db.cache_get("m", "q", ["h1", "h2", "h3"]), {"h1": 0.5, "h2": 0.25})
        self.assertEqual(self.db.cache_get("other", "q", ["h1"]), {})
        self.assertEqual(self.db.purge_cache(14), 0)
        self.assertEqual(self.db.purge_cache(-1), 2)

    def test_orphans_are_listed(self):
        self.db.upsert_documents([self.document()])
        self.assertEqual(len(self.db.orphans([("convo.db", "chat:turns", "a.md", "h1")])), 0)
        self.assertEqual(len(self.db.orphans([("convo.db", "chat:turns", "a.md", "changed")])), 1)

    def test_quantization_round_trip(self):
        matrix = [[0.5, -0.25, 0.0], [1.0, 0.1, -1.0]]
        scale, blob = quantize(matrix)
        restored = dequantize(blob, scale, 2, 3)
        for row, original in zip(restored, matrix):
            for value, expected in zip(row, original):
                self.assertAlmostEqual(value, expected, delta=0.01)
        self.assertEqual(quantize([[0.0, 0.0]])[0], 1.0)


class MaxSimTest(unittest.TestCase):
    def setUp(self):
        self.directory = Path(short_temp())
        self.db = SemanticDb(self.directory / "semantic.db")
        self.encoder = StubEncoder()

    def tearDown(self):
        self.db.close()
        shutil.rmtree(self.directory, ignore_errors=True)

    def index(self, path, text, archive="chat:turns"):
        self.db.upsert_documents([{"origin_db": "convo.db", "source_archive": archive, "file_path": path, "content_hash": "h",
                                   "passages": [{"section": "user", "start": 0, "end": len(text), "tokens": len(text.split()), "text": text}]}])
        for pid, passage in self.db.passages_without_vectors(self.encoder.model_id, 100):
            self.db.put_vectors(self.encoder.model_id, self.encoder.dimension, [(pid, self.encoder.encode_passage(passage))])

    def test_maxsim_is_higher_for_shared_words(self):
        query = self.encoder.encode_query("nginx timeout")
        same = maxsim(query, self.encoder.encode_passage("the nginx timeout is raised"))
        other = maxsim(query, self.encoder.encode_passage("lunch plans for friday"))
        self.assertGreater(same, other)
        self.assertAlmostEqual(same, 2.0, delta=0.05)

    def test_search_returns_the_best_passage_first_and_honours_filters(self):
        self.index("fix101/a.md", "the nginx timeout is raised to 120 seconds")
        self.index("fix102/b.md", "backup retention is thirty days")
        self.index("fix102/c.md", "nginx notes", archive="worklog:chunks")
        hits = search(self.db, self.encoder, "nginx timeout", 3)
        self.assertEqual(hits[0]["file_path"], "fix101/a.md")
        self.assertEqual([h["file_path"] for h in search(self.db, self.encoder, "nginx", 3, ["worklog:chunks"])], ["fix102/c.md"])
        self.assertEqual([h["file_path"] for h in search(self.db, self.encoder, "nginx backup", 3, None, None, "fix102")][0:1], ["fix102/b.md"])
        self.assertEqual(search(self.db, self.encoder, "", 3), [])

    def test_search_is_deterministic(self):
        for i in range(5):
            self.index(f"d{i}.md", "same words here")
        first = [h["passage_id"] for h in search(self.db, self.encoder, "same words", 5)]
        second = [h["passage_id"] for h in search(self.db, self.encoder, "same words", 5)]
        self.assertEqual(first, second)


class IntegrityTest(unittest.TestCase):
    def test_tree_checksum_and_weight_checks(self):
        directory = Path(short_temp())
        try:
            (directory / "config.json").write_text("{}")
            (directory / "model.safetensors").write_bytes(b"weights")
            first = tree_sha256(directory)
            self.assertEqual(first, tree_sha256(directory))
            self.assertEqual(weight_problems(directory), [])
            (directory / "model.safetensors").write_bytes(b"changed")
            self.assertNotEqual(first, tree_sha256(directory))
            (directory / "pytorch_model.bin").write_bytes(b"pickle")
            self.assertTrue(any("unsafe" in p for p in weight_problems(directory)))
            (directory / "model.safetensors").unlink()
            self.assertTrue(any("safetensors" in p for p in weight_problems(directory)))
        finally:
            shutil.rmtree(directory, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
