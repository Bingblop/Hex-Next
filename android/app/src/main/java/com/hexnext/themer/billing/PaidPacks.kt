package com.hexnext.themer.billing

/**
 * Ids of the catalogue packs sold in the Play build (generated from tools/pack_names.json;
 * tests/test_paid_packs.py keeps the two in sync). A pack whose id is not here is the user's own
 * (built and imported by them) and is never locked.
 */
object PaidPacks {
    val IDS: Set<String> = setOf(
        "aurora",
        "aurora-borealis",
        "ballroom",
        "bubblegum-bandit",
        "carnival",
        "clearwater",
        "compass-rose",
        "crimson-eye",
        "dandelion",
        "double-scoop",
        "dusk-and-dawn",
        "ember-glow",
        "fever-dream",
        "firefly",
        "first-light",
        "fox-hunt",
        "hearthside",
        "highland-mist",
        "iron-lotus",
        "jackpot",
        "lagoon",
        "lantern-light",
        "leapfrog",
        "lotus-garden",
        "midnight-express",
        "moonlit-hollow",
        "night-patrol",
        "north-star",
        "owl-hours",
        "particle-zoo",
        "pixel-pop",
        "pixel-pop-remix",
        "riverstone",
        "riverstone-classic",
        "rocket-fuel",
        "sandpaper",
        "solar-flare",
        "stardust",
        "supernova",
        "thin-ice",
        "thunderclap",
        "velvet-rope",
        "velvet-rope-encore",
        "watercolor",
    )

    fun isPaid(packId: String): Boolean = packId in IDS
}
