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
        assertTrue(PackAccess.isUnlocked(nothingOwned, "variscite", bundled = setOf("variscite"), official = noOfficial))
        assertFalse(PackAccess.isUnlocked(nothingOwned, "variscite", bundled = emptySet(), official = noOfficial))
    }

    @Test
    fun theDefaultPackIsFreeEvenIfACatalogueListsIt() {
        assertFalse(PackAccess.isPaid(DefaultPack.ID, setOf(DefaultPack.ID)))
        assertTrue(PackAccess.isUnlocked(nothingOwned, DefaultPack.ID, bundled = emptySet(), official = setOf(DefaultPack.ID)))
        assertFalse(DefaultPack.ID in PaidPacks.IDS)
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
        assertTrue(PackAccess.isPaid("carnelian", noOfficial))
    }

    @Test
    fun cachedHintNeverUnlocksAnApply() {
        val starting = EntitlementState(cachedProductIds = setOf("pack_carnelian"))
        assertTrue(PackAccess.looksUnlocked(starting, "carnelian", emptySet(), noOfficial))
        assertFalse(PackAccess.isUnlocked(starting, "carnelian", emptySet(), noOfficial))
    }

    @Test
    fun simpleModeUsesOnlyTheDefaultPack() {
        val spec = ThemeSpec.default().copy(
            mode = ThemeMode.SIMPLE,
            simplePackId = "carnelian",
            elementPacks = mapOf(STATUS_BAR to "ametrine"),
        )
        assertEquals(setOf("carnelian"), PackAccess.packsUsedBy(spec))
    }

    @Test
    fun advancedModeAddsEachEnabledPartsPack() {
        val spec = ThemeSpec.default().copy(
            mode = ThemeMode.ADVANCED,
            simplePackId = null,
            elementPacks = mapOf(STATUS_BAR to "ametrine", SETTINGS_ICONS to "fly"),
            elementEnabled = mapOf(SETTINGS_ICONS to false),
        )
        assertEquals(setOf("ametrine"), PackAccess.packsUsedBy(spec))
    }

    @Test
    fun theDefaultPackCountsOnlyWhileAnEnabledPartFallsBackToIt() {
        val everyPartOverridden = Elements.ALL.associate { it.id to "ametrine" }
        val spec = ThemeSpec.default().copy(
            mode = ThemeMode.ADVANCED,
            simplePackId = "carnelian",
            elementPacks = everyPartOverridden,
        )
        assertEquals(setOf("ametrine"), PackAccess.packsUsedBy(spec))

        // One part falls back to the default pack again: it is used.
        val oneFallsBack = spec.copy(elementPacks = everyPartOverridden - STATUS_BAR)
        assertEquals(setOf("ametrine", "carnelian"), PackAccess.packsUsedBy(oneFallsBack))

        // That part is switched off: the default pack is unused again.
        val fallbackDisabled = oneFallsBack.copy(elementEnabled = mapOf(STATUS_BAR to false))
        assertEquals(setOf("ametrine"), PackAccess.packsUsedBy(fallbackDisabled))
    }

    @Test
    fun everyPartSwitchedOffUsesNoPack() {
        val spec = ThemeSpec.default().copy(
            mode = ThemeMode.SIMPLE,
            simplePackId = "carnelian",
            elementEnabled = Elements.ALL.associate { it.id to false },
        )
        assertEquals(emptySet<String>(), PackAccess.packsUsedBy(spec))
    }

    @Test
    fun overridesForUnknownPartsAreIgnored() {
        val spec = ThemeSpec.default().copy(
            mode = ThemeMode.ADVANCED,
            simplePackId = null,
            elementPacks = mapOf("not_an_element" to "ametrine"),
        )
        assertEquals(emptySet<String>(), PackAccess.packsUsedBy(spec))
    }

    @Test
    fun lockedPacksAreReported() {
        val spec = ThemeSpec.default().copy(
            mode = ThemeMode.ADVANCED,
            simplePackId = "carnelian",
            elementPacks = mapOf(STATUS_BAR to "ametrine"),
        )
        val state = EntitlementState(ready = true, ownedProductIds = setOf("pack_carnelian"))
        assertEquals(setOf("ametrine"), PackAccess.lockedPacksIn(spec) { PackAccess.isUnlocked(state, it, emptySet(), noOfficial) })
        assertEquals(emptySet<String>(), PackAccess.lockedPacksIn(spec) { true })
        assertEquals(emptySet<String>(), PackAccess.lockedPacksIn(ThemeSpec.default()) { false })
    }

    @Test
    fun aLockedPackIsPreviewedButNeverBuilt() {
        val owned = EntitlementState(ready = true, ownedProductIds = setOf("pack_carnelian"))
        val unlocked: (String) -> Boolean = { PackAccess.isUnlocked(owned, it, emptySet(), noOfficial) }

        // Simple mode: the locked pack stays in the preview, and Apply refuses it.
        val simple = ThemeSpec.default().copy(simplePackId = "ametrine")
        val shownSimple = PackAccess.specForPreview(simple)
        assertEquals("ametrine", shownSimple.simplePackId)
        assertEquals(setOf("ametrine"), PackAccess.packsUsedBy(shownSimple))
        assertEquals(setOf("ametrine"), PackAccess.lockedPacksIn(simple, unlocked))
        assertFalse(PackAccess.canBuild(simple, unlocked))

        // A bought pack previews and builds.
        val bought = ThemeSpec.default().copy(simplePackId = "carnelian")
        assertTrue(PackAccess.specForPreview(bought) === bought)
        assertTrue(PackAccess.canBuild(bought, unlocked))

        // Advanced mode: a locked part keeps its pack in the preview; the build is refused for it.
        val advanced = ThemeSpec.default().copy(
            mode = ThemeMode.ADVANCED,
            simplePackId = "carnelian",
            elementPacks = mapOf(STATUS_BAR to "ametrine"),
        )
        val shown = PackAccess.specForPreview(advanced)
        assertEquals(mapOf(STATUS_BAR to "ametrine"), shown.elementPacks)
        assertTrue(shown.isElementEnabled(STATUS_BAR))
        assertEquals(setOf("carnelian", "ametrine"), PackAccess.packsUsedBy(shown))
        assertEquals(setOf("ametrine"), PackAccess.lockedPacksIn(advanced, unlocked))
        assertFalse(PackAccess.canBuild(advanced, unlocked))

        // The cached purchase hint makes the badge look unlocked, but never lets a build run.
        val starting = EntitlementState(cachedProductIds = setOf("pack_ametrine"))
        assertTrue(PackAccess.looksUnlocked(starting, "ametrine", emptySet(), noOfficial))
        assertFalse(PackAccess.canBuild(simple) { PackAccess.isUnlocked(starting, it, emptySet(), noOfficial) })
    }

    private companion object {
        const val STATUS_BAR = "status_bar"
        const val SETTINGS_ICONS = "settings_icons"
    }
}
