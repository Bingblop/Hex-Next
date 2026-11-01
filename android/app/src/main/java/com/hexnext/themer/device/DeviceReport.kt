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
    /** `mPriority` of `android:SemWT_<pkg>` from `cmd overlay dump`; null when there is none. */
    val semwtPriority: Int? = null,
    /** `mState` of `android:SemWT_<pkg>` (`STATE_ENABLED`, `STATE_MISSING_TARGET` …); null when there is none. */
    val semwtState: String? = null,
    val error: String? = null,
) {
    val arscFound: Boolean get() = arscPath != null
}

/**
 * What `cmd overlay dump android:SemWT_<target>` (or, as a fallback, the copied `.frro`) said
 * about Samsung's wallpaper-theme overlay for one target.
 */
data class SemWtStatus(
    val target: String,
    val overlayId: String,
    /** True when the dump printed an info block for [overlayId]. */
    val found: Boolean = false,
    val owner: String? = null,
    val state: String? = null,
    val enabled: Boolean = false,
    val priority: Int? = null,
    val targetOverlayable: String? = null,
    /** `mBaseCodePath`, the live `.frro` under `/data/resource-cache`. */
    val frroPath: String? = null,
    /** Where [resourceCount] came from: [SOURCE_DUMP], [SOURCE_FRRO] or [SOURCE_NONE]. */
    val source: String = SOURCE_NONE,
    /** Distinct resources Samsung themes in the target. */
    val resourceCount: Int = 0,
    /** Values including per-configuration (`night`) duplicates. */
    val valueCount: Int = 0,
    /** Resources the idmap could not find in the target (`W failed to find resource`). */
    val missingCount: Int = 0,
    /** Raw dump output, `files/scan/semwt/<target>.txt`. */
    val dumpPath: String? = null,
    /** Parsed resources, `files/scan/semwt/<target>.json`. */
    val mappingPath: String? = null,
    val error: String? = null,
) {
    val isMaxPriority: Boolean get() = priority == Int.MAX_VALUE

    companion object {
        const val SOURCE_DUMP = "dump"
        const val SOURCE_FRRO = "frro"
        const val SOURCE_NONE = "none"
    }
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
    val semWt: List<SemWtStatus> = emptyList(),
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
                        .put("semwtPriority", p.semwtPriority ?: JSONObject.NULL)
                        .put("semwtState", p.semwtState ?: JSONObject.NULL)
                        .put("error", p.error ?: JSONObject.NULL)
                )
            }
        })
        put("semWt", JSONArray().apply {
            semWt.forEach { w ->
                put(
                    JSONObject()
                        .put("target", w.target)
                        .put("overlayId", w.overlayId)
                        .put("found", w.found)
                        .put("owner", w.owner ?: JSONObject.NULL)
                        .put("state", w.state ?: JSONObject.NULL)
                        .put("enabled", w.enabled)
                        .put("priority", w.priority ?: JSONObject.NULL)
                        .put("targetOverlayable", w.targetOverlayable ?: JSONObject.NULL)
                        .put("frroPath", w.frroPath ?: JSONObject.NULL)
                        .put("source", w.source)
                        .put("resourceCount", w.resourceCount)
                        .put("valueCount", w.valueCount)
                        .put("missingCount", w.missingCount)
                        .put("dumpPath", w.dumpPath ?: JSONObject.NULL)
                        .put("mappingPath", w.mappingPath ?: JSONObject.NULL)
                        .put("error", w.error ?: JSONObject.NULL)
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
            if (p.semwtState != null) append(" (${p.semwtState}, priority ${p.semwtPriority ?: "?"})")
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
        if (semWt.isNotEmpty()) {
            appendLine()
            appendLine("SemWT dumps (cmd overlay dump android:SemWT_<target>)")
            appendLine("-----------------------------------------------------")
            semWt.forEach { w ->
                append("${w.target}: ")
                if (w.found) {
                    append("${w.state ?: "?"}, priority ${w.priority ?: "?"}${if (w.isMaxPriority) " (MAX)" else ""}")
                    append(", owner ${w.owner ?: "?"}")
                } else {
                    append("no overlay")
                }
                append(", ${w.resourceCount} resources/${w.valueCount} values via ${w.source}")
                if (w.missingCount > 0) append(", ${w.missingCount} missing in target")
                if (w.error != null) append(" — ${w.error}")
                appendLine()
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
