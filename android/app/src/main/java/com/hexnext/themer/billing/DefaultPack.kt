package com.hexnext.themer.billing

/**
 * The free pack every install starts with: Quartz, the original Hex Installer look (its default
 * palette, the element art it tinted itself and its previews; see `tools/make_default_pack.py`).
 * It ships in the APK under `assets/packs/`, is seeded on first start (and again if deleted), is
 * selected on a fresh install, and is never paid: it is not in [PaidPacks] and [PackAccess.isPaid]
 * refuses it even if a catalogue ever listed the id.
 */
object DefaultPack {
    const val ID = "quartz"
}
