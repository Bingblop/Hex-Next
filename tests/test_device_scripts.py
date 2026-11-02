import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path

DEVICE = Path(__file__).resolve().parents[1] / "tools" / "device"


def default_pin(script, name):
    text = (DEVICE / script).read_text()
    match = re.search(r'^%s="\$\{%s:-([^}]*)\}"' % (name, name), text, re.M)
    if match is None:
        raise AssertionError("%s has no default %s" % (script, name))
    return match.group(1)


class DeviceScriptPinTest(unittest.TestCase):
    def test_test_overlay_pins_the_same_build_as_install(self):
        for name in ("RUN_ID", "APK_ZIP_SHA256"):
            self.assertEqual(
                default_pin("test-overlay.sh", name),
                default_pin("install.sh", name),
                "%s in test-overlay.sh differs from install.sh" % name,
            )

    def test_default_pin_is_complete(self):
        # Kyle tests the latest green build by default, so both values are set together (or
        # neither). Until the CI debug key is pinned, installing a new pin needs one uninstall,
        # which install.sh explains when pm reports INSTALL_FAILED_UPDATE_INCOMPATIBLE.
        run_id = default_pin("install.sh", "RUN_ID")
        sha = default_pin("install.sh", "APK_ZIP_SHA256")
        self.assertEqual(bool(run_id), bool(sha))
        if run_id:
            self.assertRegex(run_id, r"^[0-9]+$")
            self.assertRegex(sha, r"^[0-9a-f]{64}$")

    def test_install_without_a_pin_refuses_before_downloading(self):
        with tempfile.TemporaryDirectory() as tmp:
            bindir = Path(tmp, "bin")
            bindir.mkdir()
            log = Path(tmp, "calls.log")
            for tool in ("curl", "su"):
                fake = bindir / tool
                fake.write_text('#!/bin/sh\necho %s >> "%s"\nexit 0\n' % (tool, log))
                fake.chmod(0o755)
            env = dict(os.environ, PATH="%s:%s" % (bindir, os.environ["PATH"]), HOME=tmp)
            env.pop("RUN_ID", None)
            env.pop("APK_ZIP_SHA256", None)
            # The shipped script has a default pin; blank it to test the no-pin path.
            script = Path(tmp, "install.sh")
            text = (DEVICE / "install.sh").read_text()
            text = re.sub(r'^(RUN_ID|APK_ZIP_SHA256)="\$\{\1:-[^}]*\}"', r'\1="${\1:-}"', text, flags=re.M)
            script.write_text(text)
            proc = subprocess.run(["sh", str(script)], env=env,
                                  capture_output=True, text=True)
            self.assertEqual(proc.returncode, 1, proc.stdout)
            self.assertIn("RUN_ID=", proc.stdout)
            self.assertFalse(log.exists(), "install.sh ran curl or su without a pin")

    def test_apk_is_not_staged_in_shell_writable_tmp(self):
        for script in ("install.sh", "test-overlay.sh", "diag.sh"):
            code = "\n".join(
                line for line in (DEVICE / script).read_text().splitlines()
                if not line.lstrip().startswith("#")
            )
            self.assertNotIn("/data/local/tmp", code, script)


class DiagScriptTest(unittest.TestCase):
    def test_root_never_writes_the_sample_frro(self):
        # $OUT_DIR may be shell-writable; root cp/chmod there would follow a planted symlink.
        text = (DEVICE / "diag.sh").read_text()
        for line in text.splitlines():
            if line.lstrip().startswith("#") or "su -c" not in line:
                continue
            for cmd in re.findall(r'su -c "([^"]*)"', line):
                self.assertNotIn("FRRO_COPY", cmd, line)
                self.assertNotIn("chmod", cmd, line)
                self.assertNotRegex(cmd, r"\bcp\b", line)
        self.assertIn('rm -f "$FRRO_COPY"', text)

    def test_debloated_packages_are_listed_without_temp_files(self):
        text = (DEVICE / "diag.sh").read_text()
        match = re.search(r'^run "Uninstalled \(debloated\) packages" "(.*)"$', text, re.M)
        self.assertIsNotNone(match)
        cmd = match.group(1)
        self.assertNotIn(">", cmd, "diag.sh may rerun this as root; it must not write files")
        with tempfile.TemporaryDirectory() as tmp:
            bindir = Path(tmp, "bin")
            bindir.mkdir()
            fake_pm = bindir / "pm"
            fake_pm.write_text(
                '#!/bin/sh\n'
                'echo package:android\necho package:com.android.settings\n'
                '[ "$3" = "-u" ] && echo package:com.samsung.android.bixby.agent\n'
                'exit 0\n')
            fake_pm.chmod(0o755)
            env = dict(os.environ, PATH="%s:%s" % (bindir, os.environ["PATH"]))
            proc = subprocess.run(["sh", "-c", cmd], env=env, cwd=tmp,
                                  capture_output=True, text=True)
            self.assertEqual(proc.stdout, "package:com.samsung.android.bixby.agent\n")
            self.assertEqual(sorted(p.name for p in Path(tmp).iterdir()), ["bin"])


class DebugKeystoreWorkflowTest(unittest.TestCase):
    def step_if(self, name):
        text = (Path(__file__).resolve().parents[1] / ".github" / "workflows" / "android.yml").read_text()
        match = re.search(r"- name: %s\n\s+if: >-\n((?:\s{10,}.*\n)+)" % re.escape(name), text)
        self.assertIsNotNone(match, name)
        return match.group(1)

    def test_only_a_bootstrap_run_caches_a_generated_key(self):
        cond = self.step_if("Save pinned debug keystore to cache")
        self.assertIn("steps.debug-keystore-check.outputs.generated == 'true'", cond)
        self.assertIn("inputs.bootstrap_debug_keystore == true", cond)
        self.assertIn("github.event_name == 'workflow_dispatch'", cond)
        self.assertNotIn("'push'", cond)


class FlavorWorkflowTest(unittest.TestCase):
    """CI builds the store flavors and keeps the installable test artifact on the Play build."""

    def workflow(self):
        return (Path(__file__).resolve().parents[1] / ".github" / "workflows" / "android.yml").read_text()

    def test_gradle_tasks_carry_the_flavor(self):
        text = self.workflow()
        for task in (":app:testPlayDebugUnitTest", ":app:lintPlayRelease", ":app:assemblePlayRelease",
                     ":app:assembleOwnerRelease", ":app:bundlePlayRelease", ":app:assemblePlayDebug"):
            self.assertIn(task, text)
        for old in (":app:testDebugUnitTest", ":app:lintRelease", ":app:assembleRelease ",
                    ":app:assembleDebug\n", "outputs/apk/release/", "outputs/apk/debug/"):
            self.assertNotIn(old, text)

    def test_debug_test_artifact_is_the_play_debug_apk(self):
        text = self.workflow()
        match = re.search(r"name: HexNext-Debug-Test-Installable\n\s+path: (\S+)", text)
        self.assertIsNotNone(match)
        self.assertEqual(match.group(1), "android/app/build/outputs/apk/play/debug/app-play-universal-debug.apk")

    def test_owner_release_is_uploaded_only_with_upload_key_secrets(self):
        text = self.workflow()
        match = re.search(r"- name: Upload owner release APK\n\s+if: (.+)\n", text)
        self.assertIsNotNone(match)
        self.assertEqual(match.group(1).strip(), "env.HEXNEXT_HAS_RELEASE_SIGNING_SECRETS == 'true'")
        self.assertIn("name: HexNext-Owner-Release", text)
        self.assertIn("android/app/build/outputs/apk/owner/release/*.apk", text)
        self.assertIn("name: HexNext-Play-AAB", text)
        self.assertIn("android/app/build/outputs/bundle/playRelease/*.aab", text)


@unittest.skipUnless(shutil.which("unzip"), "unzip is not installed")
class PackScriptTest(unittest.TestCase):
    """pack.sh stores a pack as <id>.zip, the only name the app deletes and replaces."""

    def run_pack(self, file_name, pack_json):
        with tempfile.TemporaryDirectory() as tmp:
            pack = Path(tmp, file_name)
            with zipfile.ZipFile(pack, "w") as z:
                z.writestr("pack.json", pack_json)
            bindir = Path(tmp, "bin")
            bindir.mkdir()
            log = Path(tmp, "su.log")
            # A stand-in for su that records the root command instead of running it.
            fake_su = bindir / "su"
            fake_su.write_text('#!/bin/sh\nshift\nprintf %%s "$*" > "%s"\n' % log)
            fake_su.chmod(0o755)
            env = dict(os.environ, PATH="%s:%s" % (bindir, os.environ["PATH"]), HOME=tmp)
            proc = subprocess.run(["sh", str(DEVICE / "pack.sh"), str(pack)], env=env,
                                  capture_output=True, text=True)
            return proc.returncode, log.read_text() if log.exists() else "", proc.stdout

    def test_renamed_file_is_stored_under_its_pack_id(self):
        rc, cmd, out = self.run_pack("velvet-rope-1.zip", json.dumps({"id": "velvet-rope", "name": "V"}, indent=1))
        self.assertEqual(rc, 0, out)
        self.assertIn("/files/packs/velvet-rope.zip'", cmd)
        self.assertIn("rm -f '/data/data/com.hexnext.themer/files/packs/velvet-rope.hexnext'", cmd)
        self.assertNotIn("packs/velvet-rope-1", cmd)
        self.assertNotIn("packs/.velvet-rope-1", cmd)

    def test_copy_is_staged_as_the_apps_part_file(self):
        # PackStore sweeps stale <id>.zip.part at start-up; a failed copy must not leave
        # a file the app never sees.
        rc, cmd, out = self.run_pack("x.zip", '{"id":"velvet-rope"}')
        self.assertEqual(rc, 0, out)
        self.assertIn("cp '", cmd)
        self.assertIn("/files/packs/velvet-rope.zip.part'", cmd)
        self.assertIn("rm -f '/data/data/com.hexnext.themer/files/packs/velvet-rope.zip.part'; false", cmd)
        self.assertNotIn(".tmp", cmd)

    def test_minified_pack_json_is_read(self):
        rc, cmd, out = self.run_pack("download.zip", '{"name":"V","id":"deluxe-3-8","version":"1"}')
        self.assertEqual(rc, 0, out)
        self.assertIn("/files/packs/deluxe-3-8.zip'", cmd)

    def test_unsafe_or_missing_id_is_refused(self):
        for pack_json in ('{"id":"a b"}', '{"id":"x\'y"}', '{"name":"no id"}', '{"id":"../x"}'):
            rc, cmd, _ = self.run_pack("p.zip", pack_json)
            self.assertEqual(rc, 2, pack_json)
            self.assertEqual(cmd, "", pack_json)


class TestOverlayRevertTest(unittest.TestCase):
    """Runs test-overlay.sh against a fake su/cmd that models this build's rule: every
    enable moves the overlay to the top of its target, so the last-enabled overlay wins."""

    FAKE_CMD = r'''#!/bin/sh
state="$OVERLAY_STATE"
[ "$1" = overlay ] || exit 0
op="$2"; shift 2
[ "${1:-}" = --user ] && shift 2
case "$op" in
  list)
    [ -z "${1:-}" ] || echo "$1"
    while read -r flag id; do
      if [ "$flag" = x ]; then echo "[x] $id"; else echo "[ ] $id"; fi
    done <"$state" ;;
  enable)
    grep -v " $1\$" "$state" >"$state.new"; echo "x $1" >>"$state.new"; mv "$state.new" "$state" ;;
  disable)
    sed "s/^x $1\$/- $1/" "$state" >"$state.new"; mv "$state.new" "$state" ;;
  fabricate)
    echo "- com.android.shell:HexNext_probe" >>"$state" ;;
esac
exit 0
'''

    def test_revert_keeps_hex_next_overlay_above_semwt(self):
        with tempfile.TemporaryDirectory() as tmp:
            bindir = Path(tmp, "bin")
            bindir.mkdir()
            scripts = {
                "su": '#!/bin/sh\n[ "$1" = -c ] && shift\nexec sh -c "$*"\n',
                "cmd": self.FAKE_CMD,
                "sleep": "#!/bin/sh\nexit 0\n",
            }
            for name, body in scripts.items():
                f = bindir / name
                f.write_text(body)
                f.chmod(0o755)
            state = Path(tmp, "state")
            # Baseline: SemWT on, Hex Next Settings overlay enabled after it (winning).
            state.write_text("x android:SemWT_com.android.settings\n"
                             "x com.hexnext.themer:HexNext_com_android_settings\n")
            env = dict(os.environ, PATH="%s:%s" % (bindir, os.environ["PATH"]),
                       OVERLAY_STATE=str(state), SKIP_INSTALL="1")
            proc = subprocess.run(["sh", str(DEVICE / "test-overlay.sh"), tmp], env=env,
                                  cwd=tmp, capture_output=True, text=True, timeout=60)
            self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
            lines = state.read_text().splitlines()
            enabled = [line.split()[1] for line in lines if line.startswith("x ")]
            self.assertEqual(enabled, ["android:SemWT_com.android.settings",
                                       "com.hexnext.themer:HexNext_com_android_settings"],
                             "revert must leave the Hex Next overlay enabled last (on top)")
            self.assertIn("- com.android.shell:HexNext_probe", lines)


if __name__ == "__main__":
    unittest.main()
