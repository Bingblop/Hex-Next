"""Static wiring for the scanner's rejection of incomplete `cmd overlay dump` output.

`ExecOutput.fullText` sets exit code 125 and returns only the inline head when a spilled output
cannot be read back, and `ThemeService.spillLargeOutput` sets 125 when it cannot write the spill
file and returns only a prefix. The behaviour of `DeviceScanner.completeOutput`/`completeDump`
is covered by `DumpCompletenessTest`; these checks pin both 125 sources and that both dump
readers in `DeviceScanner` go through it before parsing, writing the scan's dump file or saving
a remembered copy.
"""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCANNER = ROOT / "android/app/src/main/java/com/hexnext/themer/device/DeviceScanner.kt"
THEME_SERVICE = ROOT / "android/app/src/main/java/com/hexnext/themer/shizuku/ThemeService.kt"
EXEC_OUTPUT = ROOT / "android/app/src/main/java/com/hexnext/themer/shizuku/ExecOutput.kt"
BEHAVIOUR_TEST = ROOT / "android/app/src/test/java/com/hexnext/themer/device/DumpCompletenessTest.kt"


def function_body(source: str, name: str) -> str:
    start = source.index(f"private fun {name}(")
    following = re.compile(r"\n    (?:private |internal )?fun |\n    /\*\*").search(source, start + 1)
    return source[start:following.start() if following else len(source)]


class SemWtDumpCompletenessTest(unittest.TestCase):
    def setUp(self) -> None:
        self.source = SCANNER.read_text(encoding="utf-8")

    def test_completeness_helpers_require_exit_zero(self) -> None:
        self.assertIn(
            "internal fun completeOutput(code: Int, text: String): String? = text.takeIf { code == 0 }",
            self.source,
        )
        self.assertRegex(
            self.source,
            r"internal fun completeDump\(code: Int, text: String, overlayId: String\): OverlayDump\? =\s*"
            r"completeOutput\(code, text\)\?\.let",
        )

    def test_semwt_dump_is_parsed_and_written_only_when_complete(self) -> None:
        body = function_body(self.source, "scanSemWt")
        start = body.index('service.exec("cmd overlay dump $overlayId")')
        live_read = body[start:body.index("val value = outcome.getOrNull()", start)]
        self.assertIn("val code = r.code()", live_read)
        self.assertIn("if (code == 0) dumpFile.writeText(text)", live_read)
        self.assertIn("code to completeDump(code, text, overlayId)", live_read)
        self.assertNotIn("OverlayDump.parseAll(", live_read)
        writes = [line.strip() for line in live_read.splitlines() if "dumpFile.writeText" in line]
        self.assertEqual(["if (code == 0) dumpFile.writeText(text)"], writes)

    def test_remembered_mapping_is_replaced_only_by_a_live_dump_with_entries(self) -> None:
        body = function_body(self.source, "scanSemWt")
        self.assertIn("if (dump != null && dump.entries.isNotEmpty()) {\n            source = SemWtStatus.SOURCE_DUMP", body)
        remembered = [line.strip() for line in body.splitlines() if "copyTo(known" in line or "known.writeText" in line]
        self.assertEqual(["runCatching { known.parentFile?.mkdirs(); dumpFile.copyTo(known, overwrite = true) }"], remembered)
        self.assertLess(body.index("if (source == SemWtStatus.SOURCE_DUMP) {"), body.index("dumpFile.copyTo(known, overwrite = true)"))
        self.assertIn("could not be dumped ($it)", body)
        self.assertIn("used the mapping remembered from an earlier scan", body)

    def test_palette_dump_is_used_or_remembered_only_when_complete(self) -> None:
        body = function_body(self.source, "readSamsungPalette")
        self.assertIn("completeOutput(r.code(), text)", body)
        self.assertNotIn("ExecOutput.fullText(service, service.exec(", body)

    def test_failed_spill_write_reports_incomplete_output(self) -> None:
        source = THEME_SERVICE.read_text(encoding="utf-8")
        start = source.index("private fun spillLargeOutput(bundle: Bundle): Bundle {")
        body = source[start:source.index("private val spillCounter", start)]
        success, failure = body.split("} catch (t: Throwable) {")
        # The spilled path keeps the shell's code; ExecOutput.fullText marks a failed read-back.
        self.assertNotIn("KEY_CODE", success)
        self.assertIn("putInt(KEY_CODE, 125)", failure)
        self.assertLess(failure.index("putInt(KEY_CODE, 125)"), failure.index("putString(KEY_OUTPUT, output.take(EXEC_INLINE_LIMIT)"))
        full_text = EXEC_OUTPUT.read_text(encoding="utf-8")
        self.assertEqual(4, full_text.count("result.putInt(ThemeService.KEY_CODE, 125)"))

    def test_behavioural_regression_covers_the_spill_failure_code(self) -> None:
        test = BEHAVIOUR_TEST.read_text(encoding="utf-8")
        self.assertIn("DeviceScanner.completeDump(125, head, overlayId)", test)
        self.assertIn("DeviceScanner.completeDump(0, head, overlayId)", test)


if __name__ == "__main__":
    unittest.main()
