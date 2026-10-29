"""Host-side contract tests for mobile probe without starting Android."""
import importlib.util
import json
import pathlib
import unittest
from urllib.request import urlopen

SOURCE = pathlib.Path(__file__).resolve().parents[1] / "app/src/main/python/mobile_bootstrap.py"
spec = importlib.util.spec_from_file_location("mobile_bootstrap", SOURCE)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class MobileBootstrapTest(unittest.TestCase):
    def tearDown(self):
        module.stop()

    def test_loopback_only(self):
        self.assertEqual(module.HOST, "127.0.0.1")
        self.assertEqual(module.PORT, 8766)

    def test_start_health_and_stop(self):
        self.assertEqual(module.start(), "STARTED")
        self.assertEqual(module.start(), "ALREADY_RUNNING")
        with urlopen("http://127.0.0.1:8766/probe", timeout=2) as answer:
            self.assertEqual(answer.status, 200)
            payload = json.load(answer)
        self.assertEqual(payload["schema"], 0)
        self.assertTrue(payload["ok"])
        self.assertFalse(payload["webhmi"])
        self.assertEqual(payload["kind"], "python-runtime-probe")
        self.assertEqual(module.stop(), "STOPPED")
        self.assertEqual(module.stop(), "NOT_RUNNING")


if __name__ == "__main__":
    unittest.main()
