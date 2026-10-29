"""Offline checks: fail closed on runtime mode, network binding and Android service."""
import ast
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"


class LocalRuntimeSecurityTests(unittest.TestCase):
    def test_all_mobile_python_modules_parse(self):
        for name in ("mobile_bootstrap.py", "mobile_webhmi.py"):
            with self.subTest(name=name):
                p = ROOT / "app/src/main/python" / name
                ast.parse(p.read_text(encoding="utf-8"), filename=str(p))

    def test_only_loopback_simulation_in_entry_point(self):
        text = (ROOT / "app/src/main/python/mobile_webhmi.py").read_text(encoding="utf-8")
        self.assertIn('HOST = "127.0.0.1"', text)
        self.assertIn("FakeBackend(_demo_machine())", text)
        self.assertIn('ws="wsproto"', text)
        self.assertIn('http="h11"', text)
        self.assertNotIn("LinuxCNCBackend(", text)
        self.assertNotIn('host="0.0.0.0"', text)

    def test_foreground_service_is_private_and_declared(self):
        root = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
        perms = {p.get(ANDROID + "name") for p in root.findall("uses-permission")}
        for required in ("android.permission.INTERNET",
                         "android.permission.ACCESS_NETWORK_STATE",
                         "android.permission.FOREGROUND_SERVICE",
                         "android.permission.FOREGROUND_SERVICE_SPECIAL_USE"):
            self.assertIn(required, perms)
        services = root.findall("./application/service")
        self.assertEqual(len(services), 1)
        svc = services[0]
        self.assertEqual(svc.get(ANDROID + "name"), ".LocalServerService")
        self.assertEqual(svc.get(ANDROID + "exported"), "false")
        self.assertEqual(svc.get(ANDROID + "foregroundServiceType"), "specialUse")
        self.assertEqual(len(svc.findall("property")), 1)

    def test_opt_in_build_avoids_rust_dependency(self):
        content = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
        self.assertIn("linumertFullWebHmi", content)
        self.assertIn("pydantic==1.10.24", content)
        self.assertIn("wsproto==1.2.0", content)
        self.assertNotIn("pydantic==2.10.6", content)
        self.assertIn("buildConfigField 'boolean', 'LOCAL_WEBHMI_ENABLED'", content)
        self.assertIn("extractPackages", content)

    def test_server_only_kept_after_authentic_health(self):
        src = (ROOT / "app/src/main/java/pl/jakubzietarski/linumert/mobile/MainActivity.java")
        text = src.read_text(encoding="utf-8")
        self.assertIn("LocalWebHmiClient.start(", text)
        self.assertIn("if (result == null)", text)
        self.assertIn("LocalServerService.retain(this, activeWifiIp)", text)
        client = (src.parent / "LocalWebHmiClient.java").read_text(encoding="utf-8")
        self.assertIn('request(base, "/api/health", null)', client)
        self.assertIn('request(base, "/api/state", token)', client)
        self.assertIn("MobileRuntimePolicy.canUseLocalHealth", client)
        self.assertIn("LOCAL_WEBHMI_UNVERIFIED_", client)


if __name__ == "__main__":
    unittest.main()
