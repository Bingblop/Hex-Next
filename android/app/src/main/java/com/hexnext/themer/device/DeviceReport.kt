package com.hexnext.themer.device

import org.json.JSONArray
import org.json.JSONObject

/** One `cmd overlay list` row. */
data class OverlayStatus(
    val overlayId: String,
    val target: String,
    val enabled: Boolean,
    val isSamsungWallpaperTheme: Boolean,
) {
    val name: String get() = overlayId.substringAfter(':')
    val isHexNext: Boolean get() = name.startsWith("HexNext")
}

/** What the scan found for one target package. */
data class PackageStatus(
    val pkg: String,
    val deprecated: Boolean,
    val apkPath: String?,
    val installed: Boolean,
    val debloated: Boolean,
    val arscPath: String? = null,
    val arscBytes: Long = 0,
    val colorCount: Int = 0,
    val drawableCount: Int = 0,
    val semWtOverlays: Int = 0,
    val error: String? = null,
) {
    val arscFound: Boolean get() = arscPath != null
}

data class DeviceReport(
    val createdAt: Long,
    val uid: Int,
    val viaSu: Boolean,
    val props: Map<String, String>,
    val overlays: List<OverlayStatus>,
    val frroPaths: List<String>,
    val frroCopied: List<String>,
    val packages: List<PackageStatus>,
    val errors: List<String>,
    val jsonPath: String? = null,
    val txtPath: String? = null,
) {
    val model: String get() = props["ro.product.model"].orEmpty()
    val oneUiRaw: String get() = props["ro.build.version.oneui"].orEmpty()
    val semWtCount: Int get() = overlays.count { it.isSamsungWallpaperTheme }
    val hexCount: Int get() = overlays.count { it.isHexNext }

    /** "One UI 9.0" from 90000, or the raw value when it is not numeric. */
    val oneUiVersion: String
        get() {
            val value = oneUiRaw.trim().toIntOrNull() ?: return oneUiRaw.ifBlank { "unknown" }
            return "One UI ${value / 10000}.${(value / 100) % 100}"
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("createdAt", createdAt)
        put("uid", uid)
        put("viaSu", viaSu)
        put("props", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
        put("overlayCount", overlays.size)
        put("semWtCount", semWtCount)
        put("hexNextCount", hexCount)
        put("overlays", JSONArray().apply {
            overlays.forEach { o ->
                put(
                    JSONObject()
                        .put("overlayId", o.overlayId)
                        .put("target", o.target)
                        .put("enabled", o.enabled)
                        .put("isSamsungWallpaperTheme", o.isSamsungWallpaperTheme)
                )
            }
        })
        put("frroPaths", JSONArray(frroPaths))
        put("frroCopied", JSONArray(frroCopied))
        put("packages", JSONArray().apply {
            packages.forEach { p ->
                put(
                    JSONObject()
                        .put("pkg", p.pkg)
                        .put("deprecated", p.deprecated)
                        .put("installed", p.installed)
                        .put("debloated", p.debloated)
                        .put("apkPath", p.apkPath ?: JSONObject.NULL)
                        .put("arscPath", p.arscPath ?: JSONObject.NULL)
                        .put("arscBytes", p.arscBytes)
                        .put("colorCount", p.colorCount)
                        .put("drawableCount", p.drawableCount)
                        .put("semWtOverlays", p.semWtOverlays)
                        .put("error", p.error ?: JSONObject.NULL)
                )
            }
        })
        put("errors", JSONArray(errors))
    }

    fun summaryText(): String = buildString {
        appendLine("Hex Next device report")
        appendLine("======================")
        appendLine("Created:      ${java.util.Date(createdAt)}")
        appendLine("Model:        ${model.ifBlank { "unknown" }}")
        appendLine("One UI:       $oneUiVersion (ro.build.version.oneui=${oneUiRaw.ifBlank { "-" }})")
        appendLine("SEM:          ${props["ro.build.version.sem"].orEmpty()}")
        appendLine("Android:      ${props["ro.build.version.release"].orEmpty()} (SDK ${props["ro.build.version.sdk"].orEmpty()})")
        appendLine("Build:        ${props["ro.build.display.id"].orEmpty()}")
        appendLine("Service uid:  $uid${if (viaSu) " (in-app, root via su -c)" else ""}")
        appendLine("Overlays:     ${overlays.size} total, $semWtCount SemWT_*, $hexCount HexNext*")
        appendLine("FRRO files:   ${frroPaths.size} found, ${frroCopied.size} copied")
        appendLine()
        appendLine("Targets")
        appendLine("-------")
        packages.forEach { p ->
            val state = when {
                p.installed -> "installed"
                p.debloated -> "DEBLOATED"
                else -> "absent"
            }
            val arsc = if (p.arscFound) "arsc ${p.arscBytes / 1024} KB, ${p.colorCount} colors, ${p.drawableCount} drawables" else "no arsc"
            append("${p.pkg}: $state")
            if (p.deprecated) append(" [deprecated]")
            append(", $arsc, ${p.semWtOverlays} SemWT")
            if (p.error != null) append(" — ${p.error}")
            appendLine()
        }
        if (overlays.any { it.isSamsungWallpaperTheme }) {
            appendLine()
            appendLine("SemWT_* overlays")
            appendLine("----------------")
            overlays.filter { it.isSamsungWallpaperTheme }.forEach {
                appendLine("${if (it.enabled) "[x]" else "[ ]"} ${it.overlayId} -> ${it.target}")
            }
        }
        if (overlays.any { it.isHexNext }) {
            appendLine()
            appendLine("HexNext overlays")
            appendLine("----------------")
            overlays.filter { it.isHexNext }.forEach {
                appendLine("${if (it.enabled) "[x]" else "[ ]"} ${it.overlayId} -> ${it.target}")
            }
        }
        if (frroCopied.isNotEmpty()) {
            appendLine()
            appendLine("Copied FRRO files")
            appendLine("-----------------")
            frroCopied.forEach { appendLine(it) }
        }
        if (errors.isNotEmpty()) {
            appendLine()
            appendLine("Errors")
            appendLine("------")
            errors.forEach { appendLine(it) }
        }
    }
}
