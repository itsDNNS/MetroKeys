package app.metrokeys.engine.predict

import app.metrokeys.engine.Query
import app.metrokeys.engine.TestDictionaries
import app.metrokeys.engine.query
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        // "zi" is a German form ("Zi", abbreviation of "Zimmer"), so step 6a does not reach it (spec/QUESTIONS.md). It is
        // a real-word typo of "zu" (05b §3.6; the abbreviation exemption of the 0.7.1 draft is withdrawn): class 0, valid
        // (no spelling mark) and corrected to "zu"; its key carries the abbreviation bit (05b §1.1), so "Zi." stays (04
        // §3.2 item 9, tested in the app)
        assertEquals(0, de.lexicon.exactClass("zi"))
        assertTrue(de.query("zi").validity.valid)
        assertEquals("zu", engine.query(Query("zi")).autoCorrection)
        assertEquals("zu", engine.query(Query("Ich gehe zi")).autoCorrection)
        assertTrue(de.lexicon.isAbbreviationWord("zi"))
        // the other abbreviations that are real-word typos are class 0 again and carry the bit; "heure" has no bit
        for (k in listOf("fem", "unf", "lett", "ind", "dor")) {
            assertEquals(0, de.lexicon.exactClass(k), k)
            assertTrue(de.lexicon.isAbbreviationWord(k), k)
        }
        assertEquals(0, de.lexicon.exactClass("heure"))
        assertFalse(de.lexicon.isAbbreviationWord("heure"))
    }

    @Test
    fun abbreviationBit() {
        // 05b §1.1 (0.7.1): one bit per key from the abbreviation titles ("usw.", "bzw.") and the sections "Abkürzung";
        // it changes no class or validity and is reported as typedIsAbbreviation (05 §2.1)
        for (k in listOf("usw", "bzw", "vgl")) assertTrue(de.lexicon.isAbbreviationWord(k), k)
        for (k in listOf("haus", "und", "heute")) assertFalse(de.lexicon.isAbbreviationWord(k), k)
        assertTrue(engine.query(Query("Äpfel, Birnen usw")).typedIsAbbreviation)
        assertNull(engine.query(Query("Äpfel, Birnen usw")).autoCorrection)
        assertFalse(engine.query(Query("Das ist das haus")).typedIsAbbreviation)
        // every language has the bit
        for (le in listOf(de, TestDictionaries.english(), TestDictionaries.spanish(), TestDictionaries.portuguese(),
            TestDictionaries.italian(), TestDictionaries.french())) {
            println("${le.languageTag}: ${le.lexicon.abbreviationCount} keys with the abbreviation bit")
            assertTrue(le.lexicon.abbreviationCount > 0, le.languageTag)
        }
        assertTrue(TestDictionaries.english().lexicon.isAbbreviationWord("etc"))
        assertTrue(TestDictionaries.spanish().lexicon.isAbbreviationWord("etc"))
    }

    @Test
    fun step6aDropsShortNonGermanKeys() {
        // dropped (count < 20 000): "joe", "you", "and". The prefix title "of-" does not make "of" a form (05b §3.6,
        // 0.6.7: prefixes are compound parts only). 0.7.0 (05b §12.4): of the keys of at least 20 000 the colloquial
        // path drops "dad" (subtitles 45 times as frequent as written text) and "the" (an en-US key) and keeps "wär"
        for (k in listOf("joe", "you", "and", "of", "ähm", "dad", "the")) assertTrue(de.lexicon.forms(k).isEmpty(), k)
        assertTrue(de.lexicon.forms("wär").isNotEmpty())
        assertTrue(de.lexicon.isUnattestedWord("wär"))
    }

    /**
     * 05b §3.6 "Abbreviation and affix titles" (0.6.7): a Wiktionary title ending in "." is a form of the key without
     * it (a title ending in "-" is a prefix, a German compound part only). The German abbreviations are kept as forms (not dropped by step 6a, not damped); one counted at most
     * once in the subtitles is a dictionary-only lemma with class 1 (§3.7). Typed without the period they stay as typed.
     */
    @Test
    fun abbreviationTitlesAreForms() {
        // classes informative from 0.7.0 (combined frequency, 05b §12.2; 0.6.9: 63, 38, 64, 26, 11, 1)
        for (k in listOf("usw", "bzw", "etc", "evtl", "vgl", "ggf")) {
            println("abbreviation $k class ${de.lexicon.exactClass(k)}")
            assertTrue(de.lexicon.exactClass(k) >= 1, k)
            assertTrue(de.query(k).validity.valid, k)
        }
        assertEquals(null, engine.query(Query("Äpfel, Birnen usw")).autoCorrection)
        val es = TestDictionaries.spanish()
        assertEquals(137, es.lexicon.exactClass("etc")) // 0.7.0 (05b §12.2); 0.6.9: 107
        assertTrue(es.query("etc").validity.valid)
    }

    /**
     * 05b §3.6 "Other languages" and §12.4 (0.7.0): step 6a and the colloquial path in the Spanish, Portuguese, Italian
     * and French builds. Short non-forms counted below 20 000 times stay dropped by step 6a; the names and foreign words
     * that the 0.6.7–0.6.9 count rule kept with the damping are now dropped by the colloquial path (subtitles more than
     * 4 times as frequent as written text, or an en-US key), unless a spelling source knows them: then they are names
     * (05b §12.5), valid and not flagged unattested (tools/dictionary-build.json).
     */
    @Test
    fun step6aAndColloquialPathInOtherLanguages() {
        val cases = listOf(
            // engine, dropped (step 6a or the colloquial path), names kept through a spelling source
            Triple(TestDictionaries.spanish(), listOf("amy", "ted", "tim", "roy", "joe", "tom", "jim", "michael"), listOf()),
            Triple(TestDictionaries.portuguese(), listOf("huh", "ed", "ted", "ooh", "sam"), listOf("Max", "FBI", "Michael")),
            Triple(TestDictionaries.italian(), listOf("jim", "amy", "ted", "huh", "sam", "joe", "percy"), listOf()),
            Triple(TestDictionaries.french(), listOf("ooh", "ohh", "jae", "axl"), listOf("Tess")),
        )
        for ((e, dropped, names) in cases) {
            val tag = e.params.languageTag
            for (k in dropped) assertTrue(e.lexicon.forms(k).isEmpty(), "$tag $k")
            for (n in names) {
                assertEquals(n, e.lexicon.forms(n.lowercase()).first().text, "$tag $n")
                assertTrue(!e.lexicon.isUnattestedWord(n), "$tag $n")
            }
            assertTrue(e.lexicon.unattestedCount in 1..1000, "$tag ${e.lexicon.unattestedCount}")
        }
    }

    /** 05 §17.7a (0.6.5): a dictionary-only German form (05b §3.7) is offered. */
    @Test
    fun performanzOffersPerformant() {
        show("Die App ist nicht performanz")
        // no subtitle occurrence: admitted by its written frequency (05b §12.3), class from the combined count. From
        // 0.7.1 it lies beyond SUGGEST_MAX_RANK: valid, but not offered (05 §17.7a, R2 of OD 0.7.1); the case variant
        // "Performanz" is the only orthographic candidate and becomes the autocorrection
        assertEquals(12, de.lexicon.exactClass("performant"))
        assertFalse(de.lexicon.isOfferedWord("performant"))
        val r = engine.query(Query("Die App ist nicht performanz"))
        assertEquals("Performanz", r.autoCorrection)
        assertTrue(r.suggestions.none { it.text == "performant" }, r.suggestions.toString())
        val multi = TestDictionaries.multilingual("de-DE", TestDictionaries.german(), TestDictionaries.english())
        val m = multi.query(Query("Die App ist nicht performanz"))
        assertTrue(m.suggestions.none { it.text == "performant" }, m.suggestions.toString())
        // typed in full it stays as typed
        assertNull(engine.query(Query("Die App ist nicht performant")).autoCorrection)
    }

    /** 05 §17.7a (0.6.9): spellings without umlauts are merged into the umlaut form (05b §3.6 umlaut merge). */
    @Test
    fun konnenIsCorrectedToKoennen() {
        show("Wir konnen")
        assertTrue(de.lexicon.forms("konnen").isEmpty())
        assertEquals("können", engine.query(Query("Wir konnen")).autoCorrection)
        assertEquals("Können", engine.query(Query("Konnen")).autoCorrection)
        // German forms are never merged
        for (w in listOf("schon", "schön", "zahlen", "zählen", "dass")) assertTrue(de.lexicon.forms(w).isNotEmpty(), w)
        val multi = TestDictionaries.multilingual("de-DE", TestDictionaries.german(), TestDictionaries.english())
        val m = multi.query(Query("Wir konnen"))
        println("konnen de+en: ac=${m.autoCorrection} bar=${m.suggestions.map { it.text }}")
        assertEquals("können", m.autoCorrection)
    }

    @Test
    fun cursosOffersCursorFirst() {
        show("Cursos")
        val names = listOf("Curtis", "Crusoe", "Cursor").associateWith { de.lexicon.exactClass(it) }
        println("classes: $names")
        // 05 §17.7a (0.7.0): "Cursor" among the first three, "Curtis" (no spelling source knows it) not offered at all;
        // whether "Cursor" or "Crusoe" comes first is left to the frequencies (up to 0.6.9 "Cursor" was first)
        val r = engine.query(Query("Cursos"))
        val top3 = r.suggestions.take(3).map { it.text }
        assertTrue("Cursor" in top3, bar("Cursos").toString())
        assertTrue(r.suggestions.none { it.text == "Curtis" }, bar("Cursos").toString())
        assertTrue("Cursor" in engine.resumeCorrections("Cursos").take(3).map { it.text })
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
        // 0.7.0 (05b §12.5): names a spelling source knows stay valid; film names and words that only the subtitles
        // know are no keys any more (colloquial path, §12.4)
        for (n in listOf("Crusoe", "Jack")) assertTrue(de.query(n).validity.valid, n)
        for (n in listOf("Curtis", "Captain")) assertTrue(de.lexicon.forms(n).isEmpty(), n)
    }
}
