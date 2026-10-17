package app.metrokeys.engine.predict

import app.metrokeys.engine.EngineSettings
import app.metrokeys.engine.PredictionEngine
import app.metrokeys.engine.Query
import app.metrokeys.engine.TestDictionaries
import app.metrokeys.engine.barTexts
import app.metrokeys.engine.entry
import app.metrokeys.engine.lexicon.Lexicon
import app.metrokeys.engine.query
import app.metrokeys.engine.touch.TapSequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Italian worked examples of spec 05 §17.10 and the it rows of the conformance checklist (§18) with the built
 * dictionary (no learned data, no taps, Italian fallback grid = the English one; 0.7.1 rules). The outcomes are
 * asserted as the spec words them; the lm values and scores come from the build (informative, 05 §17.10).
 */
class ItalianExamplesTest {
    private val it = TestDictionaries.italian()
    private val engine = TestDictionaries.single(TestDictionaries.italian())

    private fun ac(text: String): String? = engine.query(Query(text)).autoCorrection

    private fun bar(text: String): List<String> = engine.query(Query(text)).suggestions.map { s -> s.text }

    private fun show(label: String, text: String) {
        val r = it.query(text)
        println("$label: valid=${r.validity} ac=${r.autoCorrection} bar=${bar(text)}")
    }

    @Test
    fun header() {
        // 05 §3.1: corpusTokens = all counts of the frequency list; S from maxCount ("non" after the accent-loss transfer)
        assertEquals(247_073_874L, it.lexicon.corpusTokens)
        assertEquals(-8.39, it.a, 1e-9)
        println("it header: S=${it.lexicon.scale} maxCount=${it.lexicon.maxCount} Bc=${it.bc}")
        // 0.7.0: with the combined frequency of 05b §12.2 "di" is the most frequent key (0.6.9: "non")
        assertEquals(255, it.lexicon.exactClass("di"))
        assertEquals(LmCost.constantBc(it.lexicon.scale), it.bc)
        // the prefix table (05b §1.1): 41 prefixes with classes, "l'" the most frequent
        assertEquals(41, it.lexicon.prefixCount)
        assertTrue(it.lexicon.prefixClass("l'") > it.lexicon.prefixClass("dell'"))
        assertEquals(it.lexicon.prefixClass("l'"), it.lexicon.prefixClass("L’"))
        assertEquals(-1, it.lexicon.prefixClass("qual'"))
        // prefixes are no keys: never valid alone, no completion
        assertFalse(it.lexicon.exact("l'"))
        assertTrue(it.lexicon.completions("l'", 5).isEmpty())
        // one-letter keys: exactly a, e, è, i, o (05 §1.1)
        val one = it.lexicon.words().filter { w -> w.text.length == 1 }.map { w -> w.text.lowercase() }.toSet()
        assertEquals(setOf("a", "e", "è", "i", "o"), one)
    }

    @Test
    fun example1Accents() {
        // "perche" is a dictionary word kept with class 0 (05b §10.4 step 5): valid, but not valid for the decision
        val p = it.query("perche")
        assertTrue(p.validity.valid)
        assertTrue(p.validity.classZero)
        assertEquals(0, it.lexicon.exactClass("perche"))
        val pc = p.entry("perché")
        assertEquals(2, pc.ed)
        assertEquals(121, pc.lm)
        assertEquals(123, pc.score)
        assertEquals("perché", p.autoCorrection?.display)
        assertEquals("4o", p.decisionStep)
        assertEquals("città", ac("citta"))
        assertEquals(152, it.query("citta").entry("città").lm)
        assertEquals("però", ac("pero"))
        assertEquals(154, it.query("pero").entry("però").lm)
        // "piu" is no key (merged into "più")
        assertFalse(it.lexicon.exact("piu"))
        val piu = it.query("piu").entry("più")
        assertEquals(2, piu.ed)
        assertEquals(125, piu.lm)
        assertEquals("più", ac("piu"))
        // wrong accent: ACCENT_SWAP makes è for é cost ED_DIACRITIC
        assertFalse(it.lexicon.exact("perchè"))
        assertEquals(2, it.query("perchè").entry("perché").ed)
        assertEquals("perché", ac("perchè"))
        show("perche", "perche")
        show("citta", "citta")
    }

    @Test
    fun example2BothSpellingsValid() {
        for (text in listOf("Questa e", "Si")) {
            val r = it.query(text)
            assertTrue(r.validity.valid, text)
            assertNull(ac(text), text)
        }
        // "è" is not offered after a typed "e": one accent unit for one letter (§8.3, open point 21)
        assertTrue("è" !in bar("Questa e"), bar("Questa e").toString())
        assertEquals("Sì", bar("Si").first())
        for ((typed, accented) in listOf("la" to "là", "li" to "lì", "da" to "dà", "se" to "sé", "ne" to "né", "papa" to "papà")) {
            assertNull(ac(typed), typed)
            assertTrue(accented in bar(typed), "$typed: ${bar(typed)}")
        }
        assertEquals("là", bar("la")[1])
        assertEquals("lì", bar("li")[1])
        assertEquals("papà", bar("papa").first())
    }

    @Test
    fun example3OneLetterRemainder() {
        // open point 21 (1:1): the candidate "è" has one unit for one letter and is removed by the filter of §8.3
        val r = it.query("c'e")
        assertFalse(r.validity.valid)
        assertTrue(r.ranked.none { c -> c.display == "c'è" }, r.ranked.toString())
        assertNull(ac("c'e"))
        assertNull(ac("dov'e"))
        assertNull(ac("C'e"))
        // typed correctly: valid, nothing to correct
        assertTrue(it.query("c'è").validity.valid)
        assertNull(ac("c'è"))
    }

    @Test
    fun example4MissingApostrophe() {
        assertFalse(it.lexicon.exact("lacqua"))
        val r = it.query("lacqua")
        val l = r.entry("l'acqua")
        assertEquals(0, l.ed)
        assertEquals(161, l.lm) // 05 §17.10 gives 168 (informative)
        assertEquals("l'acqua", r.autoCorrection?.display)
        assertEquals("4o", r.decisionStep)
        assertEquals("L'acqua", ac("Lacqua"))
        assertEquals("L'ACQUA", ac("LACQUA"))
        assertEquals("dell'anno", ac("dellanno"))
        assertFalse(it.lexicon.exact("daccordo"))
        assertEquals("d'accordo", ac("daccordo"))
        val ua = it.query("unaltra")
        assertEquals("un'altra", ua.autoCorrection?.display)
        // the two-word "un altra" changes no letter either but ranks far behind
        assertEquals(437, ua.entry("un altra").score)
        assertEquals("anch'io", ac("anchio"))
        // "cè" is a class-0 form; "c'è" is the first suggestion. 05 §17.10 example 4 expects no autocorrection because
        // "ce" scores 155 there; by §5 a typed accented letter for a plain one is a substitution (ED_SUBSTITUTE 160),
        // so "ce" scores 313 and is far from "c'è" (spec/QUESTIONS.md, 0.7.1)
        val ce = it.query("cè")
        assertTrue(ce.validity.classZero)
        assertEquals("c'è", ce.bar().first().display)
        assertEquals(160, ce.entry("ce").ed)
        assertEquals("c'è", ce.autoCorrection?.display)
    }

    @Test
    fun example5ValidWordElidedWordOffered() {
        for ((text, elided) in listOf("Non ce" to "c'è", "Lui cera" to "c'era", "dove" to "dov'è")) {
            val r = it.query(text)
            assertTrue(r.validity.valid, text)
            assertNull(ac(text), text)
            assertTrue(elided in bar(text).take(3), "$text: ${bar(text)}")
        }
    }

    @Test
    fun example6AfterAnElidedPrefix() {
        val b = bar("dell'")
        println("dell' → $b")
        assertTrue(b.isNotEmpty())
        assertTrue(b.all { s -> s.startsWith("dell'") }, b.toString())
        assertTrue("dell'acqua" in b.take(5) && "dell'anno" in b.take(5), b.toString())
        assertEquals("c'è", bar("c'").first())
        // typed "l": the prefix candidate "l'" among the first five, marked as an elision prefix
        val p = engine.query(Query("l"))
        val i = p.suggestions.indexOfFirst { s -> s.text == "l'" }
        assertTrue(i in 0..4, p.suggestions.toString())
        assertTrue(p.suggestions[i].isElisionPrefix)
        assertNull(p.autoCorrection)
        val after = bar("l'")
        assertTrue(after.isNotEmpty() && after.all { s -> s.startsWith("l'") }, after.toString())
        // an all-caps prefix gives all-caps elided words
        assertTrue(bar("DELL'").all { s -> s == s.uppercase() && s.startsWith("DELL'") })
    }

    @Test
    fun example7Completions() {
        // "perché" is the first completion. Before 0.6.7 the correction "Percy" (a name of the dubbed films, class 124,
        // ED 116) became the autocorrection and stood before it; since the damping of non-forms (05b §3.6, class 49) it
        // is too expensive, so "perché" is the first entry and nothing is autocorrected (spec/QUESTIONS.md)
        val perch = engine.query(Query("perch"))
        println("perch: ac=${perch.autoCorrection} bar=${perch.suggestions.map { s -> s.text }}")
        assertEquals("perché", perch.suggestions.first { s -> !s.isAutoCorrection }.text)
        assertNull(perch.autoCorrection)
        assertEquals("perché", perch.suggestions.first().text)
        assertEquals("perché", it.query("perch").ranked.first().display)
        assertEquals("tournée", bar("La band è in tourne").first(), bar("La band è in tourne").toString())
    }

    @Test
    fun example8NextWordAndTruncation() {
        val un = engine.query(Query("Un ")).suggestions.map { s -> s.text }
        println("Un → $un")
        assertEquals("po'", un.first())
        assertEquals("è", engine.query(Query("Non ")).suggestions.first().text)
        val r = it.query("un po")
        assertFalse(r.validity.valid)
        assertEquals(0, r.entry("po'").ed)
        assertEquals(93, r.entry("po'").lm)
        assertEquals("po'", ac("un po"))
    }

    @Test
    fun example9CaseError() {
        assertEquals(listOf("Italia"), it.lexicon.forms("italia").map { f -> f.text })
        val r = it.query("Vivo in italia")
        assertFalse(r.validity.valid)
        val c = r.entry("Italia")
        assertEquals(44, c.ed)
        assertEquals(123, c.lm)
        assertEquals("Italia", ac("Vivo in italia"))
    }

    @Test
    fun example10ValidWordsAreNotCorrected() {
        for ((typed, accented) in listOf("Vado la" to "là", "papa" to "papà", "li" to "lì", "se" to "sé")) {
            val r = it.query(typed)
            assertTrue(r.validity.valid, typed)
            assertNull(ac(typed), typed)
            assertTrue(it.isKnown(typed.substringAfterLast(' ')), typed)
            assertEquals(2, r.entry(accented).ed)
            assertTrue(accented in r.barTexts(), "$typed: ${r.barTexts()}")
        }
    }

    @Test
    fun example11MissingSpace() {
        assertFalse(it.lexicon.exact("perfavore"))
        val pf = it.query("perfavore")
        assertEquals("per favore", pf.autoCorrection?.display)
        assertEquals(149, pf.autoCorrection!!.lm)
        assertEquals(194, it.acScore(pf.autoCorrection!!, "perfavore"))
        val ua = it.query("unaltro")
        assertEquals("un altro", ua.autoCorrection?.display)
        assertEquals(160, ua.autoCorrection!!.lm)
        assertEquals(217, it.acScore(ua.autoCorrection!!, "unaltro"))
        val b = bar("unaltro")
        assertTrue("un altro" in b.take(3), b.toString())
        assertTrue("un'altro" !in b, b.toString())
        assertTrue(it.query("unaltro").ranked.none { c -> c.display == "un'altro" })
    }

    @Test
    fun example12SingleLetters() {
        for (w in listOf("a", "e", "è", "i", "o")) {
            assertTrue(it.query(w).validity.valid, w)
            assertNull(ac(w), w)
        }
        assertFalse(it.query("é").validity.valid)
        assertNull(ac("é"))
        // open point 21: "è" is not offered (one unit for one letter, §8.3)
        assertTrue("è" !in bar("é"), bar("é").toString())
    }

    @Test
    fun example13Multilingual() {
        val m = TestDictionaries.multilingual("it-IT", TestDictionaries.italian(), TestDictionaries.english())
        val w = m.languageWeights(emptyList())
        assertEquals(0.65, w.getValue("it-IT"), 1e-9)
        assertEquals(0.35, w.getValue("en-US"), 1e-9)
        assertEquals("perché", m.query(Query("perche")).autoCorrection)
        val en = m.languageWeights("I think we should go now".split(' ').reversed())
        assertTrue(en.getValue("en-US") > 0.95, en.toString())
        for (prefix in listOf("I", "I think", "I think we", "I think we should", "I think we should go", "I think we should go now")) {
            assertNull(m.query(Query(prefix)).autoCorrection, prefix)
        }
        assertEquals("l'acqua", m.query(Query("lacqua")).autoCorrection)
        assertEquals("it-IT", m.query(Query("lacqua")).autoCorrectionLanguage)
        // 05 §14.1: "Grazie mille per tutto" with it and es (it selected) → it > 0.95; an elided word counts as its remainder
        val es = TestDictionaries.multilingual("it-IT", TestDictionaries.italian(), TestDictionaries.spanish())
        assertTrue(es.languageWeights("Grazie mille per tutto".split(' ').reversed()).getValue("it-IT") > 0.95)
        assertEquals(it.languageLog10("anno"), it.languageLog10("dell'anno"))
    }

    @Test
    fun conformanceTouchGeometry() {
        // 05 §4 / §18: horizontal 51, a↔q 72, z↔s 63, c↔d 99, two keys apart 160
        val t = it.touch
        fun sub(a: Char, b: Char) = t.substitutionCost(a, Float.NaN, Float.NaN, b)
        assertEquals(51, sub('a', 's'))
        assertEquals(72, sub('a', 'q'))
        assertEquals(63, sub('z', 's'))
        assertEquals(99, sub('c', 'd'))
        assertEquals(160, sub('a', 'd'))
    }

    @Test
    fun accentSwapEditDistance() {
        val ed = EditDistance(it.params, it.touch)
        assertEquals(2, ed.weighted("perchè", "perché", TapSequence.none(6)))
        assertEquals(2, ed.weighted("piú", "più", TapSequence.none(3)))
        assertEquals(2, ed.weighted("é", "è", TapSequence.none(1)))
        // a typed accented letter for a plain one stays a substitution
        assertEquals(160, ed.weighted("è", "e", TapSequence.none(1)))
        // other languages: no accent swap
        val es = TestDictionaries.spanish()
        assertEquals(es.params.edSubstitute, EditDistance(es.params, es.touch).weighted("è", "é", TapSequence.none(1)))
    }

    @Test
    fun learningElidedWords() {
        val e = TestDictionaries.italian()
        val p = PredictionEngine(listOf(e), EngineSettings())
        p.learn("dell'anno", listOf("inizio"))
        assertEquals(1, e.user.count("anno"))
        assertEquals(0, e.user.count("dell'anno"))
        assertEquals(mapOf("anno" to 1), e.user.followers("dell'"))
        assertEquals(mapOf("anno" to 1), e.user.followers("inizio dell'"))
        // the bare prefix is never learned
        p.learn("l'", emptyList())
        assertEquals(0, e.user.count("l'"))
        // proofing: an elided word is known if its remainder is
        assertTrue(p.isKnown("l'acqua"))
        assertTrue(p.isKnown("dell'anno"))
        assertFalse(p.isKnown("c'e"))
        assertTrue(p.isKnown("c'è"))
        assertTrue(p.isElisionPrefix("dell'"))
        assertTrue(p.isElisionPrefix("L’"))
        assertFalse(p.isElisionPrefix("po'"))
        assertEquals("l'", Lexicon.normalizePrefix("L’"))
        // emoji of an elided word: its remainder (05 §9)
        assertEquals("❤️", e.emojiFor("l'amore"))
    }

    @Test
    fun lettersOfAPrefixAreNeverAutocorrected() {
        // 05 §10.1 step 2a, §8.5 (0.6.3 regression): "dell" + space stays "dell" (before: "delk"), "dell'" first
        for ((typed, prefix) in listOf("dell" to "dell'", "nell" to "nell'", "quest" to "quest'", "Dell" to "Dell'")) {
            assertFalse(it.query(typed).validity.valid, typed)
            assertNull(ac(typed), typed)
            assertEquals(prefix, bar(typed).first(), "$typed: ${bar(typed)}")
        }
        // a letter sequence that is a dictionary word is not affected ("un", "com")
        assertTrue(it.query("un").validity.valid)
    }
}
