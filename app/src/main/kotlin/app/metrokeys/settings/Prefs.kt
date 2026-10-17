package app.metrokeys.settings

import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import app.metrokeys.R
import app.metrokeys.engine.ActiveLanguages
import app.metrokeys.keyboard.SoundSource
import java.util.Locale

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
    const val LANGUAGE_RECENCY = "language_recency"
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
    const val KEY_SOUNDS_SILENT_MODE = "key_sounds_silent_mode"
    const val VIBRATION = "vibration"
    const val SOUND_SOURCE = "sound_source"
    const val SOUND_SOURCE_MIGRATED = "sound_source_migrated"
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
    const val SPANISH = "es-ES"
    const val FRENCH = "fr-FR"
    const val ITALIAN = "it-IT"
    const val PORTUGUESE = "pt-BR"

    /** The supported keyboard languages in the fixed language order: alphabetical by tag (07 §9.5). */
    val SUPPORTED_LANGUAGES = listOf(GERMAN, ENGLISH, SPANISH, FRENCH, ITALIAN, PORTUGUESE)

    /** Default tap bindings of the three application-bar buttons (spec 07 §9.12). */
    val DEFAULT_NAV_TAP = listOf("editor", "emoji", "voice")

    fun of(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The supported keyboard language of each ISO 639 language code, whatever the region (07 §9.1, 0.6.9). */
    private val BY_LANGUAGE = SUPPORTED_LANGUAGES.associateBy { it.substringBefore('-') }

    /**
     * The default of [LANGUAGES] (07 §9.1, 0.6.9): the first locale of the system locale list whose language is
     * supported, mapped by language only (`de-AT` → `de-DE`, `pt-PT` → `pt-BR`, `es-MX` → `es-ES`); `en-US` when no
     * locale is supported.
     */
    fun defaultLanguage(systemLocales: List<Locale>): String =
        systemLocales.firstNotNullOfOrNull { BY_LANGUAGE[it.language] } ?: ENGLISH

    /**
     * The device's own locale list, not the per-app language of the settings app and the keyboard's texts
     * (07 §9.1, §9.5).
     */
    fun systemLocales(context: Context): List<Locale> {
        val list: LocaleList = (if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java)?.systemLocales
        } else null) ?: Resources.getSystem().configuration.locales
        return (0 until list.size()).map { list[it] }
    }

    /**
     * The sound source (07 §9.1, §9.9) after the one-time migration: while [SOUND_SOURCE_MIGRATED] is not set, a stored
     * `system` (or none) becomes `metrokeys` – earlier versions had no own sounds, so `system` was no deliberate choice –
     * and `custom` is kept; then the flag is set, and a later choice of `system` is never changed again. Whichever of
     * keyboard and settings app reads it first runs the migration.
     */
    fun soundSource(p: SharedPreferences): SoundSource {
        if (!p.getBoolean(SOUND_SOURCE_MIGRATED, false)) {
            synchronized(this) {
                if (!p.getBoolean(SOUND_SOURCE_MIGRATED, false)) {
                    val old = p.getString(SOUND_SOURCE, null)
                    p.edit {
                        if (old == null || old == SoundSource.SYSTEM.value) putString(SOUND_SOURCE, SoundSource.METROKEYS.value)
                        putBoolean(SOUND_SOURCE_MIGRATED, true)
                    }
                }
            }
        }
        return SoundSource.of(p.getString(SOUND_SOURCE, null))
    }

    /** [languages] of the app's settings with the device's locale list. */
    fun languages(context: Context): List<String> = languages(of(context)) { systemLocales(context) }

    /**
     * Enabled keyboard languages in the fixed language order; unknown tags are ignored; never empty. The one place
     * that decides the default (07 §9.1, 0.6.9): while [LANGUAGES] has never been stored, the default of
     * [systemLocales] is stored now, by whichever of keyboard and settings app reads it first, and kept from then on.
     * A stored set without any supported tag is left as stored and read as that default.
     */
    fun languages(p: SharedPreferences, systemLocales: () -> List<Locale>): List<String> {
        val set = p.getStringSet(LANGUAGES, null) ?: synchronized(this) {
            p.getStringSet(LANGUAGES, null) ?: setOf(defaultLanguage(systemLocales())).also {
                p.edit().putStringSet(LANGUAGES, it).apply()
            }
        }
        return SUPPORTED_LANGUAGES.filter { it in set }.ifEmpty { listOf(defaultLanguage(systemLocales())) }
    }
}

/** Names of the keyboard languages in the app language (07 §9.5, string table §11.2). */
object LanguageNames {
    fun res(tag: String): Int = when (tag) {
        Prefs.GERMAN -> R.string.lang_de
        Prefs.ENGLISH -> R.string.lang_en
        Prefs.SPANISH -> R.string.lang_es
        Prefs.FRENCH -> R.string.lang_fr
        Prefs.ITALIAN -> R.string.lang_it
        Prefs.PORTUGUESE -> R.string.lang_pt
        else -> R.string.lang_en
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
    /** Order in which the enabled languages were last selected, most recent first (07 §9.1, 05 §14.5). */
    val languageRecency: List<String> = emptyList(),
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
    /** Key sounds also in silent/vibrate mode, through the media volume (07 §9.8 item 2). */
    val keySoundsSilentMode: Boolean = false,
    val vibration: Boolean,
    val soundSource: SoundSource,
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
        /**
         * The defaults of spec 07 §9.1. `languages` has no fixed default (it follows the device, [Prefs.languages]);
         * here it is the fallback `{en-US}`.
         */
        val DEFAULT = KeyboardSettings(
            languages = listOf(Prefs.ENGLISH), multilingual = true, showCommaKey = false, activeLanguage = null, suggestions = true,
            autocorrect = true, spaceAfterSuggestion = true, autoCaps = true, doubleSpacePeriod = true,
            shapeWriting = true, spellingUnderline = true, contactsWordSource = false, autoswitchAfterEmoji = true,
            clipboardHistory = true, autofillInline = true, keySounds = true, keySoundVolume = 100, vibration = false,
            soundSource = SoundSource.METROKEYS, theme = ThemeChoice.SYSTEM, accentIndex = 5, highContrast = HighContrastChoice.SYSTEM,
            keyboardHeightPercent = 100, customFonts = false, navMode = NavMode.APP_BAR, navTap = Prefs.DEFAULT_NAV_TAP,
            navHold = listOf("none", "none", "none"), learnedResetAt = 0, customAssetsStamp = 0,
        )

        /** The settings of the app's preference file; stores the default of `languages` if it is missing. */
        fun read(context: Context): KeyboardSettings = read(Prefs.of(context)) { Prefs.systemLocales(context) }

        fun read(p: SharedPreferences, systemLocales: () -> List<Locale>): KeyboardSettings = KeyboardSettings(
            languages = Prefs.languages(p, systemLocales),
            multilingual = p.getBoolean(Prefs.MULTILINGUAL, true),
            showCommaKey = p.getBoolean(Prefs.SHOW_COMMA_KEY, false),
            activeLanguage = p.getString(Prefs.ACTIVE_LANGUAGE, null),
            languageRecency = ActiveLanguages.parse(p.getString(Prefs.LANGUAGE_RECENCY, null)),
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
            keySoundsSilentMode = p.getBoolean(Prefs.KEY_SOUNDS_SILENT_MODE, false),
            vibration = p.getBoolean(Prefs.VIBRATION, false),
            soundSource = Prefs.soundSource(p),
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
