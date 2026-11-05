import argparse
import hashlib
import json
import re
import sqlite3
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import common
import snapshot

PROBE_QUERIES = [
    'traefik headers', 'how traefik passes headers', 'summary of KD-8130', 'argocd', 'sctask1544083', 'mongo metric',
    'trace in konto', 'kibana audit', 'sync manifest', 'oauth session', 'tempo loki', 'jenkins seed',
]
TIMESTAMP_KEY = re.compile(r'(?:timestamp|event_time|generated_at):\s*"?([^"\n]+)')
ISO_FORM = re.compile(r'^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})(:\d{2})?(\.\d+)?\s*(Z|[+-]\d{2}:?\d{2})$')
HUMAN_FORM = re.compile(r'^\w+, (\w{3}) (\d{1,2}), (\d{4}), (\d{1,2}):(\d{2}) (AM|PM) \(UTC([+-]\d{1,2})(?::(\d{2}))?\)$')
MONTHS = {name: index + 1 for index, name in enumerate(['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'])}
CHUNK = 500


def parse_instant(text):
    found = TIMESTAMP_KEY.search(text or '')
    if not found:
        return None
    value = found.group(1).strip()
    iso = ISO_FORM.match(value)
    if iso:
        zone = iso.group(5)
        zone = '+00:00' if zone == 'Z' else (zone if ':' in zone else zone[:3] + ':' + zone[3:])
        try:
            return datetime.fromisoformat(f"{iso.group(1)}T{iso.group(2)}{iso.group(3) or ':00'}{zone}").astimezone(timezone.utc)
        except ValueError:
            return None
    human = HUMAN_FORM.match(value)
    if human:
        month, day, year, hour, minute, meridian, offset_hours, offset_minutes = human.groups()
        hour = int(hour) % 12 + (12 if meridian == 'PM' else 0)
        sign = -1 if offset_hours.startswith('-') else 1
        offset = timedelta(hours=abs(int(offset_hours)), minutes=int(offset_minutes or 0)) * sign
        try:
            return datetime(int(year), MONTHS[month], int(day), hour, int(minute), tzinfo=timezone(offset)).astimezone(timezone.utc)
        except (ValueError, KeyError):
            return None
    return None


def parse_freeze(task):
    return datetime.fromisoformat(task['freeze_instant'].replace('Z', '+00:00')).astimezone(timezone.utc)


def corpus_key(task):
    excluded = sorted(task['excluded'])
    marker = hashlib.md5(','.join(map(str, excluded)).encode()).hexdigest()[:8] if excluded else 'none'
    freeze = parse_freeze(task).strftime('%Y%m%dT%H%MZ')
    return f"t{task['cutoff_turn']}-x{marker}-f{freeze}"


def snapshot_manifest(snapshot_dir):
    return json.loads((Path(snapshot_dir) / 'manifest.json').read_text(encoding='utf-8'))


def source_fingerprints(snapshot_dir):
    return {name: entry['content']['content_sha256'] for name, entry in snapshot_manifest(snapshot_dir).items() if 'content' in entry}


def backup(source, target):
    reader = sqlite3.connect(f'file:{source}?mode=ro', uri=True)
    writer = sqlite3.connect(target)
    try:
        reader.backup(writer)
    finally:
        writer.close()
        reader.close()


def turn_number_function(name):
    found = re.search(r'turn-(\d+)\.md$', name or '')
    return int(found.group(1)) if found else None


def finish(connection):
    connection.commit()
    connection.execute("INSERT INTO documents_fts(documents_fts) VALUES('optimize')")
    connection.commit()
    connection.isolation_level = None
    connection.execute('VACUUM')


def delete_ids(connection, ids):
    for start in range(0, len(ids), CHUNK):
        part = ids[start:start + CHUNK]
        connection.execute(f"DELETE FROM documents WHERE id IN ({','.join('?' * len(part))})", part)


def count(path, archive):
    connection = sqlite3.connect(f'file:{path}?mode=ro', uri=True)
    try:
        return connection.execute('SELECT count(*) FROM documents WHERE source_archive = ?', (archive,)).fetchone()[0]
    finally:
        connection.close()


def build(snapshot_dir, task, root=None):
    snapshot_dir = Path(snapshot_dir)
    key = corpus_key(task)
    target = Path(root or snapshot_dir / 'tasks') / key
    if target.exists():
        raise FileExistsError(f'refusing to overwrite {target}')
    target.mkdir(parents=True)
    (target / 'datasets.json').write_bytes((snapshot_dir / 'datasets.json').read_bytes())
    backup(snapshot_dir / 'worklogs.db', target / 'worklogs.db')
    backup(snapshot_dir / 'convo.db', target / 'convo.db')
    backup(snapshot_dir / 'artifacts.db', target / 'artifacts.db')
    freeze = parse_freeze(task)
    removed = {}
    convo = sqlite3.connect(target / 'convo.db')
    convo.create_function('turnno', 1, turn_number_function)
    before = convo.execute("SELECT count(*) FROM documents WHERE source_archive = 'chat:turns'").fetchone()[0]
    marks = ','.join('?' * len(task['excluded']))
    clause = f"turnno(file_name) > ?{f' OR turnno(file_name) IN ({marks})' if task['excluded'] else ''}"
    convo.execute(f"DELETE FROM documents WHERE source_archive = 'chat:turns' AND ({clause})", [task['cutoff_turn'], *sorted(task['excluded'])])
    removed['chat:turns'] = before - convo.execute("SELECT count(*) FROM documents WHERE source_archive = 'chat:turns'").fetchone()[0]
    finish(convo)
    convo.close()
    artifacts = sqlite3.connect(target / 'artifacts.db')
    late, unparseable, kept = [], 0, 0
    for doc_id, head in artifacts.execute("SELECT id, substr(content, 1, 700) FROM documents WHERE source_archive = 'chat:artifacts'").fetchall():
        instant = parse_instant(head)
        if instant is None:
            unparseable += 1
        elif instant > freeze:
            late.append(doc_id)
        else:
            kept += 1
    delete_ids(artifacts, late)
    removed['chat:artifacts'] = len(late)
    finish(artifacts)
    artifacts.close()
    fingerprints = {name: snapshot.content_fingerprint(target / name) for name in ('convo.db', 'worklogs.db', 'artifacts.db')}
    manifest = {
        'key': key,
        'parameters': {'cutoff_turn': task['cutoff_turn'], 'excluded': sorted(task['excluded']), 'freeze_instant': parse_freeze(task).isoformat()},
        'source_content_sha256': source_fingerprints(snapshot_dir),
        'removed': removed,
        'artifacts_kept_dated': kept,
        'artifacts_unparseable_kept': unparseable,
        'worklog_documents_kept': fingerprints['worklogs.db']['documents'],
        'fingerprints': fingerprints,
        'sizes_bytes': {name: (target / name).stat().st_size for name in ('convo.db', 'worklogs.db', 'artifacts.db')},
    }
    (target / 'manifest.json').write_text(json.dumps(manifest, indent=1), encoding='utf-8')
    return target, manifest


def ensure(snapshot_dir, task):
    snapshot_dir = Path(snapshot_dir)
    target = snapshot_dir / 'tasks' / corpus_key(task)
    if not target.exists():
        return build(snapshot_dir, task)[0]
    manifest = json.loads((target / 'manifest.json').read_text(encoding='utf-8'))
    if manifest['key'] != corpus_key(task) or manifest['source_content_sha256'] != source_fingerprints(snapshot_dir):
        raise RuntimeError(f'{target} does not match the snapshot; delete that folder and run again')
    return target


def match_expression(query):
    return ' '.join(f'"{token}"' for token in re.findall(r'\w+', query))


def hits(path, query, only_chat_turns=False):
    connection = sqlite3.connect(f'file:{path}?mode=ro', uri=True)
    try:
        sql = ('SELECT d.source_archive, d.file_path FROM documents_fts f JOIN documents d ON d.id = f.rowid '
               'WHERE documents_fts MATCH ?')
        return {(row[0], row[1]) for row in connection.execute(sql, (match_expression(query),))}
    finally:
        connection.close()


def eligible(task, pair):
    if pair[0] != 'chat:turns':
        return True
    number = turn_number_function(pair[1])
    return number is None or (number <= task['cutoff_turn'] and number not in task['excluded'])


def verify(snapshot_dir, task):
    snapshot_dir = Path(snapshot_dir)
    folder = ensure(snapshot_dir, task)
    results = []

    def check(name, ok, detail=''):
        results.append((name, bool(ok), detail))

    convo = sqlite3.connect(f'file:{folder / "convo.db"}?mode=ro', uri=True)
    convo.create_function('turnno', 1, turn_number_function)
    above = convo.execute("SELECT count(*) FROM documents WHERE source_archive = 'chat:turns' AND turnno(file_name) > ?", (task['cutoff_turn'],)).fetchone()[0]
    check('no chat turn above the cutoff', above == 0, str(above))
    if task['excluded']:
        marks = ','.join('?' * len(task['excluded']))
        found = convo.execute(f"SELECT count(*) FROM documents WHERE source_archive = 'chat:turns' AND turnno(file_name) IN ({marks})", sorted(task['excluded'])).fetchone()[0]
        check('no excluded turn left', found == 0, str(found))
    convo.close()
    freeze = parse_freeze(task)
    for name in ('convo.db', 'artifacts.db'):
        connection = sqlite3.connect(f'file:{folder / name}?mode=ro', uri=True)
        documents = connection.execute('SELECT count(*) FROM documents').fetchone()[0]
        rows = connection.execute('SELECT count(*) FROM documents_fts_docsize').fetchone()[0]
        check(f'{name}: documents equal index rows', documents == rows, f'{documents} / {rows}')
        connection.close()
        writer = sqlite3.connect(folder / name)
        try:
            writer.execute("INSERT INTO documents_fts(documents_fts) VALUES('integrity-check')")
            check(f'{name}: integrity-check', True)
        except sqlite3.DatabaseError as error:
            check(f'{name}: integrity-check', False, str(error))
        finally:
            writer.close()
    artifacts = sqlite3.connect(f'file:{folder / "artifacts.db"}?mode=ro', uri=True)
    later = sum(1 for (head,) in artifacts.execute("SELECT substr(content, 1, 700) FROM documents WHERE source_archive = 'chat:artifacts'")
                if (parse_instant(head) or freeze) > freeze)
    artifacts.close()
    check('no artifact after the freeze instant', later == 0, str(later))
    mismatches = []
    for db in ('convo.db', 'artifacts.db'):
        for query in PROBE_QUERIES:
            cut = hits(folder / db, query)
            full = {pair for pair in hits(snapshot_dir / db, query)}
            if db == 'convo.db':
                full = {pair for pair in full if eligible(task, pair)}
            else:
                connection = sqlite3.connect(f'file:{snapshot_dir / db}?mode=ro', uri=True)
                dated = {}
                for path, head in connection.execute("SELECT file_path, substr(content, 1, 700) FROM documents WHERE source_archive = 'chat:artifacts'"):
                    dated[path] = parse_instant(head)
                connection.close()
                full = {pair for pair in full if (dated.get(pair[1]) or freeze) <= freeze}
            if cut != full:
                mismatches.append((db, query, len(cut), len(full)))
    check('index results equal the full snapshot without post-freeze documents (12 probe queries, 2 databases)', not mismatches, str(mismatches[:3]))
    return folder, results


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('command', choices=['build', 'verify', 'manifest'])
    parser.add_argument('--snapshot', default=None)
    parser.add_argument('--tasks', default='')
    args = parser.parse_args()
    snapshot_dir = Path(args.snapshot) if args.snapshot else common.DATA_DIR
    tasks = common.load_tasks()
    if args.tasks:
        tasks = {key: task for key, task in tasks.items() if key in args.tasks.split(',')}
    failed = False
    for key, task in tasks.items():
        if args.command == 'build':
            folder = ensure(snapshot_dir, task)
            manifest = json.loads((folder / 'manifest.json').read_text(encoding='utf-8'))
            print(key, folder.name, 'removed', manifest['removed'], 'sizes MB', {k: round(v / 1e6) for k, v in manifest['sizes_bytes'].items()},
                  'artifacts unparseable', manifest['artifacts_unparseable_kept'])
        elif args.command == 'manifest':
            print(key, corpus_key(task))
        else:
            folder, results = verify(snapshot_dir, task)
            print('==', key, folder.name)
            for name, ok, detail in results:
                print(f"  {'PASS' if ok else 'FAIL'}  {name}  {detail}")
                failed = failed or not ok
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
