package com.hexnext.themer.billing

import com.hexnext.engine.compile.ThemeMode
import com.hexnext.engine.compile.ThemeSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackAccessTest {
    private val nothingOwned = EntitlementState(ready = true)

    @Test
    fun bundledDebugPacksCountAsUnlocked() {
        assertTrue(PackAccess.isUnlocked(nothingOwned, "aurora", bundled = setOf("aurora")))
        assertFalse(PackAccess.isUnlocked(nothingOwned, "aurora", bundled = emptySet()))
    }

    @Test
    fun packsTheUserBuiltAreNeverLocked() {
        assertTrue(PackAccess.isUnlocked(nothingOwned, "my-own-pack", bundled = emptySet()))
    }

    @Test
    fun cachedHintNeverUnlocksAnApply() {
        val starting = EntitlementState(cachedProductIds = setOf("pack_ballroom"))
        assertTrue(PackAccess.looksUnlocked(starting, "ballroom", emptySet()))
        assertFalse(PackAccess.isUnlocked(starting, "ballroom", emptySet()))
    }

    @Test
    fun simpleModeUsesOnlyTheDefaultPack() {
        val spec = ThemeSpec.default().copy(
            mode = ThemeMode.SIMPLE,
            simplePackId = "ballroom",
            elementPacks = mapOf("statusbar" to "bubblegum-bandit"),
        )
        assertEquals(setOf("ballroom"), PackAccess.packsUsedBy(spec))
    }

    @Test
    fun advancedModeAddsEachEnabledPartsPack() {
        val spec = ThemeSpec.default().copy(
            mode = ThemeMode.ADVANCED,
            simplePackId = null,
            elementPacks = mapOf("statusbar" to "bubblegum-bandit", "quicksettings" to "fly"),
            elementEnabled = mapOf("quicksettings" to false),
        )
        assertEquals(setOf("bubblegum-bandit"), PackAccess.packsUsedBy(spec))
    }

    @Test
    fun lockedPacksAreReported() {
        val spec = ThemeSpec.default().copy(
            mode = ThemeMode.ADVANCED,
            simplePackId = "ballroom",
            elementPacks = mapOf("statusbar" to "bubblegum-bandit"),
        )
        val state = EntitlementState(ready = true, ownedProductIds = setOf("pack_ballroom"))
        assertEquals(setOf("bubblegum-bandit"), PackAccess.lockedPacksIn(spec) { PackAccess.isUnlocked(state, it, emptySet()) })
        assertEquals(emptySet<String>(), PackAccess.lockedPacksIn(spec) { true })
        assertEquals(emptySet<String>(), PackAccess.lockedPacksIn(ThemeSpec.default()) { false })
    }
}
