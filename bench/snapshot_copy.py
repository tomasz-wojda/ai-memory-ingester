import argparse
import json
import shutil
import sqlite3
import sys
from datetime import date
from pathlib import Path

from common import REPO
from snapshot import manifest

COPIED_DATABASES = ('convo.db', 'worklogs.db', 'artifacts.db')
COPIED_FILES = ('datasets.json',)


def backup_database(source, target):
    reader = sqlite3.connect(f'file:{source}?mode=ro', uri=True)
    writer = sqlite3.connect(target)
    try:
        reader.backup(writer)
    finally:
        writer.close()
        reader.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--name', default=date.today().strftime('%Y%m%d'))
    parser.add_argument('--root', default=str(REPO / 'data' / 'eval' / 'snapshots'))
    args = parser.parse_args()
    target = Path(args.root) / args.name
    if target.exists():
        print(f'refusing to overwrite {target}', file=sys.stderr)
        return 1
    target.mkdir(parents=True)
    source = REPO / 'data'
    for name in COPIED_DATABASES:
        backup_database(source / name, target / name)
    for name in COPIED_FILES:
        shutil.copyfile(source / name, target / name)
    result = manifest([target / name for name in COPIED_DATABASES])
    (target / 'manifest.json').write_text(json.dumps(result, indent=1), encoding='utf-8')
    print(target)
    for name, entry in result.items():
        print(name, entry['content']['documents'], 'documents', entry['bytes'], 'bytes')
    return 0


if __name__ == '__main__':
    sys.exit(main())
