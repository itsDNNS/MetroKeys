package app.metrokeys.engine.touch

import app.metrokeys.engine.TestDictionaries
import app.metrokeys.engine.predict.LanguageParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Touch model table of spec 05 §4. */
class TouchModelTest {
    private val g = KeyboardGeometry.fallback("de-DE")
    private val de = TouchModel(g, LanguageParams.GERMAN)
    private val en = TouchModel(KeyboardGeometry.fallback("en-US"), LanguageParams.ENGLISH)

    private fun TouchModel.noTap(t: Char, i: Char) = proximity(t, Float.NaN, Float.NaN, i)
    private fun TouchModel.sub(t: Char, i: Char) = substitutionCost(t, Float.NaN, Float.NaN, i)

    @Test
    fun keyCentreTable() {
        assertEquals(13516.8, de.touchCost(g.ownKey('s')!!.centerX, g.ownKey('s')!!.centerY, g.ownKey('a')!!), 1e-3)
        assertEquals(386, de.noTap('s', 'a'))
        assertEquals(30, de.sub('s', 'a'))
        assertEquals(49, en.sub('s', 'a'))
        assertEquals(623, de.noTap('a', 'q'))
        assertEquals(623, de.noTap('a', 'w'))
        assertEquals(48, de.sub('a', 'q'))
        assertEquals(67, en.sub('a', 'q'))
        assertEquals(527, de.noTap('y', 's'))
        assertEquals(41, de.sub('y', 's'))
        assertEquals(60, en.sub('z', 's'))
        assertEquals(913, de.noTap('y', 'a'))
        assertEquals(71, de.sub('y', 'a'))
        assertEquals(90, en.sub('z', 'a'))
        assertEquals(-1, de.noTap('a', 'd'))
        assertEquals(100, de.sub('a', 'd'))
        assertEquals(140, en.sub('a', 'd'))
    }

    @Test
    fun sameKeyAndMissingKey() {
        // ü lives on the u key: without a tap it is not a neighbour of u (substitution cost)
        assertEquals(-1, de.noTap('ü', 'u'))
        assertEquals(100, de.sub('ü', 'u'))
        assertEquals(-1, de.noTap('a', '1'))
    }

    @Test
    fun realTapAcrossTheBorder() {
        val a = g.ownKey('a')!!
        val v = de.proximity('s', a.centerX + 0.55f * g.keyWidth, a.centerY, 'a')
        assertEquals(117, v)
        assertEquals(10, de.substitutionCost('s', a.centerX + 0.55f * g.keyWidth, a.centerY, 'a'))
    }

    @Test
    fun layoutDataMatchesTheFallbackGrid() {
        for (lang in listOf("de", "en")) {
            val layout = TestDictionaries.layoutGeometry(lang)
            val fallback = KeyboardGeometry.fallback(if (lang == "de") "de-DE" else "en-US")
            assertEquals(26, layout.keys.size)
            assertEquals(108f, layout.keyWidth, 0.01f)
            assertEquals(189f, layout.keyHeight, 0.01f)
            for (k in fallback.keys) {
                val l = layout.ownKey(k.letter)!!
                assertEquals(k.centerX, l.centerX, 0.01f, "$lang ${k.letter}")
                assertEquals(k.centerY, l.centerY, 0.01f, "$lang ${k.letter}")
            }
        }
        val multi = KeyboardGeometry.fromLayoutJson(TestDictionaries.specFile("layouts-de-multi.json").readText())
        assertEquals(26, multi.keys.size)
    }

    @Test
    fun tapAlignmentFromTheEnd() {
        val taps = listOf(Tap('x', 1f, 1f), Tap('h', 2f, 2f), Tap('a', 3f, 3f), Tap('Z', 4f, 4f))
        val seq = TapSequence.align("Haus", taps)
        // aligned from the end: s↔Z (mismatch → unknown), u↔a (mismatch), a↔h (mismatch), H↔x
        assertEquals(4, seq.size)
        assertEquals(Float.NaN, seq.x(3))
        val seq2 = TapSequence.align("Haus", listOf(Tap('h', 2f, 2f), Tap('a', 3f, 3f), Tap('u', 5f, 5f), Tap('S', 6f, 6f)))
        assertEquals(2f, seq2.x(0))
        assertEquals(6f, seq2.x(3))
        val short = TapSequence.align("Haus", listOf(Tap('u', 5f, 5f), Tap('s', 6f, 6f)))
        assertEquals(Float.NaN, short.x(0))
        assertEquals(5f, short.x(2))
        assertNull(g.touchKey('1'))
    }
}
