package app.metrokeys.ime

import app.metrokeys.engine.Query
import app.metrokeys.settings.KeyboardSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The typing path of 0.6.5 (docs/APP.md "Performance"): no engine query on the main thread while typing, the ahead
 * query of the current word used at the autocorrection trigger, the strip still updated after the typing pause of
 * 03 §6.2, and the field read only after pauses.
 */
class TypingPathTest {
    private lateinit var editor: FakeEditor
    private lateinit var cache: CachingEditor
    private lateinit var sched: FakeScheduler
    private lateinit var logic: InputLogic
    private val strips = ArrayList<StripContent>()
    private val corrected = ArrayList<String>()
    private val mainQueries = ArrayList<String>()
    private val backgroundQueries = ArrayList<String>()
    private val engine get() = InputLogicTest.engine

    @Before
    fun setUp() {
        editor = FakeEditor()
        cache = CachingEditor(editor)
        sched = FakeScheduler()
        logic = InputLogic(cache, sched, object : InputLogic.Listener {
            override fun onStrip(content: StripContent) {
                strips.add(content)
            }

            override fun onAutoCorrected(correction: String) {
                corrected.add(correction)
            }

            override fun onTextChanged() {}

            override fun onReleaseShift() {}
        })
        editor.onUpdate = { a, b, c, d -> sched.main(0) { logic.onSelectionUpdate(a, b, c, d) } }
        engine.deleteLearnedData()
        engine.queryObserver = { q: Query -> (if (sched.inBackground) backgroundQueries else mainQueries).add(q.before) }
        logic.engine = engine
        logic.settings = KeyboardSettings.DEFAULT
        logic.startInput(FieldState.from(InputLogicTest.TEXT_MULTILINE, 0), 0, 0)
    }

    @After
    fun tearDown() {
        engine.queryObserver = null
    }

    /** Types [s] with [gap] ms between the keys (100 ms: ten keys per second), without the final pause. */
    private fun typeFast(s: String, gap: Long = 100) {
        for (ch in s) {
            sched.run(gap)
            logic.onText(ch.toString())
            sched.run()
        }
    }

    @Test
    fun fastTypingAsksTheEngineNothingOnTheMainThread() {
        typeFast("ich habe dke katze gesehen ")
        sched.run(400)
        assertTrue(editor.toString(), editor.toString().startsWith("ich habe die "))
        assertTrue(corrected.toString(), "die" in corrected)
        assertEquals(emptyList<String>(), mainQueries)
        // one ahead query per key of a word (and next-word predictions after the separators), all in the background
        assertTrue(backgroundQueries.contains("ich habe dke"))
    }

    @Test
    fun theStripStillWaitsForTheTypingPause() {
        typeFast("Hal")
        // the first key came after a pause and is shown at once; then the keys come faster than 700 ms and the strip
        // waits for the typing pause (03 §6.2)
        assertEquals(listOf("H"), strips.filter { it.kind == StripKind.TYPING }.map { it.word })
        // the ahead queries ran at once, one per key
        assertEquals(listOf("H", "Ha", "Hal"), backgroundQueries)
        sched.run(349)
        assertEquals(listOf("H"), strips.filter { it.kind == StripKind.TYPING }.map { it.word })
        sched.run(1)
        assertEquals(StripKind.TYPING, strips.last().kind)
        assertEquals("Hal", strips.last().word)
        // the strip used the ahead results: no further query
        assertEquals(3, backgroundQueries.size)
        assertEquals(emptyList<String>(), mainQueries)
    }

    @Test
    fun aTriggerBeforeTheAheadQueryStartedComputesItOnceOnTheMainThread() {
        sched.deferBackground = true
        typeFast("Dke ")
        // the query thread was busy: the trigger took the query over instead of waiting for it
        assertEquals(listOf("Dke"), mainQueries)
        assertEquals(listOf("Die"), corrected)
        sched.runBackground()
        // the claimed and the replaced ahead queries never ran (the strip query of the first key and the next-word
        // predictions after the space did)
        assertTrue(backgroundQueries.toString(), backgroundQueries.none { it == "Dke" || it == "Dk" })
        sched.run(400)
        assertEquals("Die |", editor.toString())
    }

    @Test
    fun typingReadsTheFieldOnlyAfterPauses() {
        val atStart = cache.reads
        typeFast("ich habe dke katze gesehen und wir gehen morgen ")
        // the first key after the input start reads text before and after the caret once; then only the copy is used
        assertTrue("reads: ${cache.reads - atStart}", cache.reads - atStart <= 2)
        sched.run(1500)
        // after a pause the first key reads the field again
        val r = cache.reads
        typeFast("x")
        assertEquals(r + 2, cache.reads)
        assertEquals(editor.text.toString(), cache.textBefore(1000).toString() + cache.textAfter(1000))
    }
}
