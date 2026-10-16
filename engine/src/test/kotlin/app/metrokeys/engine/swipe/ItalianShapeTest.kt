package app.metrokeys.engine.swipe

import app.metrokeys.engine.TestDictionaries
import app.metrokeys.engine.predict.LanguageParams
import app.metrokeys.engine.touch.KeyboardGeometry
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Italian shape writing, spec 06 §8 E12–E14, §7.1 and the elided entries of §4.1, on the Italian fallback grid (the
 * English page) with the built dictionary. Search costs and the winner are asserted; shape distances are ≈ values.
 */
class ItalianShapeTest {
    private val g = KeyboardGeometry.fallback("it-IT")
    private val it = TestDictionaries.italian().also { e -> e.geometry = g }
    private val w = ShapeWriter(it)

    private fun trace(letters: String) = ShapeWriterTest.traceOn(g, letters)

    private fun seq(letters: String) = ShapeWriter.keySequence(trace(letters), g)!!

    private fun letters(s: ShapeWriter.KeySequence) = s.keys.map { k -> g.keys[k].letter }.joinToString("")

    private fun show(label: String, r: ShapeResult) = println("$label: " + r.candidates.take(5).map { c ->
        "${c.text} search=${"%.2f".format(c.searchCost)} d=${"%.1f".format(c.shapeDistance)} bonus=${c.contextBonus} total=${"%.1f".format(c.total)}"
    })

    /** LM part of the search cost: LM_PER_CLASS × (255 − class) of the entry that was found. */
    private fun lm(@Suppress("UNUSED_PARAMETER") c: ShapeCandidate, cls: Int) = 255 - cls

    /** Class of an elided entry: p_pre(prefix) · P(follower | prefix) as a frequency class (06 §4.1). */
    private fun elidedClass(prefix: String, follower: String): Int {
        val pre = it.unigramLog10(it.lexicon.prefixClass(prefix))
        val pair = it.dictionary.pairs!!.bigram(prefix, follower)
        assertTrue(pair >= 0, "no pair ($prefix, $follower)")
        return app.metrokeys.engine.predict.LmCost.roundHalfAway((pre + app.metrokeys.engine.predict.LmCost.pairLog10(pair) - it.a) / it.bc)
            .coerceIn(0, 255)
    }

    @Test
    fun constantsOfTheItalianColumn() {
        val s = LanguageParams.ITALIAN.shape
        assertEquals(8.0, s.unusedKeyCost)
        assertEquals(20.75, s.neighbourFactor * 250, 1e-9)
        assertEquals(14.0, s.doubleLetterCost)
        assertEquals(8.0, s.accentCost, 1e-9)
        assertEquals(45f, s.cornerAngle)
        // equal to the English constants except the accent factor
        assertEquals(LanguageParams.ENGLISH.shape.copy(accentFactor = 0.16), s)
    }

    @Test
    fun elidedEntriesAreInTheIndex() {
        val n = w.elidedEntryCount
        println("elided shape-index entries: $n")
        assertTrue(n in 3000..12000, "$n")
    }

    @Test
    fun e12PiuThroughItsBaseKey() {
        val s = seq("piu")
        assertEquals("poiu", letters(s))
        val expected = listOf(180, 0, 0, 180)
        for ((i, a) in s.angles.withIndex()) assertEquals(expected[i].toFloat(), a, 1.5f, "angle $i")
        val t = trace("piu")
        val all = ShapeWriterTest.allCandidates(w, t)
        val piu = all.getValue("più")
        assertEquals(16.0 + lm(piu, it.lexicon.exactClass("più")), piu.searchCost, 1e-6)
        all["poi"]?.let { p -> assertEquals(28.75 + lm(p, it.lexicon.exactClass("poi")), p.searchCost, 1e-6) }
        all["pio"]?.let { p -> assertEquals(49.5 + lm(p, it.lexicon.exactClass("pio")), p.searchCost, 1e-6) }
        assertTrue("piu" !in all)
        val r = w.recognize(t, "")
        show("E12", r)
        assertEquals("più", r.candidates.first().text)
        assertEquals("poi", r.candidates[1].text)
    }

    @Test
    fun e13ElidedWordAgainstCe() {
        val s = seq("ce")
        assertEquals("cde", letters(s))
        val t = trace("ce")
        val all = ShapeWriterTest.allCandidates(w, t)
        val ce = all.getValue("ce")
        assertEquals(8.0 + 255 - it.lexicon.exactClass("ce"), ce.searchCost, 1e-6)
        val elided = all.getValue("c'è")
        // 16 + LM, the class of the elided entry from p_pre(c') · P(è | c')
        val cls = elidedClass("c'", "è")
        println("E13 class(c'è)=$cls class(ce)=${it.lexicon.exactClass("ce")}")
        assertEquals(16.0 + 255 - cls, elided.searchCost, 1e-6)
        assertEquals(ce.shapeDistance, elided.shapeDistance, 1e-9)
        val r = w.recognize(t, "")
        show("E13", r)
        assertEquals("c'è", r.candidates.first().text)
        assertEquals("ce", r.candidates[1].text)
        show("E13 after 'non '", w.recognize(t, "non "))
        // counter-check: after a prefix ("c'" picked from the bar) no elided entries are searched, and the previous
        // word is the prefix: (c', è) gives "è" a context bonus
        val after = w.recognize(t, "c'")
        show("E13 after c'", after)
        assertTrue(after.candidates.none { c -> c.text.contains('\'') })
        assertTrue(w.contextBonus("è", it.lexicon.exactClass("è"), app.metrokeys.engine.text.PrevContext.Word("c'")) > 0)
    }

    @Test
    fun e14ElidedWordInOneGesture() {
        val s = seq("lacqua")
        assertEquals("lkjhgfdsasxcxdswqwertyuytdsa", letters(s))
        val t = trace("lacqua")
        val all = ShapeWriterTest.allCandidates(w, t)
        val acqua = all.getValue("l'acqua")
        // skipped angles: spec 24 + 9 + 29 + 16 = 78 → 15.6; the synthetic trace's corners lie a little elsewhere
        // (18 + 9 + 49 + 16 = 92 → 18.4), so the search cost is 2.8 above the spec's 191.6
        assertEquals(191.6 + 2.8 + 255 - elidedClass("l'", "acqua"), acqua.searchCost, 0.3)
        all["lascia"]?.let { l -> assertEquals(228.75 + 255 - it.lexicon.exactClass("lascia"), l.searchCost, 1.0) }
        val r = w.recognize(t, "")
        show("E14", r)
        assertEquals("l'acqua", r.candidates.first().text)
        assertEquals("L'acqua", r.texts(ShiftState.ONE_SHOT).first())
    }

    @Test
    fun acceptanceAccuracyItalian() {
        // 06 open point 13: common words ending in an accented vowel and elided entries; the trace is the letters alone
        val words = ("più è perché così già città può anche sono come questo tempo casa grazie bene sempre adesso " +
            "allora niente qualcosa vedere fare dire andare sapere volere mangiare lavoro amico ragazzo donna uomo " +
            "giorno notte mondo cosa vita ancora domani oggi prima dopo molto troppo l'ho dell'anno un'altra").split(" ")
        val rnd = Random(42)
        var hits = 0
        val misses = ArrayList<String>()
        for (word in words) {
            val ox = (rnd.nextFloat() * 2 - 1) * 0.15f * g.keyWidth
            val oy = (rnd.nextFloat() * 2 - 1) * 0.15f * g.keyWidth
            val t = ShapeWriterTest.traceOn(g, word.replace("'", "")) {
                (ox + (rnd.nextFloat() * 2 - 1) * 0.1f * g.keyWidth) to (oy + (rnd.nextFloat() * 2 - 1) * 0.1f * g.keyWidth)
            }
            val top = w.recognize(t, "").candidates.firstOrNull()?.text?.lowercase()
            if (top == word) hits++ else misses.add("$word→$top")
        }
        val rate = hits.toDouble() / words.size
        println("Italian shape-writing top-1 rate: $rate (${words.size} words), misses $misses")
        assertTrue(rate >= 0.85, "top-1 rate $rate, misses $misses")
    }
}
