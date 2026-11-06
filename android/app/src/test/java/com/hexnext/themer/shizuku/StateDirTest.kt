package com.hexnext.themer.shizuku

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StateDirTest {
    private val script = MonitorScript.prepareStateDirScript

    @Test
    fun `root prepares the state directory so the UID 1000 service can write in it`() {
        assertTrue(script.startsWith("d='${MonitorScript.DIR}';"))
        assertTrue(script.contains("chown -h 2000:1000 \"\$d\""))
        assertTrue(script.contains("chmod 775 \"\$d\""))
        // Subdirectories too, without following links.
        assertTrue(script.contains("find \"\$d\" -mindepth 1 -type d -exec chown -h 2000:1000 {} + -exec chmod 775 {} +"))
        // A symlink planted in the directory's place is removed, not followed.
        assertTrue(script.contains("[ -L \"\$d\" ] && rm -f \"\$d\""))
        assertFalse(script.contains("£"))
        assertTrue(script.endsWith("; true"))
    }
}
