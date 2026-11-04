import argparse
import csv
import json
import random
import statistics
import sys
from pathlib import Path

BOOTSTRAP_RESAMPLES = 10000
BOOTSTRAP_SEED = 1


def load_rows(path):
    with open(path, newline='', encoding='utf-8') as handle:
        return list(csv.DictReader(handle))


def system_name(row):
    return row['system'] if row['setting'] == 'default' else f"{row['system']}[{row['setting']}]"


def values_by_query(rows, system, metric, order, depth=None):
    out = {}
    for row in rows:
        if system_name(row) != system or row['order'] != order:
            continue
        if depth is not None and int(row['depth']) != depth:
            continue
        out[(row['task'], row['query'])] = float(row[metric])
    return out


def bootstrap_interval(differences):
    generator = random.Random(BOOTSTRAP_SEED)
    count = len(differences)
    means = sorted(statistics.fmean(generator.choices(differences, k=count)) for _ in range(BOOTSTRAP_RESAMPLES))
    return means[int(0.025 * BOOTSTRAP_RESAMPLES)], means[int(0.975 * BOOTSTRAP_RESAMPLES) - 1]


def paired_report(baseline, candidate):
    keys = sorted(set(baseline) & set(candidate))
    differences = [candidate[key] - baseline[key] for key in keys]
    if not differences:
        return {'pairs': 0}
    low, high = bootstrap_interval(differences)
    per_task = {}
    for key, difference in zip(keys, differences):
        per_task.setdefault(key[0], []).append(difference)
    return {
        'pairs': len(differences),
        'mean_baseline': round(statistics.fmean(baseline[key] for key in keys), 2),
        'mean_candidate': round(statistics.fmean(candidate[key] for key in keys), 2),
        'mean_difference': round(statistics.fmean(differences), 2),
        'ci95': [round(low, 2), round(high, 2)],
        'ci_excludes_zero': low > 0 or high < 0,
        'wins': sum(1 for d in differences if d > 0),
        'losses': sum(1 for d in differences if d < 0),
        'ties': sum(1 for d in differences if d == 0),
        'worst_loss': round(min(differences), 2),
        'best_gain': round(max(differences), 2),
        'per_task_mean_difference': {task: round(statistics.fmean(values), 2) for task, values in sorted(per_task.items())},
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('csv_path')
    parser.add_argument('--baseline', default='lexical')
    parser.add_argument('--systems', default='')
    parser.add_argument('--metric', default='cov@10')
    parser.add_argument('--order', default='fused')
    parser.add_argument('--depth', type=int, default=None)
    parser.add_argument('--baseline-order', default='')
    args = parser.parse_args()
    rows = load_rows(Path(args.csv_path))
    available = sorted({system_name(row) for row in rows})
    candidates = args.systems.split(',') if args.systems else [name for name in available if name != args.baseline]
    baseline = values_by_query(rows, args.baseline, args.metric, args.baseline_order or args.order)
    report = {}
    for name in candidates:
        report[name] = paired_report(baseline, values_by_query(rows, name, args.metric, args.order, args.depth))
    json.dump({'metric': args.metric, 'order': args.order, 'baseline': args.baseline, 'systems': report}, sys.stdout, indent=2)
    print()


if __name__ == '__main__':
    main()
