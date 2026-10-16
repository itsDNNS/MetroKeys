package app.metrokeys.engine.predict

import app.metrokeys.engine.EngineSettings
import app.metrokeys.engine.PredictionEngine
import app.metrokeys.engine.Proofreader
import app.metrokeys.engine.Query
import app.metrokeys.engine.TestDictionaries
import app.metrokeys.engine.query
import app.metrokeys.engine.text.PrevContext
import app.metrokeys.engine.text.TextModel
import app.metrokeys.engine.touch.TapSequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Portuguese (Brazil) worked examples of spec 05 §17.9 and the pt rows of the conformance checklist (§18) with the
 * built dictionary (no learned data, no taps, Portuguese fallback grid). The outcomes are normative; the lm values and
 * scores come from the build and are recorded here (05 §17.9) so that a later build can be compared.
 */
class PortugueseExamplesTest {
    private val pt = TestDictionaries.portuguese()
    private val engine = TestDictionaries.single(TestDictionaries.portuguese())

    private fun LanguageSuggestions.corr(display: String): Candidate =
        corrections.firstOrNull { it.display == display } ?: error("no correction $display in $corrections")

    private fun ac(text: String): String? = engine.query(Query(text)).autoCorrection

    private fun bar(text: String): List<String> = engine.query(Query(text)).suggestions.map { it.text }

    @Test
    fun header() {
        // 05 §3.1: corpusTokens = all counts of the main list plus the credited hyphen parts; S from maxCount ("que")
        assertEquals(433_743_576L, pt.lexicon.corpusTokens)
        assertEquals(-8.64, pt.a, 1e-9)
        assertEquals(0.02815, pt.bc, 1e-9)
        assertEquals(15.4297f, pt.lexicon.scale, 0.0005f)
        assertEquals(255, pt.lexicon.exactClass("que"))
        // lm without context: round(170.52 − 0.5067 · k): class 255 → 41, class 0 → 171
        assertEquals(41, pt.lm("que"))
        assertEquals(171, pt.lm("idéia"))
    }

    @Test
    fun example1Voce() {
        assertFalse(pt.lexicon.exact("voce"))
        val r = pt.query("voce")
        assertFalse(r.validity.valid)
        val v = r.corr("você")
        assertEquals(30, v.ed)
        assertEquals(v.lm + 37, pt.acScore(v, 4))
        assertEquals(41, v.lm)
        val vice = r.corr("vice")
        assertEquals(44, vice.ed)
        assertTrue(pt.acScore(vice, 4) - pt.acScore(v, 4) > pt.params.acMargin)
        assertEquals("você", r.autoCorrection?.display)
        assertEquals("você", ac("voce"))
        assertTrue(pt.query("vc").validity.valid)
        assertNull(ac("vc"))
    }

    @Test
    fun example2Tilde() {
        assertFalse(pt.lexicon.exact("nao"))
        val r = pt.query("nao")
        val n = r.corr("não")
        assertEquals(30, n.ed)
        assertEquals(n.lm + 50, pt.acScore(n, 3))
        assertEquals(91, pt.acScore(n, 3))
        assertEquals(65, r.corr("ano").ed)
        assertEquals("não", ac("nao"))
        val a = pt.query("Até amanha")
        val m = a.corr("amanhã")
        assertEquals(30, m.ed)
        assertEquals(51, m.lm) // lm(amanhã | até); unigram below
        assertEquals(m.lm + 25, pt.acScore(m, 6))
        assertEquals("amanhã", ac("Até amanha"))
    }

    @Test
    fun example3Cedilla() {
        val r = pt.query("acao")
        val a = r.corr("ação")
        assertEquals(60, a.ed)
        assertEquals(a.lm + 75, pt.acScore(a, 4))
        assertEquals(89, a.lm)
        // ação comes from the correction pool, not from a completion (a variant prefix changes one position only)
        assertEquals(Candidate.INF, a.completionCost)
        assertEquals("ação", bar("acao").first())
        assertEquals("ação", ac("acao"))
        assertEquals("informação", ac("informacao"))
        assertEquals(60, pt.query("informacao").corr("informação").ed)
        val c = pt.query("comecar").corr("começar")
        assertEquals(30, c.ed)
        assertEquals(c.lm + 21, pt.acScore(c, 7))
        assertEquals("começar", ac("comecar"))
        for (w in listOf("comecei", "comece")) {
            assertTrue(pt.query(w).validity.valid, w)
            assertNull(ac(w), w)
        }
    }

    @Test
    fun example4ValidWordsAreNotCorrected() {
        val r = pt.query("Ele esta")
        assertTrue(r.validity.valid)
        assertTrue(r.validity.cost <= pt.params.acValidRare)
        assertNull(r.autoCorrection)
        assertEquals(30, r.corr("está").ed)
        assertTrue("está" in bar("Ele esta").take(3), bar("Ele esta").toString())
        for ((typed, accented) in listOf("e" to "é", "nos" to "nós", "pais" to "país", "da" to "dá", "la" to "lá", "por" to "pôr",
            "pode" to "pôde", "sabia" to "sábia")) {
            assertTrue(pt.query(typed).validity.valid, typed)
            assertNull(ac(typed), typed)
            assertTrue(pt.lexicon.exact(accented), accented)
        }
        // AC_VALID_RARE 201 is above every static cost of the dictionary (class 0 → 171)
        assertTrue(pt.lm("idéia") <= pt.params.acValidRare)
    }

    @Test
    fun example5NextWord() {
        assertEquals("por", bar("Obrigado ").first())
        assertTrue("dia" in bar("Bom ").take(3), bar("Bom ").toString())
        assertTrue("noite" in bar("Boa ").take(3), bar("Boa ").toString())
        assertTrue(engine.query(Query("")).suggestions.all { Character.isUpperCase(it.text[0]) })
    }

    @Test
    fun example6CompletionThroughAnAccentVariant() {
        assertTrue("atenç" in pt.prefixList("atenc", TapSequence.none(5)))
        assertTrue(pt.lexicon.completions("atenc", 24).none { it.text == "atenção" })
        val r = pt.query("Obrigado pela atenc")
        val a = r.ranked.first { it.display == "atenção" }
        assertEquals(a.lm + Params.COMP_SURCHARGE + 30, a.completionCost)
        assertTrue("atenção" in bar("Obrigado pela atenc").take(3), bar("Obrigado pela atenc").toString())
        assertFalse(pt.lexicon.exact("atencao"))
        assertEquals("atenção", ac("Obrigado pela atencao"))
        assertTrue("começar" in bar("Vamos comec").take(3), bar("Vamos comec").toString())
    }

    @Test
    fun example7HyphenatedClitics() {
        // the hyphen separates words: "me" is a new current word without pair context
        val ctx = QueryContext.of("Deixe-m")
        assertEquals("m", ctx.word)
        assertEquals(PrevContext.None, ctx.prev)
        assertTrue("me" in bar("Deixe-m").take(3), bar("Deixe-m").toString())
        assertTrue(pt.query("Deixe-me").validity.valid)
        assertNull(ac("Deixe-me"))
        for (w in listOf("fazê", "lo", "la", "los", "las", "ajudá", "pô")) assertTrue(pt.lexicon.exact(w), w)
        assertNull(ac("Preciso fazê"))
        assertNull(ac("Preciso fazê-lo"))
        val marks = Proofreader(engine).run("Preciso fazê-lo agora. Deixe-me ver.", 0, 0, emptyList())
        assertTrue(marks.add.isEmpty(), marks.add.toString())
        val f = pt.query("faze")
        assertTrue(f.validity.valid)
        assertNull(f.autoCorrection)
        assertEquals(30, f.corr("fazê").ed)
    }

    @Test
    fun example8MissingSpace() {
        assertFalse(pt.lexicon.exact("porfavor"))
        assertFalse(pt.lexicon.exact("tudobem"))
        val r = pt.query("porfavor")
        val pf = r.corr("por favor")
        assertEquals(213, pf.ed)
        assertEquals(pf.lm + 133, pt.acScore(pf, 8))
        assertEquals(68, pf.lm)
        assertEquals("por favor", ac("porfavor"))
        val tb = pt.query("tudobem").corr("tudo bem")
        assertEquals(tb.lm + 152, pt.acScore(tb, 7))
        assertTrue("tudo bem" in bar("tudobem").take(3), bar("tudobem").toString())
        val ag = pt.query("agente")
        assertTrue(ag.validity.valid)
        assertNull(ag.autoCorrection)
        assertTrue(ag.ranked.none { it.display == "a gente" })
    }

    @Test
    fun example9CaseError() {
        assertEquals(listOf("Brasil"), pt.lexicon.forms("brasil").map { it.text })
        val r = pt.query("Eu moro no brasil")
        assertFalse(r.validity.valid)
        val b = r.corr("Brasil")
        assertEquals(44, b.ed)
        assertEquals(b.lm + 36, pt.acScore(b, 6))
        assertEquals("Brasil", ac("Eu moro no brasil"))
    }

    @Test
    fun example10ElidedPrefix() {
        assertEquals(2, pt.elisionPrefixLength("d'agua"))
        assertEquals(2, pt.elisionPrefixLength("D’agua"))
        assertEquals(0, pt.elisionPrefixLength("dagua"))
        assertEquals(0, TestDictionaries.spanish().elisionPrefixLength("d'agua"))
        assertFalse(pt.lexicon.exact("agua"))
        val r = pt.query("um copo d'agua")
        val a = r.corr("d'água")
        assertEquals(30, a.ed)
        assertEquals(a.lm + 37, pt.acScore(a, 4))
        assertEquals(pt.lm("água"), a.lm) // previous word none
        assertEquals("d'água", r.autoCorrection?.display)
        assertEquals("d'água", ac("um copo d'agua"))
        assertEquals("D'água", ac("D'agua"))
        assertEquals("d’água", ac("um copo d’agua"))
        assertTrue(bar("um copo d'agu").all { it.startsWith("d'") }, bar("um copo d'agu").toString())
        // a correctly typed word: valid, no correction, no spelling mark
        assertTrue(pt.query("um copo d'água").validity.valid)
        assertNull(ac("um copo d'água"))
        assertTrue(engine.isKnown("d'água"))
        assertTrue(Proofreader(engine).run("um copo d'água ", 0, 0, emptyList()).add.isEmpty())
        // the prefix alone gives no query
        assertTrue(bar("um copo d'").isEmpty())
        // learning stores the remainder
        engine.learn("d'água", listOf("copo"))
        assertEquals(1, engine.languages[0].user.count("água"))
        assertEquals(0, engine.languages[0].user.count("d'água"))
    }

    @Test
    fun example11PreReformSpellings() {
        for (w in listOf("idéia", "vôo")) {
            assertEquals(0, pt.lexicon.exactClass(w), w)
            assertTrue(pt.query(w).validity.valid, w)
            assertNull(ac(w), w)
            assertTrue(engine.isKnown(w), w)
        }
        assertTrue(pt.lexicon.exactClass("ideia") > 0)
        val r = pt.query("idéia")
        assertEquals(155, r.corr("ideia").ed)
        assertTrue("ideia" in bar("idéia"), bar("idéia").toString())
        val i = pt.query("ideia")
        assertNull(i.autoCorrection)
        assertEquals(171 + 30, i.corr("idéia").correctionCost)
        assertTrue(bar("ideia").indexOf("idéia").let { it < 0 || it >= 3 }, bar("ideia").toString())
    }

    @Test
    fun example12SingleLetters() {
        for (w in listOf("a", "à", "e", "é", "o")) {
            assertTrue(pt.query(w).validity.valid, w)
            assertNull(ac(w), w)
        }
        for (w in listOf("i", "ó", "ô")) {
            assertFalse(pt.query(w).validity.valid, w)
            assertNull(ac(w), w)
        }
        assertEquals(setOf("a", "à", "e", "é", "o"), pt.lexicon.words().map { it.text.lowercase() }.filter { it.length == 1 }.toSet())
    }

    @Test
    fun ordinalIndicators() {
        for (w in listOf("1º", "2ª", "nº")) {
            assertEquals(w, TextModel.currentWord("o $w"))
            assertNull(ac("o $w"), w)
            assertFalse(TextModel.isCheckable(w), w)
        }
        assertTrue(Proofreader(engine).run("o nº 5 e a 2ª vez ", 0, 0, emptyList()).add.isEmpty())
        // the degree sign is a symbol and separates words
        assertEquals("", TextModel.currentWord("20°"))
    }

    @Test
    fun example13MultilingualPortugueseAndSpanish() {
        val e = PredictionEngine(listOf(TestDictionaries.spanish(), TestDictionaries.portuguese()), EngineSettings(multilingual = true))
        e.activeLanguage = "pt-BR"
        val empty = e.languageWeights(emptyList())
        assertEquals(0.65, empty.getValue("pt-BR"), 1e-12)
        assertEquals(0.35, empty.getValue("es-ES"), 1e-12)
        assertEquals("você", e.query(Query("voce")).autoCorrection)
        val es = e.languageWeights("Muchas gracias por tu ayuda".split(" ").reversed())
        assertTrue(es.getValue("es-ES") > 0.95, es.toString())
        for (prefix in listOf("Muchas", "Muchas gracias", "Muchas gracias por", "Muchas gracias por tu", "Muchas gracias por tu ayuda")) {
            assertNull(e.query(Query(prefix)).autoCorrection, prefix)
        }
        val w = e.languageWeights("Muito obrigado pela ajuda".split(" ").reversed())
        assertTrue(w.getValue("pt-BR") > 0.95, w.toString())
        // the elided prefix only applies in the Portuguese engine
        assertEquals("d'água", e.query(Query("um copo d'agua")).autoCorrection)
    }

    @Test
    fun prefixVariantsWithManyAccents() {
        // 05 §1.1 notes: accent variants cost 0 and come first, so the limit of 24 never removes them
        fun kinds(p: String): Triple<Int, Int, Int> {
            val v = pt.prefixList(p, TapSequence.none(p.length)).drop(1)
            val accents = v.count { it.length == p.length && it.indices.count { i -> it[i] != p[i] } == 1 &&
                EditDistanceAccess.isAccentOf(p, it) }
            val swaps = v.count { it.length == p.length && it.toSet() == p.toSet() && it != p && it.indices.count { i -> it[i] != p[i] } == 2 }
            return Triple(accents, swaps, v.size - accents - swaps)
        }
        assertEquals(Triple(12, 3, 9), kinds("acao"))
        assertEquals(Triple(16, 8, 0), kinds("informacao"))
        val ca = pt.prefixList("ca", TapSequence.none(2))
        assertTrue(ca.containsAll(listOf("cá", "cà", "cã", "câ", "ça")), ca.toString())
    }

    @Test
    fun emoji() {
        assertEquals("👍", pt.emojiFor("sim"))
        assertEquals("😂", pt.emojiFor("kkkk"))
        assertNull(pt.emojiFor("nossa"))
        assertTrue(engine.query(Query("obrigado")).suggestions.any { it.text == "🙏" })
    }
}

/** Accent test of the prefix variants: the variant replaces one base letter by one of its PREFIX_ACCENTS letters. */
private object EditDistanceAccess {
    fun isAccentOf(p: String, v: String): Boolean {
        val i = p.indices.first { p[it] != v[it] }
        return LanguageParams.PORTUGUESE.prefixAccents[p[i]]?.contains(v[i]) == true
    }
}
