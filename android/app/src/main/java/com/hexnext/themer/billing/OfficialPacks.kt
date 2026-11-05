package com.hexnext.themer.billing

import android.content.Context
import com.hexnext.engine.pack.PackIdAliases
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ids of the packs this install has seen in a fetched catalogue (`index.json`) or downloaded from
 * one. They are paid like [PaidPacks.IDS] (see [PackAccess.isPaid]), so a pack added to the
 * catalogue after this APK shipped is not mistaken for a free personal import when its zip is
 * imported from a file. The set only grows and survives restarts; one instance per process.
 */
class OfficialPacks private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()
    private val current = MutableStateFlow(load())

    /** Every recorded id. */
    val ids: StateFlow<Set<String>> = current.asStateFlow()

    /** Adds [packIds] (former catalogue ids as their current id) to the recorded set and persists it when it grew. */
    fun record(packIds: Collection<String>) {
        synchronized(lock) {
            val merged = current.value + packIds.filter { it.isNotBlank() }.map(PackIdAliases::current)
            if (merged.size == current.value.size) return
            prefs.edit().putStringSet(KEY_IDS, HashSet(merged)).apply()
            current.value = merged
        }
    }

    /**
     * The recorded ids, with an id recorded under a former catalogue id read as its current id
     * ([PackIdAliases]); the former id itself is kept too, so a check under either id finds it.
     */
    private fun load(): Set<String> =
        runCatching { prefs.getStringSet(KEY_IDS, null)?.toSet() }.getOrNull().orEmpty()
            .flatMapTo(LinkedHashSet()) { listOf(it, PackIdAliases.current(it)).distinct() }

    companion object {
        private const val PREFS = "hexnext_packs"
        private const val KEY_IDS = "official_pack_ids"

        @Volatile
        private var instance: OfficialPacks? = null

        fun get(context: Context): OfficialPacks =
            instance ?: synchronized(this) { instance ?: OfficialPacks(context).also { instance = it } }
    }
}
