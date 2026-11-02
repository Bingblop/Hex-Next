package com.hexnext.themer.billing

import com.hexnext.engine.compile.Elements
import com.hexnext.engine.compile.ThemeSpec

/**
 * Which packs may be downloaded, selected and applied. Every catalogue pack is a paid unlock:
 * the ids this APK shipped with ([PaidPacks]) and the [official] ids this install has seen in a
 * fetched catalogue or downloaded from one ([OfficialPacks]). Only a pack imported from a file
 * whose id is in neither is the user's own and free. [bundled] holds the test packs seeded into
 * debuggable builds, which count as unlocked there only (the caller passes an empty set in other
 * builds).
 */
object PackAccess {
    /** True when [packId] is a catalogue pack, so it needs a purchase. */
    fun isPaid(packId: String, official: Set<String>): Boolean = PaidPacks.isPaid(packId) || packId in official

    /** True when [packId] may be used: a verified purchase, a free pack, or a debug bundled pack. */
    fun isUnlocked(state: EntitlementState, packId: String, bundled: Set<String>, official: Set<String>): Boolean =
        !isPaid(packId, official) || packId in bundled || state.unlocks(packId)

    /** [isUnlocked], or the cached hint while the store has not answered yet: for display only. */
    fun looksUnlocked(state: EntitlementState, packId: String, bundled: Set<String>, official: Set<String>): Boolean =
        !isPaid(packId, official) || packId in bundled || state.looksUnlocked(packId)

    /**
     * The pack ids [spec] would apply drawables from, resolved like the theme compiler does: each
     * enabled element ([ThemeSpec.isElementEnabled]) uses [ThemeSpec.packFor]. The default pack
     * therefore counts only while some enabled element falls back to it, and a disabled element
     * uses nothing.
     */
    fun packsUsedBy(spec: ThemeSpec): Set<String> =
        Elements.ALL.asSequence()
            .filter { spec.isElementEnabled(it.id) }
            .mapNotNullTo(LinkedHashSet()) { spec.packFor(it.id) }

    /** The packs [spec] uses that [isUnlocked] rejects; an apply must not run while this is not empty. */
    fun lockedPacksIn(spec: ThemeSpec, isUnlocked: (String) -> Boolean): Set<String> =
        packsUsedBy(spec).filterNotTo(LinkedHashSet()) { isUnlocked(it) }
}
