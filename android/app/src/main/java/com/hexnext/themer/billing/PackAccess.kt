package com.hexnext.themer.billing

import com.hexnext.engine.compile.Elements
import com.hexnext.engine.compile.ThemeSpec
import com.hexnext.engine.pack.PackIdAliases

/**
 * Which packs may be applied (every pack may be downloaded, selected and previewed; see
 * [specForPreview]). Every catalogue pack is a paid unlock:
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
     * The theme the live preview renders for [spec]: [spec] itself, locked packs included. Every
     * catalogue pack may be downloaded, selected and previewed before purchase; the preview is
     * compiled and rendered in memory only and builds no overlay. Building stays gated: an apply
     * runs only while [lockedPacksIn] (with the store-verified [isUnlocked], never the cached
     * hint) is empty, and the applier reads only the directory of unlocked packs.
     */
    fun specForPreview(spec: ThemeSpec): ThemeSpec = spec

    /** True when [spec] may be built into overlays: it uses no pack that [isUnlocked] rejects. */
    fun canBuild(spec: ThemeSpec, isUnlocked: (String) -> Boolean): Boolean = lockedPacksIn(spec, isUnlocked).isEmpty()
}
