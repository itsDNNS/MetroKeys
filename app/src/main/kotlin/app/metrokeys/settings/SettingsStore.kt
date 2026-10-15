package app.metrokeys.settings

import android.Manifest
import android.app.LocaleManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.LocaleList
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import app.metrokeys.engine.ActiveLanguages
import app.metrokeys.ime.MetroKeysService
import app.metrokeys.keyboard.UserAssets
import app.metrokeys.settings.ui.Accents
import app.metrokeys.settings.ui.MetroFonts
import java.io.File

/**
 * Observable access to the shared preferences of spec 07 §9.1 for Compose: every read subscribes to a change
 * counter that the preference listener increments, so the pages and the main list summaries follow every change
 * (also changes written by the keyboard). Writes are applied immediately (07 §1.5).
 */
class SettingsStore(private val context: Context) {
    val prefs: SharedPreferences = Prefs.of(context)
    private var version by mutableIntStateOf(0)

    /** Incremented on resume and on window focus so that permission and setup state are re-read. */
    var resumes by mutableIntStateOf(0)
        private set

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> version++ }

    fun start() = prefs.registerOnSharedPreferenceChangeListener(listener)

    fun stop() = prefs.unregisterOnSharedPreferenceChangeListener(listener)

    fun resumed() {
        resumes++
    }

    fun bool(key: String, default: Boolean): Boolean {
        version
        return prefs.getBoolean(key, default)
    }

    fun string(key: String, default: String): String {
        version
        return prefs.getString(key, default) ?: default
    }

    fun int(key: String, default: Int): Int {
        version
        return prefs.getInt(key, default)
    }

    fun long(key: String, default: Long): Long {
        version
        return prefs.getLong(key, default)
    }

    fun languages(): List<String> {
        version
        return Prefs.languages(prefs)
    }

    fun set(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

    fun set(key: String, value: String) = prefs.edit().putString(key, value).apply()

    fun set(key: String, value: Int) = prefs.edit().putInt(key, value).apply()

    fun edit(block: SharedPreferences.Editor.() -> Unit) = prefs.edit().apply(block).apply()

    /** Enabled languages; a disabled language also leaves the selection order (05 §14.5 "Changes"). */
    fun setLanguages(langs: Collection<String>) {
        val recency = ActiveLanguages.parse(prefs.getString(Prefs.LANGUAGE_RECENCY, null)).filter { it in langs }
        prefs.edit().putStringSet(Prefs.LANGUAGES, langs.toSet()).putString(Prefs.LANGUAGE_RECENCY, ActiveLanguages.format(recency)).apply()
    }

    // ---------------------------------------------------------------- derived values

    /** The keyboard accent index (07 §10: `-1` = cobalt). */
    fun keyboardAccent(): Int = Accents.index(string(Prefs.ACCENT, "-1"))

    /** The accent of the settings app (07 §9.10 "App colour"); `system` needs Android 12. */
    fun appAccent(): Color {
        val kb = Accents.COLORS[keyboardAccent()]
        return when (val v = string(Prefs.APP_COLOR, "keyboard")) {
            "keyboard" -> kb
            "system" -> systemAccent() ?: kb
            else -> v.toIntOrNull()?.let { Accents.COLORS.getOrNull(it) } ?: kb
        }
    }

    /** The primary colour of Android's dynamic light colour scheme (Android 12+). */
    fun systemAccent(): Color? = if (Build.VERSION.SDK_INT >= 31) dynamicLightColorScheme(context).primary else null

    /** The faces of the settings app: the user's fonts when selected (07 §4). */
    fun fonts(): MetroFonts {
        long(Prefs.CUSTOM_ASSETS_STAMP, 0)
        return if (string(Prefs.FONT_SOURCE, "bundled") == "custom") MetroFonts.custom(UserAssets.fontsDir(context)) else MetroFonts.BUNDLED
    }

    fun permitted(permission: String): Boolean {
        resumes
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    /** The permission state right now (outside composition); also refreshes what the pages show. */
    fun permittedNow(permission: String): Boolean {
        resumes++
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    /** Turns the contacts source off when the permission was revoked (07 §9.6). */
    fun checkContactsPermission() {
        if (prefs.getBoolean(Prefs.CONTACTS_WORD_SOURCE, false) &&
            context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED
        ) {
            set(Prefs.CONTACTS_WORD_SOURCE, false)
        }
    }

    /** Clipboard history off: the keyboard clears its history; the stored file goes too (07 §9.7). */
    fun clearClipboardFile() {
        File(context.noBackupFilesDir, "clipboard.bin").delete()
        app.metrokeys.ime.ClipImages.dir(context).deleteRecursively()
    }
}

/** Whether MetroKeys is enabled and selected as the input method (07 §9.3). */
data class SetupState(val enabled: Boolean, val selected: Boolean) {
    val ready: Boolean get() = enabled && selected

    companion object {
        fun read(context: Context): SetupState {
            val imm = context.getSystemService(InputMethodManager::class.java)
            val enabled = imm?.enabledInputMethodList?.any { it.packageName == context.packageName } == true
            val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            val selected = current != null &&
                ComponentName.unflattenFromString(current)?.className == MetroKeysService::class.java.name
            return SetupState(enabled, selected)
        }
    }
}

/** The per-app language (07 §9.5, Android 13+): "" = same as system, "de" or "en". */
object AppLocale {
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 33

    fun current(context: Context): String {
        if (Build.VERSION.SDK_INT < 33) return ""
        val l = context.getSystemService(LocaleManager::class.java)?.applicationLocales ?: return ""
        if (l.isEmpty) return ""
        return if (l[0].language == "de") "de" else "en"
    }

    fun set(context: Context, tag: String) {
        if (Build.VERSION.SDK_INT < 33) return
        val lm = context.getSystemService(LocaleManager::class.java) ?: return
        lm.applicationLocales = if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }
}
