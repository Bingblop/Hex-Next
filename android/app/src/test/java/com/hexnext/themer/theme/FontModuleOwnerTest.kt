package com.hexnext.themer.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FontModuleOwnerTest {
    private val play = "com.hexnext.themer"
    private val owner = "com.hexnext.themer.owner"

    private fun prop(ownerLine: String?) = listOfNotNull(
        "id=${FontInstaller.MODULE_ID}",
        ownerLine,
        "name=Hex Next font",
    ).joinToString("\n")

    @Test
    fun noModuleCanBeReplacedByEitherBuild() {
        assertTrue(FontInstaller.isOwnedBy(null, play, ownsLegacy = true))
        assertTrue(FontInstaller.isOwnedBy(null, owner, ownsLegacy = false))
    }

    @Test
    fun onlyTheRecordedBuildOwnsTheModule() {
        val ownersModule = prop("${FontInstaller.OWNER_KEY}=$owner")
        assertTrue(FontInstaller.isOwnedBy(ownersModule, owner, ownsLegacy = false))
        assertFalse(FontInstaller.isOwnedBy(ownersModule, play, ownsLegacy = true))

        val playsModule = prop("${FontInstaller.OWNER_KEY}=$play")
        assertTrue(FontInstaller.isOwnedBy(playsModule, play, ownsLegacy = true))
        assertFalse(FontInstaller.isOwnedBy(playsModule, owner, ownsLegacy = false))
    }

    @Test
    fun aModuleFromBeforeTheOwnerKeyBelongsToThePlayBuild() {
        val legacy = prop(null)
        assertTrue(FontInstaller.isOwnedBy(legacy, play, ownsLegacy = true))
        assertFalse(FontInstaller.isOwnedBy(legacy, owner, ownsLegacy = false))
    }
}
