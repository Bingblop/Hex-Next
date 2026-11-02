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
            // Paths that were actually painted: a path whose fill and stroke are both unreadable
            // (or "none") draws nothing, and a glyph of only such paths would be a blank icon.
            var painted = 0
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
                if (fill != null || stroke != null) painted++
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
            if (painted == 0) throw AiProviderException("The glyph's colours could not be read, so it would be blank. Try again.")
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

    /**
     * `#RRGGBB` / `#RRGGBBAA` / `#RGB` / `#RGBA` or a CSS colour name (`white`) → ARGB (see
     * [parseCssHex]); key missing → [default]; `"currentColor"` → the default foreground blue.
     * `null`, `"none"`, `"transparent"` and anything else that cannot be read → null (not
     * painted), so an outline answered with `"fill":null` or `"fill":"transparent"` stays an
     * outline instead of turning into a solid blue shape.
     */
    private fun colorOf(element: kotlinx.serialization.json.JsonElement?, default: Int?): Int? {
        if (element == null) return default
        val text = (element as? JsonPrimitive)?.contentOrNull()?.trim() ?: return null
        if (text.isEmpty() || text.equals("none", ignoreCase = true) || text.equals("transparent", ignoreCase = true)) return null
        if (text.equals("currentColor", ignoreCase = true)) return DEFAULT_FILL
        parseCssHex(text)?.let { return it }
        // Named colours: the full CSS table, since Color.parseColor knows only about 20 names
        // and throws for orange, pink, gold, brown and the rest.
        return CSS_NAMED_COLORS[text.lowercase()]
    }

    /** The CSS Color Module named colours (148, grey spellings included) as opaque ARGB. */
    private val CSS_NAMED_COLORS: Map<String, Int> = """
        aliceblue F0F8FF antiquewhite FAEBD7 aqua 00FFFF aquamarine 7FFFD4 azure F0FFFF
        beige F5F5DC bisque FFE4C4 black 000000 blanchedalmond FFEBCD blue 0000FF blueviolet 8A2BE2
        brown A52A2A burlywood DEB887 cadetblue 5F9EA0 chartreuse 7FFF00 chocolate D2691E
        coral FF7F50 cornflowerblue 6495ED cornsilk FFF8DC crimson DC143C cyan 00FFFF
        darkblue 00008B darkcyan 008B8B darkgoldenrod B8860B darkgray A9A9A9 darkgreen 006400
        darkgrey A9A9A9 darkkhaki BDB76B darkmagenta 8B008B darkolivegreen 556B2F darkorange FF8C00
        darkorchid 9932CC darkred 8B0000 darksalmon E9967A darkseagreen 8FBC8F darkslateblue 483D8B
        darkslategray 2F4F4F darkslategrey 2F4F4F darkturquoise 00CED1 darkviolet 9400D3
        deeppink FF1493 deepskyblue 00BFFF dimgray 696969 dimgrey 696969 dodgerblue 1E90FF
        firebrick B22222 floralwhite FFFAF0 forestgreen 228B22 fuchsia FF00FF gainsboro DCDCDC
        ghostwhite F8F8FF gold FFD700 goldenrod DAA520 gray 808080 green 008000 greenyellow ADFF2F
        grey 808080 honeydew F0FFF0 hotpink FF69B4 indianred CD5C5C indigo 4B0082 ivory FFFFF0
        khaki F0E68C lavender E6E6FA lavenderblush FFF0F5 lawngreen 7CFC00 lemonchiffon FFFACD
        lightblue ADD8E6 lightcoral F08080 lightcyan E0FFFF lightgoldenrodyellow FAFAD2
        lightgray D3D3D3 lightgreen 90EE90 lightgrey D3D3D3 lightpink FFB6C1 lightsalmon FFA07A
        lightseagreen 20B2AA lightskyblue 87CEFA lightslategray 778899 lightslategrey 778899
        lightsteelblue B0C4DE lightyellow FFFFE0 lime 00FF00 limegreen 32CD32 linen FAF0E6
        magenta FF00FF maroon 800000 mediumaquamarine 66CDAA mediumblue 0000CD mediumorchid BA55D3
        mediumpurple 9370DB mediumseagreen 3CB371 mediumslateblue 7B68EE mediumspringgreen 00FA9A
        mediumturquoise 48D1CC mediumvioletred C71585 midnightblue 191970 mintcream F5FFFA
        mistyrose FFE4E1 moccasin FFE4B5 navajowhite FFDEAD navy 000080 oldlace FDF5E6 olive 808000
        olivedrab 6B8E23 orange FFA500 orangered FF4500 orchid DA70D6 palegoldenrod EEE8AA
        palegreen 98FB98 paleturquoise AFEEEE palevioletred DB7093 papayawhip FFEFD5
        peachpuff FFDAB9 peru CD853F pink FFC0CB plum DDA0DD powderblue B0E0E6 purple 800080
        rebeccapurple 663399 red FF0000 rosybrown BC8F8F royalblue 4169E1 saddlebrown 8B4513
        salmon FA8072 sandybrown F4A460 seagreen 2E8B57 seashell FFF5EE sienna A0522D silver C0C0C0
        skyblue 87CEEB slateblue 6A5ACD slategray 708090 slategrey 708090 snow FFFAFA
        springgreen 00FF7F steelblue 4682B4 tan D2B48C teal 008080 thistle D8BFD8 tomato FF6347
        turquoise 40E0D0 violet EE82EE wheat F5DEB3 white FFFFFF whitesmoke F5F5F5 yellow FFFF00
        yellowgreen 9ACD32
    """.trim().split(Regex("\\s+")).chunked(2).associate { (name, hex) ->
        name to (0xFF000000.toInt() or hex.toInt(16))
    }

    /**
     * A hex colour as models write it in SVG and CSS: `#RRGGBB`, `#RGB`, and with alpha last,
     * `#RRGGBBAA` / `#RGBA` (so `#FFFFFF66` is white at 40 %, not opaque pale yellow) → ARGB;
     * null when it is not a hex colour. Leading `#` optional.
     */
    internal fun parseCssHex(text: String): Int? {
        val hex = text.trim().removePrefix("#")
        val argb = when (hex.length) {
            8 -> hex.substring(6) + hex.substring(0, 6)
            4 -> hex.substring(3) + hex.substring(0, 3)
            else -> hex
        }
        return Pack.parseColor(argb)
    }

    private const val DEFAULT_FILL: Int = 0xFF3E91FF.toInt()
}
