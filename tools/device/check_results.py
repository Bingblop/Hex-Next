#!/usr/bin/env python3
"""Checks a device-lab job's results against its suite's expectations.

    tools/device/check_results.py <lab-dir> <job-id> [--write] [--suite=<suite>]

Reads jobs/<id>.json (its "expect" block, from tools/device/suites/) and results/<id>/, prints a
short verdict, and with --write saves it as results/<id>/verdict.md. Exit status 0 when every check
passed or does not apply to the phone, 1 otherwise.

Expectations:
  apply_ok          the hook's apply finished with a new, successful report
  restore_finished  after the soft reboot the app logged "Restore finished: success=true"
  no_crash          no Hex Next crash during the job
  overridden        each listed resource resolves to a Hex Next overlay (frro://...HexNext_...)
                    in every lookup; a name the phone does not have (another One UI version) is n/a
"""
import json
import re
import sys
from pathlib import Path

PKG = "com.hexnext.themer"


def lookups(res: Path) -> list[dict[str, str]]:
    out = []
    for f in sorted(res.glob("lookup-*.txt"), key=lambda p: int(re.sub(r"\D", "", p.stem) or 0)):
        table = {}
        for line in f.read_text(errors="replace").splitlines():
            if " = " in line:
                name, value = line.split(" = ", 1)
                table[name.strip()] = value.strip()
        out.append(table)
    return out


def job_start_ms(res: Path) -> int:
    times = []
    hook = res / "hook.jsonl"
    if hook.exists():
        for line in hook.read_text(errors="replace").splitlines():
            try:
                at = json.loads(line).get("at")
            except ValueError:
                continue
            if at:
                times.append(int(at))
    return min(times) - 15 * 60_000 if times else 0


def check(lab: Path, job_id: str, suite: str | None = None) -> tuple[bool, list[str]]:
    job = json.loads((lab / "jobs" / f"{job_id}.json").read_text())
    res = lab / "results" / job_id
    expect = job.get("expect", {})
    if not expect and suite:
        expect = json.loads((Path(__file__).parent / "suites" / f"{suite}.json").read_text()).get("expect", {})
        job["suite"] = suite
    lines, ok = [], True
    status = json.loads((res / "status.json").read_text()) if (res / "status.json").exists() else {}
    lines.append(f"{job_id} ({job.get('suite', 'custom')}): {status.get('model', '?')} One UI {status.get('oneui', '?')}, "
                 f"runner {'ok' if status.get('ok') else 'failed: ' + str(status.get('failed'))}")

    def verdict(name: str, passed: bool | None, detail: str = "") -> None:
        nonlocal ok
        mark = "n/a" if passed is None else ("pass" if passed else "FAIL")
        if passed is False:
            ok = False
        lines.append(f"- {mark}: {name}{(' (' + detail + ')') if detail else ''}")

    hook = (res / "hook.jsonl").read_text(errors="replace").splitlines() if (res / "hook.jsonl").exists() else []
    if expect.get("apply_ok"):
        applies = [json.loads(h) for h in hook if '"cmd":"apply"' in h]
        verdict("apply", bool(applies) and all(a.get("ok") for a in applies), applies[-1].get("detail", "") if applies else "no apply")
    if expect.get("restore_finished"):
        text = (res / "restore.txt").read_text(errors="replace") if (res / "restore.txt").exists() else ""
        m = re.search(r"Restore finished: success=(\w+), (\d+) apps", text)
        verdict("restore after the soft reboot", bool(m and m.group(1) == "true"), m.group(0) if m else "no finish line")
    if expect.get("no_crash"):
        start = job_start_ms(res)
        crash = res / "app-crash.txt"
        when = 0
        if crash.exists():
            m = re.search(r"time=(\d+)", crash.read_text(errors="replace"))
            when = int(m.group(1)) if m else 0
        system = (res / "crash-log.txt").read_text(errors="replace") if (res / "crash-log.txt").exists() else ""
        verdict("no Hex Next crash", when < start and f"Process: {PKG}" not in system.split("--------- beginning of")[-1],
                "app crash record from this job" if when >= start else "")
    tables = lookups(res)
    for name in expect.get("overridden", []):
        values = [t[name] for t in tables if name in t]
        if not values:
            verdict(f"{name} themed", False, "not looked up")
        elif all(re.search(r"not found|No resource|Error|unknown", v, re.I) for v in values):
            verdict(f"{name} themed", None, "the phone has no such resource")
        else:
            # Drawables resolve to the overlay's frro path; a fabricated colour shows only its value,
            # so an overridden colour is a literal where Samsung's own is often a reference (@… ->).
            themed = [("HexNext_" in v) or (":color/" in name and "->" not in v and "frro" not in v) for v in values]
            verdict(f"{name} themed", all(themed), values[-1][:90])
    return ok, lines


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    lab, job_id = Path(sys.argv[1]), sys.argv[2]
    suite = next((a.split("=", 1)[1] for a in sys.argv if a.startswith("--suite=")), None)
    ok, lines = check(lab, job_id, suite)
    text = "\n".join(lines) + "\n"
    print(text, end="")
    if "--write" in sys.argv:
        (lab / "results" / job_id / "verdict.md").write_text(text)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
