"""Chaquopy-only local runtime probe, NOT a LinuxCNC or WebHMI server.

This probe deliberately uses a different port and schema than WebHMI.
No machine commands, files, tokens or remote network binding.
SPDX-License-Identifier: GPL-2.0-or-later
"""
from __future__ import annotations

import json
import platform
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = "127.0.0.1"
PORT = 8766
_lock = threading.RLock()
_server: ThreadingHTTPServer | None = None
_thread: threading.Thread | None = None


class _Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/probe":
            self.send_error(404)
            return
        payload = json.dumps({
            "ok": True,
            "kind": "python-runtime-probe",
            "webhmi": False,
            "schema": 0,
            "python": platform.python_version(),
        }).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, format, *args):
        return


def start() -> str:
    global _server, _thread
    with _lock:
        if _server is not None:
            return "ALREADY_RUNNING"
        server = ThreadingHTTPServer((HOST, PORT), _Handler)
        server.daemon_threads = True
        thread = threading.Thread(target=server.serve_forever,
                                  name="linumert-chaquopy-probe", daemon=True)
        _server, _thread = server, thread
        thread.start()
        return "STARTED"


def stop() -> str:
    global _server, _thread
    with _lock:
        server, thread = _server, _thread
        _server, _thread = None, None
    if server is None:
        return "NOT_RUNNING"
    server.shutdown()
    server.server_close()
    if thread is not None:
        thread.join(timeout=3)
    return "STOPPED"
