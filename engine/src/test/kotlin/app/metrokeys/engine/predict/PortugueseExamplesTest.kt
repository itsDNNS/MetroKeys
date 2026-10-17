package app.metrokeys.engine.predict

import app.metrokeys.engine.EngineSettings
import app.metrokeys.engine.PredictionEngine
import app.metrokeys.engine.Proofreader
import app.metrokeys.engine.Query
import app.metrokeys.engine.TestDictionaries
import app.metrokeys.engine.entry
import app.metrokeys.engine.query
import app.metrokeys.engine.text.PrevContext
import app.metrokeys.engine.text.TextModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Portuguese (Brazil) worked examples of spec 05 §17.9 and the pt rows of the conformance checklist (§18) with the
 * built dictionary (no learned data, no taps, Portuguese fallback grid; 0.7.1 rules). The outcomes are normative; the lm
 * values and scores come from the build and are recorded here (05 §17.9) so that a later build can be compared.
 */
class PortugueseExamplesTest {
    private val pt = TestDictionaries.portuguese()
    private val engine = TestDictionaries.single(TestDictionaries.portuguese())

    private fun ac(text: String): String? = engine.query(Query(text)).autoCorrection

    private fun bar(text: String): List<String> = engine.query(Query(text)).suggestions.map { it.text }

    @Test
    fun header() {
        // 05 §3.1: corpusTokens = all counts of the main list plus the credited hyphen parts; S from maxCount ("de")
        assertEquals(433_743_576L, pt.lexicon.corpusTokens)
        assertEquals(-8.64, pt.a, 1e-9)
        assertEquals(0.02808, pt.bc, 1e-9)
        assertEquals(15.4637f, pt.lexicon.scale, 0.0005f)
        assertEquals(255, pt.lexicon.exactClass("de"))
        // the median anchor row of 05 §1.1: the most frequent form costs 91, class 0 254
        assertEquals(91, pt.lm("de"))
        assertEquals(254, pt.lm("idéia"))
    }

    @Test
    fun example1Voce() {
        assertFalse(pt.lexicon.exact("voce"))
        val r = pt.query("voce")
        assertFalse(r.validity.valid)
        val v = r.entry("você")
        assertEquals(30, v.ed)
        assertEquals(101, v.lm)
        assertEquals(131, v.score)
        assertEquals("você", r.autoCorrection?.display)
        assertEquals("4o", r.decisionStep)
        assertTrue(pt.query("vc").validity.valid)
        assertNull(ac("vc"))
    }

    @Test
    fun example2Tilde() {
        assertFalse(pt.lexicon.exact("nao"))
        val r = pt.query("nao")
        val n = r.entry("não")
        assertEquals(30, n.ed)
        assertEquals(95, n.lm)
        assertEquals(125, n.score)
        assertEquals("não", r.autoCorrection?.display)
        val a = pt.query("Até amanha")
        val m = a.entry("amanhã")
        assertEquals(30, m.ed)
        assertEquals(117, m.lm)
        assertEquals(147, m.score)
        assertEquals("amanhã", a.autoCorrection?.display)
    }

    @Test
    fun example3Cedilla() {
        val r = pt.query("acao")
        val a = r.entry("ação")
        // two accent slips (c → ç, a → ã) of the key-slip search: a same-length correction
        assertEquals(CandidateSource.KEY_SLIP, a.source)
        assertTrue(a.isCorrection)
        assertEquals(60, a.ed)
        assertEquals(169, a.lm)
        assertEquals(229, a.score)
        assertEquals("ação", bar("acao").first())
        assertEquals("ação", ac("acao"))
        val i = pt.query("informacao").entry("informação")
        assertEquals(60, i.ed)
        assertEquals(168, i.lm)
        assertEquals("informação", ac("informacao"))
        val c = pt.query("comecar").entry("começar")
        assertEquals(30, c.ed)
        assertEquals(161, c.lm)
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
        val esta = r.entry("está")
        assertEquals(30, esta.ed)
        assertEquals(105, esta.lm)
        assertTrue("está" in bar("Ele esta").take(3), bar("Ele esta").toString())
        for ((typed, accented) in listOf("e" to "é", "nos" to "nós", "pais" to "país", "da" to "dá", "la" to "lá", "por" to "pôr",
            "pode" to "pôde", "sabia" to "sábia")) {
            assertTrue(pt.query(typed).validity.valid, typed)
            assertNull(ac(typed), typed)
            assertTrue(pt.lexicon.exact(accented), accented)
        }
        assertEquals("nós", bar("nos").first())
        assertEquals("país", bar("pais").first())
    }

    @Test
    fun example5NextWord() {
        assertEquals("por", bar("Obrigado ").first())
        assertTrue("dia" in bar("Bom ").take(3), bar("Bom ").toString())
        assertTrue("noite" in bar("Boa ").take(3), bar("Boa ").toString())
        assertTrue(engine.query(Query("")).suggestions.all { Character.isUpperCase(it.text[0]) })
    }

    @Test
    fun example6CompletionThroughAnAccentSlip() {
        val r = pt.query("Obrigado pela atenc")
        val a = r.bar().first()
        assertEquals("atenção", a.display)
        assertFalse(a.isCorrection)
        assertEquals(162, a.lm)
        assertEquals(202, a.score)
        assertNull(r.autoCorrection)
        assertEquals("atencioso", r.bar()[1].display)
        assertFalse(pt.lexicon.exact("atencao"))
        assertEquals("atenção", ac("Obrigado pela atencao"))
        val c = pt.query("Vamos comec").bar().first()
        assertEquals("começar", c.display)
        assertEquals(156, c.score)
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
        assertEquals(30, f.entry("fazê").ed)
    }

    @Test
    fun example8MissingSpace() {
        assertFalse(pt.lexicon.exact("porfavor"))
        assertFalse(pt.lexicon.exact("tudobem"))
        val r = pt.query("porfavor")
        val pf = r.entry("por favor")
        assertEquals(213, pf.ed)
        assertEquals(140, pf.lm)
        assertEquals(186, pt.acScore(pf, "porfavor"))
        assertEquals("por favor", r.autoCorrection?.display)
        val t = pt.query("tudobem")
        val tb = t.entry("tudo bem")
        assertEquals(166, tb.lm)
        assertEquals(218, pt.acScore(tb, "tudobem"))
        assertEquals("tudo bem", t.autoCorrection?.display)
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
        val b = r.entry("Brasil")
        assertEquals(44, b.ed)
        assertEquals(168, b.lm)
        assertEquals("Brasil", r.autoCorrection?.display)
    }

    @Test
    fun example10ElidedPrefix() {
        assertEquals(2, pt.elisionPrefixLength("d'agua"))
        assertEquals(2, pt.elisionPrefixLength("D’agua"))
        assertEquals(0, pt.elisionPrefixLength("dagua"))
        assertEquals(0, TestDictionaries.spanish().elisionPrefixLength("d'agua"))
        assertFalse(pt.lexicon.exact("agua"))
        val r = pt.query("um copo d'agua")
        val a = r.entry("d'água")
        assertEquals(30, a.ed)
        assertEquals(156, a.lm)
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
        // 0.7.1: valid (no spelling mark), but class-0 forms are not valid for the autocorrection decision (§10.1 step 4)
        for ((w, reformed) in listOf("idéia" to "ideia", "vôo" to "voo")) {
            assertEquals(0, pt.lexicon.exactClass(w), w)
            val r = pt.query(w)
            assertTrue(r.validity.valid, w)
            assertTrue(r.validity.classZero, w)
            assertTrue(engine.isKnown(w), w)
            assertEquals(reformed, ac(w), w)
        }
        assertTrue(Proofreader(engine).run("uma idéia ", 0, 0, emptyList()).add.isEmpty())
        val r = pt.query("idéia")
        val i = r.entry("ideia")
        assertEquals(155, i.ed)
        assertEquals(157, i.lm)
        assertEquals(312, i.score)
        // typing "ideia" offers "idéia" only far down (a class-0 form is never offered by the key-slip search)
        val ideia = pt.query("ideia")
        assertNull(ideia.autoCorrection)
        assertTrue(bar("ideia").indexOf("idéia").let { it < 0 || it >= 3 }, bar("ideia").toString())
        // one undo keeps the old spelling for good (§10.4)
        engine.rejectAutoCorrection("idéia", "ideia", accepted = true)
        assertNull(ac("idéia"))
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
        // the accent variants of a one-letter word are not offered: lm + 400 · 1 / 1 > COMP_LIMIT (§8.3)
        assertTrue("à" !in bar("a"), bar("a").toString())
        assertTrue("é" !in bar("e"), bar("e").toString())
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
    fun slipAccentsWithManyTargets() {
        // SLIP_ACCENTS pt: a → á, à, ã, â; c → ç (05 §1.1), each slip SLIP_ACCENT 16
        val t = app.metrokeys.engine.touch.TouchModel(app.metrokeys.engine.touch.KeyboardGeometry.fallback("pt-BR"), pt.params)
        val slips = SlipTable(t, pt.params)
        for (x in listOf('á', 'à', 'ã', 'â')) assertEquals(16, slips.slip('a', x), "$x")
        assertEquals(16, slips.slip('c', 'ç'))
        // ç has its own key on the Portuguese page, far from c: no neighbour slip the other way
        assertEquals(-1, slips.slip('ç', 'c'))
    }

    @Test
    fun emoji() {
        assertEquals("👍", pt.emojiFor("sim"))
        assertEquals("😂", pt.emojiFor("kkkk"))
        assertNull(pt.emojiFor("nossa"))
        assertTrue(engine.query(Query("obrigado")).suggestions.any { it.text == "🙏" })
    }
}
