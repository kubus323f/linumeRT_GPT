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
    def run(self):
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
                "linumert_webhmi.app", create_app=lambda gateway, token: object()),
        }
        self.patcher = patch.dict(sys.modules, self.modules)
        self.patcher.start()
        spec = importlib.util.spec_from_file_location("mobile_webhmi_under_test", SOURCE)
        self.runtime = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.runtime)

    def tearDown(self):
        self.runtime.stop()
        self.patcher.stop()

    def test_idempotent_start_and_stop(self):
        first = json.loads(self.runtime.start())
        second = json.loads(self.runtime.start())
        self.assertEqual(first["token"], second["token"])
        self.assertGreaterEqual(len(first["token"]), 16)
        self.assertEqual(len(FakeServer.instances), 1)
        self.assertEqual(FakeServer.instances[0].config.options["host"], "127.0.0.1")
        self.assertEqual(FakeServer.instances[0].config.options["port"], 8765)
        self.assertTrue(FakeGateway.instances[0].started)
        for _ in range(50):
            if json.loads(self.runtime.status())["state"] == "READY":
                break
            time.sleep(0.01)
        self.assertEqual(json.loads(self.runtime.status())["state"], "READY")
        self.assertEqual(self.runtime.stop(), "STOPPED")
        self.assertTrue(FakeGateway.instances[0].stopped)
        self.assertEqual(json.loads(self.runtime.status())["state"], "STOPPED")

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
