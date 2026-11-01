import heapq

from .store import dequantize


def maxsim(query_vectors, passage_vectors):
    total = 0.0
    for q in query_vectors:
        best = max((sum(a * b for a, b in zip(q, p)) for p in passage_vectors), default=0.0)
        total += best
    return total


def search(db, encoder, query_text, top_k, archives=None, origin_db=None, path_contains=None):
    query_vectors = encoder.encode_query(query_text)
    if not query_vectors:
        return []
    heap = []
    for row in db.iter_vectors(encoder.model_id, archives, origin_db, path_contains):
        matrix = dequantize(row["matrix"], row["scale"], row["token_count"], row["dimension"])
        score = round(maxsim(query_vectors, matrix), 4)
        item = (score, row["passage_id"], row)
        if len(heap) < top_k:
            heapq.heappush(heap, item)
        elif item[:2] > heap[0][:2]:
            heapq.heapreplace(heap, item)
    ordered = sorted(heap, key=lambda item: (-item[0], item[1]))
    return [{"passage_id": pid, "score": score, "origin_db": row["origin_db"], "source_archive": row["source_archive"],
             "file_path": row["file_path"], "text": row["text"]} for score, pid, row in ordered]
