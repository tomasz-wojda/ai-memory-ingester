import argparse
import csv
import hashlib
import json
import random
import re
import shutil
import sqlite3
import statistics
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path

import common
import snapshot
import task_corpus

ROOT = common.REPO / 'data/eval/real'
BUDGET_BYTES = 22000
MAX_ITEMS = 20
TAIL_LINES = 400
GREP_WORDS = 3
REPEATS = 3
METHODS = ['01-prompt-log', '02-engine-current', '03-bm25', '04-mmarco', '05-bge']
STOP = set('the and for are was with from that this have has not but you your what how why when where which who all can '
           'jak co czy dla się jest oraz przez nie lub ale tak już jako ten ta to na do w z o u po przy aby tego były była było'.split())
ENTRY_START = '--- PROMPT LOG ENTRY'
ENTRY_END = '--- END PROMPT LOG ENTRY ---'
SESSION_PREFIX = 'claudetranscriptuserstwojdaworkspace'


def words_of(phrase):
    return [w for w in re.findall(r'[\w\-]+', phrase.lower()) if len(w) >= 3 and w not in STOP]


def sha256_text(text):
    return hashlib.sha256(text.encode('utf-8')).hexdigest()


def session_observations(session_id):
    connection = sqlite3.connect(f'file:{common.DATA_DIR / "journal.db"}?mode=ro', uri=True)
    try:
        rows = connection.execute('SELECT observation_id, source_kind, user_text, assistant_text FROM journal_observations WHERE session_id = ?', (session_id,)).fetchall()
        sequence = connection.execute('SELECT max(sequence) FROM journal_observations').fetchone()[0]
    finally:
        connection.close()
    return rows, sequence


def prompt_log_entries(lines):
    entries, start = [], None
    for number, line in enumerate(lines):
        if line.startswith(ENTRY_START):
            start = number
        elif line.startswith(ENTRY_END) and start is not None:
            entries.append((start, number))
            start = None
    return entries


def entry_key(lines, start, end):
    body = '\n'.join(lines[start + 1:end])
    user = re.search(r'^USER:\s?(.*?)(?=^ASSISTANT:)', body, re.S | re.M)
    assistant = re.search(r'^ASSISTANT:\s?(.*)', body, re.S | re.M)
    return (normalize_user(user.group(1) if user else ''), normalize_assistant(assistant.group(1) if assistant else ''))


def normalize_user(text):
    return (text or '').strip()[:80]


def normalize_assistant(text):
    return re.sub(r'^[A-Z]+ \u2014 ', '', (text or '').strip())[:60]


def build_snapshot(session_id):
    target = ROOT / 'snapshot'
    if target.exists():
        raise SystemExit(f'{target} exists; delete it to freeze a new snapshot')
    target.mkdir(parents=True)
    rows, sequence = session_observations(session_id)
    observation_ids = {row[0] for row in rows}
    keys = {(normalize_user(row[2]), normalize_assistant(row[3])) for row in rows if row[1] == 'rule_write'}
    (target / 'datasets.json').write_bytes((common.DATA_DIR / 'datasets.json').read_bytes())
    removed = {}
    for name in ('convo.db', 'artifacts.db', 'worklogs.db'):
        task_corpus.backup(common.DATA_DIR / name, target / name)
    for name, archive in (('convo.db', 'chat:turns'), ('artifacts.db', 'chat:artifacts')):
        connection = sqlite3.connect(target / name)
        doomed = []
        for doc_id, head in connection.execute('SELECT id, substr(content, 1, 900) FROM documents WHERE source_archive = ?', (archive,)).fetchall():
            observed = re.search(r'journal_observation_id:\s*"([^"]*)"', head)
            session = re.search(r'session:\s*"([^"]*)"', head)
            if (observed and observed.group(1) in observation_ids) or (session and session.group(1).startswith(SESSION_PREFIX + session_id.replace('-', '')[:8])):
                doomed.append(doc_id)
        task_corpus.delete_ids(connection, doomed)
        task_corpus.finish(connection)
        connection.close()
        removed[archive] = len(doomed)
    original = (common.WORKSPACE_ROOT / 'prompt.log').read_text(encoding='utf-8', errors='replace').split('\n')
    drop = set()
    entries = prompt_log_entries(original)
    for start, end in entries:
        if entry_key(original, start, end) in keys:
            drop.update(range(start, end + 1))
    kept = [line for number, line in enumerate(original) if number not in drop]
    (target / 'prompt.log').write_text('\n'.join(kept), encoding='utf-8')
    manifest = {
        'created_at': datetime.now(timezone.utc).isoformat(), 'session_excluded': session_id, 'journal_sequence': sequence,
        'excluded': {**removed, 'prompt.log entries': sum(1 for s, e in entries if entry_key(original, s, e) in keys), 'prompt.log lines': len(drop)},
        'session_observations': len(rows), 'prompt_log': {'lines_original': len(original), 'lines_kept': len(kept), 'sha256_original': sha256_text('\n'.join(original))},
        'fingerprints': {name: snapshot.content_fingerprint(target / name) for name in ('convo.db', 'artifacts.db', 'worklogs.db')},
    }
    (target / 'snapshot.json').write_text(json.dumps(manifest, indent=1), encoding='utf-8')
    print(json.dumps(manifest['excluded']), '| lines kept', len(kept), 'of', len(original))


def front_matter_value(text, key):
    found = re.search(rf'^{key}:\s*"?([^"\n]*)', text[:1500], re.M)
    return found.group(1).strip() if found else None


def evidence(text):
    marker = text.find('\n---')
    body = text[marker + 5:] if marker >= 0 else text
    return body[:common.SNIPPET]


def fetch(snapshot_dir, origin_db, doc_id):
    connection = sqlite3.connect(f'file:{snapshot_dir / origin_db}?mode=ro', uri=True)
    try:
        row = connection.execute('SELECT content FROM documents WHERE id = ?', (doc_id,)).fetchone()
    finally:
        connection.close()
    return row[0] if row else ''


def render_items(items, snapshot_dir):
    blocks, total, used = [], 0, []
    for rank, item in enumerate(items[:MAX_ITEMS], 1):
        text = fetch(snapshot_dir, item['origin_db'], item['id'])
        item['date'] = front_matter_value(text, 'timestamp') or front_matter_value(text, 'generated_at')
        item['evidence_bytes'] = len(evidence(text).encode('utf-8'))
        block = f"## {rank}. {item['source_archive']} | {item['file_path']} | {item['date'] or 'no date'} | score {item.get('score')}\n\n{evidence(text)}\n\n"
        size = len(block.encode('utf-8'))
        if total + size > BUDGET_BYTES:
            break
        blocks.append(block)
        total += size
        used.append(item)
    return ''.join(blocks), used


def normalize_engine(rows):
    out = []
    for rank, row in enumerate(rows, 1):
        out.append({'rank': rank, 'origin_db': row.get('origin_db'), 'id': row.get('id'), 'source_archive': row.get('source_archive'), 'file_path': row.get('file_path'),
                    'file_name': row.get('file_name'), 'pool': row.get('pool'), 'score': row.get('rerank_score', row.get('fused_score', row.get('rank'))),
                    'rerank_score': row.get('rerank_score')})
    return out


def run_query(snapshot_dir, phrase, rerank, socket_path, extra=()):
    command = ['./ai-memory-ingester', 'query', phrase, '--limit', str(MAX_ITEMS), '--json', '--late', 'off', '--rerank', rerank,
               '--rerank-depth', '30', '--rerank-budget-ms', '10000', '--data-dir', str(snapshot_dir), *extra]
    env = dict(__import__('os').environ)
    env.pop('AI_MEMORY_SERVICE_AUTOSTART', None)
    if socket_path is not None:
        env['AI_MEMORY_SERVICE_SOCKET'] = str(socket_path)
    else:
        env['AI_MEMORY_SERVICE_SOCKET'] = '/nonexistent/none.sock'
    started = time.perf_counter()
    done = subprocess.run(command, cwd=common.REPO, capture_output=True, text=True, timeout=600, env=env)
    wall = time.perf_counter() - started
    out = done.stdout
    rows = json.loads(out[out.index('['):]) if '[' in out else []
    return rows, wall, done.returncode, command


def write_method(folder, command_text, items, context, walls, extra_meta, raw=None):
    folder.mkdir(parents=True, exist_ok=True)
    (folder / 'command.txt').write_text(command_text + '\n', encoding='utf-8')
    if items is not None:
        (folder / 'results.json').write_text(json.dumps(items, indent=1, ensure_ascii=False), encoding='utf-8')
    (folder / 'context.md').write_text(context, encoding='utf-8')
    for name, text in (raw or {}).items():
        (folder / name).write_text(text, encoding='utf-8')
    dates = sorted(i['date'] for i in (items or []) if i.get('date'))
    meta = {'wall_cold_s': round(walls[0], 3), 'wall_warm_s': round(statistics.median(walls[1:] or walls), 3), 'walls_s': [round(w, 3) for w in walls],
            'context_bytes': len(context.encode('utf-8')), 'items_in_context': len(items) if items is not None else None,
            'date_first': dates[0] if dates else None, 'date_last': dates[-1] if dates else None, **extra_meta}
    if items:
        counts = {}
        for item in items:
            counts[item['source_archive']] = counts.get(item['source_archive'], 0) + 1
        meta['archives'] = counts
    (folder / 'meta.json').write_text(json.dumps(meta, indent=1), encoding='utf-8')


def method_prompt_log(query, folder, snapshot_dir, lines_text):
    phrase, qtype = query['phrase'], query['type']
    lines = lines_text.split('\n')
    candidates = [w for w in dict.fromkeys(words_of(phrase))]
    explicit = [w for w in (query.get('grep_terms') or '').split('|') if w]
    identifiers = [t for t in dict.fromkeys(re.findall(r'[\w\-]+', phrase)) if len(t) >= 2 and (t.isupper() or any(c.isdigit() for c in t))]
    rule = 'owner terms'
    if explicit:
        chosen = explicit
    elif identifiers:
        chosen = identifiers
        rule = 'identifiers in the phrase (upper-case or digit tokens, as a person greps them)'
    else:
        rule = f'the {GREP_WORDS} rarest content words found in the log'
        counted = [(sum(1 for line in lines if w in line.lower()), w) for w in candidates]
        chosen = [w for count, w in sorted(counted) if count > 0][:GREP_WORDS]
    pattern = '|'.join(re.escape(w) for w in chosen)
    log = snapshot_dir / 'prompt.log'
    walls, tail_text, grep_text = [], '', ''
    for _ in range(REPEATS):
        started = time.perf_counter()
        tail_text = subprocess.run(['tail', '-n', str(TAIL_LINES), str(log)], capture_output=True, text=True).stdout
        grep_text = subprocess.run(['grep', '-n', '-i', '-E', '-C', '2', pattern, str(log)], capture_output=True, text=True).stdout
        walls.append(time.perf_counter() - started)
    if qtype == 'recent':
        selected = tail_text.split('\n')
        picked, total = [], 0
        for line in reversed(selected):
            size = len(line.encode('utf-8')) + 1
            if total + size > BUDGET_BYTES:
                break
            picked.append(line)
            total += size
        context = '\n'.join(reversed(picked))
        mode = 'tail'
    else:
        windows, current = [], []
        for line in grep_text.split('\n'):
            if line == '--':
                if current:
                    windows.append(current)
                current = []
            else:
                current.append(line)
        if current:
            windows.append(current)
        out, total = [], 0
        for window in reversed(windows):
            block = '\n'.join(window) + '\n--\n'
            size = len(block.encode('utf-8'))
            if total + size > BUDGET_BYTES or len(out) >= MAX_ITEMS:
                break
            out.append(block)
            total += size
        context = ''.join(out)
        mode = 'grep'
    command = f"tail -n {TAIL_LINES} prompt.log ; grep -n -i -E -C 2 '{pattern}' prompt.log   (grep words {chosen}, rule: {rule}; context mode: {mode})"
    write_method(folder, command, None, context, walls, {'grep_words': chosen, 'context_mode': mode, 'grep_matches_lines': grep_text.count('\n'), 'grep_windows': len([w for w in grep_text.split('\n--\n')])},
                 raw={'raw-tail.txt': tail_text, 'raw-grep.txt': grep_text})


def method_engine(query, folder, snapshot_dir, rerank, socket_path):
    walls, rows, code, command = [], [], 0, []
    degraded = False
    for _ in range(REPEATS):
        rows, wall, code, command = run_query(snapshot_dir, query['phrase'], rerank, socket_path)
        walls.append(wall)
    items = normalize_engine(rows)
    context, used = render_items(items, snapshot_dir)
    if rerank == 'on':
        degraded = not any(i.get('rerank_score') is not None for i in items)
    write_method(folder, ' '.join(command), used, context, walls, {'exit_code': code, 'degraded': degraded, 'returned_items': len(items)})


def method_bm25(query, folder, snapshot_dir):
    words = words_of(query['phrase'])
    strict = ' '.join(f'"{w}"' for w in words)
    relaxed = ' OR '.join(f'"{w}"' for w in words)
    walls, rows, used_expression = [], [], strict
    for _ in range(REPEATS):
        started = time.perf_counter()
        rows = []
        for expression in (strict, relaxed):
            rows = []
            for name in ('convo.db', 'worklogs.db', 'artifacts.db'):
                connection = sqlite3.connect(f'file:{snapshot_dir / name}?mode=ro', uri=True)
                try:
                    for doc_id, archive, path, fname, score in connection.execute(
                            'SELECT d.id, d.source_archive, d.file_path, d.file_name, bm25(documents_fts) FROM documents_fts f JOIN documents d ON d.id = f.rowid '
                            'WHERE documents_fts MATCH ? ORDER BY bm25(documents_fts) LIMIT ?', (expression, MAX_ITEMS)):
                        rows.append({'origin_db': name, 'id': doc_id, 'source_archive': archive, 'file_path': path, 'file_name': fname, 'score': round(score, 4)})
                finally:
                    connection.close()
            used_expression = expression
            if len(rows) >= 10:
                break
        rows.sort(key=lambda r: r['score'])
        walls.append(time.perf_counter() - started)
    items = [{'rank': i + 1, **row, 'pool': 'bm25'} for i, row in enumerate(rows[:MAX_ITEMS])]
    context, used = render_items(items, snapshot_dir)
    command = f"SQLite FTS5 on convo.db, worklogs.db, artifacts.db: MATCH {used_expression!r} ORDER BY bm25(documents_fts) LIMIT {MAX_ITEMS} per database, merged by score (OR form used when fewer than 10 rows match the AND form)"
    write_method(folder, command, used, context, walls, {'expression': used_expression, 'returned_items': len(items)})


def start_model_service(model_name, work):
    folder = dict(common.SYSTEMS)[model_name]
    common.write_config(work, folder, common.manifest_checksums()[folder], 'mps', 512)
    process, socket_path = common.start_service(work)
    return process, socket_path


def collect(queries, snapshot_dir, only):
    manifest = json.loads((snapshot_dir / 'snapshot.json').read_text(encoding='utf-8'))
    lines_text = (snapshot_dir / 'prompt.log').read_text(encoding='utf-8')
    test_root = ROOT / 'test'
    failures = []
    for query in queries:
        base = test_root / query['id']
        base.mkdir(parents=True, exist_ok=True)
        (base / 'query.json').write_text(json.dumps({**query, 'snapshot': manifest['created_at'], 'journal_sequence': manifest['journal_sequence'], 'excluded': manifest['excluded']}, indent=1, ensure_ascii=False), encoding='utf-8')
        for method, runner in (('01-prompt-log', lambda q, f: method_prompt_log(q, f, snapshot_dir, lines_text)),
                               ('02-engine-current', lambda q, f: method_engine(q, f, snapshot_dir, 'off', None)),
                               ('03-bm25', lambda q, f: method_bm25(q, f, snapshot_dir))):
            if only and method not in only:
                continue
            try:
                runner(query, base / method)
                print(query['id'], method, 'ok', flush=True)
            except Exception as error:
                failures.append((query['id'], method, repr(error)))
                print(query['id'], method, 'FAILED', repr(error), flush=True)
    for method, model in (('04-mmarco', 'mmarco'), ('05-bge', 'bge-m3')):
        if only and method not in only:
            continue
        work = Path(tempfile.mkdtemp(prefix='gold-', dir='/tmp'))
        process = None
        try:
            process, socket_path = start_model_service(model, work)
            for query in queries:
                try:
                    if not common.warm_up(socket_path, query['phrase'], snapshot_dir):
                        raise RuntimeError('the service did not score a warm-up request')
                    method_engine(query, test_root / query['id'] / method, snapshot_dir, 'on', socket_path)
                    print(query['id'], method, 'ok', flush=True)
                except Exception as error:
                    failures.append((query['id'], method, repr(error)))
                    print(query['id'], method, 'FAILED', repr(error), flush=True)
        finally:
            if process is not None:
                common.stop_service(process)
            shutil.rmtree(work, ignore_errors=True)
    if failures:
        with open(ROOT / 'failures.tsv', 'a', newline='', encoding='utf-8') as handle:
            csv.writer(handle, delimiter='\t').writerows(failures)
    return failures


def blind_labels(queries):
    for query in queries:
        folder = ROOT / 'test' / query['id'] / 'phase2'
        folder.mkdir(parents=True, exist_ok=True)
        target = folder / 'blind-map.json'
        if target.exists():
            continue
        generator = random.Random(f"{1}:{query['id']}")
        labels = list('ABCDE')
        generator.shuffle(labels)
        target.write_text(json.dumps(dict(zip(labels, METHODS)), indent=1), encoding='utf-8')


def read_queries(selected):
    with open(ROOT / 'queries.tsv', newline='', encoding='utf-8') as handle:
        rows = list(csv.DictReader(handle, delimiter='\t'))
    return [row for row in rows if not selected or row['id'] in selected]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['snapshot', 'collect', 'labels'])
    parser.add_argument('--session', default='b1979cf6-59bd-48dd-980e-12a34661c4e4')
    parser.add_argument('--queries', default='')
    parser.add_argument('--methods', default='')
    args = parser.parse_args()
    ROOT.mkdir(parents=True, exist_ok=True)
    selected = set(filter(None, args.queries.split(',')))
    if args.command == 'snapshot':
        build_snapshot(args.session)
    elif args.command == 'collect':
        failures = collect(read_queries(selected), ROOT / 'snapshot', set(filter(None, args.methods.split(','))))
        sys.exit(1 if failures else 0)
    else:
        blind_labels(read_queries(selected))


if __name__ == '__main__':
    main()
