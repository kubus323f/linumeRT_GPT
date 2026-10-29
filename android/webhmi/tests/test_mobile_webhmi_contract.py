"""Source regression gates for optional full-WebHMI bridge."""
from pathlib import Path
import unittest

BASE = Path(__file__).resolve().parents[1]
SCRIPT = BASE / "app/src/main/python/mobile_webhmi.py"
GRADLE = BASE / "app/build.gradle"


class WebHmiContract(unittest.TestCase):
    def test_local_only_and_actual_repository_gateway(self):
        text = SCRIPT.read_text(encoding="utf-8")
        self.assertIn('HOST = "127.0.0.1"', text)
        self.assertIn("PORT = 8765", text)
        self.assertIn("from linumert_webhmi.gateway import Gateway", text)
        self.assertIn("from linumert_webhmi.backend import FakeBackend", text)
        self.assertIn("FakeBackend(_demo_machine())", text)
        self.assertNotIn("LinuxCNCBackend(", text)
        self.assertIn("secrets.token_urlsafe(32)", text)

    def test_optional_packaging(self):
        text = GRADLE.read_text(encoding="utf-8")
        self.assertIn("linumertFullWebHmi", text)
        self.assertIn("../../../core", text)
        self.assertIn("../../../webhmi/server", text)
        self.assertIn("extractPackages", text)
        self.assertIn("LOCAL_WEBHMI_ENABLED", text)


if __name__ == "__main__":
    unittest.main()
