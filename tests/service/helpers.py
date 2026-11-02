import json
import os
import shutil
import socket
import sys
import tempfile
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "service"))

from ai_memory_service.config import load_config, socket_path  # noqa: E402
from ai_memory_service.server import App, UnixHTTPServer, make_handler  # noqa: E402


def short_temp():
    return tempfile.mkdtemp(dir="/tmp", prefix="ams")


def raw_request(sock_path, method, path, body=None, token=None, headers=None, timeout=5.0):
    payload = json.dumps(body).encode("utf-8") if body is not None else b""
    lines = [f"{method} {path} HTTP/1.1", "Host: local", f"Content-Length: {len(payload)}", "Connection: close"]
    if token is not None:
        lines.append(f"Authorization: Bearer {token}")
    for key, value in (headers or {}).items():
        lines.append(f"{key}: {value}")
    request = ("\r\n".join(lines) + "\r\n\r\n").encode("utf-8") + payload
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
        sock.settimeout(timeout)
        sock.connect(str(sock_path))
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


class ServiceFixture:
    def __init__(self, allow_stub=True, overrides=None, max_connections=32):
        self.directory = Path(short_temp())
        config = load_config(self.directory, {"db_path": str(self.directory / "semantic.db"), **(overrides or {})})
        config["socket"] = "run/s.sock"
        (self.directory / "run").mkdir()
        self.config = config
        self.token = "test-token"
        self.app = App(config, self.token, allow_stub=allow_stub)
        self.sock = socket_path(config)
        self.server = UnixHTTPServer(str(self.sock), make_handler(self.app), max_connections)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def request(self, method, path, body=None, token="default", headers=None):
        return raw_request(self.sock, method, path, body, self.token if token == "default" else token, headers)

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.app.close()
        shutil.rmtree(self.directory, ignore_errors=True)
