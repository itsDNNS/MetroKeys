package app.metrokeys.engine.predict

import app.metrokeys.engine.EngineSettings
import app.metrokeys.engine.PredictionEngine
import app.metrokeys.engine.Query
import app.metrokeys.engine.TestDictionaries
import app.metrokeys.engine.entry
import app.metrokeys.engine.query
import app.metrokeys.engine.text.PrevContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spanish worked examples of spec 05 §17.8 and the es rows of the conformance checklist (§18) with the built
 * dictionary (no learned data, no taps, Spanish fallback grid; 0.7.1 rules). The outcomes are normative; the lm values
 * and scores come from the build and are recorded here (05 open point 7).
 */
class SpanishExamplesTest {
    private val es = TestDictionaries.spanish()
    private val engine = TestDictionaries.single(TestDictionaries.spanish())

    private fun ac(text: String): String? = engine.query(Query(text)).autoCorrection

    private fun first(text: String): String? = engine.query(Query(text)).suggestions.firstOrNull()?.text

    private fun bar(text: String): List<String> = engine.query(Query(text)).suggestions.map { it.text }

    @Test
    fun header() {
        // 05 §3.1: corpusTokens fixed by the source, S from maxCount of the finished list
        assertEquals(423_290_924L, es.lexicon.corpusTokens)
        assertEquals(-8.63, es.a, 1e-9)
        assertEquals(0.02862, es.bc, 1e-9)
        assertEquals(15.1738f, es.lexicon.scale, 0.0005f)
        assertEquals(255, es.lexicon.exactClass("de"))
        // the median anchor row of 05 §1.1: the most frequent form costs 91
        assertEquals(91, es.lm("de"))
    }

    @Test
    fun example1Cancion() {
        assertFalse(es.lexicon.exact("cancion"))
        val r = es.query("cancion")
        assertFalse(r.validity.valid)
        val c = r.entry("canción")
        assertEquals(CandidateSource.KEY_SLIP, c.source)
        assertEquals(0, c.ed)
        assertEquals(168, c.lm)
        assertEquals(168, c.score)
        assertEquals("canción", r.autoCorrection?.display)
        assertEquals("4o", r.decisionStep)
        assertEquals("canción", ac("cancion"))
    }

    @Test
    fun example2Manana() {
        assertFalse(es.lexicon.exact("manana"))
        val r = es.query("Hasta manana")
        val m = r.entry("mañana")
        assertEquals(0, m.ed)
        assertEquals(122, m.lm)
        assertEquals("mañana", r.autoCorrection?.display)
        // typed with the ñ key: a valid word, no correction
        val v = es.query("Hasta mañana")
        assertTrue(v.validity.valid)
        assertNull(v.autoCorrection)
    }

    @Test
    fun example3ValidWordsAreNotCorrected() {
        val r = es.query("Creo que esta")
        assertTrue(r.validity.valid)
        assertEquals(127, r.validity.cost)
        assertNull(r.autoCorrection)
        assertEquals("5", r.decisionStep)
        assertEquals(122, r.entry("está").lm)
        assertEquals("está", r.bar().first().display)
        assertNull(ac("Creo que esta bien"))
        assertEquals("está", first("Creo que esta"))
        // "ano": no autocorrection, "año" among the first two (after the completion "años")
        assertNull(ac("ano"))
        val ano = es.query("ano")
        assertEquals(149, ano.entry("año").score)
        assertTrue("año" in bar("ano").take(2), bar("ano").toString())
        for (typed in listOf("si", "el", "tu", "mas", "solo", "como", "donde", "dime", "hazlo")) {
            assertNull(ac(typed), typed)
        }
    }

    @Test
    fun example4CompletionThroughAnAccentSlip() {
        val r = es.query("Buenos di")
        val dias = r.bar().first()
        assertEquals("días", dias.display)
        assertFalse(dias.isCorrection)
        assertEquals(97, dias.lm)
        assertEquals(107, dias.score)
        assertFalse(es.lexicon.exact("dias"))
        assertEquals("días", ac("Buenos dias"))
    }

    @Test
    fun example5NextWord() {
        val muchas = engine.query(Query("Muchas ")).suggestions.map { it.text }
        assertTrue("gracias" in muchas.take(5), muchas.toString())
        val buenos = engine.query(Query("Buenos ")).suggestions.map { it.text }
        assertTrue("días" in buenos.take(3), buenos.toString())
        // at the text start the list is capitalised
        assertTrue(engine.query(Query("")).suggestions.all { Character.isUpperCase(it.text[0]) })
    }

    @Test
    fun example6CliticForms() {
        assertTrue(es.lexicon.exact("dámelo"))
        assertFalse(es.lexicon.exact("damelo"))
        val r = es.query("damelo")
        val d = r.entry("dámelo")
        assertEquals(0, d.ed)
        assertEquals(202, d.lm)
        assertEquals("dámelo", r.autoCorrection?.display)
        for (w in listOf("dime", "hazlo", "decírselo", "llámame", "vámonos", "dímelo")) {
            val v = es.query(w)
            assertTrue(v.validity.valid, w)
            assertNull(v.autoCorrection, w)
        }
        assertEquals("llámame", ac("llamame"))
    }

    @Test
    fun example7MissingSpace() {
        assertFalse(es.lexicon.exact("porfavor"))
        assertFalse(es.lexicon.exact("aveces"))
        val r = es.query("porfavor")
        val pf = r.entry("por favor")
        assertEquals(208, pf.ed)
        assertEquals(145, pf.lm)
        assertEquals(353, pf.score)
        assertEquals(184, es.acScore(pf, "porfavor"))
        assertEquals("por favor", r.autoCorrection?.display)
        val av = es.query("aveces")
        assertFalse(av.validity.valid)
        assertEquals(158, av.entry("a veces").lm)
        // 0.7.1 review: step 7 no longer applies to unknown words; "veces" has the better AC score
        assertEquals("veces", av.autoCorrection?.display)
        // "a veces" among the first three suggestions after the autocorrection, which the bar shows first (§9;
        // spec/QUESTIONS.md, 0.7.1 review)
        assertEquals("veces", bar("aveces").first())
        assertTrue("a veces" in bar("aveces").drop(1).take(3), bar("aveces").toString())
    }

    @Test
    fun example8CaseError() {
        assertEquals(listOf("España"), es.lexicon.forms("españa").map { it.text })
        val r = es.query("Vivo en españa")
        assertFalse(r.validity.valid)
        val e = r.entry("España")
        assertEquals(44, e.ed)
        assertEquals(129, e.lm)
        assertEquals("España", r.autoCorrection?.display)
        assertEquals("4o", r.decisionStep)
    }

    @Test
    fun example9AfterAnInvertedQuestionMark() {
        val ctx = QueryContext.of("¿Donde")
        assertEquals("Donde", ctx.word)
        assertEquals(PrevContext.SentenceStart, ctx.prev)
        assertEquals(CaseMode.FIRST_UPPER, es.caseMode("Donde", ctx.preceding))
        val r = es.query("¿Donde")
        assertTrue(r.validity.valid)
        assertNull(r.autoCorrection)
        assertEquals("Dónde", first("¿Donde"))
        assertNull(ac("Hola, ¿Donde"))
    }

    @Test
    fun example10Swap() {
        val r = es.query("Hloa")
        val h = r.entry("Hola")
        assertEquals(65, h.ed)
        assertEquals(148, h.lm)
        assertEquals(213, h.score)
        assertEquals(197, es.acScore(h, "Hloa"))
        assertEquals("Hola", ac("Hloa"))
    }

    @Test
    fun example11SingleLetters() {
        for (w in listOf("y", "o", "a", "e", "u")) {
            assertTrue(es.query(w).validity.valid, w)
            assertNull(ac(w), w)
        }
        assertFalse(es.query("i").validity.valid)
        assertNull(ac("i"))
        // the dictionary holds exactly the one-letter words of 05 §1.1
        assertEquals(setOf("a", "e", "o", "u", "y"), es.lexicon.words().map { it.text.lowercase() }.filter { it.length == 1 }.toSet())
    }

    @Test
    fun example12MultilingualGermanAndSpanish() {
        val e = PredictionEngine(listOf(TestDictionaries.german(), TestDictionaries.spanish()), EngineSettings(multilingual = true))
        e.activeLanguage = "es-ES"
        val empty = e.languageWeights(emptyList())
        assertEquals(0.65, empty.getValue("es-ES"), 1e-12)
        assertEquals(0.35, empty.getValue("de-DE"), 1e-12)
        assertEquals("für", e.query(Query("Vielen Dank fur")).autoCorrection)
        val w = e.languageWeights("Muchas gracias por tu ayuda".split(" ").reversed())
        assertTrue(w.getValue("es-ES") > 0.95, w.toString())
        for (prefix in listOf("Muchas", "Muchas gracias", "Muchas gracias por", "Muchas gracias por tu", "Muchas gracias por tu ayuda")) {
            assertNull(e.query(Query(prefix)).autoCorrection, prefix)
        }
    }

    @Test
    fun emoji() {
        assertEquals("👍", es.emojiFor("sí"))
        assertNull(es.emojiFor("si"))
        assertEquals("🙏", es.emojiFor("porfa"))
        assertTrue(engine.query(Query("gracias")).suggestions.any { it.text == "🙏" })
    }

    @Test
    fun slipAccentsWithSeveralTargets() {
        // SLIP_ACCENTS es: u → ú, ü; n → ñ (one direction only, 05 §8.1)
        val t = app.metrokeys.engine.touch.TouchModel(app.metrokeys.engine.touch.KeyboardGeometry.fallback("es-ES"), es.params)
        val slips = SlipTable(t, es.params)
        assertEquals(24, slips.slip('u', 'ú'))
        assertEquals(24, slips.slip('u', 'ü'))
        assertEquals(24, slips.slip('n', 'ñ'))
        assertEquals(-1, slips.slip('ñ', 'n'))
        assertEquals(0, slips.slip('a', 'a'))
        // a↔q are directly below each other on the Spanish page: 527 · 20 / 1000
        assertEquals(10, slips.slip('a', 'q'))
    }
}
