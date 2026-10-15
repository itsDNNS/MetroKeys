package app.metrokeys.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Curves of spec 02 §7.1 and the scroller of 03 §6.8 / 02 §7.4 with the reference values. */
class MotionTest {
    @Test
    fun sampleValuesOfTheCurveTable() {
        assertEquals(0.397f, Curves.logarithmic(0.1f, 150f), 0.001f)
        assertEquals(0.925f, Curves.logarithmic(0.5f, 150f), 0.001f)
        assertEquals(0.075f, Curves.exponential(0.5f, 150f), 0.001f)
        assertEquals(0.603f, Curves.exponential(0.9f, 150f), 0.001f)
        assertEquals(0.032f, Curves.sCurve(0.1f, 10f), 0.001f)
        assertEquals(0.5f, Curves.sCurve(0.5f, 10f), 0.001f)
        assertEquals(0.834f, Curves.sCurve(0.7f, 10f), 0.001f)
    }

    @Test
    fun returnCurvePassesThroughItsPoints() {
        assertEquals(1f, Curves.returnCurve(0f), 1e-6f)
        assertEquals(0.5f, Curves.returnCurve(1f / 3), 1e-5f)
        assertEquals(0.2f, Curves.returnCurve(2f / 3), 1e-5f)
        assertEquals(0f, Curves.returnCurve(1f), 1e-6f)
    }

    @Test
    fun maximumOvershootIsAbout776() {
        val s = ListScroller(1f, 36f)
        assertEquals(776.0, s.xMax, 2.0)
        assertEquals(0.3865, s.rho, 0.002)
    }

    @Test
    fun inertiaOf3000PxPerSecond() {
        val s = ListScroller(1f, 36f)
        s.setRange(0f, 100000f)
        s.touchDown(0)
        s.startDrag()
        s.dragTo(0f)
        s.release(3000f, 0)
        assertTrue(s.update(1000))
        assertTrue(s.update(1700))
        assertFalse(s.update(1720))
        assertEquals(1745f, s.offset, 2f)
    }

    @Test
    fun slowReleaseHasNoInertia() {
        val s = ListScroller(1f, 36f)
        s.setRange(0f, 1000f)
        s.jumpTo(500f)
        s.startDrag()
        s.release(150f, 0)
        assertFalse(s.update(100))
        assertEquals(500f, s.offset, 0.01f)
    }

    @Test
    fun dragPastTheEdgeCompressesAndReturns() {
        val s = ListScroller(1f, 36f)
        s.setRange(0f, 1000f)
        s.startDrag()
        s.dragTo(400f) // content pulled right beyond the start
        assertEquals(0f, s.offset, 0f)
        assertEquals(-400f, s.overshoot, 0.01f)
        assertEquals(400f * s.rho.toFloat(), s.compression, 0.5f)
        s.release(0f, 0)
        assertTrue(s.update(40))
        assertFalse(s.update(100))
        assertEquals(0f, s.overshoot, 0f)
    }

    @Test
    fun flickIntoTheEdgeOvershootsAndComesBack() {
        val s = ListScroller(1f, 36f)
        s.setRange(0f, 500f)
        s.jumpTo(400f)
        s.startDrag()
        s.release(6000f, 0)
        var maxOver = 0f
        var t = 0L
        while (s.update(t) && t < 5000) {
            maxOver = maxOf(maxOver, s.overshoot)
            t += 8
        }
        assertTrue(maxOver > 0f)
        assertTrue(maxOver < s.xMax)
        assertEquals(500f, s.offset, 0.01f)
        assertEquals(0f, s.overshoot, 0.01f)
    }

    @Test
    fun animatedScrollUsesTheSCurve() {
        val s = ListScroller(1f, 36f)
        s.setRange(0f, 1000f)
        s.jumpTo(0f)
        s.animateTo(197f, 0)
        s.update(200)
        assertEquals(98.5f, s.offset, 0.5f)
        s.update(400)
        assertEquals(197f, s.offset, 0.01f)
    }

    @Test
    fun displayMappingIsInvertible() {
        val s = ListScroller(1f, 36f)
        s.setRange(0f, 1000f)
        s.startDrag()
        s.dragTo(300f)
        for (x in listOf(0f, 100f, 2000f)) assertEquals(x, s.content(s.display(x, 3000f), 3000f), 0.01f)
        s.dragTo(-1500f)
        for (x in listOf(2900f, 2000f, 1500f)) assertEquals(x, s.content(s.display(x, 3000f), 3000f), 0.01f)
    }
}
