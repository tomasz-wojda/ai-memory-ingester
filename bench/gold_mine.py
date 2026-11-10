import argparse
import csv
import glob
import json
import os
import re
import sqlite3
from pathlib import Path

import common
import task_corpus

OUT = common.REPO / 'data/eval/real/mined'
TICKET = re.compile(r'\b(?:[A-Za-z]{2,10}-\d+|SCTASK\d{6,10})\b')
CONTINUATION = re.compile(r'^(yes|ok|okay|continue|go|next|tak|dalej|proceed)\b', re.I)
QUERY_TAG = re.compile(r'<user_query>\s*(.*?)\s*</user_query>', re.S)
ENGINE_PHRASE = re.compile(r'ai-memory-ingester\s+query\s+(?:"([^"]+)"|\'([^\']+)\')')
CALL_LIMIT = 15
MIN_CHARS = 60


def transcript_root(workspace_slug):
    return Path(os.path.expanduser(f'~/.cursor/projects/{workspace_slug}/agent-transcripts'))


def text_of(message):
    return '\n'.join(c.get('text', '') for c in (message.get('content') or []) if isinstance(c, dict) and c.get('type') == 'text')


def user_query(text):
    found = QUERY_TAG.search(text)
    return (found.group(1) if found else text).strip()


def session_starts(workspace_slug):
    connection = sqlite3.connect(f'file:{common.DATA_DIR / "convo.db"}?mode=ro', uri=True)
    starts = {}
    try:
        for (head,) in connection.execute("SELECT substr(content, 1, 900) FROM documents WHERE source_archive = 'chat:turns' AND content LIKE ?", (f'%cursor-transcript:{workspace_slug}/%',)):
            source = re.search(r'source:\s*"cursor-transcript:[^/]+/([^"]+)"', head)
            instant = task_corpus.parse_instant(head)
            if source and instant:
                key = source.group(1)
                if key not in starts or instant < starts[key]:
                    starts[key] = instant
    finally:
        connection.close()
    return starts


def classify(calls):
    forms, patterns, tails, engine = set(), [], [], []
    resume = sweep = False
    for name, payload in calls:
        blob = json.dumps(payload, ensure_ascii=False)
        low = blob.lower()
        resume = resume or 'resume.md' in low
        sweep = sweep or 'sweep-memory' in low
        command = payload.get('command', '') if name == 'Shell' else ''
        for match in ENGINE_PHRASE.finditer(command):
            engine.append(match.group(1) or match.group(2))
        if 'prompt.log' not in low:
            continue
        if name in ('Read', 'ReadFile'):
            forms.add('read-offset' if payload.get('offset') else 'read-start-or-whole')
        elif name in ('Grep', 'rg'):
            forms.add('grep-tool')
            patterns.append(str(payload.get('pattern', '')))
        elif name == 'Shell':
            if re.search(r'\btail\b', command):
                forms.add('shell-tail')
                found = re.search(r'tail\s+(?:-n\s*|-)(\d+)', command)
                if found:
                    tails.append(found.group(1))
            elif re.search(r'\b(grep|rg)\b', command):
                forms.add('shell-grep')
                found = re.search(r'(?:grep|rg)\s+(?:-[A-Za-z0-9]+\s+)*[\'"]?([^\'" |]{2,60})', command)
                if found:
                    patterns.append(found.group(1))
    return forms, patterns, tails, engine, resume, sweep


def mine(workspace_slug):
    starts = session_starts(workspace_slug)
    rows = []
    for path in sorted(glob.glob(str(transcript_root(workspace_slug) / '*' / '*.jsonl'))):
        records = []
        for line in open(path, errors='replace'):
            try:
                records.append(json.loads(line))
            except ValueError:
                pass
        users = [i for i, r in enumerate(records) if r.get('role') == 'user']
        if not users:
            continue
        first, end = users[0], (users[1] if len(users) > 1 else len(records))
        calls = []
        for record in records[first + 1:end]:
            for block in ((record.get('message') or {}).get('content') or []):
                if isinstance(block, dict) and block.get('type') == 'tool_use':
                    calls.append((block.get('name'), block.get('input') or {}))
        forms, patterns, tails, engine, resume, sweep = classify(calls[:CALL_LIMIT])
        query = user_query(text_of(records[first].get('message') or {}))
        session = Path(path).stem
        started = starts.get(session)
        rows.append({
            'session': session, 'started_at': started.isoformat() if started else '', 'file_date': __import__('datetime').date.fromtimestamp(os.path.getmtime(path)).isoformat(),
            'chars': len(query), 'ticket_key': 'yes' if TICKET.search(query) else '', 'continuation': 'yes' if CONTINUATION.match(query) else '',
            'prompt_log_forms': ','.join(sorted(forms)), 'grep_patterns': ' | '.join(patterns), 'tail_n': ','.join(tails), 'engine_queries': ' | '.join(engine),
            'read_resume': 'yes' if resume else '', 'ran_sweep': 'yes' if sweep else '',
            'candidate': 'yes' if (forms and len(query) >= MIN_CHARS and not CONTINUATION.match(query)) else '',
            'first_message': query.replace('\n', '\\n'),
        })
    return rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--workspace-slug', default='Users-twojda-workspace')
    args = parser.parse_args()
    rows = mine(args.workspace_slug)
    OUT.mkdir(parents=True, exist_ok=True)
    target = OUT / 'sessions.tsv'
    with open(target, 'w', newline='', encoding='utf-8') as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]), delimiter='\t')
        writer.writeheader()
        writer.writerows(rows)
    used = [r for r in rows if r['prompt_log_forms']]
    candidates = [r for r in rows if r['candidate']]
    print(f"sessions {len(rows)} | with a prompt.log read in the first {CALL_LIMIT} calls {len(used)} | candidates {len(candidates)} | with ticket key {sum(1 for r in candidates if r['ticket_key'])} | dated {sum(1 for r in rows if r['started_at'])}")
    print(target)


if __name__ == '__main__':
    main()
