package com.hexnext.themer.ai

import com.hexnext.engine.compile.ThemeSpec
import com.hexnext.engine.pack.Pack
import com.hexnext.themer.theme.InstalledPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A theme proposed from a description ("warm sunset, cosy, dark").
 *
 * @property seedArgb opaque accent seed.
 * @property accents up to six further opaque colours, most important first.
 * @property mode `light`, `dark` or `system`.
 * @property packStyle one of [ThemeSuggester.PACK_STYLES].
 */
data class ThemeSuggestion(
    val name: String,
    val seedArgb: Int,
    val accents: List<Int>,
    val mode: String,
    val packStyle: String,
    val notes: String,
) {
    /** True for `dark`. */
    val isDark: Boolean get() = mode == "dark"

    /** True for `light`. */
    val isLight: Boolean get() = mode == "light"
}

/**
 * "Describe your theme": asks a [TextProvider] for a palette and maps the answer onto a
 * [ThemeSpec]. Nothing here touches the phone; the caller puts the spec into the editor and the
 * user applies it as usual.
 */
object ThemeSuggester {
    val PACK_STYLES: List<String> = listOf("minimal", "playful", "glass", "retro", "neon", "nature")

    val SYSTEM_PROMPT: String = """
        You are a colour designer for Android system themes. From the user's description, answer with ONLY a JSON object, no prose, no code fences:
        {"name":"<2-4 word theme name>","seed":"#RRGGBB","accents":["#RRGGBB", ... up to 6],"mode":"light|dark|system","packStyle":"minimal|playful|glass|retro|neon|nature","notes":"<one sentence on the mood>"}
        "seed" is the main accent colour; "accents" are secondary colours that work with it, most important first. Choose "mode" from the description, "system" when it does not say. Keep colours saturated enough to read as accents on both light and dark surfaces.
    """.trimIndent()

    /** Asks [provider] to turn [description] into a suggestion. Throws [AiProviderException]. */
    suspend fun suggest(keys: AiKeyStore, provider: TextProvider, description: String): ThemeSuggestion =
        withContext(Dispatchers.IO) {
            val apiKey = keys.get(provider.id) ?: throw AiProviderException("No key for ${provider.displayName}. Add one in Settings > AI providers.")
            val text = description.trim()
            if (text.isEmpty()) throw AiProviderException("Describe the theme first.")
            parse(provider.complete(SYSTEM_PROMPT, text, apiKey))
        }

    /** Parses a model reply (fences and surrounding prose tolerated). Throws [AiProviderException]. */
    fun parse(reply: String): ThemeSuggestion {
        val root = try {
            AiHttp.json.parseToJsonElement(GlyphRasterizer.extractJson(reply)) as? JsonObject
                ?: throw AiProviderException("The model did not answer with a theme.")
        } catch (e: AiProviderException) {
            throw e
        } catch (e: Exception) {
            throw AiProviderException("The model answered with malformed theme JSON. Try again.", e)
        }
        val seed = root.string("seed")?.let(Pack::parseColor)?.let { it or OPAQUE }
            ?: throw AiProviderException("The theme has no valid seed colour.")
        val accents = (root["accents"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull()?.let(Pack::parseColor) }
            .map { it or OPAQUE }
            .filter { it != seed }
            .distinct()
            .take(MAX_ACCENTS)
        val mode = root.string("mode")?.trim()?.lowercase()?.takeIf { it in MODES } ?: "system"
        val style = root.string("packStyle")?.trim()?.lowercase()?.takeIf { it in PACK_STYLES } ?: "minimal"
        val name = root.string("name")?.trim()?.take(40)?.ifEmpty { null } ?: "Suggested theme"
        return ThemeSuggestion(name, seed, accents, mode, style, root.string("notes")?.trim()?.take(200).orEmpty())
    }

    /**
     * Maps [suggestion] onto [current]:
     * - seed → `source.seedArgb` (the caller must also push it to the seed preference, see
     *   `MainViewModel.setSeed`, because the apply path takes the seed from there);
     * - accents[0] → `source.secondaryArgb`, accents[1] → `source.tertiaryArgb`: these are the
     *   accent roles the compiler already derives every `system_accent2_*` / `system_accent3_*`
     *   shade and pack slot from, so they follow light and dark correctly; further accents are
     *   display only;
     * - mode → background lightness of the matching adjustments (dark: darker dark surfaces,
     *   light: brighter light surfaces); `system` leaves both as they are;
     * - packStyle → the installed pack whose name, features or categories match best, else the
     *   current pack;
     * - name → theme name.
     */
    fun toThemeSpec(suggestion: ThemeSuggestion, current: ThemeSpec, packs: List<InstalledPack>): ThemeSpec {
        val source = current.source.copy(
            seedArgb = suggestion.seedArgb,
            secondaryArgb = suggestion.accents.getOrNull(0),
            tertiaryArgb = suggestion.accents.getOrNull(1),
        )
        val dark = when {
            suggestion.isDark -> current.darkAdjustments.copy(backgroundLightness = 85)
            suggestion.isLight -> current.darkAdjustments.copy(backgroundLightness = 100)
            else -> current.darkAdjustments
        }
        val light = when {
            suggestion.isLight -> current.lightAdjustments.copy(backgroundLightness = 110)
            suggestion.isDark -> current.lightAdjustments.copy(backgroundLightness = 100)
            else -> current.lightAdjustments
        }
        val pack = matchPack(suggestion.packStyle, packs)?.id ?: current.simplePackId
        return current.copy(
            name = suggestion.name,
            source = source,
            darkAdjustments = dark,
            lightAdjustments = light,
            simplePackId = pack,
        )
    }

    /** The installed pack whose name, id, features and category labels match [packStyle] best, or null. */
    fun matchPack(packStyle: String, packs: List<InstalledPack>): InstalledPack? {
        val words = STYLE_WORDS[packStyle] ?: return null
        return packs
            .map { pack -> pack to score(pack, words) }
            .filter { it.second > 0 }
            .maxWithOrNull(compareBy<Pair<InstalledPack, Int>> { it.second }.thenBy { it.first.name })
            ?.first
    }

    private fun score(pack: InstalledPack, words: List<String>): Int {
        val haystack = buildList {
            add(pack.name)
            add(pack.id)
            addAll(pack.features)
            addAll(pack.pack.categories.keys)
            addAll(pack.pack.categories.values)
        }.joinToString(" ").lowercase()
        return words.count { it in haystack }
    }

    private val STYLE_WORDS: Map<String, List<String>> = mapOf(
        "minimal" to listOf("minimal", "clean", "simple", "line", "outline", "mono", "thin", "flat"),
        "playful" to listOf("playful", "fun", "cartoon", "bubble", "round", "cute", "candy", "pop"),
        "glass" to listOf("glass", "blur", "frost", "translucent", "acrylic", "crystal", "aero"),
        "retro" to listOf("retro", "vintage", "classic", "pixel", "8bit", "8-bit", "old", "legacy"),
        "neon" to listOf("neon", "glow", "cyber", "vivid", "led", "synth", "laser"),
        "nature" to listOf("nature", "leaf", "forest", "wood", "earth", "organic", "green", "flora"),
    )

    private val MODES = setOf("light", "dark", "system")
    private const val MAX_ACCENTS = 6
    private const val OPAQUE: Int = 0xFF000000.toInt()
}
