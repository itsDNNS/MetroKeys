package app.metrokeys.engine.predict

import app.metrokeys.engine.TestDictionaries
import app.metrokeys.engine.lexicon.Lexicon
import app.metrokeys.engine.lexicon.WordForm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The spelling source of spec 05d on small word lists, with costs computed by hand from the rules of §3–§5 and the
 * parameters of §9 (German unless stated). In a list of a few forms every form has spelling class 1 (rank ≤
 * `SPELL_RANK_1`), so every word end adds F1 = −5.
 */
class SpellingSourceTest {
    private fun source(tag: String, vararg forms: String): SpellingSource {
        val entries = forms.groupBy { app.metrokeys.engine.text.toKey(it) }.mapValues { (_, v) -> v.map { WordForm(it, 200) } }
        val lex = Lexicon.fromEntries(tag, 16f, 1000000, 100000000, entries)
        return SpellingSource(lex, SpellingParams.forLanguage(tag))
    }

    private fun SpellingSource.costs(typed: String, valid: Boolean = false): Map<String, Int> {
        val r = suggest(typed, valid)
        return r.words.zip(r.costs).toMap()
    }

    @Test
    fun substitutionVowelForConsonantWithAdjacentKey() {
        // typed x for a (§4.5.3 rule 4): vowel/consonant P10 43, allowed because the look-ahead matches, minus the
        // adjacent-key discount P20 6 (x is an adjacent key of a); word end F1 −5: 32
        assertEquals(mapOf("haus" to 32), source("de-DE", "haus").costs("hxus"))
    }

    @Test
    fun omittedLetter() {
        // "hus" for "haus" (§4.5 item 4): omitted a, P11 29, not the first letter; F1 −5: 24
        assertEquals(mapOf("haus" to 24), source("de-DE", "haus").costs("hus"))
    }

    @Test
    fun extraLetterDoublingAConsonant() {
        // "hauss" (§4.8, §4.5.1): the extra s at the word end doubles the s before it, P2 22; F1 −5: 17
        assertEquals(mapOf("haus" to 17), source("de-DE", "haus").costs("hauss"))
    }

    @Test
    fun swapAtTheWordEnd() {
        // "hasu" (§4.5 item 4): swap P6 22, at the last typed index −E5 2; F1 −5: 15
        assertEquals(mapOf("haus" to 15), source("de-DE", "haus").costs("hasu"))
    }

    @Test
    fun pairTableAndVowelClasses() {
        // "hoes" for "haus" needs the second round (§5.1): o for a by the pair table o→a 44, e for u two vowels of
        // different classes P8 28; F1 −5: 67 > S0 52, found with the limit 2 · 52 of round 1
        assertEquals(mapOf("haus" to 67), source("de-DE", "haus").costs("hoes"))
        // u for ü by the pair table (u→ü 3) instead of the accent cost P28 7
        assertEquals(mapOf("für" to 0), source("de-DE", "für").costs("fur").mapValues { (_, c) -> c })
    }

    @Test
    fun groupRule() {
        // "k→ck 23" (typed k where the candidate has ck): "dek" → "deck": rule shape at the word end; the cost is the
        // rule cost minus the word-end discount and F1
        val c = source("de-DE", "deck").costs("dek")
        assertTrue("deck" in c, c.toString())
        assertTrue(c.getValue("deck") < 23, c.toString())
        // without the rule the omitted c costs P11 29 − 5 = 24: the rule is cheaper
        val noRule = source("en-US", "deck").costs("dek")
        assertTrue(c.getValue("deck") <= noRule.getValue("deck"), "$c $noRule")
    }

    @Test
    fun casePenaltyOfAnAllCapsCandidate() {
        // en: typed "nasa" for "NASA" (§4.9): lower-case start, all-caps candidate, mism 3 = l − 1: P16 18 + P18 45 / 10
        // = 22; F1 −5: 17
        assertEquals(mapOf("NASA" to 17), source("en-US", "NASA").costs("nasa"))
        // a capital initial alone costs P16 (de 5), which F1 offsets
        assertEquals(mapOf("Haus" to 0), source("de-DE", "Haus").costs("haus"))
    }

    @Test
    fun twoWordCandidate() {
        // en "ofthe" (§3.3): "of" matched exactly (net 0), the space omitted P13 47 with the space mark, F1 of both words:
        // 47 − 10 = 37
        assertEquals(37, source("en-US", "of", "the").costs("ofthe").getValue("of the"))
        // the first letter of the second word takes the typed capital (§3.1 step 2.2)
        assertTrue("of The" in source("en-US", "of", "the").costs("ofThe"))
    }

    @Test
    fun confidenceKeepsOnlyTheBest() {
        // "hauss" (m = 5): R = 76, T = 21; best 17 ≤ 21 and no runner-up → confident: only the first word (§5.2, §5.3)
        val r = source("de-DE", "haus").suggest("hauss", false)
        assertTrue(r.confident)
        assertEquals(listOf("haus"), r.words)
        // a runner-up within the ratio ("hausse": the e after the typed end P11 − E1 = 23, −5: 18 ≤ 17 · 76 / 32)
        val two = source("de-DE", "haus", "hausse").suggest("hauss", false)
        assertFalse(two.confident)
        assertEquals(listOf("haus", "hausse"), two.words)
    }

    @Test
    fun validTypedWordKeepsWordOrderAndIsAppended() {
        // §5.4: the list stays sorted by word (no cost sort for a word of ≤ 3 letters), the typed word appended
        val r = source("de-DE", "das", "dass", "was", "dad").suggest("das", true)
        assertEquals("das", r.words.last())
        assertEquals(r.words.dropLast(1).sorted(), r.words.dropLast(1))
    }

    @Test
    fun transliteratedGermanInput() {
        // §5.5: ue → ü (cost 2), ss → ß (cost 1); the forms found are the whole result, no search runs
        val r = source("de-DE", "für", "fur").suggest("fuer", false)
        assertEquals(listOf("für"), r.words)
        assertEquals(listOf(2), r.costs)
        assertEquals(0, r.visits)
        val s = source("de-DE", "Straße").suggest("Strasse", false)
        assertEquals(listOf("Straße"), s.words)
        assertEquals(listOf(1), s.costs)
        // the first letter's case differs: + 1
        assertEquals(listOf(3), source("de-DE", "Übel").suggest("uebel", false).costs)
    }

    @Test
    fun frenchUnaccentedCapitals() {
        // §5.3 (French only): a word with an accented capital is added once more with the capital unaccented, and the
        // typed word itself is dropped
        val r = source("fr-FR", "état", "étal").suggest("Etat", false)
        assertTrue("État" in r.words, r.words.toString())
        assertFalse("Etat" in r.words, r.words.toString())
    }

    @Test
    fun bundledGermanListGivesTheExamplesOf05() {
        val de = TestDictionaries.german()
        assertEquals(listOf("Dann", "Dan", "DNA", "Dran", "Dän"), de.spelling.suggest("Dnan", false).words.take(5))
        assertEquals(listOf("machte", "mochte", "möchte"), de.spelling.suggest("mchte", false).words.take(3))
        assertEquals("nicht", de.spelling.suggest("nict", false).words.first())
        // every visit is counted; the budget is far away
        val r = de.spelling.suggest("Uberpeufe", false)
        assertTrue(r.visits in 1 until SpellingSource.SPELL_VISIT_MAX)
        assertFalse(r.budgetReached)
    }

    @Test
    fun cancellation() {
        val de = TestDictionaries.german()
        var calls = 0
        val e = kotlin.runCatching { de.spelling.suggest("Uberpeufe", false) { ++calls > 2 } }.exceptionOrNull()
        assertTrue(e is QueryCancelledException, e.toString())
        assertEquals(3, calls)
    }

    @Test
    fun introsortOrderOfEqualCosts() {
        // Appendix A: for up to 32 entries the cost sort is an insertion sort and keeps the word order of equal costs
        val de = TestDictionaries.german()
        val r = de.spelling.suggest("hsus", false)
        for (i in 1 until r.costs.size) assertTrue(r.costs[i - 1] <= r.costs[i], r.costs.toString())
    }
}
