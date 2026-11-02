package com.hexnext.themer.billing

import com.hexnext.engine.compile.Elements
import com.hexnext.engine.compile.ThemeMode
import com.hexnext.engine.compile.ThemeSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackAccessTest {
    private val nothingOwned = EntitlementState(ready = true)
    private val noOfficial = emptySet<String>()

    @Test
    fun bundledDebugPacksCountAsUnlocked() {
        assertTrue(PackAccess.isUnlocked(nothingOwned, "aurora", bundled = setOf("aurora"), official = noOfficial))
        assertFalse(PackAccess.isUnlocked(nothingOwned, "aurora", bundled = emptySet(), official = noOfficial))
    }

    @Test
    fun packsTheUserBuiltAreNeverLocked() {
        assertTrue(PackAccess.isUnlocked(nothingOwned, "my-own-pack", bundled = emptySet(), official = noOfficial))
        assertTrue(PackAccess.looksUnlocked(nothingOwned, "my-own-pack", bundled = emptySet(), official = noOfficial))
    }

    @Test
    fun aCataloguePackNewerThanThisBuildIsPaid() {
        val official = setOf("brand-new-pack")
        assertTrue(PackAccess.isPaid("brand-new-pack", official))
        assertFalse(PackAccess.isPaid("brand-new-pack", noOfficial))
        assertFalse(PackAccess.isUnlocked(nothingOwned, "brand-new-pack", bundled = emptySet(), official = official))
        assertFalse(PackAccess.looksUnlocked(nothingOwned, "brand-new-pack", bundled = emptySet(), official = official))
        val bought = EntitlementState(ready = true, ownedProductIds = setOf(ProductIds.forPack("brand-new-pack")))
        assertTrue(PackAccess.isUnlocked(bought, "brand-new-pack", bundled = emptySet(), official = official))
        val all = EntitlementState(ready = true, unlockAll = true)
        assertTrue(PackAccess.isUnlocked(all, "brand-new-pack", bundled = emptySet(), official = official))
    }

    @Test
    fun shippedPaidPacksStayPaidWithoutACatalogue() {
        assertTrue(PackAccess.isPaid("ballroom", noOfficial))
    }

    @Test
    fun cachedHintNeverUnlocksAnApply() {
        val starting = EntitlementState(cachedProductIds = setOf("pack_ballroom"))
        assertTrue(PackAccess.looksUnlocked(starting, "ballroom", emptySet(), noOfficial))
        assertFalse(PackAccess.isUnlocked(starting, "ballroom", emptySet(), noOfficial))
    }

    @Test
    fun simpleModeUsesOnlyTheDefaultPack() {
        val spec = ThemeSpec.default().copy(
            mode = ThemeMode.SIMPLE,
            simplePackId = "ballroom",
            elementPacks = mapOf(STATUS_BAR to "bubblegum-bandit"),
        )
        assertEquals(setOf("ballroom"), PackAccess.packsUsedBy(spec))
    }

    @Test
    fun advancedModeAddsEachEnabledPartsPack() {
        val spec = ThemeSpec.default().copy(
            mode = ThemeMode.ADVANCED,
            simplePackId = null,
            elementPacks = mapOf(STATUS_BAR to "bubblegum-bandit", SETTINGS_ICONS to "fly"),
            elementEnabled = mapOf(SETTINGS_ICONS to false),
        )
        assertEquals(setOf("bubblegum-bandit"), PackAccess.packsUsedBy(spec))
    }

    @Test
    fun theDefaultPackCountsOnlyWhileAnEnabledPartFallsBackToIt() {
        val everyPartOverridden = Elements.ALL.associate { it.id to "bubblegum-bandit" }
        val spec = ThemeSpec.default().copy(
            mode = ThemeMode.ADVANCED,
            simplePackId = "ballroom",
            elementPacks = everyPartOverridden,
        )
        assertEquals(setOf("bubblegum-bandit"), PackAccess.packsUsedBy(spec))

        // One part falls back to the default pack again: it is used.
        val oneFallsBack = spec.copy(elementPacks = everyPartOverridden - STATUS_BAR)
        assertEquals(setOf("bubblegum-bandit", "ballroom"), PackAccess.packsUsedBy(oneFallsBack))

        // That part is switched off: the default pack is unused again.
        val fallbackDisabled = oneFallsBack.copy(elementEnabled = mapOf(STATUS_BAR to false))
        assertEquals(setOf("bubblegum-bandit"), PackAccess.packsUsedBy(fallbackDisabled))
    }

    @Test
    fun everyPartSwitchedOffUsesNoPack() {
        val spec = ThemeSpec.default().copy(
            mode = ThemeMode.SIMPLE,
            simplePackId = "ballroom",
            elementEnabled = Elements.ALL.associate { it.id to false },
        )
        assertEquals(emptySet<String>(), PackAccess.packsUsedBy(spec))
    }

    @Test
    fun overridesForUnknownPartsAreIgnored() {
        val spec = ThemeSpec.default().copy(
            mode = ThemeMode.ADVANCED,
            simplePackId = null,
            elementPacks = mapOf("not_an_element" to "bubblegum-bandit"),
        )
        assertEquals(emptySet<String>(), PackAccess.packsUsedBy(spec))
    }

    @Test
    fun lockedPacksAreReported() {
        val spec = ThemeSpec.default().copy(
            mode = ThemeMode.ADVANCED,
            simplePackId = "ballroom",
            elementPacks = mapOf(STATUS_BAR to "bubblegum-bandit"),
        )
        val state = EntitlementState(ready = true, ownedProductIds = setOf("pack_ballroom"))
        assertEquals(setOf("bubblegum-bandit"), PackAccess.lockedPacksIn(spec) { PackAccess.isUnlocked(state, it, emptySet(), noOfficial) })
        assertEquals(emptySet<String>(), PackAccess.lockedPacksIn(spec) { true })
        assertEquals(emptySet<String>(), PackAccess.lockedPacksIn(ThemeSpec.default()) { false })
    }

    private companion object {
        const val STATUS_BAR = "status_bar"
        const val SETTINGS_ICONS = "settings_icons"
    }
}
