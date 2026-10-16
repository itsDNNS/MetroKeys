package app.metrokeys.ime

import app.metrokeys.engine.swipe.KeySequenceTracker
import app.metrokeys.engine.swipe.ShapeTrace
import app.metrokeys.engine.touch.KeyboardGeometry

/**
 * The live best word of a running swipe (06 §5.3, SHOULD; 03 §6.2): each time the key sequence of 06 §3.2 gains a key
 * and no earlier partial recognition is still running, the trace so far is recognised on the worker and its best word
 * becomes the single entry of the strip. The result on release replaces it; a gesture that ends without a result
 * (cancel, a long press that won, nothing recognised) returns the strip to what it showed before the swipe.
 *
 * All calls on the main thread; [Host.bestWord] runs through [Scheduler.background].
 */
class LiveSwipe(private val scheduler: Scheduler, private val host: Host) {
    interface Host {
        /** Geometry of the letter page, null without an engine (no live word then). */
        fun geometry(): KeyboardGeometry?

        /** Best word of the partial [trace] with the case of 06 §5.2, null if nothing was found (worker thread). */
        fun bestWord(trace: ShapeTrace, before: String): String?

        /** Shows [word] as the single strip entry. */
        fun showLive(word: String)

        /** Shows the strip of the text state again (the list before the swipe). */
        fun restoreStrip()
    }

    /** A gesture is running (between [start] and [end] / [abort]). */
    var active = false
        private set

    /** A live word of the current or just ended gesture is shown. */
    var shown = false
        private set

    private var gesture = 0
    private var running = false

    /** A live word of an earlier gesture whose final result has not arrived yet is still shown. */
    private var stale = false
    private var tracker: KeySequenceTracker? = null
    private var before = ""
    private var xs = FloatArray(64)
    private var ys = FloatArray(64)
    private var ts = LongArray(64)
    private var n = 0

    /** The touch became a trace: all samples since touch-down (times relative to it), the text before the caret. */
    fun start(x: FloatArray, y: FloatArray, t: LongArray, textBefore: String) {
        if (active) abort()
        gesture++
        active = true
        if (shown) stale = true
        shown = false
        running = false
        before = textBefore
        n = 0
        tracker = host.geometry()?.let { KeySequenceTracker(it) }
        var gained = false
        for (i in x.indices) if (append(x[i], y[i], t[i])) gained = true
        if (gained) recognise()
    }

    /** A further sample of the running trace (time relative to the touch-down). */
    fun add(x: Float, y: Float, t: Long) {
        if (!active) return
        if (append(x, y, t)) recognise()
    }

    /** The finger lifted: the final recognition follows; returns the gesture's id for [finished]. */
    fun end(): Int {
        active = false
        return gesture
    }

    /**
     * The final recognition of gesture [id] arrived; [hasResult] false when nothing is inserted or shown: the strip
     * returns to the list before the swipe.
     */
    fun finished(id: Int, hasResult: Boolean) {
        if (id != gesture) {
            // a later gesture is running: put back the strip only for this gesture's own leftover live word
            if (!hasResult && stale && !shown) host.restoreStrip()
            stale = false
            return
        }
        if (!hasResult && shown) host.restoreStrip()
        shown = false
    }

    /** The gesture ended without recognition (cancel, a long press won, 06 §2.4). */
    fun abort() {
        if (!active && !shown) return
        active = false
        gesture++
        running = false
        if (shown) host.restoreStrip()
        shown = false
    }

    private fun append(x: Float, y: Float, t: Long): Boolean {
        if (n == xs.size) {
            xs = xs.copyOf(n * 2)
            ys = ys.copyOf(n * 2)
            ts = ts.copyOf(n * 2)
        }
        xs[n] = x
        ys[n] = y
        ts[n] = t
        n++
        return (tracker?.add(x, y) ?: -1) >= 0
    }

    private fun recognise() {
        // one key gives no word (06 §3: at least two keys); an earlier partial recognition is still running
        if (running || (tracker?.size ?: 0) < 2) return
        running = true
        val id = gesture
        val trace = ShapeTrace(xs.copyOf(n), ys.copyOf(n), ts.copyOf(n))
        val b = before
        scheduler.background {
            val word = host.bestWord(trace, b)
            scheduler.main(0) {
                if (id != gesture) return@main
                running = false
                if (!active || word == null) return@main
                shown = true
                stale = false
                host.showLive(word)
            }
        }
    }
}
