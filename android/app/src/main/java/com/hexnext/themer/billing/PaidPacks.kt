package com.hexnext.themer.billing

import com.hexnext.engine.pack.PackIdAliases

/**
 * Ids of the catalogue packs sold in the Play build (generated from tools/pack_names.json;
 * tests/test_paid_packs.py keeps the two in sync). A pack whose id is not here, and is not a
 * former id of one of them ([PackIdAliases]), is the user's own (built and imported by them) and
 * is never locked.
 */
object PaidPacks {
    val IDS: Set<String> = setOf(
        "agate",
        "ametrine",
        "apatite",
        "axinite",
        "bixbite",
        "calcite",
        "carnelian",
        "cerussite",
        "charoite",
        "chrysocolla",
        "citrine",
        "cordierite",
        "covellite",
        "diopside",
        "ekanite",
        "epidote",
        "erythrite",
        "euclase",
        "fayalite",
        "fuchsite",
        "garnet",
        "idocrase",
        "indicolite",
        "iolite",
        "kornerupine",
        "kunzite",
        "kyanite",
        "larimar",
        "lazulite",
        "lepidolite",
        "morganite",
        "okenite",
        "olivine",
        "pectolite",
        "peridot",
        "scolecite",
        "sodalite",
        "topaz",
        "variscite",
        "wavellite",
        "willemite",
        "xenotime",
        "zircon",
        "zoisite",
    )

    /** True for a catalogue pack, also under a former catalogue id (an older official zip). */
    fun isPaid(packId: String): Boolean = PackIdAliases.current(packId) in IDS
}
