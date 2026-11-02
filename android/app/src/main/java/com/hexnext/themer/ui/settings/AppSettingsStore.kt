package com.hexnext.themer.ui.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet

/** Light or dark for the app itself, independent of the theme compiled for the phone. */
enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
}

/** Where the app's own Material 3 colours come from. */
enum class PaletteSource(val label: String) {
    /** The phone's dynamic colours (the wallpaper, or the theme Hex Next applied). */
    DYNAMIC("Phone colours"),
    /** A scheme built from [AppSettings.seedArgb]. */
    SEED("Custom colour"),
}

/** Material 3 contrast levels; [level] is the value the colour utilities take (0, 0.5, 1). */
enum class ContrastLevel(val label: String, val level: Double) {
    STANDARD("Standard", 0.0),
    MEDIUM("Medium", 0.5),
    HIGH("High", 1.0),
}

/**
 * Everything under "App settings". Every field changes the UI live through
 * [AppSettingsStore.settings]; nothing here touches the theme applied to the phone.
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val palette: PaletteSource = PaletteSource.DYNAMIC,
    /** Opaque ARGB seed of the [PaletteSource.SEED] scheme. */
    val seedArgb: Int = DEFAULT_SEED,
    /** Paints dark backgrounds pure black, for AMOLED screens. */
    val pureBlack: Boolean = false,
    val contrast: ContrastLevel = ContrastLevel.STANDARD,
    /** Vibrate on apply, success and failure. */
    val haptics: Boolean = true,
    /** Skip card and screen animations. */
    val reduceMotion: Boolean = false,
    /** True once the first-launch setup guide was finished or skipped. */
    val onboardingDone: Boolean = false,
) {
    /** Whether the app draws dark, given what the system currently says. */
    fun isDark(systemDark: Boolean): Boolean = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    companion object {
        /** The accent the app ships with (the same blue as the editor's default preset family). */
        const val DEFAULT_SEED: Int = 0xFF3E7BFA.toInt()
    }
}

/**
 * SharedPreferences-backed store of [AppSettings]. One instance per process via [get]; reads are
 * synchronous on first access (a handful of primitives), writes are `apply()`ed.
 */
class AppSettingsStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())

    /** Current settings; collect in Compose with `collectAsStateWithLifecycle()`. */
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Applies [transform] to the current settings, persists and publishes the result. */
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = _settings.updateAndGet(transform)
        write(next)
    }

    fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }
    fun setPalette(source: PaletteSource) = update { it.copy(palette = source) }
    fun setSeed(argb: Int) = update { it.copy(seedArgb = argb or 0xFF000000.toInt(), palette = PaletteSource.SEED) }
    fun setPureBlack(enabled: Boolean) = update { it.copy(pureBlack = enabled) }
    fun setContrast(level: ContrastLevel) = update { it.copy(contrast = level) }
    fun setHaptics(enabled: Boolean) = update { it.copy(haptics = enabled) }
    fun setReduceMotion(enabled: Boolean) = update { it.copy(reduceMotion = enabled) }
    fun setOnboardingDone(done: Boolean) = update { it.copy(onboardingDone = done) }

    private fun read(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            themeMode = enumOrDefault(prefs.getString(KEY_THEME_MODE, null), defaults.themeMode),
            palette = enumOrDefault(prefs.getString(KEY_PALETTE, null), defaults.palette),
            seedArgb = prefs.getInt(KEY_SEED, defaults.seedArgb) or 0xFF000000.toInt(),
            pureBlack = prefs.getBoolean(KEY_PURE_BLACK, defaults.pureBlack),
            contrast = enumOrDefault(prefs.getString(KEY_CONTRAST, null), defaults.contrast),
            haptics = prefs.getBoolean(KEY_HAPTICS, defaults.haptics),
            reduceMotion = prefs.getBoolean(KEY_REDUCE_MOTION, defaults.reduceMotion),
            onboardingDone = prefs.getBoolean(KEY_ONBOARDING_DONE, defaults.onboardingDone),
        )
    }

    private fun write(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_THEME_MODE, settings.themeMode.name)
            .putString(KEY_PALETTE, settings.palette.name)
            .putInt(KEY_SEED, settings.seedArgb)
            .putBoolean(KEY_PURE_BLACK, settings.pureBlack)
            .putString(KEY_CONTRAST, settings.contrast.name)
            .putBoolean(KEY_HAPTICS, settings.haptics)
            .putBoolean(KEY_REDUCE_MOTION, settings.reduceMotion)
            .putBoolean(KEY_ONBOARDING_DONE, settings.onboardingDone)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    companion object {
        private const val PREFS = "hexnext_app_settings"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_PALETTE = "palette"
        private const val KEY_SEED = "seed_argb"
        private const val KEY_PURE_BLACK = "pure_black"
        private const val KEY_CONTRAST = "contrast"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_REDUCE_MOTION = "reduce_motion"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"

        @Volatile
        private var instance: AppSettingsStore? = null

        /** The process-wide store; safe to call from the activity, a view model or a composable. */
        fun get(context: Context): AppSettingsStore =
            instance ?: synchronized(this) {
                instance ?: AppSettingsStore(context).also { instance = it }
            }
    }
}
