import hashlib
import json
import sqlite3
import sys
from pathlib import Path

from common import DATA_DIR, PROMPT_LOG

DOCUMENT_DATABASES = ('convo.db', 'worklogs.db', 'artifacts.db')
WATCHED = [DATA_DIR / name for name in ('convo.db', 'worklogs.db', 'artifacts.db', 'journal.db')] + [PROMPT_LOG]


def file_entry(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as handle:
        for block in iter(lambda: handle.read(1 << 20), b''):
            digest.update(block)
    stat = path.stat()
    return {'bytes': stat.st_size, 'mtime': stat.st_mtime, 'sha256': digest.hexdigest()}


def content_fingerprint(path):
    connection = sqlite3.connect(f'file:{path}?mode=ro', uri=True)
    digest = hashlib.sha256()
    count = size = 0
    last = 0
    for doc_id, content in connection.execute('SELECT id, content FROM documents ORDER BY id'):
        data = (content or '').encode('utf-8')
        digest.update(str(doc_id).encode('ascii') + b'\0' + data + b'\0')
        count += 1
        size += len(data)
        last = doc_id
    connection.close()
    return {'documents': count, 'content_bytes': size, 'max_id': last, 'content_sha256': digest.hexdigest()}


def manifest(paths=None):
    out = {}
    for path in (paths or WATCHED):
        path = Path(path)
        if not path.exists():
            continue
        entry = file_entry(path)
        if path.name in DOCUMENT_DATABASES:
            entry['content'] = content_fingerprint(path)
        out[path.name] = entry
    return out


def stable_key(entry):
    return entry['content']['content_sha256'] if 'content' in entry else entry['sha256']


def same_data(left, right):
    return {name: stable_key(entry) for name, entry in left.items()} == {name: stable_key(entry) for name, entry in right.items()}


if __name__ == '__main__':
    json.dump(manifest(), sys.stdout, indent=1)
    print()
