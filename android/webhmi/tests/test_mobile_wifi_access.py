"""Offline LAN access boundaries, no real HTTP listener or Android runtime."""
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
P = ROOT / "app/src/main/python/mobile_webhmi.py"
spec = importlib.util.spec_from_file_location("mobile_webhmi_wifi_contract", P)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class WifiAccessContractTest(unittest.TestCase):
    def test_explicit_rfc1918_only(self):
        for ip in ("10.0.1.2", "172.16.0.1", "172.31.255.254",
                   "192.168.0.44", "192.168.88.22"):
            with self.subTest(ip=ip):
                self.assertEqual(module.validate_wifi_ip(ip), ip)
        self.assertEqual(module.validate_wifi_ip(""), "")

    def test_reject_wildcard_public_and_ipv6(self):
        for ip in ("0.0.0.0", "127.0.0.1", "8.8.8.8", "169.254.1.2",
                   "172.32.1.2", "192.0.2.1", "::", "::1",
                   "fe80::1", "192.168.1.1:8765", "wifi.example"):
            with self.subTest(ip=ip):
                with self.assertRaises(ValueError):
                    module.validate_wifi_ip(ip)

    def test_loopback_default_and_explicit_wifi_sockets(self):
        source = P.read_text(encoding="utf-8")
        self.assertIn('HOST = "127.0.0.1"', source)
        self.assertIn('listeners.append(_bind(HOST))', source)
        self.assertIn('if wifi_ip:\n                listeners.append(_bind(wifi_ip))', source)
        self.assertIn('extra_hosts=(wifi_ip,) if wifi_ip else ()', source)
        self.assertNotIn('sock.bind(("0.0.0.0"', source)
        self.assertIn('server.run(sockets=listeners)', source)

    def test_android_only_offers_wifi_not_cellular(self):
        source = (ROOT / "app/src/main/java/pl/jakubzietarski/linumert/mobile"
                  / "WifiInterface.java").read_text(encoding="utf-8")
        self.assertIn("TRANSPORT_WIFI", source)
        self.assertIn("ip.isSiteLocalAddress()", source)
        self.assertIn("LinkProperties link", source)
        ui = (ROOT / "app/src/main/java/pl/jakubzietarski/linumert/mobile"
              / "MainActivity.java").read_text(encoding="utf-8")
        self.assertIn("Zastosuj ustawienie Wi-Fi", ui)
        self.assertIn("setChecked(false)", ui)
        self.assertIn("WifiInterface.currentPrivateIpv4(this)", ui)
        self.assertIn("ACTION_VIEW", ui)
        self.assertIn("copyLocalToken()", ui)
        self.assertIn("ClipDescription.EXTRA_IS_SENSITIVE", ui)

    def test_wifi_loss_revokes_network_access_and_notification(self):
        root = ROOT / "app/src/main/java/pl/jakubzietarski/linumert/mobile"
        service = (root / "LocalServerService.java").read_text(encoding="utf-8")
        self.assertIn("approvedWifiNetwork", service)
        self.assertIn("onLost(Network network)", service)
        self.assertIn("onLinkPropertiesChanged(", service)
        self.assertIn("revokeOnNetworkLoss()", service)
        self.assertIn("LocalWebHmiClient.stop()", service)
        self.assertIn("FOREGROUND_SERVICE_TYPE_SPECIAL_USE", service)
        self.assertIn("stopForeground(STOP_FOREGROUND_REMOVE)", service)

    def test_authentication_works_with_pydantic_one_or_two(self):
        source = (ROOT.parents[1] / "webhmi/server/linumert_webhmi/app.py"
                  ).read_text(encoding="utf-8")
        self.assertIn('hasattr(Command, "model_validate")', source)
        self.assertIn("Command.parse_obj(data)", source)
        self.assertIn("host_ok(socket.headers.get(\"host\"))", source)
        self.assertIn("origin_ok(", source)
        self.assertIn("token_ok(auth.get(\"auth\"))", source)


if __name__ == "__main__":
    unittest.main()
