import argparse
import re
import sys

import common

WINDOW = 6


def load_lines(path):
    full = common.WORKSPACE_ROOT / path
    if not full.exists():
        return None
    return full.read_text(encoding='utf-8', errors='replace').split('\n')


def locate(groups, lines):
    matches = [[i for i, line in enumerate(lines) if re.search(g, line, re.I)] for g in groups]
    if any(not m for m in matches):
        return {'status': 'missing', 'missing': [g for g, m in zip(groups, matches) if not m]}
    for start in matches[0]:
        low, high = start - WINDOW, start + WINDOW
        if all(any(low <= i <= high for i in m) for m in matches[1:]):
            return {'status': 'close', 'line': start + 1, 'excerpt': lines[start].strip()[:160]}
    return {'status': 'apart', 'line': matches[0][0] + 1, 'excerpt': lines[matches[0][0]].strip()[:160]}


def turn_matches(task, groups):
    cursor = common.connection('convo.db', common.task_data_dir(task)).execute("SELECT file_name, content FROM documents WHERE source_archive = 'chat:turns'")
    count = 0
    for name, content in cursor:
        number = common.number(name)
        if number is None or number > task['cutoff_turn'] or number in task['excluded']:
            continue
        text = (content or '').lower()
        if all(re.search(g, text) for g in groups):
            count += 1
    return count


def describe(result):
    if result is None:
        return 'source not found'
    if result['status'] == 'missing':
        return 'no match for ' + ', '.join(f'`{g}`' for g in result['missing'])
    where = 'all anchors within 12 lines' if result['status'] == 'close' else 'anchors found, but far apart'
    return f"{where}, line {result['line']}: {result['excerpt']}"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('task')
    parser.add_argument('--frozen', action='store_true')
    args = parser.parse_args()
    common.set_frozen(args.frozen)
    tasks = common.load_tasks()
    task = tasks[args.task.upper()]
    sources = {path: load_lines(path) for path in task.get('review_sources', [])}
    out = [f"# Ledger review: task {task['key']}, {task['name']}", '',
           f"Freeze: turn number {task['cutoff_turn']} or lower, journal before {task['journal_cutoff'].isoformat()}.",
           'Decide per fact: keep, change the weight, change or add an anchor, or drop. Strict facts are the ones that are scored.', '']
    total = sum(weight for _, weight, _, _ in task['ledger'])
    out.append(f"Facts: {len(task['ledger'])}, total weight {total}, strict set {len(task['strict'])} facts.")
    out.append('')
    for fact, weight, _, groups in task['ledger']:
        strict = task['strict'].get(fact)
        used = strict or groups
        out.append(f"## {fact}, weight {weight}, {'strict' if strict else 'not in the strict set'}")
        out.append(f"- Anchors used: {', '.join(f'`{g}`' for g in used)}")
        for path, lines in sources.items():
            out.append(f"- `{path}`: {describe(locate(used, lines) if lines else None)}")
        count = turn_matches(task, used)
        out.append(f"- Turns before the freeze that match all anchors: {count}" + ('  (source only: no turn holds it)' if count == 0 else ''))
        out.append('- Decision: ')
        out.append('')
    folder = common.OUT_DIR / 'review'
    folder.mkdir(parents=True, exist_ok=True)
    target = folder / f"task_{task['key'].lower()}.md"
    target.write_text('\n'.join(out), encoding='utf-8')
    flagged = [line for line in out if 'no match for' in line or 'source not found' in line or 'far apart' in line]
    print(target, '| facts', len(task['ledger']), '| flagged lines', len(flagged))


if __name__ == '__main__':
    sys.exit(main())
