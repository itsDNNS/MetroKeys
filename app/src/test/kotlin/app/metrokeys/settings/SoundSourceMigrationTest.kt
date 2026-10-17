package app.metrokeys.settings

import app.metrokeys.keyboard.SoundSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The one-time migration of `sound_source` (07 §9.9) and the default "MetroKeys" (07 §9.1). */
class SoundSourceMigrationTest {
    private val en = { listOf(Locale.US) }

    @Test
    fun freshInstallGetsMetroKeys() {
        val p = FakePrefs()
        assertEquals(SoundSource.METROKEYS, Prefs.soundSource(p))
        assertEquals("metrokeys", p.getString(Prefs.SOUND_SOURCE, null))
        assertTrue(p.getBoolean(Prefs.SOUND_SOURCE_MIGRATED, false))
        val writes = p.writes
        // runs once: no further writes
        assertEquals(SoundSource.METROKEYS, Prefs.soundSource(p))
        assertEquals(writes, p.writes)
    }

    @Test
    fun anEarlierSystemChoiceSwitchesOnceThenAnewChoiceStays() {
        val p = FakePrefs()
        p.edit().putString(Prefs.SOUND_SOURCE, "system").apply()
        assertEquals(SoundSource.METROKEYS, KeyboardSettings.read(p, en).soundSource)
        assertEquals("metrokeys", p.getString(Prefs.SOUND_SOURCE, null))
        // the user chooses Android's clicks after the migration: never changed again
        p.edit().putString(Prefs.SOUND_SOURCE, "system").apply()
        assertEquals(SoundSource.SYSTEM, Prefs.soundSource(p))
        assertEquals(SoundSource.SYSTEM, KeyboardSettings.read(p, en).soundSource)
        assertEquals("system", p.getString(Prefs.SOUND_SOURCE, null))
    }

    @Test
    fun ownSoundsAreKept() {
        val p = FakePrefs()
        p.edit().putString(Prefs.SOUND_SOURCE, "custom").apply()
        assertEquals(SoundSource.CUSTOM, Prefs.soundSource(p))
        assertEquals("custom", p.getString(Prefs.SOUND_SOURCE, null))
        assertTrue(p.getBoolean(Prefs.SOUND_SOURCE_MIGRATED, false))
    }

    @Test
    fun silentModeSettingDefaultsOff() {
        val p = FakePrefs()
        assertEquals(false, KeyboardSettings.read(p, en).keySoundsSilentMode)
        p.edit().putBoolean(Prefs.KEY_SOUNDS_SILENT_MODE, true).apply()
        assertEquals(true, KeyboardSettings.read(p, en).keySoundsSilentMode)
        assertEquals(SoundSource.METROKEYS, KeyboardSettings.DEFAULT.soundSource)
    }
}
