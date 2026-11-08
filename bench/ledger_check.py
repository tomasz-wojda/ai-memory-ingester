import argparse
import random
import re
import sys

import common

FALSE_MATCH_LIMIT = 0.01
TURN_SHARE_MINIMUM = 70.0
SAMPLE_SEED = 1


def read_sources(task):
    blobs = []
    for path in task.get('review_sources', []):
        full = common.WORKSPACE_ROOT / path
        if full.exists():
            blobs.append(full.read_text(encoding='utf-8', errors='replace').lower())
    return blobs


def matches_all(groups, text):
    return all(re.search(group, text) for group in groups)


def ticket_pattern(task):
    if task.get('text_match'):
        return re.compile(task['text_match'], re.I)
    keys = [re.escape(key.lower()) for key in (task.get('journal_keys') or [])]
    return re.compile('|'.join(keys), re.I) if keys else None


def chat_turn_texts(task, data_dir):
    cursor = common.connection('convo.db', data_dir).execute("SELECT file_name, content FROM documents WHERE source_archive = 'chat:turns'")
    out = []
    for name, content in cursor:
        if not common.in_scope(task, name):
            continue
        out.append((content or '').lower())
    return out


def check_sources(task, blobs):
    return sorted(fact for fact, groups in task['strict'].items() if not any(matches_all(groups, blob) for blob in blobs))


def check_false_matches(task, turns, sample_size):
    pattern = ticket_pattern(task)
    unrelated = [text for text in turns if not (pattern and pattern.search(text))]
    sample = random.Random(SAMPLE_SEED).sample(unrelated, min(sample_size, len(unrelated)))
    rates = {}
    for fact, groups in task['strict'].items():
        hits = sum(1 for text in sample if matches_all(groups, text))
        rates[fact] = hits / len(sample) if sample else 0.0
    return rates, len(sample)


def check_turn_presence(task, turns):
    weights = {fact: weight for fact, weight, _, _ in task['ledger']}
    absent = sorted(fact for fact, groups in task['strict'].items() if not any(matches_all(groups, text) for text in turns))
    total = sum(weights[fact] for fact in task['strict'])
    present = total - sum(weights[fact] for fact in absent)
    return (round(100.0 * present / total, 1) if total else 0.0), absent


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('task')
    common.add_freeze_arguments(parser)
    parser.add_argument('--sample', type=int, default=3000)
    args = parser.parse_args()
    common.apply_freeze_arguments(args)
    task = common.load_tasks()[args.task.upper()]
    data_dir = common.task_data_dir(task)
    blobs = read_sources(task)
    failures = []
    print(f"task {task['key']}: {len(task['ledger'])} facts, strict set {len(task['strict'])}, review sources found {len(blobs)} of {len(task.get('review_sources', []))}")
    if not blobs:
        failures.append('no review source found')
    missing = check_sources(task, blobs) if blobs else sorted(task['strict'])
    print(f"(a) strict anchors not matched in any review source: {missing or 'none'}")
    if missing:
        failures.append('a')
    turns = chat_turn_texts(task, data_dir)
    rates, size = check_false_matches(task, turns, args.sample)
    over = {fact: round(rate * 100, 2) for fact, rate in rates.items() if rate > FALSE_MATCH_LIMIT}
    worst = max(rates.values()) * 100 if rates else 0.0
    print(f"(b) false matches on {size} unrelated turns (limit {FALSE_MATCH_LIMIT * 100:.0f}%): worst {worst:.2f}%; over the limit: {over or 'none'}")
    if over:
        failures.append('b')
    share, absent = check_turn_presence(task, turns)
    print(f"(c) strict weight present in some turn at or before the cutoff: {share}% (minimum {TURN_SHARE_MINIMUM}%); source only: {absent or 'none'}")
    if share < TURN_SHARE_MINIMUM:
        print('    below the minimum: mark the source-only facts and remove them from the strict set')
    print('RESULT:', 'FAIL ' + ','.join(failures) if failures else 'PASS')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
