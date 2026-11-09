import argparse
import json
import re
import shutil
import statistics
import tempfile
from pathlib import Path

import common

PLAIN = re.compile(r'^[a-z0-9_\- ]+$')
SETTINGS = {
    'lexical': ('off', ()),
    'lexical+route': ('off', ('--topic-route', 'on')),
    'default': ('on', ()),
    'default+route': ('on', ('--topic-route', 'on')),
}


def needle_queries(task, per_task=3):
    out = []
    for fact, weight, _, groups in task['ledger']:
        if fact not in task['strict'] or weight < 2:
            continue
        words = [group for group in groups if PLAIN.match(group)]
        if len(words) < 2:
            continue
        out.append((fact, ' '.join(words)))
        if len(out) == per_task:
            break
    return out


def hit(task, fact, rows, data_dir):
    top = common.frozen_top(task, common.chat_rows(rows), 10)
    texts, _, _ = common.turn_texts(top, 'head', data_dir)
    return common.present({fact: task['strict'][fact]}, texts)[fact]


def main():
    parser = argparse.ArgumentParser()
    common.add_freeze_arguments(parser)
    parser.add_argument('--settings', default='lexical,default')
    parser.add_argument('--per-task', type=int, default=3)
    parser.add_argument('--tasks', default='')
    parser.add_argument('--tag', default='')
    args = parser.parse_args()
    common.apply_freeze_arguments(args)
    tasks = common.select_tasks(common.load_tasks(), args.tasks, args.adjudicated_only)
    names = args.settings.split(',')
    needs_service = any(SETTINGS[name][0] == 'on' for name in names)
    work = Path(tempfile.mkdtemp(prefix='nd-', dir='/tmp'))
    process, socket_path = None, None
    if needs_service:
        folder = dict(common.SYSTEMS)['mmarco']
        common.write_config(work, folder, common.manifest_checksums()[folder], 'mps', 512)
        process, socket_path = common.start_service(work)
        common.warm_up(socket_path, 'warm up query', common.task_data_dir(next(iter(tasks.values()))))
    hits = {name: {} for name in names}
    try:
        for key, task in tasks.items():
            data_dir = common.task_data_dir(task)
            for fact, query in needle_queries(task, args.per_task):
                for name in names:
                    rerank, extra = SETTINGS[name]
                    _, rows, _, _ = common.run_engine(query, rerank, 30, 10000, socket_path if rerank == 'on' else None, extra_args=extra, data_dir=data_dir)
                    hits[name][f'{key}/{fact}'] = 1.0 if hit(task, fact, rows, data_dir) else 0.0
    finally:
        if process is not None:
            common.stop_service(process)
        shutil.rmtree(work, ignore_errors=True)
    cases = sorted(next(iter(hits.values())))
    report = {'frozen': args.frozen, 'freeze_by': common.FREEZE_BY if args.frozen else None, 'needles': len(cases),
              'hit_rate': {name: round(100 * statistics.fmean(hits[name].values()), 1) for name in names}, 'hits': hits}
    for other in names[1:]:
        base = names[0]
        report[f'{other}_vs_{base}'] = {'gained': sum(1 for c in cases if hits[other][c] > hits[base][c]), 'lost': sum(1 for c in cases if hits[other][c] < hits[base][c])}
    common.OUT_DIR.mkdir(parents=True, exist_ok=True)
    (common.OUT_DIR / f'needles{args.tag}.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print('needles', len(cases), 'hit@10 %', report['hit_rate'])
    for key, value in report.items():
        if '_vs_' in key:
            print(key, value)


if __name__ == '__main__':
    main()
