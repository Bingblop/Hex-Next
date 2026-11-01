package com.hexnext.themer.theme

import com.hexnext.engine.compile.DeviceSnapshot
import com.hexnext.engine.compile.OneUiRules

/**
 * The drawable resources a custom background can replace, found in the device snapshot.
 *
 * A slot is a `drawable` entry of a themed target (the One UI rules' active targets: Samsung and
 * system packages only) whose name reads like a background: `bg`, `background` or `backgrounds`
 * as a whole `_`-separated word, or an `_bg` / `_background` suffix. Slots are keyed
 * `pkg:drawable/name` (the key of [com.hexnext.engine.compile.ThemeSpec.customBackgrounds]),
 * de-duplicated, Settings first, then in rule target order, then by name.
 *
 * TODO: the Settings window background resource is not known yet; `tools/device/diag.sh`
 * ("Settings window background") probes it. Once confirmed, pin it as the first slot.
 */
object BackgroundSlots {

    data class Slot(val key: String, val packageName: String, val resourceName: String, val label: String)

    private const val SETTINGS = "com.android.settings"
    private val WORD = Regex("(^|_)(bg|background|backgrounds)(_|$)")

    /** True when a drawable named [name] is a background candidate. */
    fun isBackgroundName(name: String): Boolean =
        WORD.containsMatchIn(name) || name.endsWith("_bg") || name.endsWith("_background")

    fun forSnapshot(snapshot: DeviceSnapshot, rules: OneUiRules): List<Slot> {
        val order = HashMap<String, Int>()
        rules.activeTargets.forEachIndexed { index, pkg -> order[pkg] = index + 1 }
        order[SETTINGS] = 0
        val seen = HashSet<String>()
        val slots = ArrayList<Slot>()
        for ((pkg, target) in snapshot.targets) {
            if (pkg !in order || !target.installed) continue
            val table = target.table ?: continue
            for (entry in table.entries) {
                if (entry.type != "drawable" || !isBackgroundName(entry.name)) continue
                val key = "$pkg:drawable/${entry.name}"
                if (!seen.add(key)) continue
                slots += Slot(key, pkg, entry.name, humanise(entry.name))
            }
        }
        slots.sortWith(compareBy<Slot> { order[it.packageName] ?: Int.MAX_VALUE }.thenBy { it.resourceName })
        return slots
    }

    /** `sec_settings_main_bg` → `Sec settings main bg`. */
    fun humanise(name: String): String {
        val words = name.split('_').filter { it.isNotEmpty() }
        if (words.isEmpty()) return name
        return words.joinToString(" ").replaceFirstChar { it.uppercaseChar() }
    }
}
