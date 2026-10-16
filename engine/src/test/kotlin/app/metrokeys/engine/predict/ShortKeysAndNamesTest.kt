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
        // dropped (count < 20 000): "joe", "you", "and"; kept (≥ 20 000), but damped: "dad", "the"
        for (k in listOf("joe", "you", "and", "of", "ähm")) assertTrue(de.lexicon.forms(k).isEmpty(), k)
        for (k in listOf("dad", "the", "wär")) assertTrue(de.lexicon.forms(k).isNotEmpty(), k)
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
