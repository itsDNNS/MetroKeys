package app.metrokeys.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * The settings shared by the settings app and the keyboard service (spec 07 §9.1). Keys and defaults are the
 * ones of the specification; only keys that exist there are used.
 */
object Prefs {
    const val FILE = "settings"

    const val LANGUAGES = "languages"
    const val MULTILINGUAL = "multilingual"
    const val SHOW_COMMA_KEY = "show_comma_key"
    const val ACTIVE_LANGUAGE = "active_language"
    const val SUGGESTIONS = "suggestions"
    const val AUTOCORRECT = "autocorrect"
    const val SPACE_AFTER_SUGGESTION = "space_after_suggestion"
    const val AUTO_CAPS = "auto_caps"
    const val DOUBLE_SPACE_PERIOD = "double_space_period"
    const val SHAPE_WRITING = "shape_writing"
    const val SPELLING_UNDERLINE = "spelling_underline"
    const val CONTACTS_WORD_SOURCE = "contacts_word_source"
    const val AUTOSWITCH_AFTER_EMOJI = "autoswitch_after_emoji"
    const val CLIPBOARD_HISTORY = "clipboard_history"
    const val AUTOFILL_INLINE = "autofill_inline"
    const val KEY_SOUNDS = "key_sounds"
    const val KEY_SOUND_VOLUME = "key_sound_volume"
    const val VIBRATION = "vibration"
    const val SOUND_SOURCE = "sound_source"
    const val THEME = "theme"
    const val ACCENT = "accent"
    const val APP_COLOR = "app_color"
    const val HIGH_CONTRAST = "high_contrast"
    const val KEYBOARD_HEIGHT = "keyboard_height"
    const val FONT_SOURCE = "font_source"
    const val NAV_MODE = "nav_mode"
    const val NAV_TAP = "nav_tap_"
    const val NAV_HOLD = "nav_hold_"
    const val LEARNED_RESET_AT = "learned_reset_at"
    const val CUSTOM_ASSETS_STAMP = "custom_assets_stamp"
    const val EMOJI_HISTORY = "emoji_history"

    /**
     * Words forgotten on the learned words page (07 §9.13 item 5) for the running keyboard: lines
     * "<timestamp>\t<word>", newest last, at most [FORGET_KEEP].
     */
    const val FORGET_WORDS = "forget_words"
    const val FORGET_KEEP = 20

    /** Parses [FORGET_WORDS]: (timestamp, word) pairs. */
    fun forgetRequests(value: String?): List<Pair<Long, String>> =
        value.orEmpty().lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null else line.substring(0, tab).toLongOrNull()?.let { it to line.substring(tab + 1) }
        }.filter { it.second.isNotEmpty() }.toList()

    /** [FORGET_WORDS] with the request ([stamp], [word]) appended. */
    fun withForgetRequest(value: String?, stamp: Long, word: String): String =
        (forgetRequests(value) + (stamp to word)).takeLast(FORGET_KEEP).joinToString("\n") { "${it.first}\t${it.second}" }

    const val GERMAN = "de-DE"
    const val ENGLISH = "en-US"
    val SUPPORTED_LANGUAGES = listOf(GERMAN, ENGLISH)

    /** Default tap bindings of the three application-bar buttons (spec 07 §9.12). */
    val DEFAULT_NAV_TAP = listOf("editor", "emoji", "voice")

    fun of(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Enabled keyboard languages in the canonical order (German, English); never empty. */
    fun languages(p: SharedPreferences): List<String> {
        val set = p.getStringSet(LANGUAGES, null) ?: setOf(GERMAN)
        return SUPPORTED_LANGUAGES.filter { it in set }.ifEmpty { listOf(GERMAN) }
    }
}

enum class ThemeChoice { SYSTEM, LIGHT, DARK }

enum class HighContrastChoice { SYSTEM, ON, OFF }

enum class NavMode { APP_BAR, COMPACT, OFF }

/** A snapshot of all keyboard settings, read once per change of the preferences. */
data class KeyboardSettings(
    val languages: List<String>,
    val multilingual: Boolean,
    val showCommaKey: Boolean,
    val activeLanguage: String?,
    val suggestions: Boolean,
    val autocorrect: Boolean,
    val spaceAfterSuggestion: Boolean,
    val autoCaps: Boolean,
    val doubleSpacePeriod: Boolean,
    val shapeWriting: Boolean,
    val spellingUnderline: Boolean,
    val contactsWordSource: Boolean,
    val autoswitchAfterEmoji: Boolean,
    val clipboardHistory: Boolean,
    val autofillInline: Boolean,
    val keySounds: Boolean,
    val keySoundVolume: Int,
    val vibration: Boolean,
    val customSounds: Boolean,
    val theme: ThemeChoice,
    val accentIndex: Int,
    val highContrast: HighContrastChoice,
    val keyboardHeightPercent: Int,
    val customFonts: Boolean,
    val navMode: NavMode,
    val navTap: List<String>,
    val navHold: List<String>,
    val learnedResetAt: Long,
    val customAssetsStamp: Long,
    /** Words forgotten in the settings (07 §9.13 item 5), (timestamp, word). */
    val forgetRequests: List<Pair<Long, String>> = emptyList(),
) {
    /** Autocorrection requires suggestions (spec 07 §9.6). */
    val effectiveAutocorrect: Boolean get() = suggestions && autocorrect
    val effectiveSpaceAfterSuggestion: Boolean get() = suggestions && spaceAfterSuggestion
    val effectiveSpellingUnderline: Boolean get() = suggestions && spellingUnderline
    val isMultilingual: Boolean get() = multilingual && languages.size >= 2

    companion object {
        /** The defaults of spec 07 §9.1. */
        val DEFAULT = KeyboardSettings(
            languages = listOf(Prefs.GERMAN), multilingual = true, showCommaKey = false, activeLanguage = null, suggestions = true,
            autocorrect = true, spaceAfterSuggestion = true, autoCaps = true, doubleSpacePeriod = true,
            shapeWriting = true, spellingUnderline = true, contactsWordSource = false, autoswitchAfterEmoji = true,
            clipboardHistory = true, autofillInline = true, keySounds = true, keySoundVolume = 100, vibration = false,
            customSounds = false, theme = ThemeChoice.SYSTEM, accentIndex = 5, highContrast = HighContrastChoice.SYSTEM,
            keyboardHeightPercent = 100, customFonts = false, navMode = NavMode.APP_BAR, navTap = Prefs.DEFAULT_NAV_TAP,
            navHold = listOf("none", "none", "none"), learnedResetAt = 0, customAssetsStamp = 0,
        )

        fun read(p: SharedPreferences): KeyboardSettings = KeyboardSettings(
            languages = Prefs.languages(p),
            multilingual = p.getBoolean(Prefs.MULTILINGUAL, true),
            showCommaKey = p.getBoolean(Prefs.SHOW_COMMA_KEY, false),
            activeLanguage = p.getString(Prefs.ACTIVE_LANGUAGE, null),
            suggestions = p.getBoolean(Prefs.SUGGESTIONS, true),
            autocorrect = p.getBoolean(Prefs.AUTOCORRECT, true),
            spaceAfterSuggestion = p.getBoolean(Prefs.SPACE_AFTER_SUGGESTION, true),
            autoCaps = p.getBoolean(Prefs.AUTO_CAPS, true),
            doubleSpacePeriod = p.getBoolean(Prefs.DOUBLE_SPACE_PERIOD, true),
            shapeWriting = p.getBoolean(Prefs.SHAPE_WRITING, true),
            spellingUnderline = p.getBoolean(Prefs.SPELLING_UNDERLINE, true),
            contactsWordSource = p.getBoolean(Prefs.CONTACTS_WORD_SOURCE, false),
            autoswitchAfterEmoji = p.getBoolean(Prefs.AUTOSWITCH_AFTER_EMOJI, true),
            clipboardHistory = p.getBoolean(Prefs.CLIPBOARD_HISTORY, true),
            autofillInline = p.getBoolean(Prefs.AUTOFILL_INLINE, true),
            keySounds = p.getBoolean(Prefs.KEY_SOUNDS, true),
            keySoundVolume = p.getInt(Prefs.KEY_SOUND_VOLUME, 100).coerceIn(0, 100),
            vibration = p.getBoolean(Prefs.VIBRATION, false),
            customSounds = p.getString(Prefs.SOUND_SOURCE, "system") == "custom",
            theme = when (p.getString(Prefs.THEME, "system")) {
                "light" -> ThemeChoice.LIGHT
                "dark" -> ThemeChoice.DARK
                else -> ThemeChoice.SYSTEM
            },
            accentIndex = (p.getString(Prefs.ACCENT, "-1")?.toIntOrNull() ?: -1).let { if (it in 0..19) it else 5 },
            highContrast = when (p.getString(Prefs.HIGH_CONTRAST, "system")) {
                "on" -> HighContrastChoice.ON
                "off" -> HighContrastChoice.OFF
                else -> HighContrastChoice.SYSTEM
            },
            keyboardHeightPercent = p.getInt(Prefs.KEYBOARD_HEIGHT, 100).coerceIn(70, 100),
            customFonts = p.getString(Prefs.FONT_SOURCE, "bundled") == "custom",
            navMode = when (p.getString(Prefs.NAV_MODE, "appbar")) {
                "compact" -> NavMode.COMPACT
                "off" -> NavMode.OFF
                else -> NavMode.APP_BAR
            },
            navTap = (0..2).map { p.getString(Prefs.NAV_TAP + it, null) ?: Prefs.DEFAULT_NAV_TAP[it] },
            navHold = (0..2).map { p.getString(Prefs.NAV_HOLD + it, null) ?: "none" },
            learnedResetAt = p.getLong(Prefs.LEARNED_RESET_AT, 0),
            customAssetsStamp = p.getLong(Prefs.CUSTOM_ASSETS_STAMP, 0),
            forgetRequests = Prefs.forgetRequests(p.getString(Prefs.FORGET_WORDS, null)),
        )
    }
}
