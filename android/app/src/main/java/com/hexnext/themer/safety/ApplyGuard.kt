package com.hexnext.themer.safety

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Boot-loop guard around an unconfirmed apply.
 *
 * [recordApply] stores the time and the current kernel boot id. Every boot appends its boot id
 * ([noteBoot], called from [BootReceiver] and on app start). Safe mode is due when the apply is
 * not [confirm]ed, is at most 10 minutes old, and at least two boots other than the apply boot
 * were seen — i.e. the device restarted twice — with the current boot among them.
 */
class ApplyGuard(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val appliedAt: Long get() = prefs.getLong(KEY_APPLIED_AT, 0L)
    val confirmed: Boolean get() = prefs.getBoolean(KEY_CONFIRMED, true)
    val applyBootId: String get() = prefs.getString(KEY_APPLY_BOOT_ID, "").orEmpty()
    val bootIds: List<String> get() = prefs.getString(KEY_BOOT_IDS, "").orEmpty().split(',').filter { it.isNotBlank() }

    fun recordApply(now: Long = System.currentTimeMillis()) {
        val boot = currentBootId().orEmpty()
        prefs.edit()
            .putLong(KEY_APPLIED_AT, now)
            .putBoolean(KEY_CONFIRMED, false)
            .putString(KEY_APPLY_BOOT_ID, boot)
            .putString(KEY_BOOT_IDS, boot)
            .apply()
    }

    /** "It works": clears the pending apply so no restart can trigger safe mode for it. */
    fun confirm() {
        prefs.edit().putBoolean(KEY_CONFIRMED, true).apply()
    }

    /** Appends the current boot id if it is new. Returns the stored list. */
    fun noteBoot(): List<String> {
        val boot = currentBootId() ?: return bootIds
        val ids = bootIds
        if (ids.lastOrNull() == boot || boot in ids) return ids
        val updated = (ids + boot).takeLast(MAX_BOOT_IDS)
        // commit(): this runs from BOOT_COMPLETED, where the process may die right after.
        prefs.edit().putString(KEY_BOOT_IDS, updated.joinToString(",")).commit()
        return updated
    }

    fun shouldEnterSafeMode(now: Long = System.currentTimeMillis()): Boolean {
        if (confirmed) return false
        val at = appliedAt
        if (at <= 0L || now < at || now - at > WINDOW_MS) return false
        val current = currentBootId() ?: return false
        val applyBoot = applyBootId
        if (applyBoot.isEmpty() || current == applyBoot) return false
        val seen = noteBoot()
        val laterBoots = seen.filter { it != applyBoot }.distinct()
        return current in laterBoots && laterBoots.size >= 2
    }

    /** Called once Hex overlays were turned off; keeps the banner until dismissed. */
    fun markSafeModeEntered(now: Long = System.currentTimeMillis(), by: String) {
        prefs.edit()
            .putBoolean(KEY_CONFIRMED, true)
            .putLong(KEY_SAFE_MODE_AT, now)
            .putString(KEY_SAFE_MODE_BY, by)
            .commit()
    }

    val safeModeAt: Long get() = prefs.getLong(KEY_SAFE_MODE_AT, 0L)
    val safeModeBy: String get() = prefs.getString(KEY_SAFE_MODE_BY, "").orEmpty()
    fun safeModeActive(): Boolean = safeModeAt > 0L

    fun dismissSafeMode() {
        prefs.edit().remove(KEY_SAFE_MODE_AT).remove(KEY_SAFE_MODE_BY).apply()
    }

    fun describe(now: Long = System.currentTimeMillis()): String {
        val age = if (appliedAt > 0) "${(now - appliedAt) / 1000}s ago" else "never"
        return "apply: $age, confirmed=$confirmed, applyBoot=${applyBootId.take(8)}, " +
            "boots=${bootIds.joinToString { it.take(8) }}, current=${currentBootId()?.take(8)}"
    }

    companion object {
        const val PREFS = "hexnext_apply"
        private const val KEY_APPLIED_AT = "appliedAt"
        private const val KEY_CONFIRMED = "confirmed"
        private const val KEY_APPLY_BOOT_ID = "applyBootId"
        private const val KEY_BOOT_IDS = "bootIds"
        private const val KEY_SAFE_MODE_AT = "safeModeAt"
        private const val KEY_SAFE_MODE_BY = "safeModeBy"
        private const val WINDOW_MS = 10 * 60 * 1000L
        private const val MAX_BOOT_IDS = 8

        /** `/proc/sys/kernel/random/boot_id` changes on every boot and needs no root. */
        fun currentBootId(): String? = runCatching {
            File("/proc/sys/kernel/random/boot_id").readText().trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}
