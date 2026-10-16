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
    fun spanishGeometry() {
        // 05 §4 and the conformance row "es: touch geometry": row 2 without indent, ñ its own key
        val gs = KeyboardGeometry.fallback("es-ES")
        val es = TouchModel(gs, LanguageParams.SPANISH)
        assertEquals(27, gs.keys.size)
        assertEquals(0f, gs.ownKey('a')!!.left)
        assertEquals(gs.ownKey('q')!!.centerX, gs.ownKey('a')!!.centerX)
        assertEquals(gs.ownKey('p')!!.centerX, gs.ownKey('ñ')!!.centerX)
        assertEquals(60, es.sub('a', 'q'))
        assertEquals(60, es.sub('ñ', 'p'))
        assertEquals(67, es.sub('z', 's'))
        assertEquals(67, es.sub('z', 'd'))
        assertEquals(90, es.sub('a', 'w'))
        assertEquals(90, es.sub('ñ', 'o'))
        assertEquals(49, es.sub('ñ', 'l'))
        assertEquals(49, es.sub('s', 'a'))
        assertEquals(185, es.sub('ñ', 'n'))
        assertEquals(185, es.sub('a', 'd'))
        // a tap just across the border: 29 instead of 49
        val a = gs.ownKey('a')!!
        assertEquals(29, es.substitutionCost('s', a.centerX + 0.55f * gs.keyWidth, a.centerY, 'a'))
        // the layout data agrees with the fallback grid
        val layout = TestDictionaries.layoutGeometry("es")
        assertEquals(27, layout.keys.size)
        for (k in gs.keys) {
            val l = layout.ownKey(k.letter)!!
            assertEquals(k.centerX, l.centerX, 0.01f, "es ${k.letter}")
            assertEquals(k.centerY, l.centerY, 0.01f, "es ${k.letter}")
        }
        assertEquals(27, TestDictionaries.layoutGeometry("es-multi").keys.size)
    }

    @Test
    fun portugueseGeometry() {
        // 05 §4 and the conformance row "pt: touch geometry": the Spanish geometry with ç in place of ñ
        val gp = KeyboardGeometry.fallback("pt-BR")
        val pt = TouchModel(gp, LanguageParams.PORTUGUESE)
        assertEquals(27, gp.keys.size)
        assertEquals(0f, gp.ownKey('a')!!.left)
        assertEquals(gp.ownKey('p')!!.centerX, gp.ownKey('ç')!!.centerX)
        assertEquals(55, pt.sub('a', 'q'))
        assertEquals(55, pt.sub('ç', 'p'))
        assertEquals(62, pt.sub('z', 's'))
        assertEquals(85, pt.sub('a', 'w'))
        assertEquals(85, pt.sub('ç', 'o'))
        assertEquals(44, pt.sub('ç', 'l'))
        assertEquals(44, pt.sub('s', 'a'))
        assertEquals(155, pt.sub('ç', 'c'))
        assertEquals(155, pt.sub('c', 'ç'))
        // a tap just across the border: 24 instead of 44
        val a = gp.ownKey('a')!!
        assertEquals(24, pt.substitutionCost('s', a.centerX + 0.55f * gp.keyWidth, a.centerY, 'a'))
        // on the Portuguese page ñ lives on n; on the Spanish page ç lives on c
        assertEquals(gp.ownKey('n'), gp.touchKey('ñ'))
        assertEquals('ç', gp.touchKey('Ç')!!.letter)
        assertEquals(KeyboardGeometry.fallback("es-ES").ownKey('c'), KeyboardGeometry.fallback("es-ES").touchKey('ç'))
        val layout = TestDictionaries.layoutGeometry("pt")
        assertEquals(27, layout.keys.size)
        for (k in gp.keys) {
            val l = layout.ownKey(k.letter)!!
            assertEquals(k.centerX, l.centerX, 0.01f, "pt ${k.letter}")
            assertEquals(k.centerY, l.centerY, 0.01f, "pt ${k.letter}")
        }
        assertEquals(27, TestDictionaries.layoutGeometry("pt-multi").keys.size)
    }

    @Test
    fun lettersWithoutAKeyLiveOnTheirBaseLetter() {
        // German and English pages: ñ on n, í on i, ç on c; the Spanish page: ñ is its own key, ü on u
        assertEquals(g.ownKey('n'), g.touchKey('ñ'))
        assertEquals(g.ownKey('i'), g.touchKey('í'))
        assertEquals(g.ownKey('c'), g.touchKey('ç'))
        assertEquals(g.ownKey('y'), g.touchKey('ý'))
        assertEquals(g.ownKey('s'), g.touchKey('ß'))
        assertEquals(g.ownKey('s'), g.touchKey('§'))
        val gs = KeyboardGeometry.fallback("es-ES")
        assertEquals('ñ', gs.touchKey('ñ')!!.letter)
        assertEquals('ñ', gs.touchKey('Ñ')!!.letter)
        assertEquals(gs.ownKey('u'), gs.touchKey('ü'))
        assertEquals(gs.ownKey('o'), gs.touchKey('ö'))
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
