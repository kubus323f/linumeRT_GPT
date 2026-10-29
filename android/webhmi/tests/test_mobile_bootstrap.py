"""Host-side contract tests for mobile probe without starting Android."""
import importlib.util
import json
import pathlib
import unittest
from unittest.mock import patch
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
        # The real Android app may be serving the production diagnostic on
        # port 8766 while the on-phone build runs. Ask the kernel to allocate
        # a free loopback port instead of touching the existing service.
        with patch.object(module, "PORT", 0):
            self.assertEqual(module.start(), "STARTED")
            self.assertEqual(module.start(), "ALREADY_RUNNING")
            test_port = module._server.server_address[1]
            self.assertGreater(test_port, 0)
            with urlopen(f"http://127.0.0.1:{test_port}/probe", timeout=2) as answer:
                self.assertEqual(answer.status, 200)
                payload = json.load(answer)
            self.assertEqual(payload["schema"], 0)
            self.assertTrue(payload["ok"])
            self.assertFalse(payload["webhmi"])
            self.assertEqual(payload["kind"], "python-runtime-probe")
            self.assertEqual(module.stop(), "STOPPED")
            self.assertEqual(module.stop(), "NOT_RUNNING")
        # Verify that the runtime uses the original fixed port outside tests.
        self.assertEqual(module.PORT, 8766)


if __name__ == "__main__":
    unittest.main()
