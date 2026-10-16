package app.metrokeys.keyboard

import app.metrokeys.keyboard.layout.Key
import app.metrokeys.keyboard.layout.KeyRole
import app.metrokeys.keyboard.layout.LayoutSet
import app.metrokeys.keyboard.layout.Page
import app.metrokeys.keyboard.layout.PxRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A clock whose delayed tasks run when the test advances the time. */
class FakeClock : TouchClock {
    var time = 1000L
    private val tasks = ArrayList<Pair<Long, Runnable>>()

    override fun now(): Long = time
    override fun post(task: Runnable, delayMs: Long) {
        cancel(task)
        tasks.add(time + delayMs to task)
    }

    override fun cancel(task: Runnable) {
        tasks.removeAll { it.second === task }
    }

    fun advance(ms: Long) {
        val end = time + ms
        while (true) {
            val next = tasks.filter { it.first <= end }.minByOrNull { it.first } ?: break
            tasks.remove(next)
            time = next.first
            next.second.run()
        }
        time = end
    }
}

/** The touch state machine of the key area (03 §1.2, §1.2a, §1.5, §2, §3.4; 02 §5.3, §5.5.6) with a fake clock. */
class KeyTouchTest {
    private val layout = LayoutSet.parse(File("../spec/data/layouts-de.json").readText())

    private inner class Host : KeyTouch.Host {
        override var page: Page = layout.page("text.letters")
        override val keyAreaWidth = 1080f
        override val keyAreaHeight = 756f
        override val scale = 1f
        override var fastTypingField = false
        var shapeWriting = true
        override val shapeWritingActive: Boolean get() = shapeWriting && page.isLetters
        var shifted = false
        val downs = ArrayList<String>()
        val commits = ArrayList<String>()
        val repeats = ArrayList<String>()
        var swipes = 0
        var pickerTaps = 0
        var tracing = false
        var pageAfterCommit: String? = null

        fun label(k: Key): String = k.labelText(shifted)

        override fun keyChoiceAllowed() = false
        override fun prefixClass(k: Key) = 0
        override fun touchDown(k: Key) {
            downs.add(name(k))
            if (k.role == KeyRole.PAGE || k.role == KeyRole.EMOJI) k.target?.let { page = layout.page(it) }
        }

        override fun pickerTap() {
            pickerTaps++
        }

        override fun commit(k: Key, x: Float, y: Float) {
            commits.add(if (k.role == KeyRole.CHAR) k.outputText(shifted)!! else k.role.name)
            if (k.role == KeyRole.CHAR) shifted = false // one-shot shift released after a character (03 §4 item 4)
            pageAfterCommit?.let { page = layout.page(it) }
        }

        override fun repeat(k: Key) {
            repeats.add(name(k))
        }

        override fun wordDelete() {
            repeats.add("word")
        }

        override fun holdOutput(k: Key) {}
        override fun pickerItems(k: Key): List<String>? {
            if (k.role != KeyRole.CHAR) return null
            val items = listOf(k.outputText(shifted)!!) + k.alternates.filter { !(shifted && it.hideWhenShifted) }.map { it.text(shifted) }
            return items.takeIf { it.size > 1 }
        }

        override fun pickerCellSize(k: Key, items: List<String>) = if (items.any { it.length > 3 }) 203f to 203f else 131f to 203f
        override fun pickerOpened(items: List<String>) {}
        override fun pickerClosed() {}
        override fun pickerCommit(k: Key, item: String) {
            commits.add(item)
        }

        override fun popupSnapshot(k: Key): String = label(k)
        override fun traceStart(xs: FloatArrayList, ys: FloatArrayList, ts: LongArrayList, downTime: Long) {
            tracing = true
        }

        override fun traceAdd(x: Float, y: Float, t: Long) {}
        override fun traceEnd() {
            tracing = false
        }

        override fun swipe(xs: FloatArray, ys: FloatArray, ts: LongArray) {
            swipes++
        }

        override fun activeKeyChanged(k: Key) {}
        override fun invalidate() {}
    }

    private val host = Host()
    private val clock = FakeClock()
    private val touch = KeyTouch(host, clock)

    private fun name(k: Key?): String = when (k?.role) {
        null -> "-"
        KeyRole.CHAR -> k.output!!
        else -> k.role.name
    }

    private fun centre(output: String): Pair<Float, Float> {
        val k = host.page.keys.first { it.output == output || it.role.name == output }
        val c = touch.cells.cell(k)
        return c.centerX to (c.top + c.bottom) / 2
    }

    private fun down(x: Float, y: Float) = touch.down(x, y, clock.time)
    private fun move(x: Float, y: Float) = touch.move(x, y, clock.time)
    private fun up(x: Float, y: Float) = touch.up(x, y, clock.time)

    private fun tap(output: String) {
        val (x, y) = centre(output)
        down(x, y)
        clock.advance(40)
        up(x, y)
    }

    @Test
    fun keysWithoutAPopupNeverShowOneAlsoNotOnRelease() {
        for (k in listOf("SHIFT", "BACKSPACE", "SPACE", "ENTER")) {
            val (x, y) = centre(k)
            down(x, y)
            assertNull(k, touch.popup)
            assertEquals(k, name(touch.pressedKey))
            clock.advance(40)
            up(x, y)
            // the pressed look ends at the release, no popup at any time
            assertNull(k, touch.popup)
            assertNull(k, touch.pressedKey)
            clock.advance(80)
            assertNull(k, touch.popup)
        }
        assertEquals(listOf("BACKSPACE", "SPACE", "ENTER"), host.commits) // shift acts on touch-down
    }

    @Test
    fun theReleaseDelayKeepsLookAndPopupUnchanged() {
        host.shifted = true // auto-shifted "Q"
        val (x, y) = centre("q")
        down(x, y)
        assertEquals("q", name(touch.popup?.key))
        assertNull(touch.popup!!.frozenLabel)
        clock.advance(50)
        up(x, y)
        assertEquals(listOf("Q"), host.commits)
        assertFalse(host.shifted) // the keys switch to lower case at once …
        // … but popup and pressed look stay for 75 ms with "Q"
        assertEquals("Q", touch.popup?.frozenLabel)
        assertEquals("q", name(touch.pressedKey))
        clock.advance(74)
        assertEquals("Q", touch.popup?.frozenLabel)
        assertEquals("q", name(touch.pressedKey))
        clock.advance(1)
        assertNull(touch.popup)
        assertNull(touch.pressedKey)
    }

    @Test
    fun aReleasedKeyKeepsItsPopupWhenThePageChanges() {
        // "@" returns to the letter page at once (03 §7.2); its popup stays over its old cell for 75 ms
        host.page = layout.page("text.symbols-1")
        host.pageAfterCommit = "text.letters"
        val (x, y) = centre("@")
        down(x, y)
        up(x, y)
        assertEquals("text.letters", host.page.id)
        assertEquals("@", touch.popup?.frozenLabel)
        assertEquals(PxRect(0f, 189f, 108f, 378f), touch.popup!!.cell)
        clock.advance(75)
        assertNull(touch.popup)
    }

    @Test
    fun aNewTouchDownEndsTheOldPopupAtOnce() {
        tap("q")
        assertEquals("q", name(touch.popup?.key))
        val (x, y) = centre("w")
        clock.advance(20)
        down(x, y)
        assertEquals("w", name(touch.popup?.key))
        assertNull(touch.popup!!.frozenLabel)
        assertEquals("w", name(touch.pressedKey))
    }

    @Test
    fun slidingOnTheLetterPageGivesNoFeedbackToTheKeyReached() {
        // g → h on a trace candidate: g's popup ends 75 ms after leaving its area, h never shows any, release types h
        down(540f, 280f)
        assertEquals("g", name(touch.popup?.key))
        move(600f, 280f) // inside g's area (486 − 10 … 594 + 10)
        assertEquals("g", name(touch.popup?.key))
        move(606f, 280f) // left the area: h is active without feedback, g lingers
        assertEquals("h", name(touch.activeKey))
        assertEquals("g", name(touch.popup?.key))
        assertEquals("g", name(touch.pressedKey))
        clock.advance(75)
        assertNull(touch.popup)
        assertNull(touch.pressedKey)
        move(640f, 280f)
        assertNull(touch.popup)
        up(640f, 280f)
        assertEquals(listOf("h"), host.commits)
        assertEquals(listOf("g"), host.downs) // no click for h
        assertNull(touch.popup)
    }

    @Test
    fun returningToTheFirstKeyOnATraceCandidateShowsNothing() {
        down(540f, 280f)
        move(620f, 280f)
        clock.advance(100)
        move(540f, 280f)
        assertEquals("g", name(touch.activeKey))
        assertNull(touch.popup)
        assertNull(touch.pressedKey)
    }

    @Test
    fun slidingOnASymbolPageMovesThePopup() {
        host.page = layout.page("text.symbols-1")
        val (x5, y5) = centre("5")
        down(x5, y5)
        assertEquals("5", name(touch.popup?.key))
        val (x6, y6) = centre("6")
        move(x6, y6)
        assertEquals("6", name(touch.popup?.key))
        assertEquals("6", name(touch.pressedKey))
        assertNull(touch.popup!!.frozenLabel)
        up(x6, y6)
        assertEquals(listOf("6"), host.commits)
        assertEquals("6", name(touch.popup?.key))
    }

    @Test
    fun aTouchInTheLowestQuarterIsNoTraceCandidateAndHandsOverTheFeedback() {
        // "," on the bottom row → "." by sliding: the popup follows (03 §1.2a item 6)
        val (x, y) = centre(",")
        down(x, y)
        val (x2, y2) = centre(".")
        move(x2 - 60, y2) // over the space bar
        assertEquals("SPACE", name(touch.pressedKey))
        assertNull(touch.popup)
        move(x2, y2)
        assertEquals(".", name(touch.popup?.key))
        up(x2, y2)
        assertEquals(listOf("."), host.commits)
    }

    @Test
    fun spaceEnterAndBackspaceReachedBySlidingDoNothing() {
        host.shapeWriting = false
        for (target in listOf("SPACE", "ENTER", "BACKSPACE")) {
            host.commits.clear()
            val (x, y) = centre("m")
            down(x, y)
            val (tx, ty) = centre(target)
            move(tx, ty)
            assertEquals(target, name(touch.activeKey))
            up(tx, ty)
            assertTrue(target, host.commits.isEmpty())
        }
    }

    @Test
    fun aKeyReachedBySlidingGetsNoHoldTimer() {
        host.shapeWriting = false
        down(540f, 280f)
        move(648f, 280f) // h
        clock.advance(2000)
        assertNull(touch.picker)
        up(648f, 280f)
        assertEquals(listOf("h"), host.commits)
        // backspace reached by sliding never repeats
        val (x, y) = centre("m")
        down(x, y)
        val (bx, by) = centre("BACKSPACE")
        move(bx, by)
        clock.advance(2000)
        assertTrue(host.repeats.isEmpty())
        up(bx, by)
    }

    @Test
    fun leavingTheKeyboardUpwardsCancelsWithoutOutput() {
        down(486f, 100f) // t
        assertEquals("t", name(touch.popup?.key))
        move(486f, -5f) // still inside t's area (top − 10)
        assertEquals("t", name(touch.activeKey))
        move(486f, -11f)
        assertNull(touch.activeKey)
        assertEquals("t", name(touch.popup?.key)) // as after a lift: 75 ms
        clock.advance(75)
        assertNull(touch.popup)
        up(486f, -60f)
        assertTrue(host.commits.isEmpty())
        // a key without popup ends at once (the space bar, leaving the key area downwards)
        host.shapeWriting = false
        val (sx, sy) = centre("SPACE")
        down(sx, sy)
        move(sx, 756f + 11f)
        assertNull(touch.pressedKey)
        up(sx, 800f)
        assertTrue(host.commits.isEmpty())
    }

    @Test
    fun comingBackIntoTheKeyAreaActivatesTheKeyBySliding() {
        host.shapeWriting = false
        down(486f, 100f)
        move(486f, -40f)
        assertNull(touch.activeKey)
        move(700f, 100f)
        assertEquals("u", name(touch.activeKey))
        assertEquals("u", name(touch.popup?.key))
        up(700f, 100f)
        assertEquals(listOf("u"), host.commits)
    }

    @Test
    fun verticalMovementInsideTheZoneKeepsTheStartKeyAndBecomesATrace() {
        // a 170 px vertical move inside g's hysteresis area (179 … 388) keeps the popup; release recognises a trace
        down(540f, 200f)
        move(540f, 290f)
        move(540f, 370f)
        assertTrue(host.tracing)
        assertEquals("g", name(touch.popup?.key))
        assertEquals("g", name(touch.pressedKey))
        up(540f, 370f)
        assertEquals(1, host.swipes)
        assertTrue(host.commits.isEmpty())
        assertEquals("g", name(touch.popup?.key))
        clock.advance(75)
        assertNull(touch.popup)
    }

    @Test
    fun aTraceThatLeavesTheStartKeyShowsNoKey() {
        down(540f, 280f)
        move(700f, 280f)
        move(800f, 280f)
        assertTrue(host.tracing)
        assertEquals("g", name(touch.popup?.key)) // the left key lingers
        clock.advance(75)
        assertNull(touch.popup)
        assertNull(touch.pressedKey)
        up(800f, 280f)
        assertEquals(1, host.swipes)
        assertTrue(host.commits.isEmpty())
        assertNull(touch.popup)
    }

    @Test
    fun aReleasedPickerStaysOpenUntilTheNextTouch() {
        // long press on s (cell 162–270 × 189–378) at (216, 300)
        down(216f, 300f)
        clock.advance(450)
        val p = touch.picker!!
        assertEquals(listOf("s", "ß", "§"), p.items)
        assertEquals(PxRect(141.5f, 189f, 290.5f, 378f), p.holdZone)
        move(285f, 300f)
        up(285f, 300f)
        assertTrue(p.sticky)
        assertEquals(0, p.highlighted)
        clock.advance(30_000)
        assertSame(p, touch.picker)
        assertEquals("s", name(touch.pressedKey))
        // next touch-down in the band on slot 1 → ß highlighted with a click; the lift types it
        down(350f, 100f)
        assertEquals(1, p.highlighted)
        assertEquals(1, host.pickerTaps)
        up(350f, 100f)
        assertEquals(listOf("ß"), host.commits)
        assertNull(touch.picker)
    }

    private fun openS() {
        down(216f, 300f)
        clock.advance(450)
        assertNotNull(touch.picker)
    }

    @Test
    fun pickerDragExamplesOf03Section34() {
        openS()
        move(295f, 300f)
        assertEquals(0, touch.picker!!.highlighted) // leaves the zone, this position changes nothing
        assertTrue(touch.picker!!.drag)
        move(300f, 300f)
        assertEquals(1, touch.picker!!.highlighted)
        up(300f, 300f)
        assertEquals(listOf("ß"), host.commits)
        assertNull(touch.picker)

        host.commits.clear()
        openS()
        move(295f, 300f)
        move(300f, 300f)
        move(250f, 300f)
        up(250f, 300f)
        assertEquals(listOf("s"), host.commits) // drag phase: not sticky
        assertNull(touch.picker)

        host.commits.clear()
        openS()
        move(216f, 185f)
        move(216f, 150f)
        up(216f, 150f)
        assertEquals(listOf("s"), host.commits)

        host.commits.clear()
        openS()
        move(295f, 300f)
        move(350f, 700f)
        up(350f, 700f)
        assertEquals(listOf("ß"), host.commits) // no vertical limit

        host.commits.clear()
        openS()
        move(295f, 300f)
        move(600f, 300f)
        assertEquals(-1, touch.picker!!.highlighted)
        up(600f, 300f)
        assertTrue(host.commits.isEmpty())
        assertNull(touch.picker)
    }

    @Test
    fun aTouchOutsideAStickyPickerClosesItAndIsSwallowed() {
        for ((x, y) in listOf(350f to 250f, 600f to 100f)) {
            host.commits.clear()
            host.downs.clear()
            openS()
            up(216f, 300f)
            assertTrue(touch.picker!!.sticky)
            down(x, y)
            assertNull(touch.picker)
            move(x + 30, y)
            up(x + 30, y)
            assertTrue(host.commits.isEmpty())
            assertEquals(listOf("s"), host.downs) // no key pressed by the closing touch
            assertNull(touch.pressedKey)
            // the following touch is handled normally
            tap("a")
            assertEquals(listOf("a"), host.commits)
        }
        // a touch in the strip inside the band (−28.22 < y < 192.78) still picks
        openS()
        up(216f, 300f)
        down(350f, -20f)
        assertNotNull(touch.picker)
        assertEquals(1, touch.picker!!.highlighted)
        up(350f, -20f)
        assertEquals("ß", host.commits.last())
    }

    @Test
    fun holdRepeatsBackspaceWithoutAnExtraDeletionOnRelease() {
        val (x, y) = centre("BACKSPACE")
        down(x, y)
        clock.advance(450)
        assertEquals(listOf("BACKSPACE"), host.repeats)
        clock.advance(83 * 3)
        assertEquals(4, host.repeats.size)
        up(x, y)
        assertTrue(host.commits.isEmpty())
        clock.advance(500)
        assertEquals(4, host.repeats.size)
    }

    @Test
    fun fastTypingTypesTheNearestLetterInsteadOfBackspace() {
        host.fastTypingField = true
        host.shapeWriting = false
        tap("d")
        clock.advance(110)
        tap("a")
        clock.advance(110)
        // 300 ms after the "d" touch-down
        down(1000f, 470f)
        assertEquals("m", name(touch.activeKey))
        up(1000f, 470f)
        assertEquals(listOf("d", "a", "m"), host.commits)
    }

    @Test
    fun afterAPageSwitchTheKeyUnderTheFingerBecomesActiveWithoutClick() {
        val (x, y) = centre("PAGE")
        down(x, y)
        assertEquals("text.symbols-1", host.page.id)
        assertNull(touch.pressedKey)
        move(x + 40, y) // less than half the key width
        assertNull(touch.activeKey)
        val (sx, sy) = centre("SPACE")
        move(sx, sy)
        assertEquals("SPACE", name(touch.activeKey))
        up(sx, sy)
        assertEquals(listOf("SPACE"), host.commits)
        assertEquals(listOf("PAGE"), host.downs)
    }
}
