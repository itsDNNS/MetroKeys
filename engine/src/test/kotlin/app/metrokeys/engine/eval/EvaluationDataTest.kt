package app.metrokeys.engine.eval

import app.metrokeys.engine.EngineSettings
import app.metrokeys.engine.PredictionEngine
import app.metrokeys.engine.Query
import app.metrokeys.engine.SuggestionKind
import app.metrokeys.engine.TestDictionaries
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks of the committed test sets (spec 05c §3, §10) and of the test-build diagnostics (§5); the evaluation itself
 * runs as the Gradle tasks evalLint, evalFast and evalFull.
 */
class EvaluationDataTest {
    private val root: File = TestDictionaries.specFile("layouts-de.json").parentFile.parentFile.parentFile

    private fun half(tag: String, base: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest("1|$tag|half|$base".toByteArray(Charsets.UTF_8))
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (d[i].toLong() and 0xFF)
        return if (java.lang.Long.remainderUnsigned(v, 2) == 0L) "dev" else "test"
    }

    @Test
    fun testSetsAreWellFormed() {
        for (tag in Evaluation.ALL_LANGUAGES) for (salt in listOf("A", "B")) {
            val ts = TestSet.read(File(root, "evaluation/testsets/$tag.$salt.tsv"))
            assertTrue(ts.header.startsWith("# EVAL_VERSION=1\tsalt=$salt\tlanguage=$tag\t"), ts.header)
            assertTrue(ts.items.isNotEmpty())
            val ids = ts.items.map { it.id }
            assertEquals(ids.sorted(), ids, "$tag $salt ids in order")
            assertEquals(ids.size, ids.toSet().size, "$tag $salt ids unique")
            for (it in ts.items) {
                // the half depends only on the base key, never on the salt (§3.2)
                assertEquals(half(tag, it.baseKey), it.half, "$tag ${it.id} ${it.baseKey}")
                assertTrue(it.variant == "N" || it.variant == "S")
                if (it.variant == "N") assertTrue(it.before.startsWith(", "), "${it.id}: ${it.before}")
                else assertTrue(it.sentenceId != "-")
            }
        }
    }

    @Test
    fun diagnosticsDoNotChangeTheResult() {
        val single = PredictionEngine(listOf(TestDictionaries.german()), EngineSettings(emojiSuggestions = false, learning = false))
        val multi = TestDictionaries.multilingual()
        for (before in listOf("Ich habe das haus", "Wir konnen", "dnan", "Das ist ein Tset", "dsa", "Hallo wrold")) {
            for (e in listOf(single, multi)) {
                val (p, d) = e.queryDiagnosed(Query(before))
                assertEquals(e.query(Query(before)), p, before)
                assertTrue(d != null && d.step.isNotEmpty(), before)
                // the diagnostics list the word entries (an emoji may replace the last one in the bar, 05 §9)
                val words = p.suggestions.filter { it.kind != SuggestionKind.EMOJI }.map { it.text }
                assertEquals(words, d!!.entries.map { it.text }.take(words.size), before)
            }
        }
        val (p, d) = single.queryDiagnosed(Query("Das ist das haus"))
        assertEquals("Haus", p.autoCorrection)
        assertEquals("4o", d!!.step) // 0.7.1: an orthographic candidate (05 §10.1 step 4o)
    }
}
