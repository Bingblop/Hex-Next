package com.hexnext.engine.pack

/**
 * Earlier catalogue ids of the catalogue packs and the id each pack ships under now (generated
 * from the `formerIds` of tools/pack_names.json; tests/test_pack_names.py keeps the two in sync).
 *
 * Migration data only, never shown: it lets a theme saved with a former id, a pack zip installed
 * under one and a purchase made under one keep working. Ids that are not former ids are their
 * own current id.
 */
public object PackIdAliases {
    public val FORMER_TO_CURRENT: Map<String, String> = mapOf(
        "aurora" to "variscite",
        "aurora-borealis" to "topaz",
        "ballroom" to "carnelian",
        "bubblegum-bandit" to "ametrine",
        "carnival" to "diopside",
        "clearwater" to "morganite",
        "compass-rose" to "zoisite",
        "crimson-eye" to "scolecite",
        "dandelion" to "wavellite",
        "double-scoop" to "kornerupine",
        "dusk-and-dawn" to "olivine",
        "ember-glow" to "kunzite",
        "fever-dream" to "peridot",
        "firefly" to "ekanite",
        "first-light" to "idocrase",
        "fox-hunt" to "xenotime",
        "hearthside" to "larimar",
        "highland-mist" to "cerussite",
        "iron-lotus" to "indicolite",
        "jackpot" to "lepidolite",
        "lagoon" to "epidote",
        "lantern-light" to "axinite",
        "leapfrog" to "chrysocolla",
        "lotus-garden" to "pectolite",
        "midnight-express" to "fayalite",
        "moonlit-hollow" to "citrine",
        "night-patrol" to "apatite",
        "north-star" to "covellite",
        "owl-hours" to "zircon",
        "particle-zoo" to "willemite",
        "pixel-pop" to "calcite",
        "pixel-pop-remix" to "agate",
        "riverstone" to "kyanite",
        "riverstone-classic" to "euclase",
        "rocket-fuel" to "cordierite",
        "sandpaper" to "okenite",
        "solar-flare" to "erythrite",
        "stardust" to "bixbite",
        "supernova" to "lazulite",
        "thin-ice" to "garnet",
        "thunderclap" to "sodalite",
        "velvet-rope" to "iolite",
        "velvet-rope-encore" to "charoite",
        "watercolor" to "fuchsite",
    )

    private val currentToFormer: Map<String, List<String>> =
        FORMER_TO_CURRENT.entries.groupBy({ it.value }, { it.key })

    /** The id pack [id] ships under now: [id] itself unless it is a former id. */
    public fun current(id: String): String = FORMER_TO_CURRENT[id] ?: id

    /** True when [id] is a former catalogue id. */
    public fun isFormer(id: String): Boolean = id in FORMER_TO_CURRENT

    /** The former ids of the pack whose current id is [current] of [id]; empty for most ids. */
    public fun formerIdsOf(id: String): List<String> = currentToFormer[current(id)].orEmpty()

    /** [current] of [id] followed by its [formerIdsOf]: every id one pack was known by. */
    public fun allIdsOf(id: String): List<String> = listOf(current(id)) + formerIdsOf(id)
}
