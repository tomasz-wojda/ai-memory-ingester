"""Latency benchmark for a re-ranking backend (task T022).

Usage: python3 service/benchmark.py --model NAME [--workspace-root DIR] [--depth 10,30,50] [--tokens 128,256,384] [--runs 20] [--batch 8,16,32] [--stub]
Prints one JSON line per cell with p50, p95, peak resident memory and the device.
The benchmark of a real model needs a verified, licensed local model (decisions open); --stub exercises the harness only.
"""
import argparse
import json
import resource
import statistics
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from ai_memory_service.backends import build_backend  # noqa: E402
from ai_memory_service.config import load_config, workspace_root  # noqa: E402

WORDS = "ingress timeout nginx backup retention certificate renewal logging library version pinned override staging audit trace".split()


def passage(tokens, seed):
    text = " ".join(WORDS[(seed + i) % len(WORDS)] for i in range(tokens))
    return text[:3900]


def rss_mb():
    usage = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return round(usage / (1024 * 1024) if sys.platform == "darwin" else usage / 1024, 1)


def percentile(values, share):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(round(share * (len(ordered) - 1))))]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True)
    parser.add_argument("--workspace-root")
    parser.add_argument("--depth", default="10,30,50")
    parser.add_argument("--tokens", default="128,256,384")
    parser.add_argument("--batch", default="16")
    parser.add_argument("--runs", type=int, default=20)
    parser.add_argument("--stub", action="store_true")
    args = parser.parse_args()
    config = load_config(workspace_root(args.workspace_root))
    backend = build_backend(args.model, config, args.stub).load()
    info = backend.info()
    query = "what did we decide about the nginx timeout"
    for depth in [int(v) for v in args.depth.split(",")]:
        for tokens in [int(v) for v in args.tokens.split(",")]:
            for batch in [int(v) for v in args.batch.split(",")]:
                texts = [passage(tokens, i) for i in range(depth)]
                for _ in range(3):
                    backend.score(query, texts[:batch])
                times = []
                for _ in range(args.runs):
                    started = time.perf_counter()
                    for start in range(0, len(texts), batch):
                        backend.score(query, texts[start:start + batch])
                    times.append((time.perf_counter() - started) * 1000)
                print(json.dumps({"model": args.model, "device": info["device"], "depth": depth, "tokens": tokens, "batch": batch,
                                  "runs": args.runs, "p50_ms": round(statistics.median(times), 2), "p95_ms": round(percentile(times, 0.95), 2),
                                  "peak_rss_mb": rss_mb()}))


if __name__ == "__main__":
    main()
