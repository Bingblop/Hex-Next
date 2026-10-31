import importlib.util
import contextlib
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location(
    "prepare_release", Path(__file__).resolve().parents[1] / "bin" / "prepare-release.py"
)
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
COMMIT = "a" * 40


class PrepareReleaseTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.gradle = Path(self.directory.name) / "build.gradle.kts"
        self.gradle.write_text('    versionName = "2.1.0"\n')

    def prepare(self, tag="v2.1.0", references=""):
        with patch.object(release, "run", side_effect=[COMMIT, references]):
            return release.prepare_release(tag, self.gradle)

    def test_new_tag_pins_selected_commit_without_creating_it(self):
        self.assertEqual(self.prepare(), {
            "tag": "v2.1.0", "version": "2.1.0", "commit": COMMIT, "prerelease": False,
        })

    def test_existing_lightweight_tag_must_match(self):
        self.assertEqual(self.prepare(references=f"{COMMIT}\trefs/tags/v2.1.0")["commit"], COMMIT)

    def test_annotated_tag_uses_peeled_commit(self):
        references = f"{'b' * 40}\trefs/tags/v2.1.0\n{COMMIT}\trefs/tags/v2.1.0^{{}}"
        self.assertEqual(self.prepare(references=references)["commit"], COMMIT)

    def test_existing_tag_at_another_commit_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "different commit"):
            self.prepare(references=f"{'b' * 40}\trefs/tags/v2.1.0")

    def test_invalid_tags_and_mismatched_versions_fail_before_git(self):
        for tag in ("2.1.0", "v2.1", "v2.2.0", "v2.1.0\nother", "v2.1.0;echo injected"):
            with self.subTest(tag=tag), patch.object(release, "run") as run, self.assertRaises(ValueError):
                release.prepare_release(tag, self.gradle)
            run.assert_not_called()

    def test_missing_version_is_rejected(self):
        self.gradle.write_text("")
        with self.assertRaisesRegex(ValueError, "versionName"):
            self.prepare()

    def test_prerelease_and_build_metadata(self):
        for version, expected in (("2.1.0-rc.1", True), ("2.1.0+build-1", False)):
            self.gradle.write_text(f'versionName = "{version}"')
            self.assertEqual(self.prepare(tag=f"v{version}")["prerelease"], expected)

    def test_git_errors_are_not_treated_as_missing_tags(self):
        with patch.object(release, "run", side_effect=subprocess.CalledProcessError(1, "git")):
            with self.assertRaises(subprocess.CalledProcessError):
                release.prepare_release("v2.1.0", self.gradle)

    def test_github_outputs_have_exact_values_and_boolean_shape(self):
        output = Path(self.directory.name) / "outputs"
        prepared = {"tag": "v2.1.0", "version": "2.1.0", "commit": COMMIT, "prerelease": False}
        with patch.object(release, "prepare_release", return_value=prepared), \
                patch("sys.argv", ["prepare-release.py", "v2.1.0"]), \
                patch.dict(os.environ, {"GITHUB_OUTPUT": str(output)}), \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(release.main(), 0)
        self.assertEqual(output.read_text(), f"tag=v2.1.0\nversion=2.1.0\ncommit={COMMIT}\nprerelease=false\n")


if __name__ == "__main__":
    unittest.main()
