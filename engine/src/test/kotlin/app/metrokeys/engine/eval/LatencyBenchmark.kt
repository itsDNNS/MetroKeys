package app.metrokeys.engine.eval

import app.metrokeys.engine.EngineSettings
import app.metrokeys.engine.PredictionEngine
import app.metrokeys.engine.Query
import app.metrokeys.engine.lexicon.Dictionary
import app.metrokeys.engine.predict.LanguageEngine
import app.metrokeys.engine.predict.LanguageParams
import app.metrokeys.engine.predict.QueryContext
import app.metrokeys.engine.text.FieldInfo
import app.metrokeys.engine.text.FieldPolicy
import app.metrokeys.engine.touch.KeyboardGeometry
import app.metrokeys.engine.touch.Tap
import java.io.File
import kotlin.test.Test

/**
 * Query latency of the 0.7.1 pipeline (05 §2.3, 05d §7) on the JVM: every item of the salt-A test set of each language
 * (both halves) is queried once after a warm-up pass, single-language profile, taps at the key centres; prints mean,
 * 95th percentile and maximum of the query time and of the spelling source's node visits. Run with MK_LATENCY=1
 * (optionally MK_LATENCY_LANGUAGES=de-DE,en-US).
 */
class LatencyBenchmark {
    @Test
    fun latency() {
        if (System.getenv("MK_LATENCY") == null) return
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "evaluation/testsets").isDirectory }
        val tags = System.getenv("MK_LATENCY_LANGUAGES")?.split(',') ?: listOf("de-DE", "en-US", "es-ES", "fr-FR", "it-IT", "pt-BR")
        for (tag in tags) {
            val set = TestSet.read(File(root, "evaluation/testsets/$tag.A.tsv"))
            val engine = LanguageEngine(LanguageParams.forLanguage(tag), Dictionary.loadBundled(tag))
            val p = PredictionEngine(listOf(engine), EngineSettings(emojiSuggestions = false, learning = false, shapeWriting = false))
            val layout = File(root, "spec/data/layouts-${tag.substringBefore('-')}.json").readText()
            p.setGeometry(KeyboardGeometry.fromLayoutJson(layout, "text.letters"))
            val g = p.geometry
            fun taps(word: String) = word.map { ch ->
                val k = if (Character.isLetter(ch)) g.touchKey(ch) else null
                if (k == null) Tap.unknown(ch) else Tap(ch, k.centerX, k.centerY)
            }
            val queries = set.items.map { Query(it.before, "", taps(QueryContext.of(it.before).word), FieldInfo(FieldPolicy.TEXT)) }
            // warm-up
            for (q in queries.take(3000)) p.query(q)
            val times = LongArray(queries.size)
            val visits = IntArray(queries.size)
            for ((i, q) in queries.withIndex()) {
                val t0 = System.nanoTime()
                p.query(q)
                times[i] = System.nanoTime() - t0
                visits[i] = engine.spelling.suggest(engine.stripElision(QueryContext.of(q.before).word), engine.typedValidity(QueryContext.of(q.before)).valid).visits
            }
            times.sort()
            visits.sort()
            fun ms(ns: Long) = "%.2f".format(ns / 1e6)
            val mean = times.average()
            println("LATENCY $tag n=${times.size} mean ${ms(mean.toLong())} ms p95 ${ms(times[(times.size * 95) / 100])} ms " +
                "max ${ms(times.last())} ms; spelling visits mean ${"%.0f".format(visits.average())} " +
                "p95 ${visits[(visits.size * 95) / 100]} max ${visits.last()}")
        }
    }
}
