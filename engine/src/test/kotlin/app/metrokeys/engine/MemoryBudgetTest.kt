package app.metrokeys.engine

import app.metrokeys.engine.swipe.ShapeTrace
import app.metrokeys.engine.touch.KeyboardGeometry
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Memory requirement of 00 "Quality requirements" / 05b §6: both languages with pair tables and the shape-writing
 * index fit a small heap, also at the peak while loading. Runs the load in a separate JVM with a fixed maximum heap.
 */
class MemoryBudgetTest {
    @Test
    fun bothLanguagesLoadWithinASmallHeap() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val p = ProcessBuilder(java, "-Xmx$HEAP_MB" + "m", "-XX:+UseSerialGC", "-cp", System.getProperty("java.class.path"),
            MemoryProbe::class.java.name)
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(120, TimeUnit.SECONDS)
        assertEquals(0, p.exitValue(), out)
    }

    companion object {
        /** JVM heap for the engine part of the 96 MB budget (the app's views and settings need the rest). */
        const val HEAP_MB = 44
    }
}

/** Loads de + en with pairs, runs queries and a multilingual swipe; prints the retained heap. */
object MemoryProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val engine = PredictionEngine.withBundledDictionaries(listOf("de-DE", "en-US"), EngineSettings(multilingual = true))
        engine.activeLanguage = "de-DE"
        val g = KeyboardGeometry.fallback("de-DE")
        engine.setGeometry(g)
        engine.query(Query("Ich habe heute leider kei"))
        val keys = "vielleicht".map { g.shapeKey(it)!! }
        val xs = FloatArray(keys.size * 8) { i -> keys[i / 8].centerX + (if (i / 8 + 1 < keys.size) (keys[i / 8 + 1].centerX - keys[i / 8].centerX) * (i % 8) / 8f else 0f) }
        val ys = FloatArray(keys.size * 8) { i -> keys[i / 8].centerY + (if (i / 8 + 1 < keys.size) (keys[i / 8 + 1].centerY - keys[i / 8].centerY) * (i % 8) / 8f else 0f) }
        val r = engine.recognizeShape(ShapeTrace(xs, ys, LongArray(xs.size) { it * 8L }), "")
        repeat(3) { System.gc() }
        val rt = Runtime.getRuntime()
        println("retained ${(rt.totalMemory() - rt.freeMemory()) / 1024} KB, swipe ${r.candidates.firstOrNull()?.text}")
        check(r.candidates.isNotEmpty())
        if (args.isNotEmpty()) Thread.sleep(args[0].toLong())
    }
}
