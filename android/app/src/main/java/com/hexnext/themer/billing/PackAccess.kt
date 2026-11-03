package com.hexnext.themer.billing

import com.hexnext.engine.compile.Elements
import com.hexnext.engine.compile.ThemeMode
import com.hexnext.engine.compile.ThemeSpec
import com.hexnext.engine.pack.PackIdAliases

/**
 * Which packs may be downloaded, selected and applied. Every catalogue pack is a paid unlock:
 * the ids this APK shipped with ([PaidPacks]) and the [official] ids this install has seen in a
 * fetched catalogue or downloaded from one ([OfficialPacks]). Only a pack imported from a file
 * whose id is in neither is the user's own and free. [bundled] holds the test packs seeded into
 * debuggable builds, which count as unlocked there only (the caller passes an empty set in other
 * builds).
 */
object PackAccess {
    /**
     * True when [packId] is a catalogue pack, so it needs a purchase. A former catalogue id
     * ([PackIdAliases]) counts as its current id, so an older official zip is never free.
     */
    fun isPaid(packId: String, official: Set<String>): Boolean =
        PackIdAliases.allIdsOf(packId).any { PaidPacks.isPaid(it) || it in official }

    /** True when [packId] may be used: a verified purchase, a free pack, or a debug bundled pack. */
    fun isUnlocked(state: EntitlementState, packId: String, bundled: Set<String>, official: Set<String>): Boolean =
        !isPaid(packId, official) || isBundled(packId, bundled) || state.unlocks(PackIdAliases.current(packId))

    /** [isUnlocked], or the cached hint while the store has not answered yet: for display only. */
    fun looksUnlocked(state: EntitlementState, packId: String, bundled: Set<String>, official: Set<String>): Boolean =
        !isPaid(packId, official) || isBundled(packId, bundled) || state.looksUnlocked(PackIdAliases.current(packId))

    private fun isBundled(packId: String, bundled: Set<String>): Boolean =
        PackIdAliases.allIdsOf(packId).any { it in bundled }

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

    /**
     * The packs [spec] uses that [isUnlocked] rejects; an apply must not run while this is not
     * empty. A pack referenced under a former id must pass [isUnlocked] under that id and under
     * its current id.
     */
    fun lockedPacksIn(spec: ThemeSpec, isUnlocked: (String) -> Boolean): Set<String> =
        packsUsedBy(spec).filterNotTo(LinkedHashSet()) { id -> isUnlocked(id) && isUnlocked(PackIdAliases.current(id)) }

    /**
     * [spec] with every pack that [isUnlocked] rejects taken out, for the live preview: a locked
     * pack (named by an imported theme file, kept after a refund, or kept by "Describe your
     * theme") must never be rendered from its artwork. In simple mode a locked pack becomes
     * colours only; in advanced mode each part that resolves to a locked pack is switched off
     * (colours only) rather than falling back to another pack. Returns [spec] itself when it uses
     * no locked pack.
     */
    fun withoutLockedPacks(spec: ThemeSpec, isUnlocked: (String) -> Boolean): ThemeSpec {
        fun ok(id: String) = isUnlocked(id) && isUnlocked(PackIdAliases.current(id))
        val referenced = listOfNotNull(spec.simplePackId) + spec.elementPacks.values
        if (referenced.all(::ok)) return spec
        val lockedParts = Elements.ALL.asSequence()
            .map { it.id }
            .filter { id -> spec.isElementEnabled(id) && spec.packFor(id)?.let { !ok(it) } == true }
            .toList()
        return spec.copy(
            simplePackId = spec.simplePackId?.takeIf(::ok),
            elementPacks = spec.elementPacks.filterValues(::ok),
            elementEnabled = if (spec.mode == ThemeMode.ADVANCED) spec.elementEnabled + lockedParts.associateWith { false } else spec.elementEnabled,
        )
    }
}
