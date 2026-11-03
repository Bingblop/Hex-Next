package com.hexnext.themer.theme

import com.hexnext.engine.compile.PackResolver
import com.hexnext.engine.pack.DirectoryPackResolver
import com.hexnext.engine.pack.Pack
import java.io.File

/**
 * Where pack zips live, split by what may read them.
 *
 * - [APPLY] (`files/packs`): packs this install may build overlays from (unlocked, free or the
 *   user's own). The applier ([ThemeApplier.apply]) and every overlay build read this directory only.
 * - [PREVIEW] (`files/packs-preview`): catalogue packs downloaded before they were bought, so the
 *   Skins gallery and the live phone preview can show them. Nothing that builds an overlay ever
 *   reads it: a locked pack is physically absent at build time even if a view-model gate regresses.
 *   A zip moves to [APPLY] once its purchase is verified.
 */
object PackDirs {
    const val APPLY = "packs"
    const val PREVIEW = "packs-preview"

    /** The directories an overlay build may read, in lookup order: [APPLY] only. */
    fun forApply(filesDir: File): List<File> = listOf(File(filesDir, APPLY))

    /** The directories the in-memory preview reads, in lookup order: [APPLY] wins over [PREVIEW]. */
    fun forPreview(filesDir: File): List<File> = forApply(filesDir) + File(filesDir, PREVIEW)
}

/**
 * A [PackResolver] over several pack directories: a pack id is served by the first directory
 * that has it. Used by the preview over [PackDirs.forPreview]; an apply uses a plain
 * [DirectoryPackResolver] over [ThemeApplier.packsDir].
 */
class LayeredPackResolver(private val layers: List<DirectoryPackResolver>) : PackResolver {

    constructor(single: DirectoryPackResolver) : this(listOf(single))

    private fun owner(id: String): DirectoryPackResolver? = layers.firstOrNull { it.pack(id) != null }

    override fun pack(id: String): Pack? = owner(id)?.pack(id)

    override fun entryNames(id: String): Set<String> = owner(id)?.entryNames(id).orEmpty()

    /** The zip file of pack [id] in the first directory that has it, or null. */
    fun zipFile(id: String): File? = owner(id)?.zipFile(id)

    /** Zip name → why it could not be read, over every directory. */
    val errors: Map<String, String> get() = layers.fold(LinkedHashMap()) { acc, r -> acc.apply { putAll(r.errors) } }

    companion object {
        fun over(dirs: List<File>): LayeredPackResolver = LayeredPackResolver(dirs.map { DirectoryPackResolver(it.apply { mkdirs() }) })
    }
}
