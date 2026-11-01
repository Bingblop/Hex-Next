package com.hexnext.engine.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OneUiProfileTest {
    @Test
    fun `parses ro build version oneui`() {
        assertEquals(OneUiVersion(9, 0, 37, 180000, 3701), OneUiVersion.fromProps(mapOf(
            "ro.build.version.oneui" to "90000", "ro.build.version.sdk" to "37",
            "ro.build.version.sep" to "180000", "ro.build.version.sem" to "3701",
        )))
        assertEquals("8.0", OneUiVersion.fromProps(mapOf("ro.build.version.oneui" to "80000"))!!.name)
        assertEquals("7.0", OneUiVersion.fromProps(mapOf("ro.build.version.oneui" to "70000"))!!.name)
        assertEquals("8.5", OneUiVersion.fromProps(mapOf("ro.build.version.oneui" to "80500"))!!.name)
        assertEquals("6.1", OneUiVersion.fromProps(mapOf("ro.build.version.oneui" to "60101"))!!.name)
    }

    @Test
    fun `falls back to sem platform int and rejects non samsung`() {
        val v = OneUiVersion.fromProps(mapOf("ro.build.version.sep" to "160000", "ro.build.version.sdk" to "35"))!!
        assertEquals(7, v.major)
        assertEquals(35, v.sdkInt)
        assertNull(OneUiVersion.fromProps(mapOf("ro.build.version.sdk" to "35")))
        assertNull(OneUiVersion.fromProps(mapOf("ro.build.version.oneui" to "garbage")))
        assertTrue(OneUiVersion(9, 0, 0, 0) > OneUiVersion(8, 5, 0, 0))
    }

    @Test
    fun `profile from getprop output`() {
        val props = OneUiProfile.parseGetprop(
            """
            [ro.build.version.oneui]: [90000]
            [ro.build.version.sdk]: [37]
            [ro.product.model]: [SM-S938B]
            garbage line
            """.trimIndent(),
        )
        assertEquals("SM-S938B", props["ro.product.model"])
        val profile = OneUiProfile.fromProps(props)
        assertTrue(profile.isSupported)
        assertEquals("9.json", profile.ruleFile)
        assertFalse(OneUiProfile.fromProps(mapOf("ro.build.version.oneui" to "60000")).isSupported)
        assertFalse(OneUiProfile.fromProps(emptyMap()).isSupported)
    }
}
