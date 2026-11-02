import hashlib
import re

REASON_CODES = (
    "rerank_off", "service_down", "service_circuit_open", "service_busy", "deadline_exceeded",
    "too_few_scored", "model_unavailable", "contract_mismatch", "unauthorized", "index_stale",
)


class ProtocolError(Exception):
    def __init__(self, status, code, message):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


MAX_DOCS_PER_CALL = 2000

def normalize_query(query):
    return re.sub(r"\s+", " ", (query or "").strip().lower())


def text_hash(text):
    return hashlib.sha256((text or "").encode("utf-8")).hexdigest()


def query_hash(query):
    return text_hash(normalize_query(query))


def passage_id(origin_db, source_archive, file_path, section, start, end):
    key = "|".join([origin_db, source_archive, file_path, str(section), str(start), str(end)])
    return hashlib.sha1(key.encode("utf-8")).hexdigest()[:16]


def require_dict(body):
    if not isinstance(body, dict):
        raise ProtocolError(400, "bad_request", "The body must be a JSON object")
    return body


def check_version(body, supported):
    version = body.get("contract_version", supported)
    if version != supported:
        raise ProtocolError(409, "contract_mismatch", f"contract_version {version} is not supported")


def validate_rerank(body, config):
    require_dict(body)
    check_version(body, config["contract_version"])
    query = body.get("query")
    if not isinstance(query, str) or not query.strip():
        raise ProtocolError(400, "bad_request", "query is required")
    passages = body.get("passages")
    if not isinstance(passages, list) or not passages:
        raise ProtocolError(400, "bad_request", "passages must be a non-empty list")
    if len(passages) > config["max_passages"]:
        raise ProtocolError(400, "bad_request", f"at most {config['max_passages']} passages")
    seen = set()
    for item in passages:
        if not isinstance(item, dict) or not isinstance(item.get("id"), str) or not isinstance(item.get("text"), str):
            raise ProtocolError(400, "bad_request", "each passage needs a string id and a string text")
        if len(item["text"]) > config["max_passage_chars"]:
            raise ProtocolError(400, "bad_request", f"a passage is longer than {config['max_passage_chars']} characters")
        if item["id"] in seen:
            raise ProtocolError(400, "bad_request", "passage ids must be unique")
        seen.add(item["id"])
    deadline = body.get("deadline_ms", config["default_deadline_ms"])
    if not isinstance(deadline, int) or deadline < 50 or deadline > 60000:
        raise ProtocolError(400, "bad_request", "deadline_ms must be between 50 and 60000")
    return {
        "query": query,
        "model": body.get("model") or config["rerank_model"],
        "deadline_ms": deadline,
        "passages": passages,
    }


def validate_upsert(body, config):
    require_dict(body)
    check_version(body, config["contract_version"])
    documents = body.get("documents")
    if not isinstance(documents, list) or not documents:
        raise ProtocolError(400, "bad_request", "documents must be a non-empty list")
    if len(documents) > MAX_DOCS_PER_CALL:
        raise ProtocolError(400, "bad_request", f"at most {MAX_DOCS_PER_CALL} documents per call")
    total = 0
    for document in documents:
        if not isinstance(document, dict):
            raise ProtocolError(400, "bad_request", "each document must be an object")
        for field in ("origin_db", "source_archive", "file_path", "content_hash"):
            if not isinstance(document.get(field), str) or not document[field]:
                raise ProtocolError(400, "bad_request", f"document.{field} is required")
        passages = document.get("passages")
        if not isinstance(passages, list):
            raise ProtocolError(400, "bad_request", "document.passages must be a list")
        for passage in passages:
            if not isinstance(passage, dict) or not isinstance(passage.get("text"), str):
                raise ProtocolError(400, "bad_request", "each passage needs a text")
            if len(passage["text"]) > config["max_passage_chars"]:
                raise ProtocolError(400, "bad_request", "a passage is too long")
            for field in ("section", "start", "end", "tokens"):
                if field not in passage:
                    raise ProtocolError(400, "bad_request", f"passage.{field} is required")
            if not isinstance(passage["section"], str):
                raise ProtocolError(400, "bad_request", "passage.section must be text")
            for field in ("start", "end", "tokens"):
                value = passage[field]
                if isinstance(value, bool) or not isinstance(value, int) or value < 0:
                    raise ProtocolError(400, "bad_request", f"passage.{field} must be a whole number of 0 or more")
            total += 1
    if total > 2000:
        raise ProtocolError(400, "bad_request", "at most 2000 passages per call")
    return documents


def validate_delete(body, config):
    require_dict(body)
    check_version(body, config["contract_version"])
    docs = body.get("docs")
    if not isinstance(docs, list) or not docs:
        raise ProtocolError(400, "bad_request", "docs must be a non-empty list")
    if len(docs) > MAX_DOCS_PER_CALL:
        raise ProtocolError(400, "bad_request", f"at most {MAX_DOCS_PER_CALL} docs per call")
    for doc in docs:
        if not isinstance(doc, dict) or not all(isinstance(doc.get(f), str) and doc[f] for f in ("origin_db", "source_archive", "file_path")):
            raise ProtocolError(400, "bad_request", "each doc needs origin_db, source_archive and file_path")
    return docs
