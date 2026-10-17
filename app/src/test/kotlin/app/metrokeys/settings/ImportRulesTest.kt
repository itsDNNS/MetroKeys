package app.metrokeys.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/** File rules of spec 07 §9.9 (sounds) and §9.11 (fonts). */
class ImportRulesTest {
    @Test
    fun soundSlotsFollowTheFirstMatchingRule() {
        val cases = mapOf(
            "AddToDictionary.wav" to "dictionary",
            "candidate-select.WAV" to "candidate",
            "Suggestion.wav" to "candidate",
            "Paste.wav" to "paste",
            "clipboard-key.wav" to "paste",
            // rule order: "candidate" before "paste", "paste" before "picker" and "key"
            "candidate_paste.wav" to "candidate",
            "paste_picker.wav" to "paste",
            "picker_open.wav" to "picker",
            "accent popup.wav" to "picker",
            "alternates.wav" to "picker",
            "Key_Space-01.wav" to "space",
            "function key.wav" to "function",
            "letter.wav" to "letter",
            "tap2.wav" to "letter",
            "KEY.wav" to "letter",
            // rule order: "dictionary" before "key", "space" before "key"
            "dictionary_key.wav" to "dictionary",
            "spacekey.wav" to "space",
            // non-letters are ignored: "spa ce" → "space"
            "spa-ce.wav" to "space",
        )
        for ((name, slot) in cases) assertEquals(name, slot, ImportRules.soundSlotForName(name))
        assertNull(ImportRules.soundSlotForName("random.wav"))
        // the extension is removed before matching
        assertNull(ImportRules.soundSlotForName("click.letterwav"))
        assertNull(ImportRules.soundSlotForName("music.mp3"))
    }

    private fun wav(dataBytes: Int): ByteArray {
        val b = ByteArray(44 + dataBytes)
        "RIFF".toByteArray().copyInto(b, 0)
        "WAVE".toByteArray().copyInto(b, 8)
        return b
    }

    @Test
    fun wavFilesNeedARiffWaveHeaderAndAtMostFourMebibytes() {
        assertTrue(ImportRules.isWav(wav(100)))
        assertFalse(ImportRules.isWav(wav(0)))
        assertFalse(ImportRules.isWav(wav(100).also { it[8] = 'X'.code.toByte() }))
        assertFalse(ImportRules.isWav(wav((ImportRules.MAX_SOUND_BYTES - 44 + 1).toInt())))
        assertTrue(ImportRules.isWav(wav((ImportRules.MAX_SOUND_BYTES - 44).toInt())))
    }

    /** A minimal sfnt with an OS/2 table (weight, fsSelection) and a name table with the given names. */
    private fun font(weight: Int, italic: Boolean, names: Map<Int, String>, version: Int = 0x00010000): ByteArray {
        val os2 = ByteArray(78)
        os2[4] = (weight shr 8).toByte()
        os2[5] = weight.toByte()
        if (italic) os2[63] = 1
        val strings = ByteArrayOutputStream()
        val records = ByteArrayOutputStream()
        val rec = DataOutputStream(records)
        for ((id, text) in names) {
            val bytes = text.toByteArray(Charsets.UTF_16BE)
            rec.writeShort(3)
            rec.writeShort(1)
            rec.writeShort(0x409)
            rec.writeShort(id)
            rec.writeShort(bytes.size)
            rec.writeShort(strings.size())
            strings.write(bytes)
        }
        val name = ByteArrayOutputStream()
        DataOutputStream(name).apply {
            writeShort(0)
            writeShort(names.size)
            writeShort(6 + names.size * 12)
            write(records.toByteArray())
            write(strings.toByteArray())
        }
        val tables = listOf("OS/2" to os2, "name" to name.toByteArray())
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeInt(version)
        d.writeShort(tables.size)
        d.writeShort(0)
        d.writeShort(0)
        d.writeShort(0)
        var offset = 12 + 16 * tables.size
        for ((tag, data) in tables) {
            d.write(tag.toByteArray(Charsets.ISO_8859_1))
            d.writeInt(0)
            d.writeInt(offset)
            d.writeInt(data.size)
            offset += data.size
        }
        for ((_, data) in tables) d.write(data)
        return out.toByteArray()
    }

    @Test
    fun fontSlotComesFromTheWeightClassAndTheFamilyName() {
        fun slot(b: ByteArray) = ImportRules.parseFont(b)?.let { ImportRules.fontSlot(it) }
        assertEquals("light", slot(font(300, false, mapOf(1 to "Demo Light"))))
        assertEquals("semilight", slot(font(350, false, mapOf(1 to "Demo"))))
        assertEquals("regular", slot(font(400, false, mapOf(1 to "Demo"))))
        assertEquals("semibold", slot(font(600, false, mapOf(1 to "Demo"))))
        assertEquals("bold", slot(font(700, false, mapOf(1 to "Demo"))))
        // the file name or the family name does not decide the weight
        assertEquals("regular", slot(font(400, false, mapOf(1 to "Demo Bold"))))
        // italic, too thin and too heavy are rejected
        assertNull(slot(font(400, true, mapOf(1 to "Demo"))))
        assertNull(slot(font(200, false, mapOf(1 to "Demo"))))
        assertNull(slot(font(900, false, mapOf(1 to "Demo"))))
        // a family containing "emoji" goes to the emoji slot, preferring the typographic family (ID 16)
        assertEquals("emoji", slot(font(400, false, mapOf(1 to "Demo", 16 to "Demo Emoji"))))
        assertEquals("regular", slot(font(400, false, mapOf(1 to "Emoji Demo", 16 to "Demo"))))
        // OpenType CFF ("OTTO") and Apple "true" headers are accepted, anything else is not
        assertNotNull(ImportRules.parseFont(font(400, false, mapOf(1 to "A"), 0x4F54544F)))
        assertNotNull(ImportRules.parseFont(font(400, false, mapOf(1 to "A"), 0x74727565)))
        assertNull(ImportRules.parseFont(font(400, false, mapOf(1 to "A"), 0x774F4646)))
        assertNull(ImportRules.parseFont(ByteArray(8)))
    }

    @Test
    fun styleNameDecidesBeforeTheWeightClass() {
        fun slot(b: ByteArray) = ImportRules.parseFont(b)?.let { ImportRules.fontSlot(it) }
        // a semilight face that declares weight class 300 (like Selawik Semilight)
        assertEquals("semilight", slot(font(300, false, mapOf(1 to "Demo Semilight", 2 to "Regular", 17 to "Semilight"))))
        // name ID 17 is preferred over ID 2
        assertEquals("semibold", slot(font(700, false, mapOf(1 to "Demo", 2 to "Bold", 17 to "SemiBold"))))
        // ID 2 alone, also with spaces and other case
        assertEquals("semilight", slot(font(400, false, mapOf(1 to "Demo", 2 to "Semi Light"))))
        assertEquals("light", slot(font(400, false, mapOf(1 to "Demo", 2 to "Light"))))
        assertEquals("bold", slot(font(400, false, mapOf(1 to "Demo", 2 to "bold"))))
        assertEquals("regular", slot(font(300, false, mapOf(1 to "Demo", 2 to "Regular"))))
        // the style decides also for weights outside the table
        assertEquals("bold", slot(font(850, false, mapOf(1 to "Demo", 2 to "Extra Bold"))))
        // an unknown style word falls back to the weight class
        assertEquals("semibold", slot(font(600, false, mapOf(1 to "Demo", 2 to "Medium"))))
        assertNull(slot(font(900, false, mapOf(1 to "Demo", 2 to "Black"))))
        // italic is still rejected, emoji still wins
        assertNull(slot(font(400, true, mapOf(1 to "Demo", 2 to "Bold Italic"))))
        assertEquals("emoji", slot(font(400, false, mapOf(1 to "Demo Emoji", 2 to "Bold"))))
    }

    @Test
    fun bundledSelawikFilesAreDetectedByTheirStyle() {
        val dir = File("src/main/res/font")
        // Selawik Semilight declares weight class 300 (like Light); its style name puts it in the Semilight slot
        val expected = mapOf(
            "selawik_light.ttf" to "light", "selawik_semilight.ttf" to "semilight", "selawik_regular.ttf" to "regular",
            "selawik_semibold.ttf" to "semibold", "selawik_bold.ttf" to "bold",
        )
        for ((file, slot) in expected) {
            val info = ImportRules.parseFont(File(dir, file).readBytes())
            assertNotNull(file, info)
            assertEquals(file, slot, ImportRules.fontSlot(info!!))
            assertTrue(file, info.family!!.startsWith("Selawik"))
        }
    }
}
