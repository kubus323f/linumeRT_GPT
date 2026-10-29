"""Offline lifecycle tests for the real WebHMI bootstrap with stubbed external server."""
import importlib.util
import json
import pathlib
import sys
import time
import types
import unittest
from unittest.mock import patch

SOURCE = (pathlib.Path(__file__).resolve().parents[1]
          / "app/src/main/python/mobile_webhmi.py")


class FakeGateway:
    instances = []
    def __init__(self, backend):
        self.backend = backend
        self.started = False
        self.stopped = False
        self.instances.append(self)
    def start(self):
        self.started = True
    def stop(self):
        self.stopped = True


class FakeBackend:
    def __init__(self, machine):
        self.machine = machine


class FakeConfig:
    def __init__(self, app, **options):
        self.options = options


class FakeServer:
    instances = []
    def __init__(self, config):
        self.config = config
        self.started = False
        self.should_exit = False
        self.instances.append(self)
    def run(self, sockets=None):
        self.sockets = sockets
        self.started = True
        while not self.should_exit:
            time.sleep(0.01)


def fake_module(name, **members):
    module = types.ModuleType(name)
    module.__dict__.update(members)
    return module


class WebHmiLifecycleTest(unittest.TestCase):
    def setUp(self):
        FakeGateway.instances.clear()
        FakeServer.instances.clear()
        self.modules = {
            "uvicorn": fake_module("uvicorn", Server=FakeServer, Config=FakeConfig),
            "linumert_webhmi": fake_module("linumert_webhmi"),
            "linumert_webhmi.__main__": fake_module(
                "linumert_webhmi.__main__", _demo_machine=lambda: object()),
            "linumert_webhmi.backend": fake_module(
                "linumert_webhmi.backend", FakeBackend=FakeBackend),
            "linumert_webhmi.gateway": fake_module(
                "linumert_webhmi.gateway", Gateway=FakeGateway),
            "linumert_webhmi.app": fake_module(
                "linumert_webhmi.app", create_app=lambda gateway, token, extra_hosts=(): object()),
        }
        self.patcher = patch.dict(sys.modules, self.modules)
        self.patcher.start()
        spec = importlib.util.spec_from_file_location("mobile_webhmi_under_test", SOURCE)
        self.runtime = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.runtime)
        # Never bind the actual port when tests run on the phone: the user's
        # running WebHMI may already own 127.0.0.1:8765.
        class FakeSocket:
            def __init__(self, address):
                self.address = address
            def close(self):
                pass
        self.bind_patch = patch.object(self.runtime, "_bind",
                                       side_effect=lambda ip: FakeSocket(ip))
        self.bind_patch.start()

    def tearDown(self):
        self.runtime.stop()
        self.bind_patch.stop()
        self.patcher.stop()

    def test_idempotent_start_and_stop(self):
        first = json.loads(self.runtime.start())
        second = json.loads(self.runtime.start())
        self.assertEqual(first["token"], second["token"])
        self.assertGreaterEqual(len(first["token"]), 16)
        self.assertEqual(len(FakeServer.instances), 1)
        self.assertEqual(FakeServer.instances[0].config.options["host"], "127.0.0.1")
        self.assertEqual(FakeServer.instances[0].config.options["port"], 8765)
        self.assertEqual([s.address for s in FakeServer.instances[0].sockets],
                         ["127.0.0.1"])
        self.assertTrue(FakeGateway.instances[0].started)
        for _ in range(50):
            if json.loads(self.runtime.status())["state"] == "READY":
                break
            time.sleep(0.01)
        self.assertEqual(json.loads(self.runtime.status())["state"], "READY")
        self.assertEqual(self.runtime.stop(), "STOPPED")
        self.assertTrue(FakeGateway.instances[0].stopped)
        self.assertEqual(json.loads(self.runtime.status())["state"], "STOPPED")

    def test_wifi_dual_bind_and_no_wildcard(self):
        started = json.loads(self.runtime.start("192.168.1.44"))
        self.assertEqual(started["wifi_ip"], "192.168.1.44")
        for _ in range(50):
            if json.loads(self.runtime.status())["state"] == "READY":
                break
            time.sleep(0.01)
        self.assertEqual([s.address for s in FakeServer.instances[0].sockets],
                         ["127.0.0.1", "192.168.1.44"])
        self.assertEqual(self.runtime.stop(), "STOPPED")

    def test_wifi_rejects_public_and_wildcard(self):
        for ip in ("0.0.0.0", "8.8.8.8", "127.0.0.1", "::", "169.254.1.1",
                   "192.168.1.5:8765", "unknown-host"):
            with self.subTest(ip=ip), self.assertRaises(ValueError):
                self.runtime.start(ip)
        self.assertEqual(FakeServer.instances, [])

    def test_missing_dependency_does_not_claim_server_started(self):
        # Fail closed if pydantic-core or another dependency is unavailable.
        with patch.dict(sys.modules, {"linumert_webhmi.backend": None}):
            with self.assertRaises(ImportError):
                self.runtime.start()
        self.assertEqual(json.loads(self.runtime.status())["state"], "STOPPED")
        self.assertEqual(FakeServer.instances, [])

    def test_start_rejects_parallel_stop(self):
        self.runtime.start()
        FakeServer.instances[0].should_exit = True
        self.assertIn(self.runtime.stop(), ("STOPPED", "STOP_PENDING"))
        for _ in range(50):
            if FakeGateway.instances[0].stopped:
                break
            time.sleep(0.01)
        self.assertTrue(FakeGateway.instances[0].stopped)


if __name__ == "__main__":
    unittest.main()
