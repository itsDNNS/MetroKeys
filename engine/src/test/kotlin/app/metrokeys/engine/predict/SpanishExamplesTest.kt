package app.metrokeys.engine.predict

import app.metrokeys.engine.EngineSettings
import app.metrokeys.engine.PredictionEngine
import app.metrokeys.engine.Query
import app.metrokeys.engine.TestDictionaries
import app.metrokeys.engine.query
import app.metrokeys.engine.text.PrevContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spanish worked examples of spec 05 §17.8 and the es rows of the conformance checklist (§18) with the built
 * dictionary (no learned data, no taps, Spanish fallback grid). The outcomes are normative; the lm values and scores
 * come from the build and are recorded here (05 open point 10) so that a later build can be compared.
 */
class SpanishExamplesTest {
    private val es = TestDictionaries.spanish()
    private val engine = TestDictionaries.single(TestDictionaries.spanish())

    private fun LanguageSuggestions.corr(display: String): Candidate =
        corrections.firstOrNull { it.display == display } ?: error("no correction $display in $corrections")

    private fun ac(text: String): String? = engine.query(Query(text)).autoCorrection

    private fun first(text: String): String? = engine.query(Query(text)).suggestions.firstOrNull()?.text

    @Test
    fun header() {
        // 05 §3.1: corpusTokens fixed by the source, S from maxCount of the finished list
        assertEquals(423_290_924L, es.lexicon.corpusTokens)
        assertEquals(-8.63, es.a, 1e-9)
        assertEquals(0.02808, es.bc, 1e-9)
        assertEquals(15.4669f, es.lexicon.scale, 0.0005f)
        // lm without context: round(170.34 − 0.5054 · k): class 255 → 41, class 0 → 170
        assertEquals(41, es.lm("que"))
        assertEquals(255, es.lexicon.exactClass("que"))
    }

    @Test
    fun example1Cancion() {
        assertFalse(es.lexicon.exact("cancion"))
        val r = es.query("cancion")
        assertFalse(r.validity.valid)
        val c = r.corr("canción")
        assertEquals(0, c.ed)
        assertEquals(c.lm, es.acScore(c, 7))
        assertTrue(es.acScore(c, 7) < es.params.acLimit)
        assertEquals(87, c.lm)
        assertEquals("canción", r.autoCorrection?.display)
        assertEquals("canción", ac("cancion"))
    }

    @Test
    fun example2Manana() {
        assertFalse(es.lexicon.exact("manana"))
        val r = es.query("Hasta manana")
        val m = r.corr("mañana")
        assertEquals(0, m.ed)
        assertEquals(56, m.lm) // lm(mañana | hasta); unigram 72
        assertEquals(72, es.lm("mañana"))
        assertEquals("mañana", r.autoCorrection?.display)
        assertEquals(150, 4 * 225 / 6)
        assertTrue(es.lm("manzana") + 150 > es.params.acLimit)
        // typed with the ñ key: a valid word, no correction
        val v = es.query("Hasta mañana")
        assertTrue(v.validity.valid)
        assertNull(v.autoCorrection)
    }

    @Test
    fun example3ValidWordsAreNotCorrected() {
        val r = es.query("Creo que esta")
        assertTrue(r.validity.valid)
        assertTrue(r.validity.cost <= es.params.acValidRare)
        assertNull(r.autoCorrection)
        assertEquals("está", r.bar().first().display)
        assertNull(ac("Creo que esta"))
        assertNull(ac("Creo que esta bien"))
        assertEquals("está", first("Creo que esta"))
        assertEquals(112, es.lm("ano"))
        assertNull(ac("ano"))
        assertEquals("año", first("ano"))
        for ((typed, accented) in listOf("si" to "sí", "el" to "él", "tu" to "tú", "mas" to "más", "solo" to "sólo",
            "como" to "cómo", "donde" to "dónde")) {
            assertNull(ac(typed), typed)
            assertTrue(es.lexicon.exact(accented), accented)
            assertEquals(accented, first(typed), typed)
        }
    }

    @Test
    fun example4CompletionThroughAnAccentVariant() {
        assertTrue("dí" in es.prefixList("di", app.metrokeys.engine.touch.TapSequence.none(2)))
        assertTrue(es.lexicon.completions("di", 24).none { it.text == "días" })
        assertEquals("días", first("Buenos di"))
        assertFalse(es.lexicon.exact("dias"))
        assertEquals("días", ac("Buenos dias"))
    }

    @Test
    fun example5NextWord() {
        val muchas = engine.query(Query("Muchas ")).suggestions.map { it.text }
        // the pinned pair sources rank cosas, personas and veces before gracias after "muchas" (see QUESTIONS.md)
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
        val d = r.corr("dámelo")
        assertEquals(0, d.ed)
        assertEquals(101, d.lm)
        assertEquals("dámelo", r.autoCorrection?.display)
        for (w in listOf("dime", "hazlo", "decírselo", "llámame", "vámonos", "dímelo")) {
            val v = es.query(w)
            assertTrue(v.validity.valid, w)
            assertNull(v.autoCorrection, w)
        }
        assertEquals("llámame", first("llamame"))
    }

    @Test
    fun example7MissingSpace() {
        assertFalse(es.lexicon.exact("porfavor"))
        assertFalse(es.lexicon.exact("aveces"))
        val r = es.query("porfavor")
        val pf = r.corr("por favor")
        assertEquals(208, pf.ed)
        assertEquals(73, pf.lm)
        assertEquals(177, es.acScore(pf, 8))
        assertEquals("por favor", ac("porfavor"))
        // "aveces": the split is a correction, but with the built pair class (a, veces) = 155 it scores 80 + 138 = 218,
        // one above AC_LIMIT 217, and "abecés" (192) is within AC_MARGIN of it: no autocorrection (spec/QUESTIONS.md)
        val av = es.query("aveces")
        assertFalse(av.validity.valid)
        val split = av.corr("a veces")
        assertEquals(80, split.lm)
        assertEquals(218, es.acScore(split, 6))
        assertTrue(av.bar().take(3).any { it.display == "a veces" }, av.bar().toString())
        assertNull(av.autoCorrection)
        // further subtitle artefacts are no words; "osea" gets the free accent correction "ósea" (spec/QUESTIONS.md)
        assertFalse(es.query("osea").validity.valid)
        assertEquals("ósea", ac("osea"))
        for ((typed, phrase) in listOf("enserio" to "en serio", "talvez" to "tal vez")) {
            val q = es.query(typed)
            assertFalse(q.validity.valid, typed)
            assertTrue(q.corrections.any { it.display == phrase }, "$typed: ${q.corrections}")
        }
    }

    @Test
    fun example8CaseError() {
        assertEquals(listOf("España"), es.lexicon.forms("españa").map { it.text })
        val r = es.query("Vivo en españa")
        assertFalse(r.validity.valid)
        val e = r.corr("España")
        assertEquals(44, e.ed)
        assertEquals(e.lm + 29, es.acScore(e, 6))
        assertEquals("España", r.autoCorrection?.display)
        assertEquals("España", ac("Vivo en españa"))
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
        val h = r.corr("Hola")
        assertEquals(65, h.ed)
        assertEquals(h.lm + 65, es.acScore(h, 4))
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
    fun prefixAccentsWithSeveralTargets() {
        val variants = es.prefixList("nu", app.metrokeys.engine.touch.TapSequence.none(2))
        assertTrue(variants.containsAll(listOf("ñu", "nú", "nü")), variants.toString())
        assertTrue(variants.indexOf("nú") < variants.indexOf("nü"))
        val upper = es.prefixList("Nu", app.metrokeys.engine.touch.TapSequence.none(2))
        assertTrue(upper.containsAll(listOf("Ñu", "Nú", "Nü")), upper.toString())
        // 05 open point 8: the variant limit is not reached for typical prefixes
        for (p in listOf("cancio", "manana", "aqui", "tambien", "pinguino")) {
            val n = es.prefixList(p, app.metrokeys.engine.touch.TapSequence.none(p.length)).size - 1
            println("prefix variants of $p: $n (limit ${Params.PREFIX_VARIANTS_MAX})")
        }
    }
}
