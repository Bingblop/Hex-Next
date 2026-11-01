package com.hexnext.themer.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.hexnext.themer.IThemeService
import com.hexnext.themer.device.ArscReader
import com.hexnext.themer.device.DeviceScanner
import com.hexnext.themer.safety.ApplyGuard
import com.hexnext.themer.shizuku.ThemeService
import java.io.ByteArrayOutputStream

/**
 * First real One UI 9 test: one `HexNext_com_android_settings` fabricated overlay with up to 10
 * accent colours and 3 accent-filled PNG icons, chosen from the names in the Settings
 * `resources.arsc` copied by the last device scan.
 *
 * Flow: [ApplyGuard.recordApply] -> `startStabilityMonitor(120)` -> [OverlayBuilder.apply] ->
 * log the `cmd overlay lookup` read-back.
 */
class SettingsTestOverlay(context: Context) {

    private val appContext = context.applicationContext

    data class Plan(val colors: List<String>, val drawables: List<String>)

    /** Picks resource names from the scanned arsc, or explains why it cannot. */
    fun plan(log: (String) -> Unit): Plan? {
        val arsc = DeviceScanner.arscFile(appContext, TARGET)
        if (!arsc.isFile) {
            log("No scan of $TARGET yet (${arsc.path} missing). Run the device scan first.")
            return null
        }
        val parsed = runCatching { ArscReader.read(arsc) }.getOrElse {
            log("Could not read ${arsc.path}: ${it.message}")
            return null
        }
        parsed.warnings.forEach { log("arsc warning: $it") }
        val colors = parsed.ofType("color")
            .map { it.name }
            .distinct()
            .filter { it.contains("dashboard", ignoreCase = true) || it.contains("icon", ignoreCase = true) }
            .sorted()
            .take(MAX_COLORS)
        val drawables = parsed.ofType("drawable")
            .map { it.name }
            .distinct()
            .filter { it.startsWith("ic_settings") || it.startsWith("sec_ic_settings") }
            .sorted()
            .take(MAX_DRAWABLES)
        log(
            "Settings arsc: ${parsed.countOf("color")} colours, ${parsed.countOf("drawable")} drawables; " +
                "picked ${colors.size} colours and ${drawables.size} drawables"
        )
        colors.forEach { log("  color/$it") }
        drawables.forEach { log("  drawable/$it") }
        if (colors.isEmpty() && drawables.isEmpty()) {
            log("No matching resource names; nothing to apply.")
            return null
        }
        return Plan(colors, drawables)
    }

    fun run(service: IThemeService, accent: Int, log: (String) -> Unit): BuildResult? {
        val plan = plan(log) ?: return null
        val argb = accent or 0xFF000000.toInt()
        val png = roundedSquarePng(argb)
        val serviceUid = runCatching { service.uid }.getOrDefault(-1)
        val drawablesAllowed = serviceUid == 0 || serviceUid == 1000
        if (!drawablesAllowed && plan.drawables.isNotEmpty()) {
            log(
                "Service uid $serviceUid cannot commit FabricatedOverlay drawables (SU mode or shell). " +
                    "Applying the ${plan.colors.size} colours only through `cmd overlay fabricate`."
            )
        }
        val request = BuildRequest(
            target = TARGET,
            colors = plan.colors.associate { "$TARGET:color/$it" to argb },
            drawables = if (drawablesAllowed) {
                plan.drawables.associate { "$TARGET:drawable/$it" to DrawableSpec(null, png) }
            } else {
                emptyMap()
            },
        )
        if (request.colors.isEmpty() && request.drawables.isEmpty()) {
            log("Nothing left to apply.")
            return null
        }

        ApplyGuard(appContext).recordApply()
        log("Apply recorded for the boot-loop guard. Tap \"It works\" once Settings looks right.")

        val monitor = service.startStabilityMonitor(MONITOR_SECONDS)
        log("Stability monitor: ${monitor.getString(ThemeService.KEY_OUTPUT).orEmpty()}")

        val result = OverlayBuilder(appContext).apply(service, request, log)
        log("Read-back (${request.colors.keys.firstOrNull() ?: request.drawables.keys.firstOrNull()}): ${result.lookup.ifBlank { "(empty)" }}")
        log("Build log: ${result.logFile.path}")
        return result
    }

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
        private const val MAX_DRAWABLES = 3
        private const val ICON_PX = 138
    }
}
