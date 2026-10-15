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
        assertEquals(183, de.lexicon.exactClass("Haus"))
        assertEquals(listOf("Haus"), de.lexicon.forms("haus").map { it.text })
        assertEquals(150, de.dictionary.pairs!!.bigram("das", "Haus"))
        assertEquals(-3.331, de.unigramLog10(183), 0.0005)
        val r = de.query("Das ist das haus")
        assertFalse(r.validity.valid)
        assertEquals(255, r.validity.cost)
        val haus = r.corr("Haus")
        assertEquals(57, haus.lm)
        assertEquals(44, haus.ed)
        assertEquals(101, haus.correctionCost)
        assertEquals(112, de.acScore(haus, 4))
        val raus = r.corr("raus")
        assertEquals(73, raus.lm)
        assertEquals(100, raus.ed)
        assertEquals(198, de.acScore(raus, 4))
        assertEquals("Haus", r.autoCorrection?.display)
    }

    @Test
    fun nikolaus171() {
        val r = de.query("Das ist das Haus vom nikolaus")
        val n = r.corr("Nikolaus")
        assertEquals(115, n.lm)
        assertEquals(44, n.ed)
        assertEquals(142, de.acScore(n, 8))
        assertEquals(192, de.acScore(r.corr("Nikolais"), 8))
        assertEquals("Nikolaus", r.autoCorrection?.display)
        // not a compound "Niko" + "Laus" (case error)
        assertFalse(de.validity("nikolaus", CaseMode.AS_TYPED).valid)
    }

    @Test
    fun dnan172() {
        val r = de.query("Dnan")
        val dann = r.corr("Dann")
        assertEquals(212, de.lexicon.exactClass("dann"))
        assertEquals(61, dann.lm)
        assertEquals(50, dann.ed)
        assertEquals(123, de.acScore(dann, 4))
        val dran = r.corr("Dran")
        assertEquals(80, dran.lm)
        assertEquals(100, dran.ed)
        assertEquals(205, de.acScore(dran, 4))
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
        assertEquals(221, de.lexicon.exactClass("für"))
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
        assertEquals(-5.041, en.unigramLog10(131), 0.0005)
    }

    @Test
    fun reallyAndMeeting173() {
        val r = de.query("Das war really")
        assertNull(r.autoCorrection)
        assertTrue(r.corrections.isNotEmpty())
        assertEquals(236, de.acScore(r.corrections.first(), 6))
        assertEquals(listOf("Meeting"), de.lexicon.forms("meeting").map { it.text })
        assertEquals("Meeting", de.query("Ich habe heute ein meeting").autoCorrection?.display)
        assertNull(en.query("I have a meeting").autoCorrection)
    }

    @Test
    fun hsus174() {
        val r = de.query("Hsus")
        val h = r.corr("Haus")
        assertEquals(75, h.lm)
        assertEquals(30, h.ed)
        assertEquals(105, h.correctionCost)
        assertEquals(112, de.acScore(h, 4))
        val huss = r.corr("Huss")
        assertEquals(130, huss.lm)
        assertEquals(50, huss.ed)
        assertEquals(192, de.acScore(huss, 4))
        assertEquals("Haus", r.autoCorrection?.display)
        val after = de.query("das Hsus").corr("Haus")
        assertEquals(57, after.lm)
        assertEquals(94, de.acScore(after, 4))
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
        assertEquals(87, de.acScore(h, 4))
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
        assertEquals(228, en.lexicon.exactClass("don't"))
        assertEquals(0, d.corr("don't").ed)
        assertEquals(38, d.corr("don't").lm)
        assertEquals(38, en.acScore(d.corr("don't"), 4))
        assertEquals("don't", d.autoCorrection?.display)

        val rc = en.query("I did not recieve")
        assertEquals(97, rc.corr("receive").lm)
        assertEquals(56, rc.corr("receive").ed)
        assertEquals(137, en.acScore(rc.corr("receive"), 7))
        assertEquals(198, en.acScore(rc.corr("deceive"), 7))
        assertEquals("receive", rc.autoCorrection?.display)

        val t = en.query("teh")
        assertEquals(36, t.corr("the").lm)
        assertEquals(56, t.corr("the").ed)
        assertEquals(129, en.acScore(t.corr("the"), 3))
        assertEquals(199, en.acScore(t.corr("yeh"), 3))
        assertEquals("the", t.autoCorrection?.display)

        assertEquals("I", en.query("i").autoCorrection?.display)
        assertNull(de.query("i").autoCorrection)

        val goin = en.query("I am goin")
        assertTrue(goin.validity.valid)
        assertEquals(45, goin.validity.cost)
        assertNull(goin.autoCorrection)
        assertEquals("going", goin.ranked.first().display)
    }

    @Test
    fun compound176() {
        assertEquals(127, de.lm("Kaffeetasse"))
        assertEquals(110, de.lm("Regal"))
        val v = de.validity("Kaffeetassenregal", CaseMode.FIRST_UPPER)
        assertTrue(v.valid)
        assertEquals(6, v.rule)
        assertEquals(127, v.cost)
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
        assertEquals(listOf("Dank" to 41, "Jahren" to 46, "Ländern" to 51, "anderen" to 52, "Fällen" to 54),
            next.take(5).map { it.text to it.cost })
        assertEquals(211, de.dictionary.pairs!!.bigram("vielen", "Dank"))

        val start = de.predictNext("").associate { it.text to it.cost }
        assertEquals(43, start["Ich"])
        assertEquals(45, start["Die"])
        assertEquals(48, start["Er"])
        assertEquals(49, start["Sie"])
        assertEquals(49, start["Das"])
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
        assertEquals(57, withPair)
        assertEquals(75, without)
        assertNotNull(de.dictionary.pairs)
    }
}
