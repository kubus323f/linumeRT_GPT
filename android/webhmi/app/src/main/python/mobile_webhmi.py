"""Run the repository's original WebHMI FakeBackend inside Android CPython.

Optional experimental build only. NOT a LinuxCNC realtime controller.
SPDX-License-Identifier: GPL-2.0-or-later
"""
from __future__ import annotations

import json
import secrets
import threading

HOST = "127.0.0.1"
PORT = 8765
_guard = threading.RLock()
_server = None
_thread: threading.Thread | None = None
_token: str | None = None
_gateway = None
_failure: str | None = None


def _serve(server, gateway):
    global _server, _gateway, _token, _failure, _thread
    try:
        server.run()
    except Exception as error:
        with _guard:
            _failure = type(error).__name__
    finally:
        try:
            gateway.stop()
        finally:
            with _guard:
                if _server is server:
                    _server = _gateway = _token = _thread = None


def status() -> str:
    with _guard:
        if _thread is not None and _thread.is_alive():
            state = "READY" if getattr(_server, "started", False) else "STARTING"
        else:
            state = "FAILED" if _failure else "STOPPED"
        return json.dumps({"state": state, "error": _failure, "port": PORT,
                           "mode": "simulation", "hardware_control": False})


def start() -> str:
    """Return an ephemeral token to the same Android app, never to logs or URL."""
    global _server, _thread, _token, _gateway, _failure
    with _guard:
        if _thread is not None:
            if _thread.is_alive() and not getattr(_server, "should_exit", False):
                return json.dumps({"state": "STARTING", "token": _token, "port": PORT})
            if _thread.is_alive():
                raise RuntimeError("STOP_PENDING")
            _server = _thread = _token = _gateway = None

        # Lazy imports make the probe-only APK independent of FastAPI/Pydantic.
        import uvicorn
        from linumert_webhmi.__main__ import _demo_machine
        from linumert_webhmi.backend import FakeBackend
        from linumert_webhmi.gateway import Gateway
        from linumert_webhmi.app import create_app

        token = secrets.token_urlsafe(32)
        gateway = Gateway(FakeBackend(_demo_machine()))
        app = create_app(gateway, token)
        config = uvicorn.Config(app, host=HOST, port=PORT, log_level="error",
                                access_log=False, ws_max_size=8192)
        server = uvicorn.Server(config)
        _failure = None
        _server, _token, _gateway = server, token, gateway
        gateway.start()
        thread = threading.Thread(target=_serve, args=(server, gateway),
                                  name="linumert-webhmi-sim", daemon=True)
        _thread = thread
        try:
            thread.start()
        except Exception:
            gateway.stop()
            _server = _token = _gateway = _thread = None
            raise
        return json.dumps({"state": "STARTING", "token": token, "port": PORT})


def stop() -> str:
    """Stop is explicit. Failed stop must never be treated as stopped."""
    with _guard:
        thread = _thread
        server = _server
        if thread is None:
            return "STOPPED"
        if server is not None:
            server.should_exit = True
    if threading.current_thread() is thread:
        return "STOP_PENDING"
    thread.join(timeout=5)
    if thread.is_alive():
        return "STOP_PENDING"
    return "STOPPED"
