import argparse
import csv
import json
import shutil
import sys
import tempfile
import time
from pathlib import Path

import common
import snapshot

CSV_FIELDS = ['task', 'branch', 'cov@5', 'cov@10', 'cov@all', 'useful_items', 'items', 'bytes', 'selected', 'time_p50', 'time_p95', 'cold_s', 'cached_p50', 'cached_p95',
              'degraded_runs', 'evidence_fallbacks']


def journal_branches(entries, task, parse_s):
    variants = [('prompt.log', False, False), ('prompt.log (greedy fill)', True, False)]
    if task['text_match']:
        variants.append(('prompt.log (text match, greedy)', True, True))
    out = {}
    for label, greedy, by_text in variants:
        started = time.perf_counter()
        chosen, size, eligible = common.select_journal(entries, task, greedy, by_text)
        select_s = time.perf_counter() - started
        texts = [e['text'] for e in chosen]
        out[label] = {
            'time_s': round(parse_s + select_s, 2), 'time_p50': round(parse_s + select_s, 2), 'time_p95': round(parse_s + select_s, 2),
            'eligible': eligible, 'selected': len(chosen), 'bytes': size, 'degraded_runs': 0, 'evidence_fallbacks': 0,
            'span': [min(e['time'] for e in chosen).isoformat(), max(e['time'] for e in chosen).isoformat()] if chosen else None,
            'lines': [min(e['startLine'] for e in chosen), max(e['endLine'] for e in chosen)] if chosen else None,
            **common.coverage_for(task, texts)}
    return out


def chat_branch(task, run, mode, rerank, depth, order='engine'):
    rows = run['rows'] if order == 'engine' else common.model_only_order(run['rows'])
    top = common.frozen_top(task, common.chat_rows(rows))
    texts, size, fallbacks = common.turn_texts(top, mode)
    return {'time_s': run['p50'], 'time_p50': run['p50'], 'time_p95': run['p95'], 'cold_s': run['cold_wall'], 'cached_p50': run['cached_p50'], 'cached_p95': run['cached_p95'], 'selected': len(top), 'bytes': size,
            'scored': sum(1 for r in run['rows'][:depth] if r.get('rerank_score') is not None),
            'degraded': rerank and not any(r.get('rerank_score') is not None for r in run['rows']),
            'degraded_runs': run['degraded_runs'], 'evidence_fallbacks': fallbacks, **common.coverage_for(task, texts)}


def multi_branch(task, run, mode, depth, with_mix):
    top = common.top_rows(task, run['rows'])
    texts, size, fallbacks = common.multi_texts(top, mode)
    out = {'time_s': run['p50'], 'time_p50': run['p50'], 'time_p95': run['p95'], 'cold_s': run['cold_wall'], 'cached_p50': run['cached_p50'], 'cached_p95': run['cached_p95'], 'selected': len(top), 'bytes': size,
           'degraded_runs': run['degraded_runs'], 'evidence_fallbacks': fallbacks,
           'undated': sum(1 for r in top if r.get('source_archive') != 'chat:turns' and not r.get('event_time')),
           'files': [r['file_path'] for r in top], **common.coverage_for(task, texts)}
    if with_mix:
        out['scored'] = sum(1 for r in run['rows'][:depth] if r.get('rerank_score') is not None)
        out['mix'] = {k: sum(1 for r in top if r['source_archive'] == k) for k in sorted({r['source_archive'] for r in top})}
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model', default='mmarco')
    parser.add_argument('--depth', type=int, default=30)
    parser.add_argument('--budget-ms', type=int, default=1200)
    parser.add_argument('--device', default='mps')
    parser.add_argument('--repeats', type=int, default=5)
    parser.add_argument('--evidence', choices=['head', 'best_passage'], default='head')
    parser.add_argument('--engine-args', default='')
    parser.add_argument('--max-tokens', type=int, default=512)
    parser.add_argument('--tasks', default='')
    parser.add_argument('--tag', default='')
    args = parser.parse_args()
    tasks = common.load_tasks()
    if args.tasks:
        tasks = {key: task for key, task in tasks.items() if key in args.tasks.split(',')}
    extra = tuple(args.engine_args.split())
    folder = dict(common.SYSTEMS)[args.model]
    entries, parse_s = common.parse_journal()
    work = Path(tempfile.mkdtemp(prefix='mb-', dir='/tmp'))
    common.write_config(work, folder, common.manifest_checksums()[folder], args.device, args.max_tokens)
    process, socket_path = common.start_service(work)
    report = {'model': args.model, 'depth': args.depth, 'budget_ms': args.budget_ms, 'repeats': args.repeats, 'evidence': args.evidence,
              'engine_args': args.engine_args, 'journal_entries': len(entries), 'journal_parse_s': round(parse_s, 2),
              'cutoffs': {k: t['journal_cutoff'].isoformat() for k, t in tasks.items()}, 'data_dir': str(common.DATA_DIR), 'snapshot': snapshot.manifest(), 'tasks': {}}
    rows_csv = []
    try:
        report['service_warm'] = common.warm_up(socket_path, next(iter(tasks.values()))['forms']['question'])
        for key, task in tasks.items():
            entry = {'name': task['name'], 'september': task['ledger_sept'], 'branches': journal_branches(entries, task, parse_s)}
            query = task['forms']['question']
            repeat = {'repeats': args.repeats}
            plain = common.run_repeated(query, 'off', args.depth, args.budget_ms, None, **repeat)
            entry['branches']['fts5'] = chat_branch(task, plain, args.evidence, False, args.depth)
            ranked = common.run_repeated(query, 'on', args.depth, args.budget_ms, socket_path, extra_args=extra, **repeat)
            entry['branches']['fts5+rerank'] = chat_branch(task, ranked, args.evidence, True, args.depth)
            entry['branches']['fts5+rerank (model-only order)'] = chat_branch(task, ranked, args.evidence, True, args.depth, 'model_only')
            worklogs = common.run_repeated(query, 'off', args.depth, args.budget_ms, None, archive='worklog:chunks', **repeat)
            entry['branches']['indexed worklogs'] = multi_branch(task, worklogs, args.evidence, args.depth, False)
            everything = common.run_repeated(query, 'on', args.depth, args.budget_ms, socket_path, archive=None, extra_args=extra, **repeat)
            entry['branches']['fts5 all + rerank'] = multi_branch(task, everything, args.evidence, args.depth, True)
            doc_form = task['forms'].get('facet') or task['forms'].get('operators')
            if doc_form:
                legacy = common.run_repeated(doc_form, 'off', args.depth, args.budget_ms, None, **repeat)
                entry['branches']['fts5 (September query form)'] = {**chat_branch(task, legacy, args.evidence, False, args.depth), 'query': doc_form}
            first_stage_chat = common.run_engine(query, 'off', 30, args.budget_ms, None)
            first_stage_all = common.run_engine(query, 'off', 30, args.budget_ms, None, archive=None)
            entry['fact_recall'] = {'chat_turns': common.fact_recall(task, first_stage_chat[1]),
                                    'all_sources': common.fact_recall(task, first_stage_all[1])}
            report['tasks'][key] = entry
            for name, branch in entry['branches'].items():
                rows_csv.append({'task': key, 'branch': name, **{field: branch.get(field) for field in CSV_FIELDS[2:]}})
    finally:
        common.stop_service(process)
        shutil.rmtree(work, ignore_errors=True)
    common.OUT_DIR.mkdir(parents=True, exist_ok=True)
    (common.OUT_DIR / f'three-way{args.tag}.json').write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
    with open(common.OUT_DIR / f'three-way{args.tag}.csv', 'w', newline='', encoding='utf-8') as handle:
        writer = csv.DictWriter(handle, fieldnames=CSV_FIELDS)
        writer.writeheader()
        writer.writerows(rows_csv)
    for key, entry in report['tasks'].items():
        print('==', key, entry['name'], '| September (manual):', entry['september'])
        for name, branch in entry['branches'].items():
            print(f"  {name:<34} time={branch['time_s']:<6} n={branch['selected']:<3} bytes={branch['bytes']:<6} cov@5/10/20/all = {branch['cov@5']}/{branch['cov@10']}/{branch['cov@20']}/{branch['cov@all']}")
        recall = entry['fact_recall']
        print('  first-stage recall (chat turns)  :', {k: v for k, v in recall['chat_turns'].items() if k.startswith('recall')}, 'never:', recall['chat_turns']['never_found'])
        print('  first-stage recall (all sources) :', {k: v for k, v in recall['all_sources'].items() if k.startswith('recall')}, 'never:', recall['all_sources']['never_found'])


if __name__ == '__main__':
    sys.exit(main())
