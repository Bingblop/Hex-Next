package com.hexnext.engine.compile

/** How the quick-settings policy (PALETTE.md, "Quick settings policy") treats an element. */
public enum class QuickSettingsPolicy {
    /** No restriction: colours and drawables are built. */
    NONE,

    /**
     * Colours only: the element's System UI drawables (tile shapes) are never built, even when
     * [QuickSettingsRules.drawablesAllowed] is on; dimensions and layouts never are anyway.
     */
    COLOURS_ONLY,
}

/**
 * A themeable One UI element.
 *
 * @property id stable identifier persisted in [ThemeSpec] maps (`settings_icons`).
 * @property label English display label.
 * @property targets target packages the element themes; empty means "any target that defines
 *   the resource" (generic framework drawables, misc).
 * @property categories pack category base names the element consumes (see [CategoryName.lookupKeys]).
 * @property quickSettingsPolicy quick-settings restriction for this element.
 */
public data class Element(
    val id: String,
    val label: String,
    val targets: List<String>,
    val categories: List<String>,
    val quickSettingsPolicy: QuickSettingsPolicy = QuickSettingsPolicy.NONE,
) {
    /** True when [targets] is empty: the element applies to every target defining its resources. */
    public val anyTarget: Boolean get() = targets.isEmpty()

    /** True when the element themes [packageName]. */
    public fun appliesTo(packageName: String): Boolean = anyTarget || packageName in targets
}

/**
 * A parsed pack category name such as `settingsicon_alt-1sett`, `dialpad_nightHp` or
 * `drawable-night-xxhdpi-v26-note`.
 *
 * Parsing: `/` is treated as `-`; the name is split on `-` and `#`; the first part is the base.
 * A trailing `H<letter>` (`Hp`, `Hr`, `Hs` …) of any part is split off as its own token; a base
 * suffix `_night` marks a night category, `_alt` adds the token `alt`, `_land` the qualifier
 * `land`. A `night` part also marks a night category.
 *
 * @property raw the category as given.
 * @property base base name without suffixes (`settingsicon`).
 * @property tokens every remaining part in order, excluding `night`.
 * @property night true for night (dark) categories.
 */
public data class CategoryName(
    val raw: String,
    val base: String,
    val tokens: List<String>,
    val night: Boolean,
) {
    /**
     * Variant tokens that select optional artwork: [tokens] minus resource qualifiers
     * (density, `v<sdk>`, `land`, `port`, `sw<N>dp`, layout direction). A category with option
     * tokens is used only when every token is switched on in the element options.
     */
    public val optionTokens: List<String> get() = tokens.filterNot(::isQualifier)

    /** Keys tried against element categories, most specific first: `base-<token>` for each token in order, then `base`. */
    public val lookupKeys: List<String>
        get() = tokens.map { "$base-$it" } + base

    public companion object {
        private val H_SUFFIX = Regex("^(.+?)(H[a-z])$")
        private val QUALIFIER = Regex("^(v\\d+|[a-z]*dpi|land|port|sw\\d+dp|w\\d+dp|h\\d+dp|ldrtl|ldltr|Idrtl|Idltr|notnight)$")

        /** True for resource qualifier tokens (not artwork options). */
        public fun isQualifier(token: String): Boolean = QUALIFIER.matches(token)

        /** Parses [category]. */
        public fun parse(category: String): CategoryName {
            val parts = category.replace('/', '-').split('-', '#').filter { it.isNotEmpty() }
            val expanded = parts.flatMap { part ->
                H_SUFFIX.matchEntire(part)?.destructured?.let { (head, h) -> listOf(head, h) } ?: listOf(part)
            }
            var base = expanded.firstOrNull().orEmpty()
            val tokens = mutableListOf<String>()
            var night = false
            val baseTokens = mutableListOf<String>()
            loop@ while (true) {
                when {
                    base.endsWith("_night") -> { night = true; base = base.removeSuffix("_night") }
                    base.endsWith("_alt") -> { baseTokens.add(0, "alt"); base = base.removeSuffix("_alt") }
                    base.endsWith("_land") -> { baseTokens.add(0, "land"); base = base.removeSuffix("_land") }
                    else -> break@loop
                }
            }
            tokens += baseTokens
            for (t in expanded.drop(1)) if (t == "night") night = true else tokens += t
            return CategoryName(category, base, tokens, night)
        }
    }
}

/** Registry of the themeable One UI elements. */
public object Elements {
    private const val ANDROID = "android"
    private const val SYSTEMUI = "com.android.systemui"
    private const val SETTINGS = "com.android.settings"
    private const val LAUNCHER = "com.sec.android.app.launcher"
    private const val DIALER = "com.samsung.android.dialer"
    private const val CONTACTS = "com.samsung.android.app.contacts"
    private const val MYFILES = "com.sec.android.app.myfiles"

    /** Id of the catch-all element that receives unknown categories. */
    public const val MISC: String = "misc"

    /** Every element, in display order. [MISC] is last. */
    public val ALL: List<Element> = listOf(
        Element("settings_icons", "Settings icons", listOf(SETTINGS), listOf("settingsicon")),
        Element("settings_dashboard", "Settings dashboard", listOf(SETTINGS), listOf("pref", "dashboard")),
        Element("navigation_bar", "Navigation bar", listOf(SYSTEMUI, ANDROID), listOf("navbar")),
        Element("status_bar", "Status bar", listOf(SYSTEMUI), listOf("indicator", "statusbar")),
        Element(
            "quick_settings_tiles", "Quick settings tiles", listOf(SYSTEMUI), listOf("qs", "qstile"),
            QuickSettingsPolicy.COLOURS_ONLY,
        ),
        Element(
            "quick_settings_panel", "Quick settings panel", listOf(SYSTEMUI),
            listOf("qspanel", "brighnessbar", "brightnessbar", "misc-qsmb1", "misc-qsmb3", "misc-brightness"),
        ),
        Element("controls", "Switches, checkboxes and radios", listOf(ANDROID, SYSTEMUI, SETTINGS), listOf("control")),
        Element(
            "notifications", "Notifications", listOf(SYSTEMUI, ANDROID),
            listOf(
                "notif", "sysnotif", "misc-notibg", "misc-notidsp", "misc-notigra", "misc-notigrad", "misc-notione",
                "misc-notitwo", "misc-notiline", "misc-notidivi", "misc-altnoti", "misc-nbnw", "misc-nbold",
                "misc-nbone", "misc-nbtwo", "misc-dpgrad", "misc-dsptwo",
            ),
        ),
        Element(
            "volume_panel", "Volume panel", listOf(SYSTEMUI),
            listOf(
                "volume", "misc-volmod", "misc-volver", "misc-vol1", "misc-vol2", "lockscreen-volpanel",
                "lockscreen-volbg", "lockscreen-volone", "lockscreen-voltwo", "lockscreen-gradvol",
                "lockscreen-vbone", "lockscreen-vbtwo",
            ),
        ),
        Element("power_menu", "Power menu", listOf(SYSTEMUI, ANDROID), listOf("powermenu", "power")),
        Element("lock_screen", "Lock screen", listOf(SYSTEMUI), listOf("lockscreen", "keyguard")),
        Element("keyboard", "Samsung Keyboard", listOf("com.samsung.android.honeyboard"), listOf("keyboard")),
        Element("dialer", "Phone", listOf(DIALER), listOf("dialer")),
        Element("dialpad", "Dialpad", listOf(DIALER), listOf("dialpad")),
        Element("in_call", "In-call screen", listOf("com.samsung.android.incallui"), listOf("incall", "incallui")),
        Element("contacts", "Contacts", listOf(CONTACTS, DIALER), listOf("avatar", "contacts")),
        Element("my_files_icons", "My Files icons", listOf(MYFILES), listOf("myfilesicon", "misc-myfiles")),
        Element("my_files", "My Files", listOf(MYFILES), listOf("file", "myfiles")),
        Element("gallery", "Gallery", listOf("com.sec.android.gallery3d"), listOf("gallery")),
        Element("clock", "Clock", listOf("com.sec.android.app.clockpackage"), listOf("clockwidget", "clock")),
        Element("calculator", "Calculator", listOf("com.sec.android.app.popupcalculator"), listOf("calculator")),
        Element("calendar", "Calendar", listOf("com.samsung.android.calendar"), listOf("calendar")),
        Element("device_care", "Device care", listOf("com.samsung.android.lool"), listOf("devicecare", "lool")),
        Element(
            "launcher_home", "One UI Home", listOf(LAUNCHER),
            listOf("home", "drawer", "page", "search", "googlesearch", "search_voice_setting_cross_drawer", "widgets"),
        ),
        Element("launcher_icons", "App icons", listOf(LAUNCHER), listOf("icons")),
        Element("launcher_badges", "App icon badges", listOf(LAUNCHER), listOf("badge")),
        Element("aod", "Always On Display", listOf("com.samsung.android.app.aodservice"), listOf("aod")),
        Element("smart_capture", "Smart capture", listOf("com.samsung.android.app.smartcapture"), listOf("smartcapture")),
        Element("notes", "Samsung Notes", listOf("com.samsung.android.app.notes"), listOf("notes")),
        Element("reminder", "Reminder", listOf("com.samsung.android.app.reminder"), listOf("reminder")),
        Element("backgrounds", "Backgrounds", listOf(ANDROID, SETTINGS, DIALER, CONTACTS), listOf("background")),
        Element("dialogs", "Dialogs and buttons", listOf(ANDROID, SETTINGS), listOf("box", "buttons")),
        Element("system_drawables", "Framework and app drawables", emptyList(), listOf("drawable", "mipmap")),
        Element(
            MISC, "Miscellaneous", emptyList(),
            listOf("misc", "mms", "msgbubble", "wabuble", "ui", "ui_messages", "timmah", "plugin", "bottom"),
        ),
    )

    private val byId: Map<String, Element> = ALL.associateBy { it.id }
    private val byCategory: Map<String, Element> = buildMap {
        for (e in ALL) for (c in e.categories) {
            check(put(c, e) == null) { "category $c assigned twice" }
        }
    }

    /** The catch-all element. */
    public val misc: Element get() = byId.getValue(MISC)

    /** Element with [id], or null. */
    public fun byId(id: String): Element? = byId[id]

    /** Elements that theme [packageName] (including any-target elements). */
    public fun forTarget(packageName: String): List<Element> = ALL.filter { it.appliesTo(packageName) }

    /** The element that explicitly lists [category] (via [CategoryName.lookupKeys]), or null. */
    public fun resolveCategory(category: String): Element? =
        CategoryName.parse(category).lookupKeys.firstNotNullOfOrNull { byCategory[it] }

    /** The element consuming [category]; unknown categories go to [misc]. */
    public fun elementForCategory(category: String): Element = resolveCategory(category) ?: misc
}
