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

/**
 * Query latency on a device (05 §2.3, 05d §7): a plain main without test framework, run with `app_process` from a
 * release-optimised (R8) dex of the engine and this class (tools/device-benchmark.sh). Arguments: the directory with
 * `<tag>.A.tsv` test sets and `layouts-<lang>.json`, the number of items per language, the language tags.
 */
object DeviceBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args[0])
        val count = args[1].toInt()
        for (tag in args.drop(2)) {
            val t0 = System.nanoTime()
            val engine = LanguageEngine(LanguageParams.forLanguage(tag), Dictionary.loadBundled(tag))
            val p = PredictionEngine(listOf(engine), EngineSettings(emojiSuggestions = false, learning = false, shapeWriting = false))
            val load = (System.nanoTime() - t0) / 1_000_000
            p.setGeometry(KeyboardGeometry.fromLayoutJson(File(dir, "layouts-${tag.substringBefore('-')}.json").readText(), "text.letters"))
            val g = p.geometry
            val lines = File(dir, "$tag.A.tsv").readLines().drop(2).filter { it.isNotEmpty() }
            // every n-th item, so that the subset covers all sets
            val step = maxOf(1, lines.size / count)
            val befores = lines.filterIndexed { i, _ -> i % step == 0 }.take(count).map { it.split('\t')[7] }
            fun taps(word: String) = word.map { ch ->
                val k = if (Character.isLetter(ch)) g.touchKey(ch) else null
                if (k == null) Tap.unknown(ch) else Tap(ch, k.centerX, k.centerY)
            }
            val queries = befores.map { Query(it, "", taps(QueryContext.of(it).word), FieldInfo(FieldPolicy.TEXT)) }
            for (q in queries.take(minOf(300, queries.size))) p.query(q)
            val times = LongArray(queries.size)
            for ((i, q) in queries.withIndex()) {
                val s = System.nanoTime()
                p.query(q)
                times[i] = System.nanoTime() - s
            }
            times.sort()
            fun ms(ns: Long) = String.format(java.util.Locale.ROOT, "%.2f", ns / 1e6)
            println("DEVICE $tag n=${times.size} load ${load} ms mean ${ms(times.average().toLong())} ms " +
                "p95 ${ms(times[(times.size * 95) / 100])} ms max ${ms(times.last())} ms")
        }
    }
}
