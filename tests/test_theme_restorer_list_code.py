"""Static wiring: ThemeRestorer's shell decisions go through its exit-code-aware helpers.

`ThemeRestorerListTest` covers the behaviour of `ThemeRestorer.overlaysGone` and
`ThemeRestorer.foregroundStartFailed`; these checks pin that the cheap pre-check, the restore
itself and the root-shell service start keep the exit code, so a failed or incomplete
`cmd overlay list` neither starts a restore nor counts as a failed one, and a refused
`am start-foreground-service` still falls back to the inline restore.
"""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RESTORER = ROOT / "android/app/src/main/java/com/hexnext/themer/theme/ThemeRestorer.kt"
BEHAVIOUR_TEST = ROOT / "android/app/src/test/java/com/hexnext/themer/theme/ThemeRestorerListTest.kt"


def function_body(source: str, signature: str) -> str:
    start = source.index(signature)
    following = re.compile(r"\n    (?:private |internal )?(?:suspend )?fun |\n    /\*\*").search(source, start + 1)
    return source[start:following.start() if following else len(source)]


class ThemeRestorerListCodeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.source = RESTORER.read_text(encoding="utf-8")

    def test_gone_requires_exit_zero(self) -> None:
        self.assertRegex(
            self.source,
            r"internal fun overlaysGone\(code: Int, list: String\): Boolean =\s*"
            r"code == 0 && list\.lines\(\)\.none \{ it\.contains\(\":HexNext_\"\) \}",
        )

    def test_failed_start_is_nonzero_or_reported_error(self) -> None:
        self.assertRegex(
            self.source,
            r"internal fun foregroundStartFailed\(code: Int, output: String\): Boolean =\s*"
            r"code != 0 \|\| output\.contains\(\"Error\", ignoreCase = true\)",
        )

    def test_shell_reads_keep_the_exit_code(self) -> None:
        # The two overlay-list reads and the root-shell service start; keepAwake is best effort
        # and may keep using fullText.
        self.assertEqual(3, self.source.count("ExecOutput.exec("))
        self.assertEqual(1, self.source.count('"cmd overlay list --user 0"'))
        self.assertNotIn("fullText(service, service.exec(OVERLAY_LIST", self.source)
        self.assertNotIn('fullText(service, service.exec("cmd overlay list', self.source)
        self.assertNotIn('service.exec("am start-foreground-service', self.source)

    def test_pre_check_uses_the_code(self) -> None:
        body = function_body(self.source, "private suspend fun needsRestore(")
        self.assertIn("val (code, list) = ExecOutput.exec(ShizukuManager.service(), OVERLAY_LIST)", body)
        self.assertIn("return overlaysGone(code, list)", body)

    def test_root_shell_start_uses_the_code_before_the_inline_fallback(self) -> None:
        body = function_body(self.source, "private suspend fun startInForeground(")
        self.assertIn("val (code, out) = ExecOutput.exec(", body)
        self.assertIn('"am start-foreground-service --user 0 -n ${app.packageName}/${RestoreService::class.java.name}"', body)
        decision = body.index("if (foregroundStartFailed(code, out)) {")
        self.assertLess(decision, body.index("restoreIfNeeded(app)"))
        self.assertNotIn('out.contains("Error"', body)

    def test_restore_returns_before_applying_or_counting_on_a_failed_list(self) -> None:
        body = function_body(self.source, "suspend fun restoreIfNeeded(")
        guard = body.index("if (!overlaysGone(code, list)) {")
        self.assertLess(body.index("val (code, list) = ExecOutput.exec(service, OVERLAY_LIST)"), guard)
        early_return = body.index("return", guard)
        self.assertLess(early_return, body.index("ThemeApplier(app).apply("))
        self.assertLess(early_return, body.index("putInt(KEY_FAILURES"))
        self.assertGreater(body.index("ServiceTasks.finish()"), body.index("} finally {"))

    def test_behavioural_regression_covers_failed_codes(self) -> None:
        test = BEHAVIOUR_TEST.read_text(encoding="utf-8")
        self.assertIn("listOf(1, 125, 255, -1)", test)
        self.assertIn("ThemeRestorer.overlaysGone(0, withoutOurs)", test)
        self.assertIn("ThemeRestorer.foregroundStartFailed(0, \"Starting service\")", test)


if __name__ == "__main__":
    unittest.main()
