package app.metrokeys.engine.predict

import app.metrokeys.engine.touch.KeyboardGeometry
import app.metrokeys.engine.touch.TouchModel
import kotlin.test.Test
import kotlin.test.assertEquals

/** Weighted edit distance of spec 05 §5. */
class EditDistanceTest {
    private val de = EditDistance(LanguageParams.GERMAN, TouchModel(KeyboardGeometry.fallback("de-DE"), LanguageParams.GERMAN))
    private val en = EditDistance(LanguageParams.ENGLISH, TouchModel(KeyboardGeometry.fallback("en-US"), LanguageParams.ENGLISH))

    private fun EditDistance.ed(t: String, c: String) = weighted(t, c, null)

    @Test
    fun caseDiacriticAndSwap() {
        assertEquals(44, de.ed("haus", "Haus"))
        assertEquals(11, en.ed("i", "I"))
        assertEquals(50, de.ed("Dnan", "Dann"))
        assertEquals(56, en.ed("teh", "the"))
        assertEquals(4, de.ed("fur", "für"))
        assertEquals(0, en.ed("fur", "für"))
        assertEquals(30, de.ed("Hsus", "Haus"))
        assertEquals(74, de.ed("hsus", "Haus"))
        assertEquals(49, en.ed("grwat", "great"))
    }

    @Test
    fun asymmetryOfDiacritics() {
        assertEquals(4, de.ed("u", "ü"))
        assertEquals(100, de.ed("ü", "u"))
    }

    @Test
    fun fillersSpacesAndEszett() {
        assertEquals(0, en.ed("dont", "don't"))
        assertEquals(0, de.ed("zB", "z.B"))
        assertEquals(225, de.ed("dasist", "das ist"))
        assertEquals(4, de.ed("strasse", "straße"))
        assertEquals(4, de.ed("Strasse", "Straße"))
        assertEquals(210, de.ed("Hau", "Haus"))
        assertEquals(175, de.ed("Hauss", "Haus"))
    }

    @Test
    fun overflow() {
        assertEquals(Params.ED_OVERFLOW, de.ed("a".repeat(19), "a"))
        assertEquals(Params.ED_OVERFLOW, EditDistance.units("a", "b".repeat(19)))
    }

    @Test
    fun units() {
        assertEquals(0, EditDistance.units("haus", "Haus"))
        assertEquals(1, EditDistance.units("Dnan", "Dann"))
        // 0.6.4: a diacritic difference counts 0 units (it used to count 1)
        assertEquals(0, EditDistance.units("fur", "für"))
        assertEquals(0, EditDistance.units("dont", "don't"))
        assertEquals(1, EditDistance.units("strasse", "straße"))
        assertEquals(1, EditDistance.units("dasist", "das ist"))
        assertEquals(2, EditDistance.units("abcd", "badc"))
    }
}
