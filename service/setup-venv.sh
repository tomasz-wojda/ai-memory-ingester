#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PYTHON_BIN=""
VENV="${HOME}/.local/share/ai-memory-service/venv"
WITH_NUMPY=1
while [ "$#" -gt 0 ]; do
  case "$1" in
    --python) PYTHON_BIN="$2"; shift 2 ;;
    --venv) VENV="$2"; shift 2 ;;
    --no-numpy) WITH_NUMPY=0; shift ;;
    -h|--help)
      echo "Usage: setup-venv.sh [--python PATH] [--venv DIR] [--no-numpy]"
      echo "Creates a virtual environment from a final Python 3.11 or newer and installs the hashed requirements."
      echo "Machine-learning packages are not installed here: they wait for the model decisions."
      exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done
if [ -z "$PYTHON_BIN" ]; then
  for candidate in /opt/homebrew/bin/python3.14 /opt/homebrew/bin/python3.13 /opt/homebrew/bin/python3.12 python3.14 python3.13 python3.12 python3.11; do
    if command -v "$candidate" >/dev/null 2>&1; then PYTHON_BIN="$(command -v "$candidate")"; break; fi
  done
fi
if [ -z "$PYTHON_BIN" ]; then echo "No Python 3.11 or newer found; pass --python PATH" >&2; exit 2; fi
if ! "$PYTHON_BIN" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 11) and sys.version_info.releaselevel == "final" else 1)'; then
  echo "$PYTHON_BIN is not a final release of Python 3.11 or newer" >&2; exit 2
fi
"$PYTHON_BIN" -m venv "$VENV"
if [ "$WITH_NUMPY" = "1" ]; then
  "$VENV/bin/python" -m pip install --quiet --disable-pip-version-check --require-hashes --only-binary=:all: -r "$ROOT/requirements/numpy.txt"
fi
echo "Virtual environment ready: $VENV"
echo "Use it with: export AI_MEMORY_SERVICE_PYTHON=$VENV/bin/python"
export PYTHONDONTWRITEBYTECODE=1 PYTHONPATH="$ROOT"
"$VENV/bin/python" -P -m ai_memory_service doctor || true
