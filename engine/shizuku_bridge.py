import os
import shutil
import subprocess
from typing import Tuple


class ShizukuBridge:
    """
    Handles execution of privileged shell commands via Shizuku (UID 0 or UID 1000),
    direct SU (UID 0), or system UID 1000 context.
    No Theme Park or Theme Store prerequisites are required.
    """

    def __init__(self):
        self.mode, self.uid = self._detect_privilege()

    def _detect_privilege(self) -> Tuple[str, int]:
        # 1. Check current process UID (UID 0 root or UID 1000 system)
        try:
            current_uid = os.getuid()
        except AttributeError:
            current_uid = 2000

        if current_uid == 0:
            return "root-direct", 0
        if current_uid == 1000:
            return "system-uid1000", 1000

        # 2. Check Shizuku rish in PATH or home directory
        rish_bin = shutil.which("rish")
        if not rish_bin:
            rish_home = os.path.expanduser("~/rish")
            if os.path.exists(rish_home) and os.access(rish_home, os.X_OK):
                rish_bin = rish_home

        if rish_bin:
            uid = self._probe_uid([rish_bin, "-c", "id -u"])
            if uid in (0, 1000):
                return f"shizuku-uid{uid}", uid
            return "shizuku-rish", uid if uid is not None else 2000

        # 3. Check su binary for UID 0 or UID 1000 escalation
        if shutil.which("su"):
            uid = self._probe_uid(["su", "-c", "id -u"])
            if uid is not None:
                return "su-root", uid

        return "simulated-uid1000", 1000

    def _probe_uid(self, cmd_list) -> int | None:
        try:
            res = subprocess.run(
                cmd_list,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                timeout=3,
            )
            if res.returncode == 0:
                for token in res.stdout.strip().split():
                    if token.isdigit():
                        return int(token)
        except Exception:
            pass
        return None

    @property
    def is_privileged(self) -> bool:
        """Returns True if running with UID 0 (Root) or UID 1000 (System) access."""
        return self.uid in (0, 1000)

    @property
    def owner_package(self) -> str:
        """Returns the RRO owning package matching the active privileged UID."""
        return "android" if self.uid == 1000 else "com.android.shell"

    def run(self, cmd: str, timeout: int = 30) -> Tuple[int, str, str]:
        """Runs a command under UID 0, UID 1000, or Shizuku privileged shell."""
        if self.mode.startswith("shizuku") and shutil.which("rish"):
            full_cmd = ["rish", "-c", cmd]
        elif self.mode == "su-root" and shutil.which("su"):
            full_cmd = ["su", "-c", cmd]
        else:
            full_cmd = ["sh", "-c", cmd]

        try:
            res = subprocess.run(
                full_cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                timeout=timeout,
            )
            stdout = res.stdout
            if "Waiting for Shizuku authorization..." in stdout:
                stdout = stdout.replace(
                    "Waiting for Shizuku authorization... check your notifications.\n",
                    "",
                )
            return res.returncode, stdout, res.stderr
        except subprocess.TimeoutExpired:
            return -1, "", "Command timed out"
        except Exception as e:
            return -1, "", str(e)
