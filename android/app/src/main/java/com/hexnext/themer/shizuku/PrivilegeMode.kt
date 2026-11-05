package com.hexnext.themer.shizuku

/** Keep real root UID 0 distinct from the ordinary Android shell, UID 2000. */
enum class PrivilegeMode(val uid: Int?, val overlays: Boolean, val survivesReboot: Boolean) {
    SYSTEM(1000, true, true),
    ROOT_FALLBACK(0, true, false),
    MONET_ONLY(2000, false, false),
    NONE(null, false, false);

    companion object {
        fun fromUid(uid: Int?): PrivilegeMode = entries.firstOrNull { it.uid == uid } ?: NONE
        fun preferredUid(vararg candidates: Int?): Int? =
            listOf(SYSTEM, ROOT_FALLBACK, MONET_ONLY).firstOrNull { it.uid in candidates }?.uid
    }
}
