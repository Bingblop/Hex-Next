package com.hexnext.themer.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.hexnext.engine.compile.AccentRecolor
import com.hexnext.engine.compile.OverlayLookup
import com.hexnext.engine.device.MappedResource
import com.hexnext.themer.IThemeService
import com.hexnext.themer.device.ArscReader
import com.hexnext.themer.device.DeviceScanner
import com.hexnext.themer.safety.ApplyGuard
import com.hexnext.themer.shizuku.ThemeService
import com.hexnext.themer.theme.SnapshotBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * First real One UI 9 test: one `HexNext_com_android_settings` fabricated overlay plus 3
 * accent-filled PNG icons.
 *
 * The colours are the ones Samsung's theme actually drives: every colour of the Settings SemWT
 * mapping saved by the scan (`files/scan/semwt/com.android.settings.json`), each re-derived from
 * the accent with [AccentRecolor] (nearest shade role in the scan-time system palette, same role
 * and shade of the accent palette; `night` values too when the service is privileged), with
 * [PROBE_COLOR] forced to the plain accent so the change is unmistakable in a screenshot. Only
 * when no mapping was saved does it fall back to up to [MAX_COLORS] arsc colour names containing
 * "dashboard" or "icon".
 *
 * Flow: [ApplyGuard.recordApply] -> `startStabilityMonitor(120)` -> lookup [PROBE_COLOR] ->
 * [OverlayBuilder.apply] -> lookup again; both read-backs go to the build log and
 * [BuildResult.lookup].
 */
class SettingsTestOverlay(context: Context) {

    private val appContext = context.applicationContext

    /**
     * @property mapping the SemWT colour mappings to recolour (empty in the arsc fallback).
     * @property colors arsc colour names (`name`, no type) for the fallback.
     * @property probe true when [PROBE_COLOR] exists in the target.
     */
    data class Plan(
        val mapping: List<MappedResource>,
        val colors: List<String>,
        val drawables: List<String>,
        val probe: Boolean,
    ) {
        val fromSemWt: Boolean get() = mapping.isNotEmpty()
    }

    /** Picks the resources from the saved SemWT mapping (or the scanned arsc), or explains why it cannot. */
    fun plan(log: (String) -> Unit): Plan? {
        val arsc = DeviceScanner.arscFile(appContext, TARGET)
        val parsed = if (arsc.isFile) {
            runCatching { ArscReader.read(arsc) }.onFailure { log("Could not read ${arsc.path}: ${it.message}") }.getOrNull()
        } else {
            log("No arsc of $TARGET yet (${arsc.path} missing).")
            null
        }
        parsed?.warnings?.forEach { log("arsc warning: $it") }
        val arscColors = parsed?.ofType("color")?.map { it.name }?.distinct().orEmpty()
        val drawables = parsed?.ofType("drawable")
            ?.map { it.name }
            ?.distinct()
            ?.filter { it.startsWith("ic_settings") || it.startsWith("sec_ic_settings") }
            ?.sorted()
            ?.take(MAX_DRAWABLES)
            .orEmpty()

        val mapping = readSemWtMapping(log)
        val colorMappings = mapping.filter { it.type == "color" && it.defaultColor != null }
        val probeName = PROBE_COLOR.substringAfter('/')
        val probe = colorMappings.any { it.typeAndName == PROBE_COLOR } || probeName in arscColors
        val plan = if (colorMappings.isNotEmpty()) {
            log("Settings SemWT mapping: ${colorMappings.size} colours (of ${mapping.size} mapped resources)")
            Plan(colorMappings, emptyList(), drawables, probe)
        } else {
            log("No Settings SemWT mapping saved; falling back to arsc names containing \"dashboard\" or \"icon\". Re-run the scan to get Samsung's mapping.")
            val colors = arscColors
                .filter { it.contains("dashboard", ignoreCase = true) || it.contains("icon", ignoreCase = true) }
                .sorted()
                .take(MAX_COLORS)
            colors.forEach { log("  color/$it") }
            Plan(emptyList(), colors, drawables, probe)
        }
        log("Picked ${drawables.size} drawables" + if (probe) "; $PROBE_COLOR is present" else "; $PROBE_COLOR not found")
        drawables.forEach { log("  drawable/$it") }
        if (plan.mapping.isEmpty() && plan.colors.isEmpty() && plan.drawables.isEmpty() && !plan.probe) {
            log("No matching resource names; nothing to apply. Run the device scan first.")
            return null
        }
        return plan
    }

    /** `files/scan/semwt/com.android.settings.json` as [MappedResource]s; empty when missing or unreadable. */
    private fun readSemWtMapping(log: (String) -> Unit): List<MappedResource> {
        val file = File(File(File(appContext.filesDir, "scan"), "semwt"), "$TARGET.json")
        if (!file.isFile) return emptyList()
        return runCatching {
            val entries = JSONObject(file.readText()).optJSONArray("entries") ?: JSONArray()
            (0 until entries.length()).mapNotNull { i ->
                val o = entries.getJSONObject(i)
                val typeAndName = o.optString("name")
                if ('/' !in typeAndName) return@mapNotNull null
                MappedResource(
                    resId = o.optString("resId").removePrefix("0x").toLongOrNull(16)?.toInt() ?: 0,
                    type = typeAndName.substringBefore('/'),
                    name = typeAndName.substringAfter('/'),
                    defaultValue = o.optString("default"),
                    nightValue = if (o.isNull("night")) null else o.optString("night").ifEmpty { null },
                    kind = o.optString("kind", MappedResource.KIND_RAW),
                )
            }
        }.getOrElse {
            log("Could not read ${file.path}: ${it.message}")
            emptyList()
        }
    }

    fun run(service: IThemeService, accent: Int, log: (String) -> Unit): BuildResult? {
        val plan = plan(log) ?: return null
        val argb = accent or 0xFF000000.toInt()
        val png = roundedSquarePng(argb)
        val serviceUid = runCatching { service.uid }.getOrDefault(-1)
        val privileged = serviceUid == 0 || serviceUid == 1000
        if (!privileged && plan.drawables.isNotEmpty()) {
            log(
                "Service uid $serviceUid cannot commit FabricatedOverlay drawables or night colours (SU mode or shell). " +
                    "Applying the day colours only through `cmd overlay fabricate`."
            )
        }
        val colors = if (plan.fromSemWt) {
            val reference = SnapshotBuilder.loadSystemShades(appContext) ?: SnapshotBuilder.readSystemShades(appContext)
            if (reference.isEmpty()) log("No system shades available: every colour falls back to the accent.")
            AccentRecolor.recolor(
                targetPackage = TARGET,
                mapping = plan.mapping,
                reference = reference,
                seedArgb = argb,
                includeNight = privileged,
                forceSeed = if (plan.probe) listOf(PROBE_COLOR) else emptyList(),
            ).map { OverlayColor(it.resourceName, it.argb, it.configuration) }
        } else {
            (plan.colors.map { "$TARGET:color/$it" } + if (plan.probe) listOf("$TARGET:$PROBE_COLOR") else emptyList())
                .distinct()
                .map { OverlayColor(it, argb) }
        }
        val request = BuildRequest(
            target = TARGET,
            colors = colors,
            drawables = if (privileged) {
                plan.drawables.map { OverlayDrawable("$TARGET:drawable/$it", DrawableSpec(null, png)) }
            } else {
                emptyList()
            },
        )
        if (request.colors.isEmpty() && request.drawables.isEmpty()) {
            log("Nothing left to apply.")
            return null
        }
        log("Applying ${request.colors.size} colour values (${request.colors.count { it.config.isNotEmpty() }} night) and ${request.drawables.size} drawables")

        ApplyGuard(appContext).recordApply()
        log("Apply recorded for the boot-loop guard. Tap \"It works\" once Settings looks right.")

        val monitor = service.startStabilityMonitor(MONITOR_SECONDS)
        log("Stability monitor: ${monitor.getString(ThemeService.KEY_OUTPUT).orEmpty()}")

        val buildLog = BuildLog(appContext)
        val probeResource = "$TARGET:$PROBE_COLOR"
        val before = if (plan.probe) lookup(service, probeResource) else ""
        if (plan.probe) {
            buildLog.write("INFO", TARGET, "probe lookup before", mapOf("resource" to probeResource, "value" to before))
            log("Lookup before ($PROBE_COLOR): ${before.ifBlank { "(empty)" }}")
        }

        val result = OverlayBuilder(appContext).apply(service, request, buildLog, log)

        if (!plan.probe) {
            log("Read-back (${request.colors.firstOrNull()?.name ?: request.drawables.firstOrNull()?.name}): ${result.lookup.ifBlank { "(empty)" }}")
            log("Build log: ${result.logFile.path}")
            return result
        }
        val after = lookup(service, probeResource)
        val expected = "#%08x".format(argb)
        val matched = OverlayLookup.matches(after, argb)
        buildLog.write(
            if (matched == true) "INFO" else "WARN", TARGET, "probe lookup after",
            mapOf("resource" to probeResource, "value" to after, "expected" to expected, "matches" to matched),
        )
        val summary = "$PROBE_COLOR: before ${before.ifBlank { "(empty)" }} -> after ${after.ifBlank { "(empty)" }} " +
            "(expected $expected, ${if (matched == true) "match" else "MISMATCH"})"
        log("Lookup after: $summary")
        log("Build log: ${result.logFile.path}")
        return result.copy(lookup = summary)
    }

    private fun lookup(service: IThemeService, resource: String): String =
        runCatching { service.exec("cmd overlay lookup --user current $TARGET $resource").getString(ThemeService.KEY_OUTPUT).orEmpty().trim() }
            .getOrElse { "lookup failed: ${it.message}" }
            .lineSequence().firstOrNull().orEmpty()

    /** 138x138 rounded square filled with [argb], PNG-encoded. */
    fun roundedSquarePng(argb: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = argb
                style = Paint.Style.FILL
            }
            val radius = ICON_PX * 0.28f
            canvas.drawRoundRect(RectF(0f, 0f, ICON_PX.toFloat(), ICON_PX.toFloat()), radius, radius, paint)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    companion object {
        const val TARGET = "com.android.settings"
        const val MONITOR_SECONDS = 120
        private const val MAX_COLORS = 10

        /** Colour the shell probe proved visible; always set to the plain accent when present. */
        const val PROBE_COLOR = "color/sec_widget_round_and_bgcolor"
        private const val MAX_DRAWABLES = 3
        private const val ICON_PX = 138
    }
}
