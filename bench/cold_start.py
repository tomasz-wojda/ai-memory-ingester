import argparse
import json
import os
import re
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import common

PATTERN = re.compile(r'Re-rank: model=\S+ device=\S+ scored=(\d+) skipped=(\d+) elapsed_ms=(\d+)')
SKIPPED = re.compile(r'Re-rank: skipped \((\w+)\)')


def one_call(query, socket_path, budget_ms):
    command = ['./ai-memory-ingester', 'query', query, '--rerank', 'on', '--rerank-depth', '30', '--rerank-budget-ms', str(budget_ms),
               '--explain', '--limit', '3', '--late', 'off', '-A', 'chat:turns']
    if os.environ.get('BENCH_DATA_DIR'):
        command += ['--data-dir', str(common.DATA_DIR)]
    env = dict(os.environ, AI_MEMORY_SERVICE_SOCKET=str(socket_path))
    started = time.perf_counter()
    done = subprocess.run(command, cwd=common.REPO, capture_output=True, text=True, timeout=600, env=env)
    wall = time.perf_counter() - started
    text = done.stdout + done.stderr
    ok = PATTERN.search(text)
    if ok:
        return {'degraded': False, 'scored': int(ok.group(1)), 'service_ms': int(ok.group(3)), 'wall_s': round(wall, 2)}
    skipped = SKIPPED.search(text)
    return {'degraded': True, 'reason': skipped.group(1) if skipped else 'unknown', 'wall_s': round(wall, 2)}


def one_start(queries, args, extra_config):
    work = Path(tempfile.mkdtemp(prefix='mb-', dir='/tmp'))
    folder = dict(common.SYSTEMS)[args.model]
    common.write_config(work, folder, common.manifest_checksums()[folder], args.device, args.max_tokens, extra_config)
    process, socket_path = common.start_service(work)
    try:
        time.sleep(args.wait)
        return [one_call(query, socket_path, args.budget_ms) for query in queries]
    finally:
        common.stop_service(process)
        shutil.rmtree(work, ignore_errors=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model', default='mmarco')
    parser.add_argument('--device', default='mps')
    parser.add_argument('--starts', type=int, default=10)
    parser.add_argument('--wait', type=float, default=15.0)
    parser.add_argument('--budget-ms', type=int, default=1200)
    parser.add_argument('--max-tokens', type=int, default=512)
    parser.add_argument('--config-extra', default='{}')
    parser.add_argument('--tag', default='')
    args = parser.parse_args()
    extra_config = json.loads(args.config_extra)
    tasks = common.load_tasks()
    queries = [task['forms']['question'] for task in tasks.values()]
    queries += [tasks['A']['extra_queries']['pl'], tasks['B']['extra_queries']['en-paraphrase']]
    queries = queries[:5]
    starts = [one_start(queries, args, extra_config) for _ in range(args.starts)]
    first = [start[0] for start in starts]
    later = [call for start in starts for call in start[1:]]
    report = {'starts': args.starts, 'wait_s': args.wait, 'budget_ms': args.budget_ms, 'config_extra': extra_config, 'queries': len(queries),
              'first_call_degraded': sum(1 for call in first if call['degraded']),
              'first_call_inside_budget': sum(1 for call in first if not call['degraded']),
              'first_call_service_ms': [call.get('service_ms') for call in first],
              'later_calls_degraded': sum(1 for call in later if call['degraded']),
              'later_calls': len(later),
              'later_service_ms_median': statistics.median([call['service_ms'] for call in later if not call['degraded']] or [0]),
              'first_call_reasons': sorted({call.get('reason') for call in first if call['degraded']}),
              'calls': starts}
    common.OUT_DIR.mkdir(parents=True, exist_ok=True)
    (common.OUT_DIR / f'cold-start{args.tag}.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k != 'calls'}, indent=2))


if __name__ == '__main__':
    sys.exit(main())
