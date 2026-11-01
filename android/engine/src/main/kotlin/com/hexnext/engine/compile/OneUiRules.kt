package com.hexnext.engine.compile

import com.hexnext.engine.device.OneUiProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Version-specific rules from `engine/src/main/resources/oneui/<major>.json`.
 *
 * @property major One UI major version the rules are for.
 * @property targets target packages to theme, in build order.
 * @property deprecated packages never themed on this version (even if installed).
 * @property quickSettingsColoursOnly enables the quick-settings policy: System UI colours are
 *   limited to panel/tile names and [QuickSettingsPolicy.COLOURS_ONLY] elements build no drawables.
 * @property renamed resource renames, `type/name` → `type/name`, applied before every lookup.
 */
@Serializable
public data class OneUiRules(
    val major: Int = 0,
    val targets: List<String> = emptyList(),
    val deprecated: List<String> = emptyList(),
    val quickSettingsColoursOnly: Boolean = true,
    val renamed: Map<String, String> = emptyMap(),
) {
    /** [targets] without [deprecated] packages. */
    public val activeTargets: List<String> get() = targets.filterNot { it in deprecated }

    /** [typeName] (`type/name`) after [renamed]. */
    public fun rename(typeName: String): String = renamed[typeName] ?: typeName

    public companion object {
        private val RulesJson = Json { ignoreUnknownKeys = true }

        /** Parses a rule file. */
        public fun parse(json: String): OneUiRules = try {
            RulesJson.decodeFromString(serializer(), json)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("invalid One UI rules: ${e.message}", e)
        }

        /** Loads the bundled rules for One UI [major]; throws [IllegalArgumentException] when there are none. */
        public fun load(major: Int): OneUiRules {
            val stream = OneUiRules::class.java.getResourceAsStream("/oneui/$major.json")
                ?: throw IllegalArgumentException("no rules for One UI $major")
            val rules = stream.use { parse(String(it.readBytes(), Charsets.UTF_8)) }
            return if (rules.major == 0) rules.copy(major = major) else rules
        }

        /**
         * Rules for [profile]: its major when supported, otherwise the newest supported major
         * (unknown or newer devices are treated like the latest One UI).
         */
        public fun forProfile(profile: OneUiProfile): OneUiRules {
            val major = profile.version?.major
            return load(if (major != null && major in OneUiProfile.SUPPORTED_MAJORS) major else OneUiProfile.SUPPORTED_MAJORS.last)
        }
    }
}
