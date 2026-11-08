import argparse
import csv
import json
import shutil
import statistics
import sys
import tempfile
from pathlib import Path

import common
import snapshot

CSV_FIELDS = ['system', 'setting', 'depth', 'task', 'query', 'order', 'cov@5', 'cov@10', 'cov@20', 'bytes@20', 'prec@10', 'ndcg@10', 'scored',
              'wall_p50', 'wall_p95', 'wall_cold', 'degraded_runs', 'evidence_fallbacks']


def parse_settings(text):
    settings = []
    for token in text.split(','):
        token = token.strip()
        if token in ('', 'default'):
            settings.append(('default', ()))
            continue
        extra = []
        for part in token.split('+'):
            if part.startswith('neighbors='):
                extra += ['--neighbors', part.partition('=')[2]]
                continue
            fusion, _, weight = part.partition(':')
            extra += ['--rerank-fusion', fusion]
            if weight:
                extra += ['--rerank-weight', weight]
        settings.append((token, tuple(extra)))
    return settings


def coverage_by_cut(task, ordered_chat, mode):
    top = common.frozen_top(task, ordered_chat)
    texts, _, fallbacks = common.turn_texts(top, mode, common.task_data_dir(task))
    result = {'top20_turns': [common.number(row.get('file_name')) for row in top], 'evidence_fallbacks': fallbacks}
    for cut in common.CUTS:
        result[f'cov@{cut}'] = common.strict_score(task, common.present(task['strict'], texts[:cut]))[0]
        result[f'bytes@{cut}'] = sum(len(t.encode('utf-8')) for t in texts[:cut])
    useful = common.coverage_for(task, texts[:10])
    result['prec@10'] = round(100.0 * useful['useful_items'] / useful['items'], 1) if useful['items'] else 0.0
    result['ndcg@10'] = common.ndcg_at(task, texts, 10)
    return result


def score_case(task, run, depth, mode):
    rows = run['rows']
    window = rows[:depth]
    if common.task_data_dir(task):
        common.assert_no_crowding(task, common.chat_rows(rows), depth or 30)
    result = {
        'scored': sum(1 for row in window if row.get('rerank_score') is not None),
        'post_cutoff_in_depth': sum(1 for row in window if not common.eligible_row(task, row)),
        'wall_s': run['p50'], 'wall_p50': run['p50'], 'wall_p95': run['p95'], 'wall_cold': run['cold_wall'], 'degraded_runs': run['degraded_runs'],
        'fused': coverage_by_cut(task, common.chat_rows(rows), mode),
        'model_only': coverage_by_cut(task, common.chat_rows(common.model_only_order(rows)), mode),
    }
    result['within_budget'] = result['fused']['bytes@20'] <= common.BUDGET
    return result


def summarize(results):
    table = {}
    for name, per_depth in results.items():
        for depth, cases in per_depth.items():
            entry = {'degraded_cases': sum(1 for case in cases.values() if case['scored'] == 0 and not name.startswith('lexical')),
                     'median_wall_s': round(statistics.median(case['wall_s'] for case in cases.values()), 2),
                     'over_budget_cases': sum(1 for case in cases.values() if not case['within_budget'])}
            for order in common.ORDERS:
                for cut in common.CUTS:
                    entry[f'{order}_cov@{cut}'] = round(statistics.mean(case[order][f'cov@{cut}'] for case in cases.values()), 1)
                entry[f'{order}_prec@10'] = round(statistics.mean(case[order]['prec@10'] for case in cases.values()), 1)
                entry[f'{order}_ndcg@10'] = round(statistics.mean(case[order]['ndcg@10'] for case in cases.values()), 1)
            table[f'{name}@{depth}'] = entry
    return table


def csv_rows(system, setting, depth, results):
    rows = []
    for case_name, case in results.items():
        task_key, query = case_name.split('/', 1)
        for order in common.ORDERS:
            rows.append({'system': system, 'setting': setting, 'depth': depth, 'task': task_key, 'query': query, 'order': order,
                         'cov@5': case[order]['cov@5'], 'cov@10': case[order]['cov@10'], 'cov@20': case[order]['cov@20'],
                         'bytes@20': case[order]['bytes@20'], 'prec@10': case[order]['prec@10'], 'ndcg@10': case[order]['ndcg@10'], 'scored': case['scored'], 'wall_p50': case['wall_p50'],
                         'wall_p95': case['wall_p95'], 'wall_cold': case['wall_cold'], 'degraded_runs': case['degraded_runs'],
                         'evidence_fallbacks': case[order]['evidence_fallbacks']})
    return rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--systems', default='lexical,mmarco,bge-m3,msmarco-l12')
    parser.add_argument('--depths', default='30')
    parser.add_argument('--device', default='mps')
    parser.add_argument('--budget-ms', type=int, default=10000)
    parser.add_argument('--settings', default='default')
    parser.add_argument('--repeats', type=int, default=5)
    parser.add_argument('--evidence', choices=['head', 'best_passage'], default='head')
    parser.add_argument('--max-tokens', type=int, default=512)
    parser.add_argument('--tasks', default='')
    parser.add_argument('--tag', default='')
    common.add_freeze_arguments(parser)
    args = parser.parse_args()
    common.apply_freeze_arguments(args)
    wanted = args.systems.split(',')
    depths = [int(value) for value in args.depths.split(',')]
    settings = parse_settings(args.settings)
    tasks = common.select_tasks(common.load_tasks(), args.tasks, args.adjudicated_only)
    cases = [(key, label, query) for key, task in tasks.items() for label, query in common.case_queries(task)]
    checksums = common.manifest_checksums()
    results, rows_csv = {}, []
    work = Path(tempfile.mkdtemp(prefix='mb-', dir='/tmp'))
    for system, folder in common.SYSTEMS:
        if system not in wanted:
            continue
        process, socket_path = None, None
        if folder is not None:
            workspace = work / system
            common.write_config(workspace, folder, checksums[folder], args.device, args.max_tokens)
            process, socket_path = common.start_service(workspace)
            if not common.warm_up(socket_path, cases[0][2], common.task_data_dir(tasks[cases[0][0]])):
                common.stop_service(process)
                results[system] = {'error': 'service did not score a warm-up request'}
                continue
        try:
            for setting, extra in (settings if folder is not None else [('default', ())]):
                name = system if setting == 'default' else f'{system}[{setting}]'
                results[name] = {}
                for depth in (depths if folder is not None else [0]):
                    results[name][depth] = {}
                    for key, label, query in cases:
                        run = common.run_repeated(query, 'on' if folder else 'off', depth or 30, args.budget_ms, socket_path,
                                                  extra_args=extra, repeats=args.repeats, data_dir=common.task_data_dir(tasks[key]))
                        case = score_case(tasks[key], run, depth, args.evidence)
                        results[name][depth][f'{key}/{label}'] = case
                        print(name, depth, key, label, [case[order][f'cov@{cut}'] for order in common.ORDERS for cut in common.CUTS], flush=True)
                    rows_csv += csv_rows(system, setting, depth, results[name][depth])
        finally:
            if process is not None:
                common.stop_service(process)
    shutil.rmtree(work, ignore_errors=True)
    report = {'cases': [{'task': key, 'form': label, 'query': query} for key, label, query in cases], 'device': args.device,
              'depths': depths, 'evidence': args.evidence, 'repeats': args.repeats, 'data_dir': str(common.DATA_DIR), 'snapshot': snapshot.manifest(), 'adjudicated_only': args.adjudicated_only, 'adjudicated': {k: t['adjudicated'] for k, t in tasks.items()}, 'frozen': args.frozen, 'freeze_by': common.FREEZE_BY if args.frozen else None, 'corpora': common.corpus_manifests(tasks), 'results': results,
              'summary': summarize({k: v for k, v in results.items() if 'error' not in v})}
    common.OUT_DIR.mkdir(parents=True, exist_ok=True)
    (common.OUT_DIR / f'model-bench{args.tag}.json').write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
    with open(common.OUT_DIR / f'model-bench{args.tag}.csv', 'w', newline='', encoding='utf-8') as handle:
        writer = csv.DictWriter(handle, fieldnames=CSV_FIELDS)
        writer.writeheader()
        writer.writerows(rows_csv)
    print(json.dumps(report['summary'], indent=2))


if __name__ == '__main__':
    sys.exit(main())
