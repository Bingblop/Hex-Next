package com.hexnext.themer.theme

import com.hexnext.engine.pack.PackIdAliases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PackLabelMigrationTest {
    @Test
    fun absentLabelIsNotCreated() {
        // JSONObject.opt returns null for an absent key.
        assertNull(PackStore.migratedLabel(null))
    }

    @Test
    fun jsonNullAndNonStringLabelsAreLeftAlone() {
        // JSONObject.NULL is a non-string sentinel; any non-string stands in for it here, because
        // the unit-test android.jar does not implement org.json.
        assertNull(PackStore.migratedLabel(Any()))
        assertNull(PackStore.migratedLabel(5))
        assertNull(PackStore.migratedLabel(true))
    }

    @Test
    fun stringLabelWithoutFormerNamesIsUnchanged() {
        assertEquals("Accent colour", PackStore.migratedLabel("Accent colour"))
        assertEquals("", PackStore.migratedLabel(""))
    }

    @Test
    fun stringLabelHasFormerNamesReplaced() {
        for (former in PackIdAliases.FORMER_TO_CURRENT.keys) {
            val label = former.split('-').joinToString(" ") + " style"
            val migrated = PackStore.migratedLabel(label)
            assertEquals(PackStore.replaceFormerNames(label), migrated)
            assertNotEquals(label, migrated)
        }
    }
}
