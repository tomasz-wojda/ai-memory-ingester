import importlib.util
import json
import os
import re
import shutil
import signal
import sqlite3
import subprocess
import tempfile
import time
from datetime import datetime, timedelta
from pathlib import Path

BENCH_DIR = Path(__file__).resolve().parent
REPO = BENCH_DIR.parent
WORKSPACE_ROOT = REPO.parents[1]
MODELS_DIR = Path(os.environ.get('MODELS_DIR') or Path.home() / '.local/share/ai-memory-service/models')
VENV_PYTHON = Path(os.environ.get('VENV_PYTHON') or Path.home() / '.local/share/ai-memory-service/venv/bin/python')
PROMPT_LOG = Path(os.environ.get('PROMPT_LOG') or WORKSPACE_ROOT / 'prompt.log')
TASKS_DIR = Path(os.environ.get('BENCH_TASKS_DIR') or REPO / 'data/eval/ledger-run/tasks')
OUT_DIR = Path(os.environ.get('BENCH_OUT_DIR') or REPO / 'data/eval/ledger-run')

BUDGET = 22000
SNIPPET = 1050
CUTS = (5, 10, 20)
ORDERS = ('fused', 'model_only')
RECALL_CUTS = (10, 30, 50, 100)

SYSTEMS = [
    ('lexical', None),
    ('msmarco-l6', 'ms-marco-MiniLM-L-6-v2'),
    ('msmarco-l12', 'ms-marco-MiniLM-L-12-v2'),
    ('mmarco', 'mmarco-mMiniLMv2-L12-H384-v1'),
    ('bge-m3', 'bge-reranker-v2-m3'),
    ('polish-roberta', 'polish-reranker-roberta-v3'),
]

CONNECTIONS = {}


def load_tasks(directory=None):
    folder = Path(directory) if directory else TASKS_DIR
    tasks = {}
    for path in sorted(folder.glob('task_*.py')):
        spec = importlib.util.spec_from_file_location(path.stem, path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        task = dict(module.TASK)
        task['key'] = path.stem.split('_', 1)[1].upper()
        task.setdefault('excluded', set())
        task.setdefault('extra_queries', {})
        task.setdefault('ledger_sept', {})
        task.setdefault('journal_keys', None)
        task.setdefault('journal_all_regex', None)
        task.setdefault('text_match', None)
        tasks[task['key']] = task
    return tasks


def manifest_checksums():
    data = json.loads((MODELS_DIR / 'models.manifest.json').read_text(encoding='utf-8'))
    return {name: entry['folder_checksum'] for name, entry in data['models'].items()}


def connection(origin):
    if origin not in CONNECTIONS:
        CONNECTIONS[origin] = sqlite3.connect(f"file:{REPO / 'data' / origin}?mode=ro", uri=True)
    return CONNECTIONS[origin]


def number(name):
    found = re.search(r'turn-(\d+)\.md$', name or '')
    return int(found.group(1)) if found else None


def turn_snippet(doc_id):
    row = connection('convo.db').execute(
        "SELECT file_name, substr(content, instr(content, char(10) || '---') + 5, ?) FROM documents WHERE id = ?",
        (SNIPPET, doc_id)).fetchone()
    return row


def document_snippet(origin, doc_id):
    row = connection(origin).execute(
        "SELECT substr(content, CASE WHEN instr(content, char(10) || '---') > 0 THEN instr(content, char(10) || '---') + 5 ELSE 1 END, ?) FROM documents WHERE id = ?",
        (SNIPPET, doc_id)).fetchone()
    return row[0] if row and row[0] else ''


def full_text(origin, doc_id):
    row = connection(origin).execute('SELECT content FROM documents WHERE id = ?', (doc_id,)).fetchone()
    return (row[0] or '') if row else ''


def eligible_row(task, row):
    if row.get('source_archive') != 'chat:turns':
        return True
    turn = number(row.get('file_name') or row.get('file_path'))
    return turn is not None and turn <= task['cutoff_turn'] and turn not in task['excluded']


def frozen_top(task, rows, limit=20):
    picked = []
    for row in rows:
        turn = number(row.get('file_name') or row.get('file_path'))
        if turn is None or turn > task['cutoff_turn'] or turn in task['excluded']:
            continue
        picked.append(row)
        if len(picked) == limit:
            break
    return picked


def top_rows(task, rows, limit=20):
    picked = []
    for row in rows:
        if not eligible_row(task, row):
            continue
        picked.append(row)
        if len(picked) == limit:
            break
    return picked


def turn_texts(rows, mode='head'):
    texts, size, fallbacks = [], 0, 0
    for row in rows:
        fetched = turn_snippet(row['id'])
        if not fetched:
            continue
        text = fetched[1] or ''
        if mode == 'best_passage':
            passage = row.get('best_passage')
            if passage:
                text = passage
            else:
                fallbacks += 1
        size += len(text.encode('utf-8'))
        texts.append(text)
    return texts, size, fallbacks


def multi_texts(rows, mode='head'):
    texts, fallbacks = [], 0
    for row in rows:
        text = document_snippet(row['origin_db'], row['id'])
        if mode == 'best_passage':
            passage = row.get('best_passage')
            if passage:
                text = passage
            else:
                fallbacks += 1
        texts.append(text)
    return texts, sum(len(t.encode('utf-8')) for t in texts), fallbacks


def present(strict, texts):
    blob = '\n'.join(texts).lower()
    return {fact: all(re.search(group, blob) for group in groups) for fact, groups in strict.items()}


def strict_score(task, flags):
    weights = {fact: weight for fact, weight, _, _ in task['ledger']}
    total = sum(weights[fact] for fact in task['strict'])
    got = sum(weights[fact] for fact in task['strict'] if flags[fact])
    return round(100.0 * got / total, 1), got, total


def coverage_for(task, texts, cuts=CUTS):
    out = {}
    for cut in cuts:
        out[f'cov@{cut}'] = strict_score(task, present(task['strict'], texts[:cut]))[0]
    flags = present(task['strict'], texts)
    out['cov@all'] = strict_score(task, flags)[0]
    out['facts'] = sorted(fact for fact in task['strict'] if flags[fact])
    anchor_sets = [task['strict'].get(fact) or groups for fact, _, _, groups in task['ledger']]
    out['useful_items'] = sum(1 for text in texts if any(all(re.search(g, text.lower()) for g in groups) for groups in anchor_sets))
    out['items'] = len(texts)
    return out


def model_only_order(rows):
    scored = [row for row in rows if row.get('rerank_score') is not None]
    scored.sort(key=lambda row: -float(row['rerank_score']))
    return scored + [row for row in rows if row.get('rerank_score') is None]


def chat_rows(rows):
    return [row for row in rows if row.get('source_archive') == 'chat:turns']


def percentile(values, share):
    ordered = sorted(values)
    if not ordered:
        return None
    index = min(len(ordered) - 1, max(0, int(round(share * len(ordered) + 0.5)) - 1))
    return ordered[index]


def fact_recall(task, rows):
    kept = [row for row in rows if eligible_row(task, row)]
    texts = [full_text(row['origin_db'], row['id']).lower() for row in kept]
    weights = {fact: weight for fact, weight, _, _ in task['ledger']}
    ranks = {}
    for fact, groups in task['strict'].items():
        ranks[fact] = next((index + 1 for index, text in enumerate(texts) if all(re.search(g, text) for g in groups)), None)
    total = sum(weights[fact] for fact in ranks)
    out = {'facts': ranks, 'results': len(kept)}
    for cut in RECALL_CUTS:
        got = sum(weights[fact] for fact, rank in ranks.items() if rank is not None and rank <= cut)
        out[f'recall@{cut}'] = round(100.0 * got / total, 1)
    out['never_found'] = sorted(fact for fact, rank in ranks.items() if rank is None)
    return out


def local_time(raw):
    if not raw:
        return None
    found = re.search(r'(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{1,2}):(\d{2}))?', raw)
    if not found:
        return None
    year, month, day, hour, minute = found.groups()
    moment = datetime(int(year), int(month), int(day), int(hour or 0), int(minute or 0))
    if raw.rstrip().endswith('Z'):
        moment += timedelta(hours=2)
    return moment


def groovy_home():
    binary = shutil.which('groovy')
    return Path(binary).resolve().parent.parent


def parse_journal():
    folder = Path(tempfile.mkdtemp(prefix='mb-', dir='/tmp'))
    target = folder / 'entries.json'
    classpath = ':'.join([str(REPO / 'dist/ai-memory-ingester.jar'), str(REPO / 'vendor/*'), str(groovy_home() / 'lib/*')])
    started = time.perf_counter()
    done = subprocess.run(['java', '--enable-native-access=ALL-UNNAMED', '-cp', classpath, 'groovy.ui.GroovyMain',
                           str(BENCH_DIR / 'dump_journal.groovy'), str(target), str(PROMPT_LOG)], capture_output=True, text=True, timeout=600)
    elapsed = time.perf_counter() - started
    if done.returncode != 0:
        raise RuntimeError(done.stderr[-400:])
    entries = json.loads(target.read_text(encoding='utf-8'))
    shutil.rmtree(folder, ignore_errors=True)
    for entry in entries:
        entry['time'] = local_time(entry.get('rawTimestamp'))
        entry['text'] = entry['rawBody']
    return entries, elapsed


def journal_matcher(task, by_text):
    if by_text:
        pattern = task['text_match']
        return lambda entry: bool(re.search(pattern, entry['rawBody'], re.I))
    if task['journal_keys']:
        keys = {key.upper() for key in task['journal_keys']}
        return lambda entry: any(ticket.upper() in keys for ticket in entry['ticketKeys'])
    patterns = task['journal_all_regex']
    return lambda entry: all(re.search(pattern, entry['rawBody'], re.I) for pattern in patterns)


def select_journal(entries, task, greedy=False, by_text=False):
    matches = journal_matcher(task, by_text)
    eligible = [e for e in entries if e['time'] is not None and e['time'] < task['journal_cutoff'] and matches(e)]
    eligible.sort(key=lambda e: (e['time'], e['byteOffset']), reverse=True)
    chosen, size = [], 0
    for entry in eligible:
        step = len(entry['text'].encode('utf-8'))
        if size + step > BUDGET:
            if greedy:
                continue
            break
        chosen.append(entry)
        size += step
    return chosen, size, len(eligible)


def write_config(workspace, folder, checksum, device, max_tokens):
    config = {
        'rerank_model': 'bench',
        'model_cache': str(MODELS_DIR),
        'models': {'bench': {'path': folder, 'sha256': checksum, 'max_tokens': max_tokens, 'device': device}},
        'max_rss_mb': 4096,
    }
    target = workspace / '.ai-worklog'
    target.mkdir(parents=True, exist_ok=True)
    (target / 'memory-service.json').write_text(json.dumps(config), encoding='utf-8')


def start_service(workspace):
    env = dict(os.environ, AI_MEMORY_SERVICE_PYTHON=str(VENV_PYTHON))
    env.pop('AI_MEMORY_SERVICE_SOCKET', None)
    log = open(workspace / 'service.log', 'w')
    process = subprocess.Popen(
        [str(REPO / 'service/ai-memory-service'), '--workspace-root', str(workspace), '--db', str(workspace / 'semantic.db'), 'serve'],
        env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    socket_path = workspace / '.ai-worklog/run/memory-service.sock'
    for _ in range(150):
        if socket_path.exists():
            return process, socket_path
        time.sleep(0.2)
    stop_service(process)
    raise RuntimeError('service socket did not appear')


def stop_service(process):
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except (ProcessLookupError, PermissionError):
        return
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)


def run_engine(query, rerank, depth, budget_ms, socket_path, archive='chat:turns', extra_args=()):
    command = ['./ai-memory-ingester', 'query', query, '--limit', '300', '--json', '--late', 'off', '--relax', 'auto',
               '--rerank', rerank, '--rerank-depth', str(depth), '--rerank-budget-ms', str(budget_ms), *extra_args]
    if archive:
        command += ['-A', archive]
    env = dict(os.environ)
    if socket_path is not None:
        env['AI_MEMORY_SERVICE_SOCKET'] = str(socket_path)
    started = time.perf_counter()
    done = subprocess.run(command, cwd=REPO, capture_output=True, text=True, timeout=600, env=env)
    wall = time.perf_counter() - started
    out = done.stdout
    rows = json.loads(out[out.index('['):]) if '[' in out else []
    return chat_rows(rows), rows, wall, done.returncode


def run_repeated(query, rerank, depth, budget_ms, socket_path, archive='chat:turns', extra_args=(), repeats=1):
    first = run_engine(query, rerank, depth, budget_ms, socket_path, archive, extra_args)
    walls = [first[2]]
    degraded = 1 if rerank == 'on' and not any(r.get('rerank_score') is not None for r in first[1]) else 0
    for _ in range(max(repeats, 1) - 1):
        _, rows, wall, _ = run_engine(query, rerank, depth, budget_ms, socket_path, archive, extra_args)
        walls.append(wall)
        if rerank == 'on' and not any(r.get('rerank_score') is not None for r in rows):
            degraded += 1
    return {'chat': first[0], 'rows': first[1], 'code': first[3], 'walls': walls, 'p50': round(percentile(walls, 0.5), 2),
            'p95': round(percentile(walls, 0.95), 2), 'degraded_runs': degraded}


def warm_up(socket_path, query):
    for _ in range(12):
        _, rows, _, _ = run_engine(query, 'on', 5, 10000, socket_path)
        if any(row.get('rerank_score') is not None for row in rows):
            return True
        time.sleep(2)
    return False


def case_queries(task):
    cases = [('question', task['forms']['question'])]
    cases += list(task['extra_queries'].items())
    return cases
