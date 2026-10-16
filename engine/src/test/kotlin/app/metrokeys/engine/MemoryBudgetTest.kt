package app.metrokeys.engine

import app.metrokeys.engine.swipe.ShapeTrace
import app.metrokeys.engine.touch.KeyboardGeometry
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Memory requirement of 00 "Quality requirements" / 05b §6: two languages (below 96 MB of the app's heap) and three
 * languages (below 128 MB) with pair tables and the shape-writing index fit a small heap, also at the peak while
 * loading. Runs the load in a separate JVM with a fixed maximum heap.
 */
class MemoryBudgetTest {
    private fun probe(heapMb: Int, vararg tags: String): String {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val p = ProcessBuilder(java, "-Xmx${heapMb}m", "-XX:+UseSerialGC", "-cp", System.getProperty("java.class.path"),
            MemoryProbe::class.java.name, tags.joinToString(","))
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(120, TimeUnit.SECONDS)
        assertEquals(0, p.exitValue(), out)
        println("memory ${tags.joinToString("+")} in ${heapMb} MB: ${out.trim()}")
        return out
    }

    /** The retained heap of each language alone (0.7.0 report, 05b §6). */
    @Test
    fun eachLanguageAlone() {
        for (tag in listOf("de-DE", "en-US", "es-ES", "fr-FR", "it-IT", "pt-BR")) probe(HEAP_TWO_MB, tag)
    }

    @Test
    fun twoLanguagesLoadWithinASmallHeap() {
        probe(HEAP_TWO_MB, "de-DE", "en-US")
        probe(HEAP_TWO_MB, "de-DE", "es-ES")
        probe(HEAP_TWO_MB, "en-US", "es-ES")
        probe(HEAP_TWO_MB, "de-DE", "pt-BR")
        probe(HEAP_TWO_MB, "en-US", "pt-BR")
        probe(HEAP_TWO_MB, "es-ES", "pt-BR")
        // Italian (0.6.2)
        probe(HEAP_TWO_MB, "it-IT", "de-DE")
        probe(HEAP_TWO_MB, "it-IT", "en-US")
        probe(HEAP_TWO_MB, "it-IT", "es-ES")
        probe(HEAP_TWO_MB, "it-IT", "pt-BR")
        // French (0.6.3)
        for (other in listOf("de-DE", "en-US", "es-ES", "it-IT", "pt-BR")) probe(HEAP_TWO_MB, "fr-FR", other)
    }

    @Test
    fun threeLanguagesLoadWithinASmallHeap() {
        probe(HEAP_THREE_MB, "de-DE", "en-US", "es-ES")
        // three languages including Portuguese (0.6.1)
        probe(HEAP_THREE_MB, "de-DE", "en-US", "pt-BR")
        probe(HEAP_THREE_MB, "de-DE", "es-ES", "pt-BR")
        probe(HEAP_THREE_MB, "en-US", "es-ES", "pt-BR")
        // three languages including Italian (0.6.2); with five enabled languages the active set holds three (05 §14.5)
        probe(HEAP_THREE_MB, "it-IT", "de-DE", "en-US")
        probe(HEAP_THREE_MB, "it-IT", "de-DE", "es-ES")
        probe(HEAP_THREE_MB, "it-IT", "de-DE", "pt-BR")
        probe(HEAP_THREE_MB, "it-IT", "en-US", "es-ES")
        probe(HEAP_THREE_MB, "it-IT", "en-US", "pt-BR")
        probe(HEAP_THREE_MB, "it-IT", "es-ES", "pt-BR")
        // three languages including French (0.6.3): every set of fr and two of the five others
        val others = listOf("de-DE", "en-US", "es-ES", "it-IT", "pt-BR")
        for (i in others.indices) for (j in i + 1 until others.size) probe(HEAP_THREE_MB, "fr-FR", others[i], others[j])
    }

    companion object {
        /**
         * JVM heap for the engine part of the 96 MB budget (the app's views and settings need the rest). 0.6.6: 56 (44
         * in 0.6.4, 80 in 0.6.5 with all dictionary-only German forms): the dictionary-only German lemmas (05b §3.7)
         * add about 7 MB.
         */
        const val HEAP_TWO_MB = 56

        /** JVM heap for the engine part with three languages (budget 128 MB, 05 §14.5); 0.6.6: 68 (0.6.4: 60, 0.6.5: 88). */
        const val HEAP_THREE_MB = 68
    }
}

/** Loads the given languages with pairs, runs queries and a multilingual swipe; prints the retained heap. */
object MemoryProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val tags = (args.firstOrNull() ?: "de-DE,en-US").split(",")
        val engine = PredictionEngine.withBundledDictionaries(tags, EngineSettings(multilingual = true))
        engine.activeLanguage = tags[0]
        val g = KeyboardGeometry.fallback(tags[0])
        engine.setGeometry(g)
        engine.query(Query("Ich habe heute leider kei"))
        engine.query(Query("Muchas gracias por to"))
        engine.query(Query("Muito obrigado pela aten"))
        engine.query(Query("Grazie per lacqu"))
        engine.query(Query("dell'"))
        engine.query(Query("Je pense quil"))
        engine.query(Query("c'"))
        val keys = "vielleicht".map { g.shapeKey(it)!! }
        val xs = FloatArray(keys.size * 8) { i -> keys[i / 8].centerX + (if (i / 8 + 1 < keys.size) (keys[i / 8 + 1].centerX - keys[i / 8].centerX) * (i % 8) / 8f else 0f) }
        val ys = FloatArray(keys.size * 8) { i -> keys[i / 8].centerY + (if (i / 8 + 1 < keys.size) (keys[i / 8 + 1].centerY - keys[i / 8].centerY) * (i % 8) / 8f else 0f) }
        val r = engine.recognizeShape(ShapeTrace(xs, ys, LongArray(xs.size) { it * 8L }), "")
        repeat(3) { System.gc() }
        val rt = Runtime.getRuntime()
        println("retained ${(rt.totalMemory() - rt.freeMemory()) / 1024} KB, swipe ${r.candidates.firstOrNull()?.text}")
        check(r.candidates.isNotEmpty())
        if (args.size > 1) Thread.sleep(args[1].toLong())
    }
}
