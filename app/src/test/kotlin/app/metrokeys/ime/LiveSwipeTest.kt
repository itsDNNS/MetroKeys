package app.metrokeys.ime

import app.metrokeys.engine.swipe.ShapeTrace
import app.metrokeys.engine.swipe.ShiftState
import app.metrokeys.engine.touch.KeyboardGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The live best word of a running swipe (06 §5.3, 03 §6.2) with a fake scheduler. */
class LiveSwipeTest {
    private val geometry = KeyboardGeometry.fromLayoutJson(File("../spec/data/layouts-de.json").readText(), "text.letters")
    private val sched = FakeScheduler()
    private val shown = ArrayList<String>()
    private var restored = 0
    private val traces = ArrayList<Int>()
    private var engineWord: ((ShapeTrace) -> String?)? = null

    private val live = LiveSwipe(sched, object : LiveSwipe.Host {
        override fun geometry(): KeyboardGeometry = geometry
        override fun bestWord(trace: ShapeTrace, before: String): String? {
            traces.add(trace.size)
            return engineWord?.invoke(trace) ?: "w${trace.size}"
        }

        override fun showLive(word: String) {
            shown.add(word)
        }

        override fun restoreStrip() {
            restored++
        }
    })

    private fun centre(c: Char): Pair<Float, Float> = geometry.keys.first { it.letter == c }.let { it.centerX to it.centerY }

    /** Samples from the centre of [a] to the centre of [b] in [n] steps. */
    private fun path(a: Char, b: Char, n: Int = 6): List<Pair<Float, Float>> {
        val (x0, y0) = centre(a)
        val (x1, y1) = centre(b)
        return (1..n).map { x0 + (x1 - x0) * it / n to y0 + (y1 - y0) * it / n }
    }

    private fun start(vararg pts: Pair<Float, Float>) =
        live.start(FloatArray(pts.size) { pts[it].first }, FloatArray(pts.size) { pts[it].second }, LongArray(pts.size) { it * 8L }, "")

    private var t = 100L
    private fun add(pts: List<Pair<Float, Float>>) {
        for ((x, y) in pts) {
            live.add(x, y, t)
            t += 8
            sched.run()
        }
    }

    @Test
    fun theBestWordIsShownEachTimeTheSequenceGainsAKey() {
        start(centre('h'))
        sched.run()
        assertTrue(shown.isEmpty()) // one key: no word yet
        add(path('h', 'j'))
        assertEquals(1, traces.size)
        assertEquals(1, shown.size)
        add(path('j', 'k'))
        assertEquals(2, shown.size)
        // samples on the same key add nothing
        add(listOf(centre('k'), centre('k')))
        assertEquals(2, shown.size)
        // every key passed on the way counts (k … a passes j h g f d s)
        add(path('k', 'a', 21))
        assertEquals(2 + 7, shown.size)
        val id = live.end()
        live.finished(id, true)
        assertEquals(0, restored)
    }

    @Test
    fun noNewRecognitionWhileAnEarlierOneIsRunning() {
        sched.deferBackground = true
        start(centre('h'))
        add(path('h', 'a'))
        add(path('a', 'l'))
        add(path('l', 'o'))
        assertEquals(0, traces.size)
        sched.runBackground()
        sched.run()
        assertEquals(1, traces.size) // only the first, started at "ha"
        assertEquals(1, shown.size)
        // the next key starts the next one
        add(path('o', 'k'))
        sched.runBackground()
        sched.run()
        assertEquals(2, traces.size)
    }

    @Test
    fun aResultArrivingAfterTheLiftIsDropped() {
        sched.deferBackground = true
        start(centre('h'))
        add(path('h', 'a'))
        val id = live.end()
        sched.runBackground()
        sched.run()
        assertTrue(shown.isEmpty())
        live.finished(id, false)
        assertEquals(0, restored)
    }

    @Test
    fun aGestureWithoutResultReturnsTheStripToTheListBeforeTheSwipe() {
        start(centre('h'))
        add(path('h', 'a'))
        assertTrue(live.shown)
        val id = live.end()
        live.finished(id, false)
        assertEquals(1, restored)
        assertFalse(live.shown)
    }

    @Test
    fun anAbortedGestureReturnsTheStrip() {
        start(centre('h'))
        add(path('h', 'a'))
        live.abort()
        assertEquals(1, restored)
        assertFalse(live.active)
        // no word shown: nothing to restore
        start(centre('h'))
        live.abort()
        assertEquals(1, restored)
    }

    @Test
    fun theSamplesBeforeTheSwipeStartCount() {
        // a swipe that started on Shift: the samples since touch-down are passed at once (06 §2.1, §3.2)
        start(80f to 470f, 80f to 360f, 90f to 350f)
        sched.run()
        assertTrue(shown.isEmpty())
        add(path('a', 's'))
        assertEquals(1, shown.size)
    }

    @Test
    fun withTheGermanEngineThePartialTraceGivesAWord() {
        val engine = InputLogicTest.engine
        engineWord = { tr -> engine.recognizeShape(tr, "").texts(ShiftState.OFF).firstOrNull() }
        val pts = listOf(centre('h')) + path('h', 'a', 10) + path('a', 'l', 10) + path('l', 'o', 10)
        live.start(FloatArray(1) { pts[0].first }, FloatArray(1) { pts[0].second }, LongArray(1), "")
        add(pts.drop(1))
        assertNotNull(shown.lastOrNull())
        assertEquals("hallo", shown.last().lowercase())
    }
}
