package app.metrokeys.engine.swipe

import app.metrokeys.engine.TestDictionaries
import app.metrokeys.engine.predict.LanguageParams
import app.metrokeys.engine.touch.KeyboardGeometry
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Spanish shape writing, spec 06 §8 E8/E9 and §7.1, on the Spanish fallback grid with the built dictionary. */
class SpanishShapeTest {
    private val g = KeyboardGeometry.fallback("es-ES")
    private val es = TestDictionaries.spanish().also { it.geometry = g }
    private val w = ShapeWriter(es)

    private fun trace(word: String) = ShapeWriterTest.traceOn(g, word)

    private fun lm(word: String) = 255 - es.lexicon.exactClass(word)

    @Test
    fun constantsOfTheSpanishColumn() {
        val s = LanguageParams.SPANISH.shape
        assertEquals(8.0, s.unusedKeyCost)
        assertEquals(23.0, s.neighbourFactor * 250, 1e-9) // horizontal neighbour
        assertEquals(19.0, s.doubleLetterCost)
        assertEquals(12.0, s.accentCost, 1e-9)
        assertEquals(20.75, LanguageParams.ENGLISH.shape.neighbourFactor * 250, 1e-9)
        assertEquals(0.5, LanguageParams.ENGLISH.shape.accentCost, 1e-9)
        assertEquals(24.0, LanguageParams.GERMAN.shape.neighbourFactor * 250, 1e-9)
    }

    @Test
    fun e8AnoThroughTheEnyeKey() {
        val t = trace("año")
        val seq = ShapeWriter.keySequence(t, g)!!
        assertEquals("asdfghjklño", seq.keys.map { g.keys[it].letter }.joinToString(""))
        val expected = listOf(180, 0, 0, 0, 0, 0, 0, 0, 0, 104, 180)
        for ((i, a) in seq.angles.withIndex()) assertEquals(expected[i].toFloat(), a, 1.5f, "angle $i") // entry points
        val r = w.recognize(t, "")
        val ano = r.candidates.first { it.text == "año" }
        assertEquals(64.0 + lm("año"), ano.searchCost, 1e-6)
        assertEquals(9.0, ano.shapeDistance, 0.5)
        assertTrue(r.candidates.none { it.text == "ano" }, r.candidates.toString())
        assertEquals("año", r.candidates.first().text)
        // ajo and aso: the skipped ñ is a corner (104° / 5 = 20.8)
        val all = ShapeWriterTest.allCandidates(w, t)
        assertEquals(84.8 + lm("ajo"), all.getValue("ajo").searchCost, 0.3)
        assertEquals(25.5, all.getValue("ajo").shapeDistance, 0.5)
        all["aso"]?.let {
            assertEquals(84.8 + lm("aso"), it.searchCost, 0.3)
            assertEquals(41.6, it.shapeDistance, 0.5)
        }
    }

    @Test
    fun e8CounterCheckThroughTheNKey() {
        val t = trace("ano")
        assertEquals("asdvbnko", ShapeWriter.keySequence(t, g)!!.keys.map { g.keys[it].letter }.joinToString(""))
        val r = w.recognize(t, "")
        val ano = r.candidates.first { it.text == "ano" }
        assertEquals(40.0 + 3 + lm("ano"), ano.searchCost, 0.2) // k skipped at ≈ 15°
        assertEquals(6.5, ano.shapeDistance, 0.5)
        assertFalse(r.candidates.take(1).any { it.text == "año" })
    }

    @Test
    fun e9QueAndQueWithAccent() {
        val t = trace("que")
        assertEquals("qwertyuytre", ShapeWriter.keySequence(t, g)!!.keys.map { g.keys[it].letter }.joinToString(""))
        val r = w.recognize(t, "")
        val que = r.candidates.first { it.text == "que" }
        val qué = r.candidates.first { it.text == "qué" }
        assertEquals(64.0 + lm("que"), que.searchCost, 1e-6)
        assertEquals(76.0 + lm("qué"), qué.searchCost, 1e-6)
        assertEquals(5.7, que.shapeDistance, 0.5)
        assertEquals(que.shapeDistance, qué.shapeDistance, 1e-9)
        // without context que first, qué the first alternative
        assertEquals(listOf("que", "qué"), r.candidates.take(2).map { it.text })
        // after "Creo " que first; after "¿" the context is the sentence start, both capitalised with one-shot shift
        assertEquals("que", w.recognize(t, "Creo ").candidates.first().text)
        val q = w.recognize(t, "¿")
        assertEquals(setOf("Que", "Qué"), q.texts(ShiftState.ONE_SHOT).take(2).toSet())
        println("E9 after ¿: " + q.candidates.take(3).map { "${it.text} total=${"%.1f".format(it.total)} bonus=${it.contextBonus}" })
    }

    @Test
    fun spanishWordsAreRecognised() {
        for (word in listOf("hola", "gracias", "mañana", "año", "bien", "casa", "tiempo", "donde", "pero", "niño")) {
            assertEquals(word, w.recognize(trace(word), "").candidates.first().text.lowercase(), word)
        }
    }

    @Test
    fun acceptanceAccuracySpanish() {
        // words whose trace is their own: no one-letter words, no pairs that differ only by an accent (tu/tú, que/qué)
        val words = ("yo ella nosotros pero no también ahora hoy mañana ayer hola gracias por favor bien muy " +
            "mucho siempre otra vez hacer tener ser estar ir venir decir saber ver dar casa tiempo año día noche " +
            "hombre mujer niño ciudad mundo país gente amigo amor agua comida escuela jugar aprender pronto quizás " +
            "porque aquí allí grande pequeño bueno nuevo").split(" ")
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
        println("Spanish shape-writing top-1 rate: $rate, misses $misses")
        assertTrue(rate >= 0.9, "top-1 rate $rate, misses $misses")
    }
}
