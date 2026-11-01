package com.hexnext.themer.overlay

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.hexnext.themer.IThemeService
import com.hexnext.themer.shizuku.ThemeService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One PNG drawable for an overlay. [config] is a resource qualifier such as `xxhdpi`, or null for default. */
class DrawableSpec(val config: String?, val png: ByteArray, val ninePatch: Boolean = false)

/** Everything one `HexNext_<target>` fabricated overlay holds. Keys are `pkg:type/name`. */
data class BuildRequest(
    val target: String,
    val colors: Map<String, Int>,
    val drawables: Map<String, DrawableSpec>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("target", target)
        put("colors", JSONObject().apply { colors.forEach { (k, v) -> put(k, v) } })
        put("drawables", JSONObject().apply {
            drawables.forEach { (k, d) ->
                put(
                    k,
                    JSONObject()
                        .put("config", d.config ?: "")
                        .put("ninePatch", d.ninePatch)
                        .put("png", Base64.encodeToString(d.png, Base64.NO_WRAP))
                )
            }
        })
    }

    companion object {
        fun fromJson(json: JSONObject): BuildRequest {
            val colors = LinkedHashMap<String, Int>()
            json.optJSONObject("colors")?.let { c -> c.keys().forEach { k -> colors[k] = c.getInt(k) } }
            val drawables = LinkedHashMap<String, DrawableSpec>()
            json.optJSONObject("drawables")?.let { d ->
                d.keys().forEach { k ->
                    val o = d.getJSONObject(k)
                    drawables[k] = DrawableSpec(
                        config = o.optString("config").ifBlank { null },
                        png = Base64.decode(o.getString("png"), Base64.NO_WRAP),
                        ninePatch = o.optBoolean("ninePatch"),
                    )
                }
            }
            return BuildRequest(json.getString("target"), colors, drawables)
        }
    }
}

data class BuildResult(
    val code: Int,
    val output: String,
    val resourceCount: Int,
    /** JSON array of {name, reason}. */
    val skipped: String,
    val millis: Long,
    /** `cmd overlay lookup` read-back of the first resource. */
    val lookup: String,
    val logFile: File,
) {
    val ok: Boolean get() = code == 0
    val skippedCount: Int get() = runCatching { JSONArray(skipped).length() }.getOrDefault(0)
}

/** JSON-lines build log: `files/logs/<yyyyMMdd-HHmmss>.jsonl`, one object per line. */
internal class BuildLog(context: Context) {

    val file: File = File(File(context.filesDir, "logs").apply { mkdirs() }, "${stamp()}.jsonl")

    fun write(level: String, target: String, message: String, data: Map<String, Any?> = emptyMap()) {
        val line = JSONObject()
            .put("timestamp", System.currentTimeMillis())
            .put("level", level)
            .put("target", target)
            .put("message", message)
            .put("data", JSONObject().apply { data.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } })
        runCatching { file.appendText(line.toString() + "\n") }
    }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}

/**
 * Builds and applies one fabricated overlay per target: writes the PNGs to `cacheDir`, opens
 * read-only descriptors, calls [IThemeService.applyOverlay], closes them, logs to
 * `files/logs/<yyyyMMdd-HHmmss>.jsonl` and saves the request in `files/requests/<target>.json` for [repair].
 */
class OverlayBuilder(context: Context) {

    private val appContext = context.applicationContext

    fun apply(service: IThemeService, request: BuildRequest, log: (String) -> Unit = {}): BuildResult {
        val buildLog = BuildLog(appContext)
        val name = overlayName(request.target)
        val started = System.currentTimeMillis()
        buildLog.write(
            "INFO", request.target, "build started",
            mapOf("overlay" to name, "colors" to request.colors.size, "drawables" to request.drawables.size)
        )
        log("Building $name: ${request.colors.size} colours, ${request.drawables.size} drawables")
        saveRequest(request)

        val pngDir = File(File(appContext.cacheDir, "overlay"), request.target).apply {
            deleteRecursively()
            mkdirs()
        }
        val drawableNames = ArrayList<String>()
        val configs = ArrayList<String>()
        val nine = ArrayList<Boolean>()
        val fds = ArrayList<ParcelFileDescriptor>()
        try {
            request.drawables.entries.forEachIndexed { i, (res, spec) ->
                val file = File(pngDir, "$i.png")
                file.writeBytes(spec.png)
                fds.add(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
                drawableNames.add(res)
                configs.add(spec.config.orEmpty())
                nine.add(spec.ninePatch)
            }
            val bundle = service.applyOverlay(
                name,
                request.target,
                request.colors.keys.toTypedArray(),
                request.colors.values.toIntArray(),
                drawableNames.toTypedArray(),
                configs.toTypedArray(),
                fds.toTypedArray(),
                nine.toBooleanArray(),
            )
            val result = toResult(bundle, buildLog.file, System.currentTimeMillis() - started)
            buildLog.write(
                if (result.ok) "OK" else "ERROR", request.target, "applyOverlay finished",
                mapOf(
                    "code" to result.code,
                    "resourceCount" to result.resourceCount,
                    "skipped" to runCatching { JSONArray(result.skipped) }.getOrDefault(JSONArray()),
                    "serviceMillis" to result.millis,
                    "lookup" to result.lookup,
                    "output" to result.output,
                )
            )
            log("applyOverlay code=${result.code}, ${result.resourceCount} resources, ${result.skippedCount} skipped, ${result.millis} ms")
            result.output.lines().filter { it.isNotBlank() }.forEach { log("  $it") }
            if (result.lookup.isNotBlank()) log("Lookup: ${result.lookup}")
            return result
        } catch (t: Throwable) {
            val message = "${t.javaClass.simpleName}: ${t.message}"
            buildLog.write("ERROR", request.target, "applyOverlay threw", mapOf("error" to message))
            log("applyOverlay failed: $message")
            return BuildResult(-1, message, 0, "[]", System.currentTimeMillis() - started, "", buildLog.file)
        } finally {
            fds.forEach { runCatching { it.close() } }
            pngDir.deleteRecursively()
        }
    }

    /**
     * Disables (and when privileged unregisters) `HexNext_<target>`, purges its idmap/frro cache
     * entries, then re-applies the last saved request for that target.
     */
    fun repair(service: IThemeService, target: String, log: (String) -> Unit = {}): BuildResult? {
        val buildLog = BuildLog(appContext)
        val name = overlayName(target)
        val removed = service.removeOverlay(name)
        log("Remove $name: ${removed.output()}")
        val purge = service.exec("rm -f /data/resource-cache/*$name*")
        log("Purged /data/resource-cache/*$name* (code ${purge.code()})")
        buildLog.write(
            "INFO", target, "repair: removed and purged",
            mapOf("remove" to removed.output(), "purgeCode" to purge.code())
        )
        val request = loadRequest(target)
        if (request == null) {
            log("No saved request for $target; nothing to re-apply.")
            buildLog.write("WARN", target, "repair: no saved request")
            return null
        }
        return apply(service, request, log)
    }

    fun loadRequest(target: String): BuildRequest? = runCatching {
        val file = requestFile(target)
        if (!file.isFile) null else BuildRequest.fromJson(JSONObject(file.readText()))
    }.getOrNull()

    private fun saveRequest(request: BuildRequest) {
        runCatching { requestFile(request.target).writeText(request.toJson().toString()) }
    }

    private fun requestFile(target: String): File =
        File(File(appContext.filesDir, "requests").apply { mkdirs() }, "$target.json")

    private fun toResult(bundle: Bundle, logFile: File, fallbackMillis: Long) = BuildResult(
        code = bundle.code(),
        output = bundle.output(),
        resourceCount = bundle.getInt(ThemeService.KEY_RESOURCE_COUNT, 0),
        skipped = bundle.getString(ThemeService.KEY_SKIPPED) ?: "[]",
        millis = bundle.getLong(ThemeService.KEY_MILLIS, fallbackMillis),
        lookup = bundle.getString(ThemeService.KEY_LOOKUP).orEmpty(),
        logFile = logFile,
    )

    private fun Bundle.code() = getInt(ThemeService.KEY_CODE, -1)
    private fun Bundle.output() = getString(ThemeService.KEY_OUTPUT).orEmpty()

    companion object {
        /** `HexNext_<target>` with dots replaced, e.g. `HexNext_com_android_settings`. */
        fun overlayName(target: String): String = "HexNext_" + target.replace('.', '_')
    }
}
