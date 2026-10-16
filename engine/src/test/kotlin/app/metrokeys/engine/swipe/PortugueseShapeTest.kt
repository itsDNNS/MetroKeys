package app.metrokeys.engine.swipe

import app.metrokeys.engine.TestDictionaries
import app.metrokeys.engine.predict.LanguageParams
import app.metrokeys.engine.touch.KeyboardGeometry
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Portuguese shape writing, spec 06 §8 E10/E11 and §7.1, on the Portuguese fallback grid with the built dictionary. */
class PortugueseShapeTest {
    private val g = KeyboardGeometry.fallback("pt-BR")
    private val pt = TestDictionaries.portuguese().also { it.geometry = g }
    private val w = ShapeWriter(pt)

    private fun trace(word: String) = ShapeWriterTest.traceOn(g, word)

    private fun lm(word: String) = 255 - pt.lexicon.exactClass(word)

    private fun letters(word: String) = ShapeWriter.keySequence(trace(word), g)!!.keys.map { g.keys[it].letter }.joinToString("")

    @Test
    fun constantsOfThePortugueseColumn() {
        val s = LanguageParams.PORTUGUESE.shape
        assertEquals(12.0, s.unusedKeyCost)
        assertEquals(23.0, s.neighbourFactor * 250, 1e-9)
        assertEquals(14.0, s.doubleLetterCost)
        assertEquals(8.0, s.accentCost, 1e-9)
        assertEquals(45f, s.cornerAngle)
    }

    @Test
    fun e10FacoThroughTheCedillaKey() {
        val t = trace("faço")
        val seq = ShapeWriter.keySequence(t, g)!!
        assertEquals("fdsasdfghjklço", seq.keys.map { g.keys[it].letter }.joinToString(""))
        val expected = listOf(180, 0, 0, 180, 0, 0, 0, 0, 0, 0, 0, 0, 104, 180)
        for ((i, a) in seq.angles.withIndex()) assertEquals(expected[i].toFloat(), a, 1.5f, "angle $i")
        val r = w.recognize(t, "")
        val faco = r.candidates.first { it.text == "faço" }
        assertEquals(120.0 + lm("faço"), faco.searchCost, 1e-6)
        assertEquals(12.3, faco.shapeDistance, 0.6)
        val all = ShapeWriterTest.allCandidates(w, t)
        all["falo"]?.let {
            assertEquals(140.8 + lm("falo"), it.searchCost, 0.3)
            assertEquals(14.9, it.shapeDistance, 0.6)
        }
        assertFalse("faça" in all || "faca" in all, all.keys.toString())
        assertEquals("faço", r.candidates.first().text)
        println("E10: " + r.candidates.take(4).map { "${it.text} search=${"%.1f".format(it.searchCost)} d=${"%.1f".format(it.shapeDistance)} total=${"%.1f".format(it.total)}" })
    }

    @Test
    fun e10CounterCheckThroughTheCKey() {
        val r = w.recognize(trace("faco"), "")
        assertTrue(r.candidates.none { it.text == "faço" }, r.candidates.toString())
        // the build keeps "faco" (a rare noun of pt.wiktionary, so never merged into "faço"); see spec/QUESTIONS.md
        println("E10 counter-check: " + r.candidates.take(3).map { it.text })
    }

    @Test
    fun e11VoceThroughItsBaseKey() {
        val t = trace("você")
        assertEquals("vbjkioijhvcfe", letters("você"))
        val r = w.recognize(t, "")
        val voce = r.candidates.first { it.text == "você" }
        // the spec's angles 5° and 17° are rounded; the synthetic trace gives 131.5 = 121.5 + LM
        assertEquals(8.0 + (5 + 17) / 5.0 + 108 + lm("você"), voce.searchCost, 1.5)
        assertEquals(8.5, voce.shapeDistance, 0.8)
        ShapeWriterTest.allCandidates(w, t)["vice"]?.let { assertEquals(145.0 + lm("vice"), it.searchCost, 1.5) }
        assertTrue(r.candidates.none { it.text == "voce" || it.text == "vocês" }, r.candidates.toString())
        assertEquals("você", r.candidates.first().text)
        assertEquals("Você", w.recognize(t, "").texts(ShiftState.ONE_SHOT).first())
        println("E11: " + r.candidates.take(4).map { "${it.text} search=${"%.1f".format(it.searchCost)} d=${"%.1f".format(it.shapeDistance)}" })
    }

    @Test
    fun portugueseWordsAreRecognised() {
        for (word in listOf("não", "ação", "também", "você", "coração", "faço", "obrigado", "casa", "tempo", "começar")) {
            assertEquals(word, w.recognize(trace(word), "").candidates.first().text.lowercase(), word)
        }
    }

    @Test
    fun acceptanceAccuracyPortuguese() {
        // 06 open point 11: common words with ç, ã and õ among them; no pairs that differ only by an accent (e/é, esta/está)
        val words = ("eu ela nós mas não também agora hoje amanhã ontem obrigado favor bem muito sempre outra vez " +
            "fazer ter ser estar dizer saber ver dar casa tempo ano dia noite homem mulher cidade mundo gente amigo " +
            "amor água comida escola jogar aprender porque aqui grande pequeno bom novo faço ação coração você " +
            "começar informação canção mãe irmão lições criança cabeça").split(" ")
        val rnd = Random(42)
        var hits = 0
        val misses = ArrayList<String>()
        for (word in words) {
            val ox = (rnd.nextFloat() * 2 - 1) * 0.15f * g.keyWidth
            val oy = (rnd.nextFloat() * 2 - 1) * 0.15f * g.keyWidth
            val t = ShapeWriterTest.traceOn(g, word) {
                (ox + (rnd.nextFloat() * 2 - 1) * 0.1f * g.keyWidth) to (oy + (rnd.nextFloat() * 2 - 1) * 0.1f * g.keyWidth)
            }
            val top = w.recognize(t, "").candidates.firstOrNull()?.text?.lowercase()
            if (top == word) hits++ else misses.add("$word→$top")
        }
        val rate = hits.toDouble() / words.size
        println("Portuguese shape-writing top-1 rate: $rate (${words.size} words), misses $misses")
        assertTrue(rate >= 0.9, "top-1 rate $rate, misses $misses")
    }
}
