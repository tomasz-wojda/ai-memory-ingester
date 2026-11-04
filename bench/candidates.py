import re
import sqlite3
import sys
from collections import defaultdict

import common

LONG = 150
MEDIUM = 60
EXCLUDED = {'noticket', 'sha256', 'utf8'}


def turn_statistics():
    cursor = common.connection('convo.db').execute("SELECT file_path, length(content), substr(content, 1, 1500) FROM documents WHERE source_archive = 'chat:turns'")
    stats = defaultdict(lambda: {'turns': 0, 'kb': 0, 'first': '9', 'last': '0'})
    for path, size, head in cursor:
        ticket = path.split('/')[0]
        entry = stats[ticket]
        entry['turns'] += 1
        entry['kb'] += (size or 0) / 1024
        found = re.search(r'timestamp:\s*"?(\d{4}-\d{2}-\d{2})', head)
        if found:
            entry['first'] = min(entry['first'], found.group(1))
            entry['last'] = max(entry['last'], found.group(1))
    return stats


def worklog_statistics():
    connection = common.connection('worklogs.db')
    chunks = defaultdict(int)
    for path, size in connection.execute("SELECT file_path, length(content) FROM documents WHERE source_archive = 'worklog:chunks'"):
        chunks[path.split('/')[0]] += (size or 0) / 1024
    capsules = {name.replace('.md', ''): size for name, size in connection.execute("SELECT file_name, length(content) FROM documents WHERE source_archive = 'worklog:state'")}
    return chunks, capsules


def group_of(turns):
    return 'long' if turns >= LONG else ('medium' if turns >= MEDIUM else 'short')


def main():
    stats = turn_statistics()
    chunks, capsules = worklog_statistics()
    rows = []
    for ticket, entry in stats.items():
        if ticket in EXCLUDED or ticket not in chunks or ticket not in capsules or entry['turns'] < 20:
            continue
        rows.append((entry['turns'], ticket, round(entry['kb']), round(chunks[ticket]), capsules[ticket], entry['first'], entry['last']))
    rows.sort(reverse=True)
    lines = ['# Candidate tasks (tickets with turns, worklog chunks and a state capsule, at least 20 turns)', '',
             '| Group | Ticket | Turns | Turn KB | Worklog KB | Capsule bytes | First | Last |', '|---|---|---:|---:|---:|---:|---|---|']
    for turns, ticket, kb, wl, cap, first, last in rows:
        lines.append(f'| {group_of(turns)} | {ticket} | {turns} | {kb} | {wl} | {cap} | {first} | {last} |')
    folder = common.OUT_DIR / 'review'
    folder.mkdir(parents=True, exist_ok=True)
    target = folder / 'candidates.md'
    target.write_text('\n'.join(lines), encoding='utf-8')
    print(target, len(rows), 'candidates')


if __name__ == '__main__':
    sys.exit(main())
