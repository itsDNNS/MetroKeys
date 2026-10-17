package app.metrokeys.engine.predict

import app.metrokeys.engine.Query
import app.metrokeys.engine.TestDictionaries
import app.metrokeys.engine.barTexts
import app.metrokeys.engine.entry
import app.metrokeys.engine.query
import app.metrokeys.engine.text.PrevContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Worked examples of spec 05 §17.1–§17.7a (0.7.1 rules: anchored LM scale, key-slip search, spelling source, policy
 * P3) with the bundled dictionaries (no learned data, no taps, fallback grid). The outcomes are normative; the numbers
 * are the computed values of this build (informative, 05 §17), equal to the spec's unless a comment says otherwise.
 */
class WorkedExamplesTest {
    private val de = TestDictionaries.german()
    private val en = TestDictionaries.english()

    @Test
    fun haus171() {
        assertEquals(184, de.lexicon.exactClass("Haus"))
        assertEquals(listOf("Haus"), de.lexicon.forms("haus").map { it.text })
        assertEquals(149, de.dictionary.pairs!!.bigram("das", "Haus"))
        val r = de.query("Das ist das haus")
        assertFalse(r.validity.valid)
        assertEquals(255, r.validity.cost)
        val haus = r.entry("Haus")
        assertEquals(CandidateSource.KEY_SLIP, haus.source)
        assertEquals(129, haus.lm)
        assertEquals(44, haus.ed)
        assertEquals(173, haus.score)
        assertEquals(184, de.acScore(haus, "haus"))
        val haha = r.entry("haha")
        assertEquals(188, haha.lm)
        assertEquals(78, haha.ed)
        assertEquals(285, de.acScore(haha, "haus"))
        assertEquals("Haus", r.autoCorrection?.display)
        assertEquals("4o", r.decisionStep)
    }

    @Test
    fun hausLearnedStaysACaseError171() {
        repeat(3) { de.learn("haus", listOf("das")) }
        assertEquals("Haus", de.query("Das ist das haus").autoCorrection?.display)
    }

    @Test
    fun nikolaus171() {
        val r = de.query("Das ist das Haus vom nikolaus")
        val n = r.entry("Nikolaus")
        assertEquals(190, n.lm)
        assertEquals(44, n.ed)
        assertEquals(234, n.score)
        assertEquals("Nikolaus", r.autoCorrection?.display)
        // the spelling suggestion "Nicolaus" changes a letter and is no orthographic candidate
        assertEquals(CandidateSource.SPELLING, r.entry("Nicolaus").source)
        // not a compound "Niko" + "Laus" (case error)
        assertFalse(de.validity("nikolaus", CaseMode.AS_TYPED).valid)
    }

    @Test
    fun dnan172() {
        val r = de.query("Dnan")
        // the spelling source returns Dann, Dan, DNA, Dran, Dän (05 §17.2)
        assertEquals(listOf("Dann", "Dan", "DNA", "Dran", "Dän"), de.spelling.suggest("Dnan", false).words.take(5))
        val dann = r.entry("Dann")
        assertEquals(134, dann.lm)
        assertEquals(50, dann.ed)
        assertEquals(184, dann.score)
        assertEquals(171, de.acScore(dann, "Dnan"))
        assertEquals(253, de.acScore(r.entry("Dan"), "Dnan"))
        assertEquals(285, de.acScore(r.entry("Dran"), "Dnan"))
        assertEquals("Dann", r.autoCorrection?.display)
        val r2 = de.query("Und dnan")
        assertEquals("dann", r2.autoCorrection?.display)
        assertEquals(132, r2.autoCorrection?.lm)
        assertEquals(169, de.acScore(r2.autoCorrection!!, "dnan"))
        assertEquals(266, de.acScore(r2.entry("dan"), "dnan"))
    }

    @Test
    fun englishAloneWouldCorrectDnanToDan() {
        val r = en.query("Dnan")
        val dan = r.entry("Dan")
        assertEquals(CandidateSource.SPELLING, dan.source)
        assertEquals(179, dan.lm)
        assertEquals(277, en.acScore(dan, "Dnan"))
        assertEquals("Dan", r.autoCorrection?.display)
    }

    @Test
    fun fur173() {
        val r = de.query("Vielen Dank fur")
        assertEquals(233, de.lexicon.exactClass("für"))
        assertEquals(229, de.dictionary.pairs!!.bigram("dank", "für"))
        val f = r.entry("für")
        assertEquals(CandidateSource.KEY_SLIP, f.source)
        assertEquals(92, f.lm)
        assertEquals(4, f.ed)
        assertEquals(96, f.score)
        assertEquals(98, de.acScore(f, "fur"))
        assertEquals(224, de.acScore(r.entry("dir"), "fur"))
        assertEquals("für", r.autoCorrection?.display)
        assertEquals("4o", r.decisionStep)
        assertEquals(131, en.lexicon.exactClass("fur"))
        assertEquals(-5.006, en.unigramLog10(131), 0.0005)
    }

    @Test
    fun reallyAndMeeting173() {
        val r = de.query("Das war really")
        assertNull(r.autoCorrection)
        val rally = r.entry("Rally")
        assertEquals(212, rally.lm)
        assertEquals(298, de.acScore(rally, "really"))
        assertEquals("6", r.decisionStep)
        assertEquals(listOf("Meeting"), de.lexicon.forms("meeting").map { it.text })
        val m = de.query("Ich habe heute ein meeting")
        assertEquals("Meeting", m.autoCorrection?.display)
        assertEquals(184, m.autoCorrection!!.lm)
        assertEquals(215, de.acScore(m.autoCorrection!!, "meeting"))
        assertNull(en.query("I have a meeting").autoCorrection)
    }

    @Test
    fun hsus174() {
        val r = de.query("Hsus")
        val h = r.entry("Haus")
        assertEquals(151, h.lm)
        assertEquals(30, h.ed)
        assertEquals(181, h.score)
        assertEquals(188, de.acScore(h, "Hsus"))
        val huss = r.entry("Huss")
        assertEquals(219, huss.lm)
        assertEquals(256, de.acScore(huss, "Hsus"))
        assertEquals("Haus", r.autoCorrection?.display)
        val after = de.query("das Hsus").entry("Haus")
        assertEquals(129, after.lm)
        assertEquals(166, de.acScore(after, "Hsus"))
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
        val ctx = QueryContext.of("Hsus", taps)
        val r = de.suggest(ctx)
        val h = r.entry("Haus")
        assertEquals(10, h.ed)
        assertEquals(163, de.acScore(h, "Hsus", ctx.taps))
        assertEquals("Haus", r.autoCorrection?.display)
    }

    @Test
    fun english175() {
        val g = en.query("a grwat")
        val great = g.entry("great")
        assertEquals(49, great.ed)
        assertEquals(144, great.lm)
        assertEquals(193, great.score)
        assertEquals(193, en.acScore(great, "grwat"))
        assertEquals("great", g.autoCorrection?.display)

        val d = en.query("I dont")
        assertEquals(listOf("don't"), en.lexicon.forms("dont").map { it.text })
        assertEquals(0, d.entry("don't").ed)
        assertEquals(97, d.entry("don't").lm) // with the pair (I, don't); 05 §17.5 gives 139 (informative)
        assertEquals("don't", d.autoCorrection?.display)
        assertEquals("4o", d.decisionStep)

        val rc = en.query("I did not recieve")
        val receive = rc.entry("receive")
        assertEquals(CandidateSource.SPELLING, receive.source)
        assertEquals(56, receive.ed)
        assertEquals(177, receive.lm)
        assertEquals(212, en.acScore(receive, "recieve"))
        assertEquals("receive", rc.autoCorrection?.display)

        val t = en.query("teh")
        val the = t.entry("the")
        assertEquals(94, the.lm)
        assertEquals(175, en.acScore(the, "teh"))
        assertEquals("the", t.autoCorrection?.display)

        for ((typed, expected, lm) in listOf(Triple("ofthe", "of the", 143), Triple("inthe", "in the", 143), Triple("thankyou", "thank you", 162))) {
            val s = en.query(typed)
            assertEquals(expected, s.autoCorrection?.display, typed)
            assertEquals(lm, s.autoCorrection!!.lm, typed)
            assertEquals(131, s.autoCorrection!!.ed, typed)
        }
        assertEquals(222, en.acScore(en.query("ofthe").entry("of the"), "ofthe"))

        assertEquals("I", en.query("i").autoCorrection?.display)
        assertNull(de.query("i").autoCorrection)

        val goin = en.query("I am goin")
        assertTrue(goin.validity.valid)
        assertEquals(7, goin.validity.rule)
        assertTrue(goin.validity.cost <= en.params.acValidRare, "cost ${goin.validity.cost}")
        assertNull(goin.autoCorrection)
        val going = goin.bar().first()
        assertEquals("going", going.display)
        assertFalse(going.isCorrection)
        assertEquals(124, going.score)

        // "restau": no split candidate ahead of the completions (05 §8.2)
        val restau = en.query("restau").bar()
        assertFalse(restau.first().display.contains(' '), restau.toString())
    }

    @Test
    fun compound176() {
        assertEquals(214, de.lm("Kaffeetasse"))
        assertEquals(189, de.lm("Regal"))
        val v = de.validity("Kaffeetassenregal", CaseMode.FIRST_UPPER)
        assertTrue(v.valid)
        assertEquals(6, v.rule)
        assertEquals(214, v.cost)
        val r = de.query("Kaffeetassenregal")
        assertNull(r.autoCorrection)
        assertTrue(de.isKnown("Kaffeetassenregal"))
        // the spelling source offers only the two-word "Kaffeetassen Regal", which German drops (05 §8.1b)
        assertEquals("Kaffeetassen Regal", de.spelling.suggest("Kaffeetassenregal", true).words.first())
        assertTrue(r.ranked.none { ' ' in it.display })
    }

    @Test
    fun completion177() {
        val r = de.query("Vielen Da")
        val first = r.bar().first()
        assertEquals("Dank", first.display)
        assertFalse(first.isCorrection)
        assertEquals(101, first.lm)
        assertEquals(111, first.score)
        // the valid typed word "Da" (lm 132) stands third in the ranked list and is not shown
        val da = r.entry("Da")
        assertTrue(da.isTyped)
        assertEquals(132, da.lm)
        assertEquals(2, r.ranked.indexOf(da))
        assertTrue("Da" !in r.barTexts())
    }

    @Test
    fun nextWord177() {
        val next = de.predictNext("Vielen ")
        assertEquals(listOf("Dank" to 104, "Jahren" to 111, "Ländern" to 118, "anderen" to 119, "Fällen" to 121),
            next.take(5).map { it.text to it.cost })
        assertEquals(211, de.dictionary.pairs!!.bigram("vielen", "Dank"))
        assertEquals(94, de.scale.cost(LmCost.pairProbability(211)))

        val start = de.predictNext("").take(8).map { it.text to it.cost }
        assertEquals(listOf("Ich" to 107, "Die" to 109, "Er" to 114, "Das" to 115, "Sie" to 115, "Der" to 116, "Es" to 118, "In" to 126), start)

        assertTrue("you" in en.predictNext("thank ").take(3).map { it.text })
    }

    @Test
    fun contextApplies() {
        // a pair can only help
        val withPair = de.lm("Haus", PrevContext.Word("das"))
        val without = de.lm("Haus")
        assertEquals(129, withPair)
        assertEquals(151, without)
        assertNotNull(de.dictionary.pairs)
    }

    // ------------------------------------------------------------------ 05 §17.7a

    @Test
    fun heureAndZi177a() {
        val r = de.query("Ich komme heure")
        assertTrue(r.validity.valid)
        assertTrue(r.validity.classZero)
        val heute = r.autoCorrection!!
        assertEquals("heute", heute.display)
        assertEquals(127, heute.lm)
        assertEquals(30, heute.ed)
        assertEquals(157, de.acScore(heute, "heure"))
        assertEquals(234, de.acScore(r.entry("heuer"), "heure"))

        val zj = de.query("zj")
        assertEquals("zu", zj.autoCorrection?.display)
        assertEquals(243, de.acScore(zj.autoCorrection!!, "zj"))
        // "zi" is a real-word typo of "zu" (class 0, 0.7.1 owner decision) and also the abbreviation "Zi.": "zi" +
        // space → "zu"; its key carries the abbreviation bit, so "Zi." stays (05 §2.1, 04 §3.2 item 9)
        val zi = de.query("zi")
        assertTrue(zi.validity.valid)
        assertTrue(zi.validity.classZero)
        assertEquals("zu", zi.autoCorrection?.display)
        val p = TestDictionaries.single(TestDictionaries.german())
        val pz = p.query(Query("zi"))
        assertEquals("zu", pz.autoCorrection)
        assertTrue(pz.typedIsAbbreviation)
        assertTrue(p.query(Query("Zi")).typedIsAbbreviation)
        // likewise "wie geht es dor" + space → "dir". "ind": class 0, so not valid for the decision; step 7 does not
        // apply (0.7.1 review), so "und" is applied also without context although "ins" is within AC_MARGIN
        assertEquals("dir", p.query(Query("wie geht es dor")).autoCorrection)
        val ind = de.query("ind")
        assertTrue(ind.validity.classZero)
        assertEquals("und", ind.ranked.first { !it.isTyped }.display)
        assertEquals("und", ind.autoCorrection?.display)
        assertEquals("und", p.query(Query("ind")).autoCorrection)
        assertEquals("und", p.query(Query("Ich bin müde ind")).autoCorrection)
        // "heure" and "zj" are no abbreviations
        assertFalse(p.query(Query("Ich komme heure")).typedIsAbbreviation)
        assertFalse(p.query(Query("zj")).typedIsAbbreviation)
    }

    @Test
    fun fueIsCorrectedToFuer177() {
        // 05 §8.1c (0.7.1): the run on "fü" hands over no completion as long as "fue", so "für" is a correction and the
        // first suggestion; the AC scores für 181 and die 206 are within AC_MARGIN, but step 7 applies only to valid
        // typed words (0.7.1 review), so "für" is applied
        val r = de.query("das ist fue")
        val first = r.ranked.first { !it.isTyped }
        assertEquals("für", first.display)
        assertTrue(first.isCorrection)
        assertEquals(181, de.acScore(first, "fue"))
        assertEquals(206, de.acScore(r.entry("die"), "fue"))
        assertEquals("für", r.autoCorrection?.display)
        assertEquals("8", r.decisionStep)
        val p = TestDictionaries.single(TestDictionaries.german()).query(Query("das ist fue"))
        assertEquals("für", p.autoCorrection)
        assertEquals("für", p.suggestions.first().text)
    }

    @Test
    fun nirIsCorrectedToMir177a() {
        // 05 §17.7a (0.7.1 review): "Ich habe nir" → "mir" (AC score 172, "nur" 177: within AC_MARGIN; the reference
        // keeps the word, step 7 applies only to valid typed words)
        val r = de.query("Ich habe nir")
        // "nir" is a class-0 form: not valid for the decision (§10.1 step 4)
        assertTrue(!r.validity.valid || r.validity.classZero)
        assertEquals("mir", r.autoCorrection?.display)
        assertEquals(172, de.acScore(r.autoCorrection!!, "nir"))
        assertEquals(177, de.acScore(r.entry("nur"), "nir"))
        assertEquals("8", r.decisionStep)
    }

    @Test
    fun uberpeufe177a() {
        val r = de.query("Uberpeufe")
        val u = r.autoCorrection!!
        assertEquals("Überprüfe", u.display)
        assertEquals(CandidateSource.KEY_SLIP, u.source)
        assertEquals(201, u.lm)
        assertEquals(38, u.ed)
        assertEquals(3, u.units)
        assertEquals(222, de.acScore(u, "Uberpeufe"))
        assertEquals(286, de.acScore(r.entry("Überzeuge"), "Uberpeufe"))
    }

    @Test
    fun cursosAndPerformanz177a() {
        val r = de.query("Cursos")
        assertNull(r.autoCorrection)
        val cursor = r.bar().first()
        assertEquals("Cursor", cursor.display)
        assertEquals(230, cursor.lm)
        assertEquals(100, cursor.ed)
        assertEquals(313, de.acScore(cursor, "Cursos"))
        assertTrue("Curtis" !in r.barTexts())

        val p = de.query("Die App ist nicht performanz")
        assertEquals("Performanz", p.autoCorrection?.display)
        assertEquals(238, p.autoCorrection!!.lm)
        assertEquals("4o", p.decisionStep)
        assertTrue("performant" !in p.barTexts())
        assertTrue(de.validity("performant", CaseMode.AS_TYPED).valid)
        assertFalse(de.lexicon.isOfferedWord("performant"))
    }

    @Test
    fun validWordsKept177a() {
        for (q in listOf("Ich habe hiervon", "abgekriegt", "gibts", "würd", "mit beidem", "das ist schon", "es wurde")) {
            val r = de.query(q)
            assertTrue(r.validity.valid, q)
            assertNull(r.autoCorrection, q)
        }
        assertTrue("schön" in de.query("das ist schon").barTexts().take(3))
        assertTrue("würde" in de.query("es wurde").barTexts().take(3))
    }

    @Test
    fun validRareWordMoreFrequentSpelling177a() {
        val s = de.query("Das ist ein Stuck")
        assertEquals(206, s.validity.cost)
        assertEquals("Stück", s.autoCorrection?.display)
        assertEquals(134, s.autoCorrection!!.lm)
        assertEquals("führt", de.query("ihr fuhrt").autoCorrection?.display)
        assertEquals("spät", de.query("es ist spat").autoCorrection?.display)
    }

    @Test
    fun konnen177a() {
        assertEquals("können", de.query("Wir konnen").autoCorrection?.display)
        val k = de.query("Konnen")
        assertEquals("Können", k.autoCorrection?.display)
        assertEquals(4, k.autoCorrection!!.ed)
    }

    @Test
    fun missingAndExtraLetters177a() {
        val w = de.query("Das ist wirklch")
        assertEquals("wirklich", w.autoCorrection?.display)
        assertEquals(CandidateSource.SPELLING, w.autoCorrection!!.source)
        assertEquals(210, w.autoCorrection!!.ed)
        assertEquals(178, de.acScore(w.autoCorrection!!, "wirklch"))
        assertEquals("einfach", de.query("das ist infach").autoCorrection?.display)
        assertEquals("eigentlich", de.query("das ist eigentlch").autoCorrection?.display)
        val n = de.query("das nict")
        assertEquals("nicht", n.autoCorrection?.display)
        assertEquals(193, de.acScore(n.autoCorrection!!, "nict"))
        assertTrue(n.barTexts().containsAll(listOf("Microsoft", "Nocturne")))

        // ambiguous omissions are corrected to the best candidate (0.7.1 review: step 7 only for valid words; the
        // omission guard does not apply when best itself is the omission reading)
        val m = de.query("das mchte")
        assertEquals("möchte", m.autoCorrection?.display)
        assertEquals("8", m.decisionStep)
        assertEquals(209, de.acScore(m.autoCorrection!!, "mchte"))
        assertEquals(223, de.acScore(m.entry("machte"), "mchte"))
        val scon = de.query("das ist scon")
        assertEquals("schon", scon.autoCorrection?.display)
        // a wrong correction the owner accepts; "gesehen" stays among the first three suggestions after the
        // autocorrection, which the bar shows first (§9; spec/QUESTIONS.md, 0.7.1 review)
        val gesen = de.query("ich habe es gesen")
        assertEquals("gegen", gesen.autoCorrection?.display)
        assertEquals("gegen", gesen.barTexts().first())
        assertTrue("gesehen" in gesen.barTexts().drop(1).take(3), gesen.barTexts().toString())
    }

    @Test
    fun farSubstitutions177a() {
        val n = de.query("das ist nibht")
        assertEquals("nicht", n.autoCorrection?.display)
        assertEquals(110, n.autoCorrection!!.lm)
        assertEquals(100, n.autoCorrection!!.ed)
        assertEquals(210, de.acScore(n.autoCorrection!!, "nibht"))
        val p = de.query("das ist pirklich")
        assertEquals("wirklich", p.autoCorrection?.display)
        assertEquals(198, de.acScore(p.autoCorrection!!, "pirklich"))
    }

    @Test
    fun englishMultilingualMeeting() {
        val m = TestDictionaries.multilingual()
        assertEquals("Meeting", m.query(Query("Ich habe heute ein meeting")).autoCorrection)
        assertNull(m.query(Query("I have a meeting")).autoCorrection)
    }
}
