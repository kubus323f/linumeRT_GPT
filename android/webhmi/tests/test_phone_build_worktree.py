"""Regression checks for the Android/Termux build's Git isolation.

The local cache may have diverged after force-updates of the development branch.
Never reset or merge it as part of the user's mobile build workflow.
"""
from pathlib import Path
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "tools/build-on-phone-termux.sh"


class PhoneBuildIsolationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.script = SCRIPT.read_text(encoding="utf-8")

    def test_detached_exact_commit_worktree(self):
        self.assertIn('SOURCE_COMMIT="$(git -C "$CACHE" rev-parse --verify', self.script)
        self.assertIn('git -C "$CACHE" worktree add --detach "$WORK" "$SOURCE_COMMIT"', self.script)
        self.assertIn('git -C "$WORK" rev-parse HEAD | tee "$OUT/source-commit.txt"', self.script)

    def test_fetch_accepts_forced_branch_history_without_merging(self):
        self.assertIn('"+refs/heads/$BRANCH:refs/remotes/origin/$BRANCH"', self.script)
        self.assertNotIn('git -C "$WORK" merge', self.script)
        self.assertNotIn('git -C "$CACHE" merge', self.script)
        self.assertNotIn('git -C "$WORK" reset --hard', self.script)
        self.assertNotIn('git -C "$CACHE" reset --hard', self.script)

    def test_original_cache_and_user_files_are_preserved(self):
        self.assertIn('CACHE="$HOME/.cache/linumert-mobile-build"', self.script)
        self.assertIn('WORK="$HOME/.cache/linumert-mobile-build-runs/$STAMP"', self.script)
        self.assertIn('SOURCE_WORKTREE_CREATED=0', self.script)
        self.assertIn('SOURCE_WORKTREE_CREATED=1', self.script)
        self.assertIn('git -C "$CACHE" worktree remove --force "$WORK"', self.script)

    def test_arm64_aapt2_is_verified_not_selected_by_path_alone(self):
        self.assertIn('if machine != 183:', self.script)
        self.assertIn('subprocess.run([str(p), "version"]', self.script)
        self.assertIn('"-Pandroid.aapt2FromMavenOverride=$AAPT2"', self.script)

    def test_package_install_does_not_uninstall(self):
        self.assertIn('adb -s 127.0.0.1:5555 install -r', self.script)
        self.assertNotIn('adb -s 127.0.0.1:5555 uninstall', self.script)
        self.assertIn('BASE="/sdcard/Download"', self.script)


if __name__ == "__main__":
    unittest.main()
