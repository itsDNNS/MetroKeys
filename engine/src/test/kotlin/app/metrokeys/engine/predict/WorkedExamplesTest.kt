package app.metrokeys.engine.predict

import app.metrokeys.engine.TestDictionaries
import app.metrokeys.engine.query
import app.metrokeys.engine.text.PrevContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Worked examples of spec 05 §17 with the bundled dictionaries (no learned data, no taps, fallback grid). */
class WorkedExamplesTest {
    private val de = TestDictionaries.german()
    private val en = TestDictionaries.english()

    private fun LanguageSuggestions.corr(display: String): Candidate =
        corrections.firstOrNull { it.display == display } ?: error("no correction $display in $corrections")

    @Test
    fun haus171() {
        // classes the example relies on
        assertEquals(184, de.lexicon.exactClass("Haus")) // 0.7.0 German list (0.6.9: 183)
        assertEquals(listOf("Haus"), de.lexicon.forms("haus").map { it.text })
        assertEquals(149, de.dictionary.pairs!!.bigram("das", "Haus")) // 0.7.0 German list (0.6.9: 150)
        assertEquals(-3.4997, de.unigramLog10(183), 0.0005) // 0.7.0 German list (0.6.9: -3.331)
        val r = de.query("Das ist das haus")
        assertFalse(r.validity.valid)
        assertEquals(255, r.validity.cost)
        val haus = r.corr("Haus")
        assertEquals(58, haus.lm) // 0.7.0 German list (0.6.9: 57)
        assertEquals(44, haus.ed)
        assertEquals(102, haus.correctionCost) // 0.7.0 German list (0.6.9: 101)
        assertEquals(113, de.acScore(haus, 4)) // 0.7.0 German list (0.6.9: 112)
        val raus = r.corr("raus")
        assertEquals(78, raus.lm) // 0.7.0 German list (0.6.9: 73)
        assertEquals(100, raus.ed)
        assertEquals(203, de.acScore(raus, 4)) // 0.7.0 German list (0.6.9: 198)
        assertEquals("Haus", r.autoCorrection?.display)
    }

    @Test
    fun nikolaus171() {
        val r = de.query("Das ist das Haus vom nikolaus")
        val n = r.corr("Nikolaus")
        assertEquals(110, n.lm) // 0.7.0 German list (0.6.9: 115)
        assertEquals(44, n.ed)
        assertEquals(137, de.acScore(n, 8)) // 0.7.0 German list (0.6.9: 142)
        assertEquals(193, de.acScore(r.corr("Nikolais"), 8)) // 0.7.0 German list (0.6.9: 192)
        assertEquals("Nikolaus", r.autoCorrection?.display)
        // not a compound "Niko" + "Laus" (case error)
        assertFalse(de.validity("nikolaus", CaseMode.AS_TYPED).valid)
    }

    @Test
    fun dnan172() {
        val r = de.query("Dnan")
        val dann = r.corr("Dann")
        assertEquals(218, de.lexicon.exactClass("dann")) // 0.7.0 German list (0.6.9: 212)
        assertEquals(62, dann.lm) // 0.7.0 German list (0.6.9: 61)
        assertEquals(50, dann.ed)
        assertEquals(124, de.acScore(dann, 4)) // 0.7.0 German list (0.6.9: 123)
        val dran = r.corr("Dran")
        assertEquals(84, dran.lm) // 0.7.0 German list (0.6.9: 80)
        assertEquals(100, dran.ed)
        assertEquals(209, de.acScore(dran, 4)) // 0.7.0 German list (0.6.9: 205)
        assertEquals("Dann", r.autoCorrection?.display)
        val r2 = de.query("Und dnan")
        assertEquals("dann", r2.autoCorrection?.display)
        assertEquals(60, r2.autoCorrection?.lm)
        assertEquals(122, de.acScore(r2.autoCorrection!!, 4))
    }

    @Test
    fun englishAloneWouldCorrectDnanToDan() {
        val r = en.query("Dnan")
        val dan = r.corr("Dan")
        assertEquals(95, dan.lm)
        assertEquals(119, dan.ed)
        assertEquals(243, en.acScore(dan, 4))
    }

    @Test
    fun fur173() {
        val r = de.query("Vielen Dank fur")
        assertEquals(233, de.lexicon.exactClass("für")) // 0.7.0 German list (0.6.9: 221)
        assertEquals(229, de.dictionary.pairs!!.bigram("dank", "für"))
        val f = r.corr("für")
        assertEquals(29, f.lm)
        assertEquals(4, f.ed)
        assertEquals(35, de.acScore(f, 3))
        val nur = r.corr("nur")
        assertEquals(59, nur.lm)
        assertEquals(225, de.acScore(nur, 3))
        assertEquals("für", r.autoCorrection?.display)
        assertEquals(131, en.lexicon.exactClass("fur"))
        assertEquals(-5.006, en.unigramLog10(131), 0.0005) // 0.7.0 English header (0.6.9: -5.041)
    }

    @Test
    fun reallyAndMeeting173() {
        val r = de.query("Das war really")
        assertNull(r.autoCorrection)
        assertTrue(r.corrections.isNotEmpty())
        // 05 §17.3 gives 236 (best candidate "Reilly"); since the damping of non-German keys (0.6.4, 05b §3.6) "Reilly"
        // costs more and "reales" is best with 242, still above AC_LIMIT 212 (spec/QUESTIONS.md)
        assertEquals("reales", r.corrections.first().display)
        assertEquals(239, de.acScore(r.corrections.first(), 6)) // 0.7.0 German list (0.6.9: 242)
        assertEquals(listOf("Meeting"), de.lexicon.forms("meeting").map { it.text })
        assertEquals("Meeting", de.query("Ich habe heute ein meeting").autoCorrection?.display)
        assertNull(en.query("I have a meeting").autoCorrection)
    }

    @Test
    fun hsus174() {
        val r = de.query("Hsus")
        val h = r.corr("Haus")
        assertEquals(78, h.lm) // 0.7.0 German list (0.6.9: 75)
        assertEquals(30, h.ed)
        assertEquals(108, h.correctionCost) // 0.7.0 German list (0.6.9: 105)
        assertEquals(115, de.acScore(h, 4)) // 0.7.0 German list (0.6.9: 112)
        val huss = r.corr("Huss")
        assertEquals(129, huss.lm) // 0.7.0 German list (0.6.9: 130)
        assertEquals(50, huss.ed)
        assertEquals(191, de.acScore(huss, 4)) // 0.7.0 German list (0.6.9: 192)
        assertEquals("Haus", r.autoCorrection?.display)
        val after = de.query("das Hsus").corr("Haus")
        assertEquals(58, after.lm) // 0.7.0 German list (0.6.9: 57)
        assertEquals(95, de.acScore(after, 4)) // 0.7.0 German list (0.6.9: 94)
    }

    @Test
    fun hsusWithRealTap174() {
        val g = de.geometry
        val a = g.ownKey('a')!!
        val tapX = a.centerX + 0.55f * g.keyWidth
        val taps = listOf(
            app.metrokeys.engine.touch.Tap.unknown('H'),
            app.metrokeys.engine.touch.Tap('s', tapX, a.centerY),
            app.metrokeys.engine.touch.Tap.unknown('u'),
            app.metrokeys.engine.touch.Tap.unknown('s'),
        )
        val r = de.suggest(QueryContext.of("Hsus", taps))
        val h = r.corr("Haus")
        assertEquals(10, h.ed)
        // 05 §17.4 states score 77; lm 75 + 5 · 10 / 4 = 87 by the formula of §10.1 (see spec/QUESTIONS.md)
        assertEquals(90, de.acScore(h, 4)) // 0.7.0 German list (0.6.9: 87)
    }

    @Test
    fun english175() {
        val g = en.query("a grwat")
        assertEquals(58, g.corr("great").lm)
        assertEquals(49, g.corr("great").ed)
        assertEquals(107, en.acScore(g.corr("great"), 5))
        assertEquals(207, en.acScore(g.corr("treat"), 5))
        assertEquals("great", g.autoCorrection?.display)

        val d = en.query("I dont")
        assertEquals(listOf("don't"), en.lexicon.forms("dont").map { it.text })
        assertEquals(226, en.lexicon.exactClass("don't")) // 0.7.0 (0.6.9: 228)
        assertEquals(0, d.corr("don't").ed)
        assertEquals(38, d.corr("don't").lm)
        assertEquals(38, en.acScore(d.corr("don't"), 4))
        assertEquals("don't", d.autoCorrection?.display)

        // 0.7.0: the English classes come from the combined frequency (05b §12.2, §4.6), so the lm values and scores
        // are informative (05 §17); the edit costs and the outcomes are unchanged. 0.6.9: lm(receive) 97, score 137,
        // deceive 198; lm(the) 36, score 129, yeh 199
        val rc = en.query("I did not recieve")
        val receive = rc.corr("receive")
        println("en 17.5: lm(receive) ${receive.lm}, score ${en.acScore(receive, 7)}, deceive ${en.acScore(rc.corr("deceive"), 7)}")
        assertEquals(56, receive.ed)
        assertEquals(receive.lm + 40, en.acScore(receive, 7))
        assertTrue(en.acScore(rc.corr("deceive"), 7) - en.acScore(receive, 7) > en.params.acMargin)
        assertEquals("receive", rc.autoCorrection?.display)

        val t = en.query("teh")
        val the = t.corr("the")
        println("en 17.5: lm(the) ${the.lm}, score ${en.acScore(the, 3)}, yeh ${en.acScore(t.corr("yeh"), 3)}")
        assertEquals(56, the.ed)
        assertEquals(the.lm + 93, en.acScore(the, 3))
        assertTrue(en.acScore(t.corr("yeh"), 3) - en.acScore(the, 3) > en.params.acMargin)
        assertEquals("the", t.autoCorrection?.display)

        assertEquals("I", en.query("i").autoCorrection?.display)
        assertNull(de.query("i").autoCorrection)

        val goin = en.query("I am goin")
        assertTrue(goin.validity.valid)
        assertTrue(goin.validity.cost <= en.params.acValidRare, "cost ${goin.validity.cost}") // 0.6.9: 45
        assertNull(goin.autoCorrection)
        assertEquals("going", goin.ranked.first().display)
    }

    @Test
    fun compound176() {
        assertEquals(126, de.lm("Kaffeetasse")) // 0.7.0 German list (0.6.9: 127)
        assertEquals(108, de.lm("Regal")) // 0.7.0 German list (0.6.9: 110)
        val v = de.validity("Kaffeetassenregal", CaseMode.FIRST_UPPER)
        assertTrue(v.valid)
        assertEquals(6, v.rule)
        assertEquals(126, v.cost) // 0.7.0 German list (0.6.9: 127)
        val r = de.query("Kaffeetassenregal")
        assertNull(r.autoCorrection)
        assertTrue(de.isKnown("Kaffeetassenregal"))
    }

    @Test
    fun completion177() {
        val r = de.query("Vielen Da")
        val first = r.ranked.first()
        assertEquals("Dank", first.display)
        assertEquals(36, first.lm)
        assertEquals(46, first.completionCost)
    }

    @Test
    fun nextWord177() {
        val next = de.predictNext("Vielen ")
        // 0.6.5 with all dictionary-only forms gave anderen 53 and Fällen 55; with the lemmas of 0.6.6 the values of
        // 05 §17.7 hold again
        // 0.7.0 (held-out sentences out of the pair count, 05b §12.6): Ländern 52 and anderen 53 (0.6.9: 51, 52); order unchanged
        assertEquals(listOf("Dank" to 41, "Jahren" to 46, "Ländern" to 52, "anderen" to 53, "Fällen" to 54),
            next.take(5).map { it.text to it.cost })
        assertEquals(211, de.dictionary.pairs!!.bigram("vielen", "Dank"))

        val start = de.predictNext("").associate { it.text to it.cost }
        println("text start: " + de.predictNext("").take(10).map { it.text to it.cost })
        assertEquals(43, start["Ich"])
        assertEquals(45, start["Die"])
        assertEquals(48, start["Er"])
        assertEquals(49, start["Sie"])
        // 0.6.5 and 0.6.6: 50 (05 §17.7 and 0.6.4: 49); the dictionary-only lemmas are counted in the pair sources too
        // (spec/QUESTIONS.md)
        assertEquals(49, start["Das"]) // 0.7.0 German list (0.6.9: 50)
        assertEquals(50, start["Der"])
        assertEquals(52, start["Es"])
        assertEquals(55, start["In"])
        assertEquals("Ich", de.predictNext("").first().text)

        assertTrue("you" in en.predictNext("thank ").take(3).map { it.text })
    }

    @Test
    fun contextApplies() {
        // a pair can only help
        val withPair = de.lm("Haus", PrevContext.Word("das"))
        val without = de.lm("Haus")
        assertEquals(58, withPair) // 0.7.0 German list (0.6.9: 57)
        assertEquals(78, without) // 0.7.0 German list (0.6.9: 75)
        assertNotNull(de.dictionary.pairs)
    }
}
