"""Supervised WebHMI FakeBackend: loopback and optional explicit Wi-Fi listener.

No Docker/Termux/root at runtime. No realtime CNC motion control.
Never bind 0.0.0.0: only 127.0.0.1 and user-approved Wi-Fi IPv4.
SPDX-License-Identifier: GPL-2.0-or-later
"""
from __future__ import annotations

import ipaddress
import json
import secrets
import socket
import threading

HOST = "127.0.0.1"
PORT = 8765
_PRIVATE_WIFI = tuple(ipaddress.ip_network(v) for v in (
    "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"))
_guard = threading.RLock()
_server = None
_thread: threading.Thread | None = None
_token: str | None = None
_gateway = None
_failure: str | None = None
_wifi_ip = ""
_listeners: list[socket.socket] = []


def validate_wifi_ip(value: str) -> str:
    if not value:
        return ""
    try:
        ip = ipaddress.ip_address(value)
    except ValueError as error:
        raise ValueError("INVALID_WIFI_ADDRESS") from error
    if not isinstance(ip, ipaddress.IPv4Address) or not any(
            ip in network for network in _PRIVATE_WIFI):
        raise ValueError("NOT_PRIVATE_WIFI_IPV4")
    return str(ip)


def _bind(ip: str) -> socket.socket:
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind((ip, PORT))
        sock.listen(128)
        sock.setblocking(False)
        return sock
    except Exception:
        sock.close()
        raise


def _serve(server, gateway, listeners):
    global _server, _gateway, _token, _failure, _thread, _wifi_ip, _listeners
    try:
        # Both explicit listeners share one ASGI app, one token, and one Gateway.
        # Uvicorn 0.34 Server.run supports the sockets argument.
        server.run(sockets=listeners)
    except Exception as error:
        with _guard:
            _failure = type(error).__name__
    finally:
        try:
            gateway.stop()
        finally:
            for sock in listeners:
                try:
                    sock.close()
                except OSError:
                    pass
            with _guard:
                if _server is server:
                    _server = _gateway = _token = _thread = None
                    _wifi_ip = ""
                    _listeners = []


def status() -> str:
    with _guard:
        if _thread is not None and _thread.is_alive():
            state = "READY" if getattr(_server, "started", False) else "STARTING"
        else:
            state = "FAILED" if _failure else "STOPPED"
        return json.dumps({
            "state": state, "error": _failure, "port": PORT,
            "wifi_ip": _wifi_ip, "mode": "simulation",
            "hardware_control": False,
        })


def start(wifi_ip: str = "") -> str:
    """Start only if stopped, or return the existing mode without changing it.

    Explicit network changes must first STOP the old server. Returning the
    existing token here is safe only within the owning app's Python bridge.
    """
    global _server, _thread, _token, _gateway, _failure, _wifi_ip, _listeners
    wifi_ip = validate_wifi_ip(wifi_ip)
    with _guard:
        if _thread is not None:
            if _thread.is_alive() and not getattr(_server, "should_exit", False):
                return json.dumps({
                    "state": "STARTING", "token": _token,
                    "port": PORT, "wifi_ip": _wifi_ip,
                })
            if _thread.is_alive():
                raise RuntimeError("STOP_PENDING")
            _server = _thread = _token = _gateway = None

        # Lazy imports leave the probe-only build independent of FastAPI.
        import uvicorn
        from linumert_webhmi.__main__ import _demo_machine
        from linumert_webhmi.backend import FakeBackend
        from linumert_webhmi.gateway import Gateway
        from linumert_webhmi.app import create_app

        # Bind before gateway.start(): failing Wi-Fi address never opens
        # the simulator or leaves a partially enabled network server.
        listeners = []
        try:
            listeners.append(_bind(HOST))
            if wifi_ip:
                listeners.append(_bind(wifi_ip))
            token = secrets.token_urlsafe(32)
            gateway = Gateway(FakeBackend(_demo_machine()))
            app = create_app(gateway, token,
                             extra_hosts=(wifi_ip,) if wifi_ip else ())
            config = uvicorn.Config(
                app, host=HOST, port=PORT, log_level="error",
                access_log=False, ws="wsproto", http="h11", ws_max_size=8192)
            server = uvicorn.Server(config)
            _failure = None
            _server, _token, _gateway, _wifi_ip = server, token, gateway, wifi_ip
            _listeners = listeners
            gateway.start()
            thread = threading.Thread(
                target=_serve, args=(server, gateway, listeners),
                name="linumert-webhmi-sim", daemon=True)
            _thread = thread
            thread.start()
            return json.dumps({
                "state": "STARTING", "token": token,
                "port": PORT, "wifi_ip": wifi_ip,
            })
        except Exception:
            for sock in listeners:
                try:
                    sock.close()
                except OSError:
                    pass
            if _gateway is not None:
                _gateway.stop()
            _server = _token = _gateway = _thread = None
            _wifi_ip = ""
            _listeners = []
            raise


def stop() -> str:
    """Explicit stop. A stuck worker must never appear as STOPPED."""
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
