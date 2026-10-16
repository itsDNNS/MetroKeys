package app.metrokeys.ime

import app.metrokeys.keyboard.Metrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Inline autofill chips (03 §16): request before layout, chip sizes and which chips are shown. */
class InlineAutofillTest {
    @Test
    fun requestIsMadeBeforeLayoutOnceTheWindowExists() {
        assertTrue(InlineAutofill.shouldRequest(enabled = true, windowAttached = true))
        assertFalse(InlineAutofill.shouldRequest(enabled = false, windowAttached = true))
        // before the keyboard window was ever shown the platform cannot render chips: no request, system drop-down
        assertFalse(InlineAutofill.shouldRequest(enabled = true, windowAttached = false))
    }

    @Test
    fun chipSpecFromTheSettingsWithoutAView() {
        // 1080 px portrait, 100 % height: strip 126 px, chips 84 % = 105 px, up to the full width
        val fromSettings = Metrics(1080, false, 1080, 1f, 420f)
        val spec = InlineAutofill.chipSpec(InlineAutofill.requestMetrics(null) { fromSettings })
        assertEquals(InlineAutofill.ChipSpec(1, 1080, 105), spec)
        // a laid-out view wins over the settings
        val laidOut = Metrics(1080, false, 1080, 0.7f, 420f)
        assertEquals(InlineAutofill.chipSpec(laidOut), InlineAutofill.chipSpec(InlineAutofill.requestMetrics(laidOut) { fromSettings }))
        // keyboard height 70 %: the strip keeps 85 % (107.1 px), chips 89 px
        assertEquals(89, InlineAutofill.chipHeight(laidOut))
        // landscape 2400 × 1080: s = min(2400 / 1596, 1080 / 1080) = 1
        assertEquals(InlineAutofill.ChipSpec(1, 2400, 105), InlineAutofill.chipSpec(Metrics(2400, true, 1080, 1f, 420f)))
    }

    @Test
    fun chipsKeepThePlatformSize() {
        // the rendered chip's size given by the platform (245 × 105 measured by the renderer)
        assertEquals(245 to 105, InlineAutofill.chipLayoutSize(245, 105, 105))
        // no platform size: wrap content with the requested height
        assertEquals(-2 to 105, InlineAutofill.chipLayoutSize(null, null, 105))
        assertEquals(-2 to 105, InlineAutofill.chipLayoutSize(-2, 0, 105))
    }

    @Test
    fun chipsAreShownAsTheyAreInflated() {
        val c = InlineAutofill.ChipSlots<String>()
        val r = c.response(2)
        assertEquals(listOf("b1"), c.inflated(r, 1, "b1"))
        assertEquals(listOf("a1", "b1"), c.inflated(r, 0, "a1"))
        assertNull(c.inflated(r, 0, null))
        assertNull(c.inflated(r, 5, "x"))
    }

    @Test
    fun repeatedResponseWithOneCallbackPerChip() {
        // after a rotation the same two suggestions arrive twice; chip 0 is called back for the first response,
        // chip 1 for the second (seen on the emulator): both are shown
        val c = InlineAutofill.ChipSlots<String>()
        val r1 = c.response(2)
        val r2 = c.response(2)
        assertEquals(listOf("a1"), c.inflated(r1, 0, "a1"))
        assertEquals(listOf("a1", "b2"), c.inflated(r2, 1, "b2"))
        // a later chip of the newer response replaces the older one, an older one never replaces a newer one
        assertEquals(listOf("a2", "b2"), c.inflated(r2, 0, "a2"))
        assertNull(c.inflated(r1, 1, "b1"))
        assertEquals(listOf("a2", "b2"), c.shown)
    }

    @Test
    fun chipsSurviveAnInputRestartButNotAClear() {
        val c = InlineAutofill.ChipSlots<String>()
        val r1 = c.response(2)
        // the input view starts again (restart, re-shown keyboard): cleared; the platform sends the response again
        c.clear()
        val r2 = c.response(2)
        // a late chip of the response before the clear is dropped, the new response's chips are shown
        assertNull(c.inflated(r1, 0, "old"))
        assertEquals(listOf("a2"), c.inflated(r2, 0, "a2"))
        assertEquals(listOf("a2", "b2"), c.inflated(r2, 1, "b2"))
        // a response with a different count starts with empty slots
        val r3 = c.response(1)
        assertEquals(emptyList<String>(), c.shown)
        assertEquals(listOf("c3"), c.inflated(r3, 0, "c3"))
        // keyboard hidden: nothing of an earlier response comes back
        c.clear()
        assertNull(c.inflated(r3, 0, "c3"))
        assertEquals(emptyList<String>(), c.shown)
    }
}
