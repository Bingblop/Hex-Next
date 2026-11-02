package com.hexnext.themer.billing

import com.hexnext.engine.compile.ThemeMode
import com.hexnext.engine.compile.ThemeSpec

/**
 * Which packs may be downloaded, selected and applied. Every catalogue pack ([PaidPacks]) is a
 * paid unlock; packs the user built and imported are free; [bundled]
 * holds the test packs seeded into debuggable builds, which count as unlocked there only (the
 * caller passes an empty set in other builds).
 */
object PackAccess {
    /** True when [packId] may be used: a verified purchase, this build, or a debug bundled pack. */
    fun isUnlocked(state: EntitlementState, packId: String, bundled: Set<String>): Boolean =
        !PaidPacks.isPaid(packId) || packId in bundled || state.unlocks(packId)

    /** [isUnlocked], or the cached hint while the store has not answered yet: for display only. */
    fun looksUnlocked(state: EntitlementState, packId: String, bundled: Set<String>): Boolean =
        !PaidPacks.isPaid(packId) || packId in bundled || state.looksUnlocked(packId)

    /** The pack ids [spec] would apply drawables from: the default pack and, in advanced mode, each enabled part's pack. */
    fun packsUsedBy(spec: ThemeSpec): Set<String> {
        val used = LinkedHashSet<String>()
        spec.simplePackId?.let { used += it }
        if (spec.mode == ThemeMode.ADVANCED) {
            spec.elementPacks.forEach { (elementId, packId) -> if (spec.isElementEnabled(elementId)) used += packId }
        }
        return used
    }

    /** The packs [spec] uses that [isUnlocked] rejects; an apply must not run while this is not empty. */
    fun lockedPacksIn(spec: ThemeSpec, isUnlocked: (String) -> Boolean): Set<String> =
        packsUsedBy(spec).filterNotTo(LinkedHashSet()) { isUnlocked(it) }
}
