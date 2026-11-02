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

    def test_no_default_pin_until_the_debug_key_is_pinned(self):
        # A run signed with a key generated in that run can never be updated in place, so the
        # scripts must not point testers at one. Only a certificate in debug-keystore.sha256
        # (the cached or secret key) makes a run worth pinning.
        pinned_cert = "".join(
            line.strip() for line in (DEVICE / "debug-keystore.sha256").read_text().splitlines()
            if not line.lstrip().startswith("#")
        )
        run_id = default_pin("install.sh", "RUN_ID")
        self.assertNotEqual(run_id, "41435774504", "run 41435774504 was signed with a throwaway key")
        if not pinned_cert:
            self.assertEqual(run_id, "", "no debug key is pinned, so no run can update in place")
            self.assertEqual(default_pin("install.sh", "APK_ZIP_SHA256"), "")

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
            proc = subprocess.run(["sh", str(DEVICE / "install.sh")], env=env,
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
