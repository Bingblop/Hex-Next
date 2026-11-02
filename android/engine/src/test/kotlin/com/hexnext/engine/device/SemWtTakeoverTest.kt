package com.hexnext.engine.device

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SemWtTakeoverTest {
    private val list = """
        android
        [x] android:SemWT_android
        [x] com.android.shell:HexNextMonetShades
        [x] com.android.shell:HexNext_android

        com.android.settings
        [ ] android:SemWT_com.android.settings
        [x] com.android.shell:HexNext_com_android_settings

        com.android.settings.intelligence
        [x] android:SemWT_com.android.settings.intelligence

        com.samsung.android.calendar
        --- android:SemWT_com.samsung.android.calendar
    """.trimIndent()

    private val state = SemWtState()
        .withTakeover("android", "com.android.shell:HexNext_android", semwtEnabledNow = true, now = 10)
        .withTakeover("com.android.settings", "com.android.shell:HexNext_com_android_settings", semwtEnabledNow = false, now = 11)

    @Test
    fun `overlay list parsing keeps targets and markers`() {
        val rows = OverlayList.parse(list)
        assertEquals(7, rows.size)
        val settings = rows.first { it.overlayId == "android:SemWT_com.android.settings" }
        assertEquals("com.android.settings", settings.target)
        assertFalse(settings.enabled)
        assertEquals("[ ]", settings.marker)
        assertEquals("---", rows.last().marker)
        assertTrue(rows.first { it.overlayId == "android:SemWT_com.android.settings.intelligence" }.enabled)
    }

    @Test
    fun `takeover keeps the first enabled state`() {
        val again = state.withTakeover("android", "com.android.shell:HexNext_android", semwtEnabledNow = false, now = 99)
        val record = again.record("android")!!
        assertTrue(record.wasEnabled)
        assertEquals(10, record.disabledAt)
        assertEquals("android:SemWT_android", record.semwtId)
        assertEquals(99, again.updatedAt)
        assertEquals(2, again.records.size)
        assertEquals(listOf("com.android.settings"), again.without(listOf("android"), 100).records.map { it.target })
    }

    @Test
    fun `state json round trip and blank state`() {
        assertEquals(state, SemWtState.fromJson(state.toJson()))
        assertEquals(SemWtState(), SemWtState.fromJson(" "))
        assertFailsWith<IllegalArgumentException> { SemWtState.fromJson("[1]") }
        assertTrue(state.toJson().contains("\"wasEnabled\":true"))
    }

    @Test
    fun `takeover disables semwt and raises only when the list shows ours below`() {
        val hex = "com.android.shell:HexNext_com_android_settings"
        val disableSem = "cmd overlay disable --user current android:SemWT_com.android.settings"
        assertEquals(listOf(disableSem), SemWtPlanner.takeover("com.android.settings", hex))
        assertEquals(listOf(disableSem), SemWtPlanner.takeover("com.android.settings", hex, OverlayList.parse(list)))
        val below = OverlayList.parse(
            """
            com.android.settings
            [x] $hex
            [x] android:SemWT_com.android.settings
            """.trimIndent(),
        )
        // SemWT above ours: its disable alone puts ours on top.
        assertEquals(listOf(disableSem), SemWtPlanner.takeover("com.android.settings", hex, below))
        val otherAbove = OverlayList.parse(
            """
            com.android.settings
            [x] $hex
            [x] android:SemWT_com.android.settings
            [x] com.example:other
            """.trimIndent(),
        )
        assertEquals(
            listOf(disableSem, "cmd overlay disable --user current $hex", "cmd overlay enable --user current $hex"),
            SemWtPlanner.takeover("com.android.settings", hex, otherAbove),
        )
        assertTrue(SemWtPlanner.takeover("com.android.settings", "x; reboot").isEmpty())
        assertFailsWith<IllegalArgumentException> { SemWtPlanner.enable("a:b c") }
        assertTrue(SemWtPlanner.raiseCycle(hex).none { "set-priority" in it })
    }

    @Test
    fun `raise need follows list order within the target section`() {
        val ours = "com.android.shell:HexNext_com_android_settings"
        val sem = "android:SemWT_com.android.settings"
        fun need(text: String) = SemWtPlanner.raiseNeed(OverlayList.parse(text.trimIndent()), "com.android.settings", ours)

        // Ours last: no raise.
        assertEquals(RaiseNeed.NONE, need("com.android.settings\n[x] $sem\n[x] $ours"))
        // SemWT after ours: raise.
        assertEquals(RaiseNeed.RAISE, need("com.android.settings\n[x] $ours\n[x] $sem"))
        // SemWT after ours but disabled: no raise.
        assertEquals(RaiseNeed.NONE, need("com.android.settings\n[x] $ours\n[ ] $sem"))
        // Another enabled overlay of the same target after ours: raise.
        assertEquals(RaiseNeed.RAISE, need("com.android.settings\n[x] $ours\n[x] com.example:other"))
        // Enabled overlays of the next target do not count.
        assertEquals(RaiseNeed.NONE, need("com.android.settings\n[x] $ours\n\nandroid\n[x] android:SemWT_android"))
        // A filtered list without headers, as the device test greps it (finding: probe one line after SemWT).
        assertEquals(RaiseNeed.NONE, need("[x] $sem\n[x] $ours"))
        assertEquals(RaiseNeed.RAISE, need("[x] $ours\n[x] $sem"))
        // Ours disabled: enable; ours missing: register again.
        assertEquals(RaiseNeed.ENABLE, need("com.android.settings\n[ ] $ours\n[x] $sem"))
        assertEquals(RaiseNeed.REGISTER, need("com.android.settings\n[x] $sem"))
        assertTrue(SemWtPlanner.needsRaise(OverlayList.parse("[x] $ours\n[x] $sem"), "com.android.settings", ours))
        assertFalse(SemWtPlanner.needsRaise(OverlayList.parse("[x] $sem"), "com.android.settings", ours))
    }

    @Test
    fun `restore re-enables only overlays that were enabled`() {
        assertEquals(listOf("cmd overlay enable --user current android:SemWT_android"), SemWtPlanner.restore(state))
    }

    @Test
    fun `reassert disables a re-enabled semwt and raises a hex overlay that fell below`() {
        val rows = OverlayList.parse(list)
        // SemWT_android was re-enabled but sits below HexNext_android: disable it, no raise needed.
        assertEquals(listOf("cmd overlay disable --user current android:SemWT_android"), SemWtPlanner.reassert(state, rows))
        val reordered = OverlayList.parse(
            """
            android
            [x] com.android.shell:HexNext_android
            [x] android:SemWT_android
            """.trimIndent(),
        )
        // SemWT re-enabled above ours: disabling it is enough, ours is then the top enabled overlay.
        assertEquals(
            listOf("cmd overlay disable --user current android:SemWT_android"),
            SemWtPlanner.reassert(state, reordered),
        )
        // Another enabled overlay above ours still needs a raise, once per shared Hex overlay.
        val shared = state.withTakeover("MonetPalette", "com.android.shell:HexNext_android", semwtEnabledNow = true, now = 12)
        val otherAbove = OverlayList.parse(
            """
            android
            [x] com.android.shell:HexNext_android
            [x] android:SemWT_android
            [x] com.example:other
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                "cmd overlay disable --user current android:SemWT_android",
                "cmd overlay disable --user current com.android.shell:HexNext_android",
                "cmd overlay enable --user current com.android.shell:HexNext_android",
            ),
            SemWtPlanner.reassert(shared, otherAbove),
        )
        val hexOff = rows.map { if (it.overlayId == "com.android.shell:HexNext_android") it.copy(enabled = false) else it }
        assertTrue(SemWtPlanner.reassert(state, hexOff).isEmpty())
    }

    @Test
    fun `guard script embeds the pairs and has no template markers left`() {
        val text = SemWtGuardScript.text(state)
        assertTrue(text.startsWith("#!/system/bin/sh\n"))
        assertTrue("android:SemWT_android|com.android.shell:HexNext_android\n" in text)
        assertTrue("android:SemWT_com.android.settings|com.android.shell:HexNext_com_android_settings'" in text)
        assertTrue("RESTORE='android:SemWT_android'" in text)
        assertTrue("FULL=${SemWtGuardScript.FULL_CHECK_TICKS}" in text)
        assertFalse('£' in text)
        assertFalse('@' in text)
        assertTrue("\$(stamp)" in text)
        assertTrue("grep -qxF \"[x] \$hex\"" in text)
        assertTrue("cksum" in text)
        assertTrue("needs_raise \"\$hex\"" in text)
        // LIST is re-read after the SemWT disable, before the raise check.
        val disableSem = text.indexOf("cmd overlay disable --user current \"\$sem\"")
        val raiseCheck = text.indexOf("needs_raise \"\$hex\"")
        assertTrue(disableSem in 0 until raiseCheck)
        assertTrue("list_overlays" in text.substring(disableSem, raiseCheck))
        assertFalse("set-priority" in text)
    }

    @Test
    fun `guard script runs all semwt disables before raising each hex overlay once`() {
        val sh = File("/bin/sh")
        if (!sh.canExecute()) return
        val hex = "com.android.shell:HexNext_android"
        val shared = SemWtState()
            .withTakeover("android", hex, semwtEnabledNow = true, now = 1)
            .withTakeover("MonetPalette", hex, semwtEnabledNow = true, now = 2)
            .withTakeover("G_MonetPalette", hex, semwtEnabledNow = true, now = 3)
        val script = SemWtGuardScript.text(shared)
        val functions = script.substring(0, script.indexOf("mkdir -p \"\$DIR\""))
        val dir = createTempDirectory("semwt-guard").toFile()
        try {
            fun run(list: String): List<String> {
                File(dir, "list").writeText(list.trimIndent() + "\n")
                File(dir, "log").writeText("")
                File(dir, "run.sh").writeText(functions + FAKE_CMD + "fix_pairs\n")
                val process = ProcessBuilder(sh.path, File(dir, "run.sh").path)
                    .directory(dir)
                    .redirectErrorStream(true)
                    .start()
                val out = process.inputStream.bufferedReader().readText()
                assertEquals(0, process.waitFor(), out)
                return File(dir, "log").readLines().filter { it.isNotBlank() }
            }
            // Only SemWT overlays above ours: their disables are enough, no framework cycle.
            assertEquals(
                listOf(
                    "disable android:SemWT_android",
                    "disable android:SemWT_MonetPalette",
                    "disable android:SemWT_G_MonetPalette",
                ),
                run(
                    """
                    android
                    [x] $hex
                    [x] android:SemWT_android
                    [x] android:SemWT_MonetPalette
                    [x] android:SemWT_G_MonetPalette
                    """,
                ),
            )
            // Another overlay above ours: one raise for the shared overlay, after the disables.
            assertEquals(
                listOf(
                    "disable android:SemWT_android",
                    "disable android:SemWT_MonetPalette",
                    "disable $hex",
                    "enable $hex",
                ),
                run(
                    """
                    android
                    [x] $hex
                    [x] android:SemWT_android
                    [x] com.example:other
                    [x] android:SemWT_MonetPalette
                    [ ] android:SemWT_G_MonetPalette
                    """,
                ),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `guard script writes its state files only through put inside the checked dir`() {
        val text = SemWtGuardScript.text(state)
        assertTrue("umask 022\n" in text)
        assertTrue("PIDFILE=semwt_guard.pid\n" in text)
        assertTrue("STATUS=semwt_guard_status.json\n" in text)
        val cd = text.indexOf("cd -P \"\$DIR\" 2>/dev/null && [ \"\$(pwd -P)\" = \"\$DIR\" ] || exit 1")
        val pidWrite = text.indexOf("put \"\$PIDFILE\" \"\$\$\"")
        assertTrue(cd in 0 until pidWrite)
        assertTrue(text.indexOf("mkdir -p \"\$DIR\"") in 0 until cd)
        assertTrue("put \"\$STATUS\"" in text)
        assertFalse("chmod" in text)
        assertFalse(Regex(""">\s*"?\${'$'}(PIDFILE|STATUS)""").containsMatchIn(text))
        assertFalse(Regex(""">\s*"?\${'$'}DIR/""").containsMatchIn(text))
    }

    @Test
    fun `guard put replaces planted symlinks instead of writing through them`() {
        val sh = File("/bin/sh")
        if (!sh.canExecute()) return
        val script = SemWtGuardScript.text(state)
        val functions = script.substring(0, script.indexOf("mkdir -p \"\$DIR\""))
        val dir = createTempDirectory("semwt-put").toFile()
        val outside = createTempDirectory("semwt-outside").toFile()
        try {
            val victim = File(outside, "victim").apply { writeText("keep\n") }
            java.nio.file.Files.createSymbolicLink(File(dir, "semwt_guard_status.json").toPath(), victim.toPath())
            java.nio.file.Files.createSymbolicLink(File(dir, "semwt_guard.pid").toPath(), outside.toPath())
            File(dir, "run.sh").writeText(functions + "put \"\$PIDFILE\" 42 || exit 3\nwrite_status running || exit 4\n")
            val process = ProcessBuilder(sh.path, File(dir, "run.sh").path)
                .directory(dir)
                .redirectErrorStream(true)
                .start()
            val out = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), out)
            assertEquals("keep\n", victim.readText())
            assertEquals(listOf("victim"), outside.list()!!.toList())
            val pid = File(dir, "semwt_guard.pid").toPath()
            val status = File(dir, "semwt_guard_status.json").toPath()
            assertFalse(java.nio.file.Files.isSymbolicLink(pid))
            assertFalse(java.nio.file.Files.isSymbolicLink(status))
            assertEquals("42\n", pid.toFile().readText())
            assertTrue(status.toFile().readText().startsWith("{\"state\":\"running\","))
            assertTrue(status.toFile().readText().endsWith("}\n"))
            assertTrue(dir.list()!!.none { it.endsWith(".tmp") })
        } finally {
            dir.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun `guard start and stop commands`() {
        val start = SemWtGuardScript.startCommand(1)
        assertTrue(start.contains("setsid sh ${SemWtGuardScript.PATH} 5 "))
        val stop = SemWtGuardScript.stopCommand()
        assertFalse('£' in stop)
        assertTrue(stop.startsWith("P=\$(cat ${SemWtGuardScript.PID_FILE}"))
        assertTrue(stop.contains("*semwt_guard.sh*) kill \$P"))
    }

    private companion object {
        /**
         * A stand-in for `cmd` that reads and edits `./list` like One UI 9: a disable clears the
         * marker, an enable sets it and moves the overlay to the end of its target's section.
         * Every disable and enable is appended to `./log`.
         */
        val FAKE_CMD = """
            cmd() {
              case "${'$'}2" in
                list) cat ./list ;;
                disable|enable)
                  id=${'$'}5
                  echo "${'$'}2 ${'$'}id" >> ./log
                  awk -v op="${'$'}2" -v id="${'$'}id" '
                    { line[NR] = ${'$'}0 }
                    END {
                      for (i = 1; i <= NR; i++) if (line[i] == "[x] " id || line[i] == "[ ] " id) at = i
                      if (!at) { for (i = 1; i <= NR; i++) print line[i]; exit }
                      if (op == "disable") { line[at] = "[ ] " id; for (i = 1; i <= NR; i++) print line[i]; exit }
                      end = NR + 1
                      for (i = at + 1; i <= NR; i++) if (line[i] !~ /^(\[.\]|---) /) { end = i; break }
                      for (i = 1; i <= NR; i++) {
                        if (i == end) print "[x] " id
                        if (i != at) print line[i]
                      }
                      if (end == NR + 1) print "[x] " id
                    }' ./list > ./list.new && mv ./list.new ./list
                  ;;
              esac
            }

        """.trimIndent() + "\n"
    }
}
