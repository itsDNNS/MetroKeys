package app.metrokeys.engine.predict

import app.metrokeys.engine.TestDictionaries
import app.metrokeys.engine.query
import app.metrokeys.engine.touch.KeyboardGeometry
import app.metrokeys.engine.touch.TapSequence
import app.metrokeys.engine.touch.TouchModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** German umlaut transliteration (05 §1.1 `UMLAUT_TRANSLIT`, §5 umlaut rule, §8.4 item 5; 0.7.0). */
class UmlautTranslitTest {
    private val de = TestDictionaries.german()
    private val edDe = EditDistance(LanguageParams.GERMAN, TouchModel(KeyboardGeometry.fallback("de-DE"), LanguageParams.GERMAN))
    private val edEn = EditDistance(LanguageParams.ENGLISH, TouchModel(KeyboardGeometry.fallback("en-US"), LanguageParams.ENGLISH))

    @Test
    fun parameterOnlyForGerman() {
        for (p in LanguageParams.ALL) assertEquals(p.languageTag == "de-DE", p.umlautTranslit, p.languageTag)
    }

    @Test
    fun editCost() {
        // 05 §5: the transliteration costs what the missing dots cost (ED_DIACRITIC de = 4)
        assertEquals(4, edDe.weighted("fuer", "für", null))
        assertEquals(4, edDe.weighted("ueber", "über", null))
        assertEquals(4, edDe.weighted("Ueber", "Über", null))
        assertEquals(4, edDe.weighted("koennen", "können", null))
        assertEquals(4, edDe.weighted("Aepfel", "Äpfel", null))
        assertEquals(4, edDe.weighted("moegen", "mögen", null))
        // plus ED_CASE when the case of the first typed letter and of the umlaut differ (as in the ligature rule)
        assertEquals(48, edDe.weighted("ueber", "Über", null))
        assertEquals(48, edDe.weighted("Ueber", "über", null))
        // only the matching vowel: "oe" is no transliteration of ü
        assertTrue(edDe.weighted("foer", "für", null) > 100)
        // without the parameter (en: ED_DIACRITIC 0, so the rule would cost 0) the e is a deletion
        assertEquals(119, edEn.weighted("fuer", "für", null))
        val withoutRule = EditDistance(LanguageParams.GERMAN.copy(umlautTranslit = false),
            TouchModel(KeyboardGeometry.fallback("de-DE"), LanguageParams.GERMAN))
        assertEquals(4 + 175, withoutRule.weighted("fuer", "für", null))
    }

    @Test
    fun units() {
        // the umlaut rule counts 1 unit, like the ß and ligature rules
        assertEquals(1, EditDistance.units("fuer", "für", umlautTranslit = true))
        assertEquals(1, EditDistance.units("koennen", "können", umlautTranslit = true))
        assertEquals(1, EditDistance.units("Ueber", "Über", umlautTranslit = true))
        // the same as dropping the e after a diacritic difference (0 units), so the unit filters are unchanged
        assertEquals(1, EditDistance.units("fuer", "für"))
    }

    @Test
    fun autocorrectionsWithoutContext() {
        for ((typed, expected) in listOf("fuer" to "für", "ueber" to "über", "Ueber" to "Über", "koennen" to "können")) {
            val r = de.query(typed)
            assertFalse(r.validity.valid, typed)
            val c = r.corrections.first()
            assertEquals(expected, c.display, typed)
            assertEquals(4, c.ed, typed)
            assertEquals(expected, r.autoCorrection?.display, typed)
        }
        // "fuer" no longer loses to "Ufer" (one swap)
        val fuer = de.query("fuer")
        assertTrue(fuer.corrections.first { it.display == "Ufer" }.correctionCost > fuer.corrections.first().correctionCost)
    }

    @Test
    fun prefixVariants() {
        // 05 §8.4 item 5: every "ae"/"oe"/"ue" replaced, case kept, added after the other prefixes ("always, not counted
        // in 2"); the plain W stays the first prefix
        val plain = LanguageEngine(LanguageParams.GERMAN.copy(umlautTranslit = false), TestDictionaries.de)
        fun added(w: String): List<String> {
            val with = de.prefixList(w, TapSequence.none(w.length))
            val without = plain.prefixList(w, TapSequence.none(w.length))
            assertEquals(without, with.take(without.size), w)
            return with.drop(without.size)
        }
        assertEquals(listOf("fü"), added("fue"))
        assertEquals(listOf("Übe"), added("Uebe"))
        assertEquals(listOf("Feü"), added("Feue"))
        assertEquals(listOf("Häuser"), added("Haeuser"))
        assertEquals(listOf("schön"), added("schoen"))
        assertEquals(listOf("FÜ"), added("FUE"))
        assertEquals(emptyList(), added("Haus"))
        assertEquals("fue", de.prefixList("fue", TapSequence.none(3)).first())
    }

    @Test
    fun completions() {
        // "fue" → "fü" → "für"
        val fue = de.query("fue")
        val fuer = fue.ranked.first()
        assertEquals("für", fuer.display)
        assertTrue(fuer.completionCost < Candidate.INF)
        // the plain prefix still completes: "Feue" → "Feuer"
        assertEquals("Feuer", de.query("Feue").ranked.first().display)
        // "Uebe" → "Über…"
        assertTrue(de.query("Uebe").ranked.take(5).any { it.display.startsWith("Über") })
    }
}
