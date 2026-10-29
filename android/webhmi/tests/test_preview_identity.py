"""Regression: never replace the existing signed Android package unintentionally."""
from pathlib import Path
import unittest

BASE = Path(__file__).resolve().parents[1]
BUILD = BASE / "app/build.gradle"
RUN = BASE / "tools/build-on-phone-termux.sh"
DEBUG_LABEL = BASE / "app/src/debug/res/values/strings.xml"


class PreviewIdentityTests(unittest.TestCase):
    def test_separate_debug_package_and_original_release_identity(self):
        build = BUILD.read_text(encoding="utf-8")
        self.assertIn("applicationId 'pl.jakubzietarski.linumert.mobile'", build)
        self.assertIn("applicationIdSuffix '.preview'", build)
        self.assertIn("versionCode 2", build)
        self.assertIn("debug {", build)

    def test_debug_app_has_visible_different_name(self):
        name = DEBUG_LABEL.read_text(encoding="utf-8")
        self.assertIn("linumeRT Mobile (TEST)", name)

    def test_installer_fails_closed_on_wrong_package_id(self):
        script = RUN.read_text(encoding="utf-8")
        self.assertIn('PREVIEW_PACKAGE="pl.jakubzietarski.linumert.mobile.preview"', script)
        self.assertIn('output-metadata.json', script)
        self.assertIn('if actual != expected or versions != [2]:', script)
        self.assertIn('adb -s 127.0.0.1:5555 install -r "$PREVIEW_APK"', script)
        self.assertNotIn('adb -s 127.0.0.1:5555 uninstall', script)
        self.assertNotIn('pm uninstall', script)
        self.assertNotIn('reset --hard', script)

    def test_health_failure_does_not_uninstall(self):
        script = RUN.read_text(encoding="utf-8")
        self.assertIn('health_ok=0', script)
        self.assertIn('http://127.0.0.1:8765/api/health', script)
        self.assertIn('if [ "$health_ok" -ne 1 ]; then', script)
        self.assertIn('=== LAUNCH PREVIEW ===', script)


if __name__ == "__main__":
    unittest.main()
