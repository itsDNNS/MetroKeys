package app.metrokeys.keyboard

import app.metrokeys.keyboard.layout.LayoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The sound rules of 03 §1.4 and 07 §9.8/§9.9: classes, sources, level relation, voices, conditions. */
class SoundPlanTest {
    private val normal = SoundConditions(soundsOn = true, volumePercent = 100, silentMode = false, ringerNormal = true, doNotDisturb = false)
    private val silent = normal.copy(ringerNormal = false)

    private fun sample(kind: SoundKind, source: SoundSource, files: Set<SoundSlot> = emptySet(), c: SoundConditions = normal) =
        SoundPlan.plan(kind, source, files, c) as Playback.Sample

    @Test
    fun levelRelation() {
        // 100 %: key and picker sounds 0.372, pick/add-to-dictionary/paste 1, "not recognised" capped at 1
        val at100 = SoundKind.entries.associateWith { SoundPlan.gain(it, 100) }
        for (k in listOf(SoundKind.LETTER, SoundKind.SPACE, SoundKind.FUNCTION, SoundKind.DELETE, SoundKind.RETURN, SoundKind.PICKER)) {
            assertEquals("$k", 0.372, at100.getValue(k), 1e-12)
        }
        for (k in listOf(SoundKind.PICK, SoundKind.DICTIONARY, SoundKind.PASTE, SoundKind.NOT_RECOGNISED)) {
            assertEquals("$k", 1.0, at100.getValue(k), 1e-12)
        }
        // 50 %: G = 0.25
        assertEquals(0.372 * 0.25, SoundPlan.gain(SoundKind.LETTER, 50), 1e-12)
        assertEquals(0.25, SoundPlan.gain(SoundKind.PASTE, 50), 1e-12)
        assertEquals(2.82 * 0.25, SoundPlan.gain(SoundKind.NOT_RECOGNISED, 50), 1e-12)
        // 60 %: 2.82 × 0.36 = 1.0152 → capped; 30 %: full 9 dB above the dictionary sound
        assertEquals(1.0, SoundPlan.gain(SoundKind.NOT_RECOGNISED, 60), 1e-12)
        assertEquals(2.82 * 0.09, SoundPlan.gain(SoundKind.NOT_RECOGNISED, 30), 1e-12)
        assertEquals(0.0, SoundPlan.gain(SoundKind.LETTER, 0), 0.0)
        // pick, add-to-dictionary and paste are 8.6 dB above the keys
        assertEquals(8.6, 20 * Math.log10(1 / SoundPlan.KEY_FACTOR), 0.05)
        // the gain is applied to the played sample
        assertEquals((0.372 * 0.64).toFloat(), sample(SoundKind.SPACE, SoundSource.METROKEYS, c = normal.copy(volumePercent = 80)).gain, 1e-7f)
    }

    @Test
    fun metroKeysSourcePlaysTheGeneratedSoundOfEverySlot() {
        val expected = mapOf(
            SoundKind.LETTER to GeneratedSound.LETTER, SoundKind.SPACE to GeneratedSound.SPACE,
            SoundKind.FUNCTION to GeneratedSound.FUNCTION, SoundKind.DELETE to GeneratedSound.FUNCTION,
            SoundKind.RETURN to GeneratedSound.FUNCTION, SoundKind.PICKER to GeneratedSound.PICKER,
            SoundKind.PICK to GeneratedSound.FUNCTION, SoundKind.DICTIONARY to GeneratedSound.DICTIONARY,
            SoundKind.NOT_RECOGNISED to GeneratedSound.DICTIONARY, SoundKind.PASTE to GeneratedSound.PASTE,
        )
        for ((k, g) in expected) {
            val s = sample(k, SoundSource.METROKEYS)
            assertEquals("$k", g, s.generated)
            assertNull(s.userSlot)
            assertFalse(s.media)
        }
    }

    @Test
    fun systemSourceUsesPlatformClicksWhereTheyExist() {
        val effects = mapOf(
            SoundKind.LETTER to ClickEffect.STANDARD, SoundKind.SPACE to ClickEffect.SPACEBAR,
            SoundKind.FUNCTION to ClickEffect.STANDARD, SoundKind.DELETE to ClickEffect.DELETE,
            SoundKind.RETURN to ClickEffect.RETURN, SoundKind.PICK to ClickEffect.STANDARD,
        )
        for ((k, e) in effects) assertEquals("$k", Playback.Platform(e, -1f), SoundPlan.plan(k, SoundSource.SYSTEM, emptySet(), normal))
        // picker, add-to-dictionary and paste have no platform click: the generated sounds
        assertEquals(GeneratedSound.PICKER, sample(SoundKind.PICKER, SoundSource.SYSTEM).generated)
        assertEquals(GeneratedSound.DICTIONARY, sample(SoundKind.DICTIONARY, SoundSource.SYSTEM).generated)
        assertEquals(GeneratedSound.DICTIONARY, sample(SoundKind.NOT_RECOGNISED, SoundSource.SYSTEM).generated)
        assertEquals(GeneratedSound.PASTE, sample(SoundKind.PASTE, SoundSource.SYSTEM).generated)
        // platform volume (07 §9.8 item 3): the system's click volume at 100 %, else 0.5 × G
        assertEquals(Playback.Platform(ClickEffect.STANDARD, 0.125f), SoundPlan.plan(SoundKind.LETTER, SoundSource.SYSTEM, emptySet(), normal.copy(volumePercent = 50)))
    }

    @Test
    fun customSourceFallsBackToTheGeneratedSoundOfTheSlot() {
        val files = setOf(SoundSlot.LETTER, SoundSlot.FUNCTION, SoundSlot.PASTE)
        assertEquals(SoundSlot.LETTER, sample(SoundKind.LETTER, SoundSource.CUSTOM, files).userSlot)
        assertEquals(SoundSlot.FUNCTION, sample(SoundKind.DELETE, SoundSource.CUSTOM, files).userSlot)
        assertEquals(SoundSlot.PASTE, sample(SoundKind.PASTE, SoundSource.CUSTOM, files).userSlot)
        // a missing space file: the generated space sound, not the letter file and never a platform click
        sample(SoundKind.SPACE, SoundSource.CUSTOM, files).let {
            assertNull(it.userSlot)
            assertEquals(GeneratedSound.SPACE, it.generated)
        }
        assertEquals(GeneratedSound.PICKER, sample(SoundKind.PICKER, SoundSource.CUSTOM, files).generated)
        // the pick slot without its own file follows the function slot: the user's function file …
        assertEquals(SoundSlot.FUNCTION, sample(SoundKind.PICK, SoundSource.CUSTOM, files).userSlot)
        // … else the generated function sound; with its own file that file
        assertEquals(GeneratedSound.FUNCTION, sample(SoundKind.PICK, SoundSource.CUSTOM, setOf(SoundSlot.LETTER)).generated)
        assertEquals(SoundSlot.PICK, sample(SoundKind.PICK, SoundSource.CUSTOM, files + SoundSlot.PICK).userSlot)
        // files only count for the custom source
        assertNull(sample(SoundKind.LETTER, SoundSource.METROKEYS, files).userSlot)
    }

    @Test
    fun keyAndPickerSoundsShareOneVoice() {
        for (k in SoundKind.entries) {
            val voice = k in setOf(SoundKind.LETTER, SoundKind.SPACE, SoundKind.FUNCTION, SoundKind.DELETE, SoundKind.RETURN, SoundKind.PICKER)
            assertEquals("$k", voice, sample(k, SoundSource.METROKEYS).keyVoice)
        }
    }

    @Test
    fun conditions() {
        for (k in SoundKind.entries) {
            for (src in SoundSource.entries) {
                assertNull(SoundPlan.plan(k, src, emptySet(), normal.copy(soundsOn = false)))
                assertNull(SoundPlan.plan(k, src, emptySet(), normal.copy(volumePercent = 0)))
                // do-not-disturb mutes everything, also with the silent-mode setting
                assertNull(SoundPlan.plan(k, src, emptySet(), normal.copy(doNotDisturb = true)))
                assertNull(SoundPlan.plan(k, src, emptySet(), silent.copy(silentMode = true, doNotDisturb = true)))
                // silent or vibrate without the silent-mode setting: nothing
                assertNull(SoundPlan.plan(k, src, emptySet(), silent))
            }
        }
    }

    @Test
    fun silentModePlaybackGoesThroughTheMediaVolume() {
        val c = silent.copy(silentMode = true, volumePercent = 70)
        for (k in SoundKind.entries) {
            for (src in SoundSource.entries) {
                val s = sample(k, src, emptySet(), c)
                assertTrue("$k $src", s.media)
                // the level relation applies alike
                assertEquals((SoundPlan.gain(k, 70)).toFloat(), s.gain, 0f)
            }
        }
        // the platform clicks are replaced by the generated sound of the slot
        assertEquals(GeneratedSound.SPACE, sample(SoundKind.SPACE, SoundSource.SYSTEM, c = c).generated)
        assertEquals(GeneratedSound.FUNCTION, sample(SoundKind.PICK, SoundSource.SYSTEM, c = c).generated)
        // in normal ringer mode the setting changes nothing
        assertEquals(Playback.Platform(ClickEffect.DELETE, -1f), SoundPlan.plan(SoundKind.DELETE, SoundSource.SYSTEM, emptySet(), normal.copy(silentMode = true)))
        assertFalse(sample(SoundKind.LETTER, SoundSource.METROKEYS, c = normal.copy(silentMode = true)).media)
    }

    @Test
    fun soundClassByTheKeysLook() {
        val layout = LayoutSet.parse(File("../spec/data/layouts-de.json").readText())
        for (id in layout.pages.keys) {
            val page = layout.page(id)
            for (k in page.keys.filter { it.hittable }) {
                val kind = SoundKind.forKey(k, page)
                val expected = when {
                    k.role == app.metrokeys.keyboard.layout.KeyRole.SPACE && (page.isLetters || page.isSymbols) -> SoundSlot.SPACE
                    k.appearance == app.metrokeys.keyboard.layout.Appearance.NORMAL -> SoundSlot.LETTER
                    else -> SoundSlot.FUNCTION
                }
                assertEquals("$id ${k.role} ${k.output}", expected, kind.slot)
            }
        }
        val letters = layout.page("text.letters")
        fun of(role: String) = SoundKind.forKey(letters.keys.first { it.role.name == role }, letters)
        assertEquals(SoundKind.DELETE, of("BACKSPACE"))
        assertEquals(SoundKind.RETURN, of("ENTER"))
        assertEquals(SoundKind.FUNCTION, of("SHIFT"))
        assertEquals(SoundKind.FUNCTION, of("PAGE"))
        assertEquals(SoundKind.SPACE, of("SPACE"))
        assertEquals(SoundKind.LETTER, SoundKind.forKey(letters.keys.first { it.output == "q" }, letters))
        assertEquals(SoundKind.FUNCTION, SoundKind.forKey(letters.keys.first { it.output == "," }, letters))
        // the emoji category tabs and the dial pad's space bar play the function sound
        val emoji = layout.pages.keys.map { layout.page(it) }.first { it.kind == app.metrokeys.keyboard.layout.PageKind.EMOJI }
        assertEquals(SoundKind.FUNCTION, SoundKind.forKey(emoji.keys.first { it.role.name == "EMOJI_CATEGORY" }, emoji))
        val dial = layout.page("phone.dialpad")
        assertEquals(SoundKind.FUNCTION, SoundKind.forKey(dial.keys.first { it.role.name == "SPACE" }, dial))
    }

    @Test
    fun soundSourceValues() {
        assertEquals(SoundSource.METROKEYS, SoundSource.of(null))
        assertEquals(SoundSource.METROKEYS, SoundSource.of("bogus"))
        assertEquals(SoundSource.SYSTEM, SoundSource.of("system"))
        assertEquals(SoundSource.CUSTOM, SoundSource.of("custom"))
        assertEquals(listOf("letter", "space", "function", "picker", "candidate", "dictionary", "paste"), SoundSlot.entries.map { it.file })
        assertEquals(app.metrokeys.settings.ImportRules.SOUND_SLOTS, SoundSlot.entries.map { it.file })
    }
}
