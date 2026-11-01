import hashlib
from pathlib import Path

UNSAFE_WEIGHT_SUFFIXES = (".bin", ".pt", ".pth", ".pkl", ".pickle", ".ckpt")


def file_sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def tree_sha256(directory):
    root = Path(directory)
    digest = hashlib.sha256()
    for path in sorted(p for p in root.rglob("*") if p.is_file()):
        relative = path.relative_to(root).as_posix()
        digest.update(f"{relative}:{file_sha256(path)}\n".encode("utf-8"))
    return digest.hexdigest()


def weight_problems(directory):
    root = Path(directory)
    files = [p for p in root.rglob("*") if p.is_file()]
    has_safetensors = any(p.suffix == ".safetensors" for p in files)
    unsafe = [p.name for p in files if p.suffix in UNSAFE_WEIGHT_SUFFIXES]
    problems = []
    if not has_safetensors:
        problems.append("no .safetensors weight file")
    if unsafe:
        problems.append("unsafe weight files: " + ", ".join(sorted(unsafe)))
    return problems
