package app.metrokeys.engine.predict

import app.metrokeys.engine.TestDictionaries
import app.metrokeys.engine.query
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Missing-space split of spec 05 §8.2 (bundled dictionaries, no learned data, fallback grid). */
class MissingSpaceTest {
    private val de = TestDictionaries.german()
    private val en = TestDictionaries.english()

    private fun LanguageSuggestions.splits(): List<Candidate> =
        (ranked + corrections).filter { ' ' in it.display }.distinct()

    @Test
    fun englishSplitsAreAutocorrected() {
        for ((typed, expected) in listOf("ofthe" to "of the", "inthe" to "in the", "thankyou" to "thank you")) {
            val r = en.query(typed)
            assertFalse(r.validity.valid, typed)
            assertEquals(expected, r.autoCorrection?.display, "$typed: ${r.corrections}")
            assertEquals(en.splitLm(expected.substringBefore(' '), expected.substringAfter(' ')) + 131,
                r.corrections.first { it.display == expected }.correctionCost, typed)
            assertEquals(listOf(expected), r.splits().map { it.display }, "$typed: at most one split")
        }
    }

    @Test
    fun pairCostWhenThePairIsKnown() {
        val pair = en.dictionary.pairs!!.bigram("of", "the")
        assertTrue(pair >= 0)
        val p = Math.pow(10.0, en.unigramLog10(en.lexicon.exactClass("of")) + LmCost.pairLog10(pair))
        assertEquals(LmCost.cost(p), en.splitLm("of", "the"))
    }

    @Test
    fun meanPartCostPlusSurchargeOtherwise() {
        val a = "res"
        val b = "tau"
        assertTrue(en.lexicon.exact(a) && en.lexicon.exact(b))
        val cost = { w: String -> LmCost.cost(Math.pow(10.0, en.unigramLog10(en.lexicon.exactClass(w)))) }
        if ((en.dictionary.pairs?.bigram(a, b) ?: -1) < 0) {
            assertEquals(Math.floorDiv(cost(a) + cost(b), 2) + Params.SPLIT_SURCHARGE, en.splitLm(a, b))
        }
        // a part missing from the word list costs SPLIT_UNKNOWN
        assertEquals(Math.floorDiv(cost("of") + Params.SPLIT_UNKNOWN, 2) + Params.SPLIT_SURCHARGE, en.splitLm("of", "qqxz"))
    }

    @Test
    fun restauGetsNoSplitAheadOfTheCompletions() {
        val r = en.query("restau")
        assertFalse(r.autoCorrection?.display.orEmpty().contains(' '), "${r.autoCorrection}")
        val firstCompletion = r.ranked.indexOfFirst { it.display.startsWith("restau") }
        assertTrue(firstCompletion >= 0, "${r.ranked}")
        val firstSplit = r.ranked.indexOfFirst { ' ' in it.display }
        assertTrue(firstSplit < 0 || firstSplit > firstCompletion, "${r.ranked}")
        assertTrue(r.ranked.none { it.display == "res tau" }, "${r.ranked}")
    }

    @Test
    fun oneLetterFirstPartOnlyForAAndI() {
        val r = en.query("iwant")
        r.splits().forEach { assertTrue(it.display.startsWith("I "), it.display) }
        // "x" + word is never a split
        assertTrue(en.query("xthe").splits().isEmpty())
    }

    @Test
    fun germanNeverSplits() {
        for (typed in listOf("imhaus", "dasist", "nikolaus", "Imhaus", "Dasist")) {
            val r = de.query(typed)
            assertTrue(r.splits().isEmpty(), "$typed: ${r.splits()}")
            assertFalse(r.autoCorrection?.display.orEmpty().contains(' '), typed)
        }
        val m = TestDictionaries.multilingual()
        for (typed in listOf("imhaus", "dasist", "nikolaus")) {
            assertTrue(m.query(app.metrokeys.engine.Query("Das ist $typed")).suggestions.none { ' ' in it.text }, typed)
        }
    }
}
