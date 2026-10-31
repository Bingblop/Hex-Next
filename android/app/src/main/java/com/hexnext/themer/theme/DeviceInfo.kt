package com.hexnext.themer.theme

/** `ro.build.version.oneui` is e.g. 80000 for One UI 8.0 or 60100 for 6.1. */
fun formatOneUi(raw: String): String? {
    val value = raw.trim().toIntOrNull() ?: return null
    return "One UI ${value / 10000}.${(value / 100) % 100}"
}
