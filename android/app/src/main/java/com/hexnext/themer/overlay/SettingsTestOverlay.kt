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
            runCatching { ArscReader.read(arsc, ArscReader.THEME_TYPES) }.onFailure { log("Could not read ${arsc.path}: ${it.message}") }.getOrNull()
        } else {
            log("No arsc of $TARGET yet (${arsc.path} missing).")
            null
        }
        parsed?.warnings?.forEach { log("arsc warning: $it") }
        val arscColors = parsed?.ofType("color")?.map { it.name }?.distinct().orEmpty()
        // AAPT's generated sub-drawables ($name__0) are not valid overlay resource names.
        val allDrawables = parsed?.ofType("drawable")?.map { it.name }?.distinct()?.sorted().orEmpty()
            .filterNot { it.startsWith('$') }
        // Settings logs the icon of every dashboard row at start ("TopLevelSettings: {"icon":
        // "com.android.settings:drawable/sec_ic_settings_connections", ...}"): the rows are the
        // legacy Hex `sec_ic_settings_*` drawables (22 rows on One UI 9.0, see
        // docs/device/oneui9-sm-s948u1/build53-icons-probe-*.txt). Those come first, then the
        // `ic_<menu>_<symbol>` glyphs build 53 proved visible (ic_apps, ic_advanced_features_plus_gearshape,
        // ic_accounts_and_backup_…), then anything else named like a dashboard icon.
        val rows = allDrawables.filter { it in DASHBOARD_ROW_ICONS }
        val glyphs = allDrawables.filter { it !in rows && (it.startsWith("sec_ic_settings_") || DASHBOARD_ROW_ICON.containsMatchIn(it)) }
        val dashboard = allDrawables.filter { it !in rows && it !in glyphs && DASHBOARD_ICON.containsMatchIn(it) }
        val drawables = (rows + glyphs + dashboard).distinct().take(MAX_DRAWABLES)
        log("Settings has ${allDrawables.size} drawables: ${rows.size}/${DASHBOARD_ROW_ICONS.size} dashboard-row icons, " +
            "${glyphs.size} menu glyphs, ${dashboard.size} other dashboard-like " +
            "(full list: ${File(arsc.parentFile, "names-drawable.txt").path})")

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
        if (!privileged) {
            // `cmd overlay fabricate` takes one resource per call and replaces the overlay each time,
            // so there is no shell fallback for a multi-colour overlay.
            log(
                "The theme service runs as uid $serviceUid, but committing an overlay needs uid 0 or 1000. " +
                    "Start Shizuku with root (or restart it after a reboot) and run the test again."
            )
            return null
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
        private const val MAX_DRAWABLES = 80

        /**
         * The icon Settings loads for each top-level row on One UI 9.0 (logged by TopLevelSettings /
         * [GS]PolicyManager at start), in dashboard order. Hidden rows (Battery, Connected devices,
         * Safety and emergency, User guide) are included: they show on other devices or after setup.
         */
        val DASHBOARD_ROW_ICONS: List<String> = listOf(
            "sec_ic_settings_cloud_and_account",       // Accounts and backup
            "sec_ic_settings_google",                  // Google services
            "sec_ic_settings_connections",             // Connections
            "sec_ic_settings_devicecare",              // Device care
            "sec_ic_settings_applications",            // Apps
            "sec_ic_settings_display",                 // Display
            "sec_ic_settings_advanced_feature",        // Advanced features
            "sec_ic_settings_wallpaper",               // Wallpaper and style
            "sec_ic_settings_general_device_management", // General management
            "sec_ic_settings_battery",                 // Battery (hidden on SM-S948U1)
            "sec_ic_settings_galaxy_ai",               // Galaxy AI
            "sec_ic_settings_sound",                   // Sounds and vibration
            "sec_ic_settings_notifications",           // Notifications
            "sec_ic_settings_themes",                  // Themes
            "sec_ic_settings_biometrics_and_security", // Security and privacy
            "sec_ic_settings_lockscreen",              // Lock screen and AOD
            "sec_ic_settings_accessibility",           // Accessibility
            "sec_ic_settings_location",                // Location
            "sec_ic_settings_multi_devices",           // Connected devices (hidden)
            "sec_ic_settings_emergency",               // Safety and emergency (hidden)
            "sec_ic_settings_user_manual",             // User guide (hidden)
            "sec_ic_settings_about",                   // About phone
            "sec_ic_settings_digitalwellbeing",        // Digital Wellbeing (row seen in the default screenshot)
            "sec_ic_settings_homescreen",              // Home screen
            "sec_ic_settings_modes",                   // Modes and Routines
            "sec_ic_settings_software_update",         // Software update
            "sec_ic_settings_tips",                    // Tips and user manual
            "sec_ic_settings_parental_controls",       // Parental controls
            "sec_ic_settings_privacy",                 // Privacy
        )

        /**
         * One UI 9 dashboard-row icons: `ic_<menu>_<symbol>` (or plain `ic_<menu>`), proven visible on
         * device for apps, advanced_features and accounts_and_backup.
         */
        private val DASHBOARD_ROW_ICON = Regex(
            "^ic_(connections|connected_devices|modes_and_routines|modes|sounds_and_vibration|sounds|sound|notifications|display|" +
                "wallpaper_and_style|wallpaper|themes|home_screen|lock_screen_and_aod|lock_screen|security_and_privacy|security|privacy|" +
                "location|safety_and_emergency|safety|accounts_and_backup|google|google_services|advanced_features|" +
                "digital_wellbeing_and_parental_controls|digital_wellbeing|device_care|battery_and_device_care|apps|general_management|" +
                "accessibility|software_update|about_phone|galaxy_ai|samsung_account|tips_and_user_manual|tips|battery)(_|$)"
        )

        /**
         * Drawables that are an icon (`ic`/`icon` as a name part) of a dashboard entry. Covers the
         * legacy `sec_ic_settings_connections` style and newer `ic_dashboard_*`/`*_dashboard_ic` styles.
         */
        private val DASHBOARD_ICON = Regex(
            "(^|_)(ic|icon)(_|$).*(connections|display|notifications|notification|sound|wallpaper|apps|application|battery|" +
                "devicecare|device_care|general_device|general_management|account|google|galaxy_ai|themes|theme|" +
                "advanced|security|privacy|location|accessibility|software_update|about|biometrics|lockscreen|lock_screen|" +
                "homescreen|home_screen|digital_wellbeing|digitalwellbeing|modes|multi_devices|connected_devices|tips|" +
                "emergency|help|dashboard)"
        )
        private const val ICON_PX = 138
    }
}
