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

/** German umlaut transliteration (05 §1.1 `UMLAUT_TRANSLIT`, §5 umlaut rule, §8.1c transliterated spellings; 0.7.0). */
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
        assertEquals("für", fuer.corrections.first().display)
    }

    @Test
    fun transliteratedSpellings() {
        // 05 §8.1c: the walk runs once more on W with every "ae"/"oe"/"ue" replaced (case of the letter kept), and on W
        // with "ss" → "ß"; W itself is always searched as well
        assertEquals(listOf("fü"), de.transliterations("fue"))
        assertEquals(listOf("Übe"), de.transliterations("Uebe"))
        assertEquals(listOf("Feü"), de.transliterations("Feue"))
        assertEquals(listOf("Häuser"), de.transliterations("Haeuser"))
        assertEquals(listOf("schön"), de.transliterations("schoen"))
        assertEquals(listOf("FÜ"), de.transliterations("FUE"))
        assertEquals(listOf("Straße"), de.transliterations("Strasse"))
        assertEquals(emptyList(), de.transliterations("Haus"))
        // only the ß run in a language without the umlaut rule
        assertEquals(listOf("Straße"), TestDictionaries.english().transliterations("Strasse"))
        assertEquals(emptyList(), TestDictionaries.english().transliterations("fue"))
    }

    @Test
    fun completions() {
        // "fue" → "fü": the run hands over only completions longer than "fue" (05 §8.1c, 0.7.1), so "für" (as long as
        // "fue") is a correction and the first entry, "führen" a completion of the run
        val fue = de.query("fue")
        val fuer = fue.ranked.first { !it.isTyped }
        assertEquals("für", fuer.display)
        assertTrue(fuer.isCorrection)
        assertTrue(fue.ranked.none { !it.isCorrection && it.source == CandidateSource.TRANSLITERATION && it.display.length <= 3 })
        assertTrue(fue.ranked.any { !it.isCorrection && it.source == CandidateSource.TRANSLITERATION && it.display.startsWith("fü") })
        // "Strass" → "Straß": "Straße" (as long as "Strass") is no completion of the run, "Straßen" is (05 §8.1c)
        val strass = de.query("Strass")
        println("Strass: " + strass.ranked.take(8).map { "${it.display}/${it.source}/${if (it.isCorrection) "c" else "p"}" })
        assertTrue(strass.ranked.none { it.display == "Straße" && !it.isCorrection })
        val strassen = strass.ranked.first { it.display == "Straßen" }
        assertFalse(strassen.isCorrection)
        assertEquals(CandidateSource.TRANSLITERATION, strassen.source)
        // the plain prefix still completes: "Feue" → "Feuer"
        assertEquals("Feuer", de.query("Feue").ranked.first().display)
        // "Uebe" → "Über…"
        assertTrue(de.query("Uebe").ranked.take(5).any { it.display.startsWith("Über") })
    }
}
