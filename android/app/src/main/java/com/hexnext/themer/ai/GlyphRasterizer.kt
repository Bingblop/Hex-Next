package com.hexnext.themer.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.PathParser
import com.hexnext.engine.pack.Pack
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream

/**
 * Turns the constrained glyph JSON text models answer with into an ARGB PNG:
 *
 * ```
 * {"viewBox":"0 0 96 96","paths":[{"d":"M12 48 L48 12 ...","fill":"#3E91FF","stroke":"#FFFFFF","strokeWidth":4}]}
 * ```
 *
 * At most [MAX_PATHS] paths are drawn, in order, scaled from the view box to [IconRequest.sizePx]
 * with antialiasing. Malformed JSON or path data raises [AiProviderException] with a message fit
 * for the screen.
 */
object GlyphRasterizer {
    const val MAX_PATHS = 12

    /** Longest `d` string handed to the path parser; a real glyph path is a few hundred characters. */
    const val MAX_PATH_DATA_CHARS = 4_096

    /** Most path commands (letters) in one `d` string. */
    private const val MAX_PATH_COMMANDS = 512

    /** The system prompt every vector provider receives. */
    val SYSTEM_PROMPT: String = """
        You design app icon glyphs as vector paths. Reply with ONLY a JSON object, no prose, no code fences:
        {"viewBox":"0 0 96 96","paths":[{"d":"<SVG path data using absolute commands M L H V C S Q T A Z only>","fill":"#RRGGBB","stroke":"#RRGGBB","strokeWidth":4}]}
        Rules: at most $MAX_PATHS paths; "stroke" and "strokeWidth" are optional; use "none" for a fill when a path is stroke-only.
        Keep the drawing centred in the view box with 8 units of padding on every side, one subject, no text, no background rectangle unless the style asks for a flat background.
        Prefer a few bold shapes over many small ones. Coordinates are plain numbers, no units.
    """.trimIndent()

    private val SPLIT = Regex("[\\s,]+")

    /** Rasterises [glyphJson] at [sizePx]; see the class docs for the format. */
    fun rasterize(glyphJson: String, sizePx: Int): ByteArray {
        val size = sizePx.coerceIn(16, 2048)
        val root = parseRoot(glyphJson)
        val viewBox = parseViewBox(root)
        val paths = root.array("paths") ?: throw AiProviderException("The glyph has no paths.")
        if (paths.isEmpty()) throw AiProviderException("The glyph has no paths.")

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val scale = size / maxOf(viewBox.width(), viewBox.height())
            val matrix = Matrix().apply {
                // Fit the view box into the square, centred, keeping its aspect ratio.
                postTranslate(-viewBox.left, -viewBox.top)
                postScale(scale, scale)
                postTranslate((size - viewBox.width() * scale) / 2f, (size - viewBox.height() * scale) / 2f)
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            var drawn = 0
            for (element in paths) {
                if (drawn >= MAX_PATHS) break
                val entry = element as? JsonObject ?: continue
                val data = entry.string("d")?.trim().orEmpty()
                if (data.isEmpty()) continue
                if (data.length > MAX_PATH_DATA_CHARS || data.count { it.isLetter() } > MAX_PATH_COMMANDS) {
                    throw AiProviderException("A glyph path is too long. Try a simpler prompt.")
                }
                val path = parsePath(data)
                path.transform(matrix)
                val fill = colorOf(entry["fill"], default = DEFAULT_FILL)
                val stroke = colorOf(entry["stroke"], default = null)
                val strokeWidth = (entry["strokeWidth"] as? JsonPrimitive)?.content?.toFloatOrNull()
                if (fill != null) {
                    paint.style = Paint.Style.FILL
                    paint.color = fill
                    canvas.drawPath(path, paint)
                }
                if (stroke != null) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = ((strokeWidth ?: 2f).coerceIn(0.25f, 32f)) * scale
                    paint.strokeCap = Paint.Cap.ROUND
                    paint.strokeJoin = Paint.Join.ROUND
                    paint.color = stroke
                    canvas.drawPath(path, paint)
                }
                drawn++
            }
            if (drawn == 0) throw AiProviderException("The glyph had no drawable paths.")
            val out = ByteArrayOutputStream(size * size / 2)
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw AiProviderException("The glyph could not be encoded as PNG.")
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Finds the JSON object in a model reply: strips code fences and anything before the first
     * `{` or after the last `}`.
     */
    fun extractJson(reply: String): String {
        val cleaned = reply.replace("```json", "").replace("```", "").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) throw AiProviderException("The model did not answer with a glyph. Try a simpler prompt.")
        return cleaned.substring(start, end + 1)
    }

    private fun parseRoot(text: String): JsonObject = try {
        AiHttp.json.parseToJsonElement(extractJson(text)) as? JsonObject
            ?: throw AiProviderException("The glyph is not a JSON object.")
    } catch (e: AiProviderException) {
        throw e
    } catch (e: Exception) {
        throw AiProviderException("The model answered with malformed glyph JSON. Try again.", e)
    }

    private fun parseViewBox(root: JsonObject): RectF {
        val text = root.string("viewBox")?.trim().orEmpty()
        val parts = if (text.isEmpty()) emptyList() else text.split(SPLIT).mapNotNull { it.toFloatOrNull() }
        if (parts.size != 4 || parts[2] <= 0f || parts[3] <= 0f) {
            return RectF(0f, 0f, 96f, 96f)
        }
        return RectF(parts[0], parts[1], parts[0] + parts[2], parts[1] + parts[3])
    }

    private fun parsePath(data: String): Path = try {
        PathParser.createPathFromPathData(data) ?: throw AiProviderException("A glyph path could not be read.")
    } catch (e: AiProviderException) {
        throw e
    } catch (e: Exception) {
        throw AiProviderException("A glyph path uses commands the app cannot draw. Try again.", e)
    }

    /** `#RRGGBB` / `#AARRGGBB` → ARGB; `"none"`, missing or invalid → [default]. */
    private fun colorOf(element: kotlinx.serialization.json.JsonElement?, default: Int?): Int? {
        val text = (element as? JsonPrimitive)?.contentOrNull()?.trim() ?: return default
        if (text.equals("none", ignoreCase = true) || text.isEmpty()) return null
        return Pack.parseColor(text) ?: default
    }

    private const val DEFAULT_FILL: Int = 0xFF3E91FF.toInt()
}
