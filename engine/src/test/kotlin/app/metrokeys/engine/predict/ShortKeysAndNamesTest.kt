package app.metrokeys.engine.predict

import app.metrokeys.engine.Query
import app.metrokeys.engine.TestDictionaries
import app.metrokeys.engine.query
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 05 §17.7a (0.6.4): short keys and names in the German build (05b §3.6 step 6a and the damping of non-German keys),
 * German alone, fallback grid, no taps, no learned data.
 */
class ShortKeysAndNamesTest {
    private val de = TestDictionaries.german()
    private val engine = TestDictionaries.single(TestDictionaries.german())

    private fun bar(text: String): List<String> = engine.query(Query(text)).suggestions.map { it.text }

    private fun show(text: String) {
        val r = de.query(text)
        println("$text: valid=${r.validity} ac=${r.autoCorrection} corr=${r.corrections.take(4)} bar=${bar(text)}")
    }

    @Test
    fun zjIsCorrectedToZu() {
        show("zj")
        assertEquals("zu", engine.query(Query("zj")).autoCorrection)
        assertEquals("zu", engine.query(Query("Ich gehe zj")).autoCorrection)
    }

    @Test
    fun zi() {
        show("zi")
        show("Ich gehe zi")
        // "zi" is a German form ("Zi", abbreviation of "Zimmer"), so step 6a does not reach it (spec/QUESTIONS.md).
        // The real-word typo rule (05b §3.6) does: count 96 < 100, its most frequent form "zi" (share 0.56) is lower case,
        // and "zu" is one key aside and ≥ 5000 times as frequent. So "zi" has class 0: valid, never offered, and a space
        // autocorrects it to "zu" as 05 §17.7a expects.
        assertEquals(0, de.lexicon.exactClass("zi"))
        assertTrue(de.query("zi").validity.valid)
        assertEquals("zu", engine.query(Query("zi")).autoCorrection)
        assertEquals("zu", engine.query(Query("Ich gehe zi")).autoCorrection)
    }

    @Test
    fun step6aDropsShortNonGermanKeys() {
        // dropped (count < 20 000): "joe", "you", "and"; kept (≥ 20 000), but damped: "dad", "the". The prefix title
        // "of-" does not make "of" a form (05b §3.6, 0.6.7: prefixes are compound parts only).
        for (k in listOf("joe", "you", "and", "of", "ähm")) assertTrue(de.lexicon.forms(k).isEmpty(), k)
        for (k in listOf("dad", "the", "wär")) assertTrue(de.lexicon.forms(k).isNotEmpty(), k)
    }

    /**
     * 05b §3.6 "Abbreviation and affix titles" (0.6.7): a Wiktionary title ending in "." is a form of the key without
     * it (a title ending in "-" is a prefix, a German compound part only). The German abbreviations are kept as forms (not dropped by step 6a, not damped); one counted at most
     * once in the subtitles is a dictionary-only lemma with class 1 (§3.7). Typed without the period they stay as typed.
     */
    @Test
    fun abbreviationTitlesAreForms() {
        for ((k, cls) in listOf("usw" to 63, "bzw" to 38, "etc" to 64, "evtl" to 26, "vgl" to 11, "ggf" to 1)) {
            assertEquals(cls, de.lexicon.exactClass(k), k)
            assertTrue(de.query(k).validity.valid, k)
        }
        assertEquals(null, engine.query(Query("Äpfel, Birnen usw")).autoCorrection)
        val es = TestDictionaries.spanish()
        assertEquals(107, es.lexicon.exactClass("etc"))
        assertTrue(es.query("etc").validity.valid)
    }

    /**
     * 05b §3.6 "Other languages" (0.6.7): step 6a and the damping of non-forms in the Spanish, Portuguese, Italian and
     * French builds. Dropped (no form of the language, at most 3 letters, count < 20 000) and kept (count ≥ 20 000) keys
     * as listed by the build report (tools/dictionary-build.json); a damped key's class is that of count / 100.
     */
    @Test
    fun step6aAndDampingInOtherLanguages() {
        val cases = listOf(
            // engine, dropped by step 6a, kept short non-forms, (damped key, class of count / 100)
            Triple(TestDictionaries.spanish(), listOf("amy", "ted", "tim", "roy"), listOf("joe", "tom", "jim")) to
                listOf("Michael" to 95, "Joe" to 95),
            Triple(TestDictionaries.portuguese(), listOf("huh", "ed", "ted", "ooh"), listOf("sam", "max", "fbi")) to
                listOf("Sam" to 99, "Michael" to 96),
            Triple(TestDictionaries.italian(), listOf("jim", "amy", "ted", "huh"), listOf("sam", "joe")) to
                listOf("Sam" to 94, "Percy" to 49),
            Triple(TestDictionaries.french(), listOf("ooh", "ohh", "jae", "axl"), listOf<String>()) to
                listOf("Tess" to 51),
        )
        for ((t, damped) in cases) {
            val (e, dropped, kept) = t
            val tag = e.params.languageTag
            for (k in dropped) assertTrue(e.lexicon.forms(k).isEmpty(), "$tag $k")
            for (k in kept) assertTrue(e.lexicon.forms(k).isNotEmpty(), "$tag $k")
            for ((k, cls) in damped) assertEquals(cls, e.lexicon.exactClass(k), "$tag $k")
        }
    }

    /** 05 §17.7a (0.6.5): a dictionary-only German form (05b §3.7) is offered. */
    @Test
    fun performanzOffersPerformant() {
        show("Die App ist nicht performanz")
        // no subtitle occurrence: added from the German Wiktionary with class 1, the lowest offered class
        assertEquals(1, de.lexicon.exactClass("performant"))
        val r = engine.query(Query("Die App ist nicht performanz"))
        val top3 = r.suggestions.take(3).map { it.text }
        println("performanz: ac=${r.autoCorrection} bar=${r.suggestions.map { it.text }}")
        assertTrue("performant" in top3, top3.toString())
        // also German with English (multilingual)
        val multi = TestDictionaries.multilingual("de-DE", TestDictionaries.german(), TestDictionaries.english())
        val m = multi.query(Query("Die App ist nicht performanz"))
        println("performanz de+en: ac=${m.autoCorrection} bar=${m.suggestions.map { it.text }}")
        assertTrue("performant" in m.suggestions.take(3).map { it.text }, m.suggestions.toString())
    }

    @Test
    fun cursosOffersCursorFirst() {
        show("Cursos")
        val names = listOf("Curtis", "Crusoe", "Cursor").associateWith { de.lexicon.exactClass(it) }
        println("classes: $names")
        val r = engine.query(Query("Cursos"))
        assertEquals("Cursor", r.suggestions.first().text, bar("Cursos").toString())
        // also in the correction search of a resumed or caret word (no taps)
        assertEquals("Cursor", engine.resumeCorrections("Cursos").first().text)
    }

    @Test
    fun realWordTypo() {
        // 05b §3.6 real-word typos (0.6.4): "heure" (a German form) is r/t one key aside from the far more frequent
        // "heute", so it has class 0: valid, never offered, but autocorrected (05 §10.1 step 5)
        show("Ich komme heure")
        assertEquals(0, de.lexicon.exactClass("heure"))
        assertTrue(de.query("heure").validity.valid)
        assertEquals("heute", engine.query(Query("Ich komme heure")).autoCorrection)
        assertTrue("heure" !in bar("Ich komme heu"), bar("Ich komme heu").toString())
        // a German form whose neighbour is not 1000 times as frequent keeps its class
        assertTrue(de.lexicon.exactClass("heuer") > 0)
    }

    @Test
    fun namesStayValid() {
        for (n in listOf("Curtis", "Crusoe", "Jack", "Captain")) assertTrue(de.query(n).validity.valid, n)
    }
}
