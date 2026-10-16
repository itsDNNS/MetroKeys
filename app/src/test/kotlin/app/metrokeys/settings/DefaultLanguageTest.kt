package app.metrokeys.settings

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The default of `languages` from the device's locale list (07 §9.1, 0.6.9). */
class DefaultLanguageTest {
    private fun locales(vararg tags: String) = tags.map { Locale.forLanguageTag(it) }

    @Test
    fun firstSupportedLanguageByLanguageOnly() {
        assertEquals("de-DE", Prefs.defaultLanguage(locales("de-AT")))
        assertEquals("de-DE", Prefs.defaultLanguage(locales("de-CH")))
        assertEquals("en-US", Prefs.defaultLanguage(locales("en-GB", "de-DE")))
        assertEquals("fr-FR", Prefs.defaultLanguage(locales("nl-NL", "fr-CA")))
        assertEquals("en-US", Prefs.defaultLanguage(locales("ja-JP")))
        assertEquals("pt-BR", Prefs.defaultLanguage(locales("pt-PT")))
        assertEquals("es-ES", Prefs.defaultLanguage(locales("es-MX")))
        assertEquals("es-ES", Prefs.defaultLanguage(locales("es-419")))
        assertEquals("it-IT", Prefs.defaultLanguage(locales("it-CH", "en-US")))
        assertEquals("fr-FR", Prefs.defaultLanguage(locales("fr-FR")))
        assertEquals("en-US", Prefs.defaultLanguage(locales("ja-JP", "zh-Hans-CN", "ko-KR")))
        assertEquals("en-US", Prefs.defaultLanguage(emptyList()))
    }

    @Test
    fun defaultIsWrittenOnceAndKeptAfterALocaleChange() {
        val p = FakePrefs()
        var device = locales("fr-FR", "en-US")
        var asked = 0
        val system = { asked++; device }
        assertEquals(listOf("fr-FR"), Prefs.languages(p, system))
        assertEquals(setOf("fr-FR"), p.getStringSet(Prefs.LANGUAGES, null))
        assertEquals(1, p.writes)
        // the device language changes: the stored value stays, nothing is written again
        device = locales("de-DE")
        assertEquals(listOf("fr-FR"), Prefs.languages(p, system))
        assertEquals(listOf("fr-FR"), KeyboardSettings.read(p, system).languages)
        assertEquals(1, p.writes)
        assertEquals(1, asked)
    }

    @Test
    fun keyboardAndSettingsAppShareTheFirstWrite() {
        val p = FakePrefs()
        // the keyboard reads first (fresh install, English device)
        assertEquals(listOf("en-US"), KeyboardSettings.read(p) { locales("en-GB") }.languages)
        // the settings app later sees the stored value, also under another device language
        assertEquals(listOf("en-US"), Prefs.languages(p) { locales("es-ES") })
        assertEquals(1, p.writes)
    }

    @Test
    fun existingInstallationsKeepTheirValue() {
        val p = FakePrefs()
        p.edit().putStringSet(Prefs.LANGUAGES, setOf("de-DE", "es-ES")).apply()
        p.writes = 0
        assertEquals(listOf("de-DE", "es-ES"), Prefs.languages(p) { locales("fr-FR") })
        assertEquals(0, p.writes)
        // a stored set of only unknown tags is not rewritten; it reads as the device default
        val q = FakePrefs()
        q.edit().putStringSet(Prefs.LANGUAGES, setOf("xx-XX")).apply()
        q.writes = 0
        assertEquals(listOf("it-IT"), Prefs.languages(q) { locales("it-IT") })
        assertEquals(setOf("xx-XX"), q.getStringSet(Prefs.LANGUAGES, null))
        assertEquals(0, q.writes)
    }

    @Test
    fun defaultSettingsUseTheFallback() {
        assertEquals(listOf("en-US"), KeyboardSettings.DEFAULT.languages)
        assertFalse(KeyboardSettings.DEFAULT.isMultilingual)
        assertTrue(Prefs.SUPPORTED_LANGUAGES.all { Prefs.defaultLanguage(listOf(Locale.forLanguageTag(it))) == it })
    }
}

/** An in-memory [SharedPreferences] that counts committed edits. */
class FakePrefs : SharedPreferences {
    private val data = HashMap<String, Any?>()
    var writes = 0

    override fun getAll(): Map<String, *> = HashMap(data)
    override fun getString(key: String, defValue: String?) = data[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?) = (data[key] as Set<String>?)?.toSet() ?: defValues
    override fun getInt(key: String, defValue: Int) = data[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = data[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = data[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = data[key] as Boolean? ?: defValue
    override fun contains(key: String) = key in data
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        val changes = HashMap<String, Any?>()
        var clear = false
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { changes[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) data.clear()
            for ((k, v) in changes) if (v == null) data.remove(k) else data[k] = v
            writes++
            return true
        }
        override fun apply() {
            commit()
        }
    }
}
