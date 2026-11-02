import argparse
import json
import os
import secrets
import signal
import socket
import stat
import subprocess
import sys
import time
from pathlib import Path

from . import CONTRACT_VERSION
from .config import load_config, socket_path, token_path, workspace_root
from .doctor import OFFLINE_VARIABLES, run_doctor
from .integrity import tree_sha256
from .server import App, UnixHTTPServer, make_handler


def pid_path(config):
    return socket_path(config).parent / "memory-service.pid"


def client_request(config, method, path, body=None, timeout=3.0):
    token = token_path(config).read_text().strip()
    payload = json.dumps(body).encode("utf-8") if body is not None else b""
    request = (f"{method} {path} HTTP/1.1\r\nHost: local\r\nAuthorization: Bearer {token}\r\n"
               f"Content-Type: application/json\r\nContent-Length: {len(payload)}\r\nConnection: close\r\n\r\n").encode() + payload
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
        sock.settimeout(timeout)
        sock.connect(str(socket_path(config)))
        sock.sendall(request)
        chunks = []
        while True:
            data = sock.recv(65536)
            if not data:
                break
            chunks.append(data)
    head, _, rest = b"".join(chunks).partition(b"\r\n\r\n")
    status = int(head.split(b" ", 2)[1])
    return status, json.loads(rest.decode("utf-8") or "{}")


def is_running(config):
    try:
        status, _ = client_request(config, "GET", "/v1/health", timeout=1.0)
        return status == 200
    except Exception:  # noqa: BLE001
        return False


def process_is_service(pid):
    try:
        result = subprocess.run(["ps", "-o", "command=", "-p", str(pid)], capture_output=True, text=True, timeout=3)
    except (OSError, subprocess.SubprocessError):
        return False
    return "ai_memory_service" in result.stdout


def prepare_run_directory(run_dir, own_names):
    run_dir.mkdir(parents=True, exist_ok=True)
    info = os.stat(run_dir)
    if info.st_uid != os.getuid():
        return f"The run directory belongs to another user: {run_dir}"
    foreign = sorted(set(os.listdir(run_dir)) - set(own_names))
    if stat.S_IMODE(info.st_mode) & 0o077 and foreign:
        return (f"The run directory holds other files and is open to other users: {run_dir}\n"
                "Set AI_MEMORY_SERVICE_SOCKET to a path inside a directory that only the service uses.")
    os.chmod(run_dir, 0o700)
    return None


def command_serve(args, config):
    for variable in OFFLINE_VARIABLES:
        os.environ.setdefault(variable, "1")
    sock = socket_path(config)
    if len(str(sock).encode("utf-8")) > 100:
        print(f"The socket path is too long for a Unix socket ({len(str(sock))} characters, limit 100): {sock}\n"
              "Set AI_MEMORY_SERVICE_SOCKET to a shorter path.", file=sys.stderr)
        return 2
    run_dir = sock.parent
    problem = prepare_run_directory(run_dir, {sock.name, token_path(config).name, pid_path(config).name})
    if problem:
        print(problem, file=sys.stderr)
        return 2
    if sock.exists():
        if is_running(config):
            print("The service is already running.", file=sys.stderr)
            return 1
        sock.unlink()
    token = secrets.token_hex(32)
    descriptor = os.open(token_path(config), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    os.fchmod(descriptor, 0o600)
    with os.fdopen(descriptor, "w") as handle:
        handle.write(token)
    app = App(config, token, allow_stub=args.stub)
    server = UnixHTTPServer(str(sock), make_handler(app), int(config["max_connections"]))
    os.chmod(sock, 0o600)
    pid_descriptor = os.open(pid_path(config), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    os.fchmod(pid_descriptor, 0o600)
    with os.fdopen(pid_descriptor, "w") as pid_handle:
        pid_handle.write(str(os.getpid()))

    def shutdown(signum, frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, shutdown)
    print(f"ai-memory-service {CONTRACT_VERSION} listening on {sock}", file=sys.stderr, flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        app.close()
        for path in (sock, token_path(config), pid_path(config)):
            try:
                path.unlink()
            except FileNotFoundError:
                pass
    return 0


def command_status(args, config):
    try:
        status, body = client_request(config, "GET", "/v1/health")
    except Exception as error:  # noqa: BLE001
        print(json.dumps({"running": False, "reason": type(error).__name__}))
        return 1
    print(json.dumps({"running": status == 200, "socket": str(socket_path(config)), **body}, indent=2))
    return 0 if status == 200 else 1


def command_stop(args, config):
    path = pid_path(config)
    if not path.exists():
        print("The service is not running.")
        return 0
    try:
        pid = int(path.read_text().strip())
        os.kill(pid, 0)
    except ProcessLookupError:
        path.unlink(missing_ok=True)
        print("The service is not running (removed a stale pid file).")
        return 0
    except (ValueError, PermissionError):
        path.unlink(missing_ok=True)
        print("The pid file did not belong to the service; removed it and sent no signal.")
        return 0
    if not process_is_service(pid):
        path.unlink(missing_ok=True)
        print("The pid file did not belong to the service; removed it and sent no signal.")
        return 0
    os.kill(pid, signal.SIGTERM)
    deadline = time.time() + 5
    while time.time() < deadline:
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            print("The service stopped.")
            return 0
        time.sleep(0.1)
    print("The service did not stop within 5 seconds.", file=sys.stderr)
    return 1


def command_doctor(args, config):
    results = run_doctor(config)
    for item in results:
        print(f"{'PASS' if item['ok'] else 'FAIL'}\t{item['check']}\t{item['detail']}")
    return 0 if all(item["ok"] for item in results) else 1


def command_checksum(args, config):
    print(tree_sha256(Path(args.path)))
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(prog="ai-memory-service")
    parser.add_argument("--workspace-root")
    parser.add_argument("--db")
    commands = parser.add_subparsers(dest="command", required=True)
    serve = commands.add_parser("serve")
    serve.add_argument("--stub", action="store_true", help="enable the test-only stub model")
    commands.add_parser("status")
    commands.add_parser("stop")
    commands.add_parser("doctor")
    checksum = commands.add_parser("checksum")
    checksum.add_argument("path")
    args = parser.parse_args(argv)
    config = load_config(workspace_root(args.workspace_root), {"db_path": args.db})
    handlers = {"serve": command_serve, "status": command_status, "stop": command_stop,
                "doctor": command_doctor, "checksum": command_checksum}
    return handlers[args.command](args, config)


if __name__ == "__main__":
    sys.exit(main())
