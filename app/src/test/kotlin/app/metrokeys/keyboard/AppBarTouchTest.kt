package app.metrokeys.keyboard

import app.metrokeys.keyboard.AppBarTouch.EMPTY
import app.metrokeys.keyboard.AppBarTouch.MENU0
import app.metrokeys.keyboard.AppBarTouch.MORE
import app.metrokeys.keyboard.AppBarTouch.NONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Touch rules of the application bar (03 §12.2) at the reference width 1080 px (s = 1). */
class AppBarTouchTest {
    private val w = 1080f
    private val all = listOf(0, 1, 2)

    private fun hit(x: Float, y: Float, expanded: Boolean = false, buttons: List<Int> = all) =
        AppBarTouch.hit(x, y, w, 1f, expanded, buttons)

    @Test
    fun regions() {
        // buttons at 392, 540, 688 (±74), "…" in the right 120 px, the rest of the 126 px bar is empty
        assertEquals(0, hit(392f, 60f))
        assertEquals(1, hit(540f, 60f))
        assertEquals(2, hit(688f, 60f))
        assertEquals(MORE, hit(1020f, 60f))
        assertEquals(EMPTY, hit(100f, 60f))
        assertEquals(EMPTY, hit(850f, 60f))
        // a button whose tap binding is "nothing" (not in the list) leaves an empty part
        assertEquals(EMPTY, hit(392f, 60f, buttons = listOf(1, 2)))
        // below the collapsed bar: outside
        assertEquals(NONE, hit(100f, 200f))
        // expanded: label row 48 + padding 12, then three menu rows of 96
        assertEquals(EMPTY, hit(100f, 150f, expanded = true))
        assertEquals(MENU0, hit(100f, 200f, expanded = true))
        assertEquals(MENU0 + 2, hit(100f, 126f + 60f + 2 * 96f + 10f, expanded = true))
    }

    @Test
    fun onlyTheDotsToggleTheBar() {
        // 03 §12.2 item 5 (0.6.4): "…" toggles on release; an empty part of the bar does nothing, without feedback
        assertTrue(AppBarTouch.togglesOnRelease(MORE))
        assertTrue(AppBarTouch.hasFeedback(MORE))
        assertFalse(AppBarTouch.togglesOnRelease(EMPTY))
        assertFalse(AppBarTouch.hasFeedback(EMPTY))
        assertFalse(AppBarTouch.togglesOnRelease(hit(100f, 60f)))
        assertFalse(AppBarTouch.togglesOnRelease(hit(100f, 150f, expanded = true)))
        for (b in all) assertFalse(AppBarTouch.togglesOnRelease(b))
        assertTrue(AppBarTouch.hasFeedback(1))
        assertTrue(AppBarTouch.hasFeedback(MENU0))
        assertFalse(AppBarTouch.hasFeedback(NONE))
    }
}
