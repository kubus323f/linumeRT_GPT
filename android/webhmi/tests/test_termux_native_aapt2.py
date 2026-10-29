"""Regression checks for native on-phone resource linker selection."""
from pathlib import Path
import unittest

SOURCE = (Path(__file__).resolve().parents[1]
          / "tools/build-on-phone-termux.sh")


class NativeAapt2PolicyTests(unittest.TestCase):
    def test_aapt2_is_runtime_verified_before_gradle(self):
        text = SOURCE.read_text(encoding="utf-8")
        self.assertIn('int.from_bytes(header[18:20], endian)', text)
        self.assertIn('machine != 183', text)
        self.assertIn('bytes([127]) + b"ELF"', text)
        self.assertIn('subprocess.run([str(p), "version"]', text)
        self.assertLess(text.index('machine != 183'),
                        text.index('gradle "${args[@]}"'))

    def test_fail_closed_when_only_x86_64_installed(self):
        text = SOURCE.read_text(encoding="utf-8")
        self.assertIn('if [ -z "$AAPT2" ]; then', text)
        self.assertIn('exit 8', text)
        self.assertIn('aapt2-probe.txt', text)
        self.assertIn('android.aapt2FromMavenOverride=$AAPT2', text)
        self.assertNotIn('AAPT2="$SDK/build-tools/35.0.0/aapt2"', text)

    def test_termux_native_preferred(self):
        text = SOURCE.read_text(encoding="utf-8")
        self.assertIn('${PREFIX:-/data/data/com.termux/files/usr}/bin/aapt2', text)
        self.assertIn('LINUMERT_AAPT2', text)
        self.assertLess(text.index('/bin/aapt2"'),
                        text.index('"$SDK/build-tools/35.0.0/aapt2"'))


if __name__ == "__main__":
    unittest.main()
