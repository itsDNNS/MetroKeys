package app.metrokeys.keyboard

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt

/** Animation curves of spec 02 §7.1. */
object Curves {
    fun linear(t: Float): Float = t.coerceIn(0f, 1f)

    fun exponential(t: Float, w: Float): Float {
        val x = t.coerceIn(0f, 1f)
        if (w == 1f) return x
        return ((w.toDouble().pow(x.toDouble()) - 1) / (w - 1)).toFloat()
    }

    fun logarithmic(t: Float, w: Float): Float = exponential(t, 1f / w)

    fun sCurve(t: Float, w: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) exponential(2 * x, w) / 2 else 0.5f + logarithmic(2 * x - 1, w) / 2
    }

    /** Uniform Catmull-Rom spline through 1, 0.5, 0.2, 0 (end points repeated), u ∈ [0, 1] (spec 03 §6.8). */
    fun returnCurve(u: Float): Float {
        val p = floatArrayOf(1f, 1f, 0.5f, 0.2f, 0f, 0f)
        val x = u.coerceIn(0f, 1f) * 3f
        val seg = minOf(2, x.toInt())
        val t = x - seg
        val p0 = p[seg]
        val p1 = p[seg + 1]
        val p2 = p[seg + 2]
        val p3 = p[seg + 3]
        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)
    }
}

/**
 * The shared list scroller of spec 03 §6.8 / 02 §7.4 for one axis: drag, inertia with decay d = 0.2/s, edge
 * compression instead of a rubber band, braking flicks into an edge, the return curve and the animated scroll to a
 * target (S-curve, weight 10, 400 ms). Positions are content offsets in device px; [s] is the layout scale.
 * Pure Kotlin so that it can be unit tested.
 */
class ListScroller(private val s: Float, dragStartPx: Float) {
    val dragStart: Float = dragStartPx

    private val d = 0.2
    private val lnD = ln(d)
    private val parking = 192.0 * s
    private val maxSpeed = 9600.0 * s
    private val pivot = 4320f * s
    private val limit = 300f * s
    private val kSlow = 7500.0 * s
    private val kFast = 32000.0 * s

    /** Overshoot of a flick at maxSpeed braked with the fast constants (≈ 776 px at the reference). */
    val xMax: Double = run {
        val c = 0.85
        var t = 0.3
        repeat(60) {
            val f = maxSpeed * d.pow(t) - kFast * t.pow(c)
            val df = maxSpeed * d.pow(t) * lnD - kFast * c * t.pow(c - 1)
            val nt = t - f / df
            t = if (nt <= 0) t / 2 else nt
        }
        (d.pow(t) - 1) * maxSpeed / lnD - kFast * t.pow(c + 1) / (c + 1)
    }

    /** ρ = L / xMax ≈ 0.3865. */
    val rho: Double = limit / xMax

    var min: Float = 0f
        private set
    var max: Float = 0f
        private set

    /** Physical position (may lie beyond the range by an overshoot). */
    var position: Float = 0f
        private set

    private enum class Mode { IDLE, DRAG, FLING, RETURN, ANIM }

    private var mode = Mode.IDLE
    private var dragging = false

    // fling state
    private var t0 = 0L
    private var x0 = 0.0
    private var v0 = 0.0
    private var tStop = 0.0
    private var edgeTime = Double.NaN
    private var edgeValue = 0f
    private var brakeK = 0.0
    private var brakeC = 0.0
    private var t1 = 0.0
    private var returnDur = 0.0
    private var returnFrom = 0f

    // return / anim state
    private var animFrom = 0f
    private var animTo = 0f
    private var animStart = 0L
    private var animMs = 400L

    // drag state
    private var dragOrigin = 0f

    val isDragging: Boolean get() = dragging
    val isMoving: Boolean get() = mode == Mode.FLING || mode == Mode.RETURN || mode == Mode.ANIM

    /** Visible offset, never beyond the range. */
    val offset: Float get() = position.coerceIn(min, max)

    /** Overshoot beyond the range: negative before [min], positive after [max]. */
    val overshoot: Float get() = when {
        position < min -> position - min
        position > max -> position - max
        else -> 0f
    }

    /** Displayed compression C (≥ 0) and the pulled edge: sign of [overshoot]. */
    val compression: Float get() {
        val c = (rho * abs(overshoot)).toFloat()
        return if (dragging) minOf(c, limit) else minOf(c, pivot)
    }

    /** Pivot distance E in px (spec 02 §7.4.3). */
    val pivotDistance: Float get() = pivot

    fun setRange(newMin: Float, newMax: Float) {
        min = newMin
        max = maxOf(newMin, newMax)
        if (mode == Mode.IDLE && !dragging) position = position.coerceIn(min, max)
    }

    /** Jumps to [p] without animation. */
    fun jumpTo(p: Float) {
        mode = Mode.IDLE
        position = p.coerceIn(min, max)
    }

    /** A touch on the list stops it where it is (spec 03 §6.8). */
    fun touchDown(now: Long) {
        update(now)
        mode = Mode.IDLE
    }

    fun startDrag() {
        dragging = true
        mode = Mode.DRAG
        dragOrigin = position
    }

    /** Finger moved by [delta] px since the drag start (content moves with the finger). */
    fun dragTo(delta: Float) {
        var p = dragOrigin - delta
        val xm = xMax.toFloat()
        if (p < min - xm) p = min - xm
        if (p > max + xm) p = max + xm
        position = p
    }

    /** Release with [velocity] px/s of the content offset (positive = offset grows). */
    fun release(velocity: Float, now: Long) {
        dragging = false
        val v = velocity.toDouble().coerceIn(-maxSpeed, maxSpeed)
        val o = overshoot
        if (o != 0f) {
            if (sign(v) == -sign(o.toDouble()) && abs(v) >= parking) {
                position = if (o < 0) min else max
            } else {
                startReturn(now, (0.2 * rho * 1000).toLong())
                return
            }
        }
        if (abs(v) < parking) {
            mode = Mode.IDLE
            return
        }
        startFling(v, now)
    }

    /** Release without a drag (tap or hold): returns a compression that was frozen by touchDown. */
    fun releaseWithoutDrag(now: Long) {
        dragging = false
        if (overshoot != 0f) startReturn(now, (0.2 * rho * 1000).toLong()) else mode = Mode.IDLE
    }

    private fun startReturn(now: Long, ms: Long) {
        mode = Mode.RETURN
        animStart = now
        animMs = maxOf(1L, ms)
        returnFrom = overshoot
    }

    private fun startFling(v: Double, now: Long) {
        mode = Mode.FLING
        t0 = now
        x0 = position.toDouble()
        v0 = v
        tStop = ln(parking / abs(v)) / lnD
        val xEnd = x0 + v * (d.pow(tStop) - 1) / lnD
        edgeTime = Double.NaN
        if (xEnd < min || xEnd > max) {
            val edge = if (xEnd < min) min else max
            val dist = abs(edge - x0)
            val arg = 1 + dist * lnD / abs(v)
            if (arg > 0) {
                edgeTime = ln(arg) / lnD
                edgeValue = edge
                val f = abs(v) / maxSpeed
                brakeK = kSlow + (kFast - kSlow) * f
                brakeC = 0.85 + (1.4 - 0.85) * (1 - f)
                val sigma = turningTime(abs(v), edgeTime)
                t1 = edgeTime + sqrt(rho) * sigma
                returnDur = 2.25 * sqrt(rho) * sigma
            }
        }
    }

    /** σ with |v0| d^(tb+σ) = k σ^c (bisection). */
    private fun turningTime(v: Double, tb: Double): Double {
        var lo = 0.0
        var hi = 10.0
        repeat(60) {
            val m = (lo + hi) / 2
            if (v * d.pow(tb + m) - brakeK * m.pow(brakeC) > 0) lo = m else hi = m
        }
        return (lo + hi) / 2
    }

    /** Animated scroll to [target] with the S-curve, weight 10, 400 ms (spec 03 §6.8). */
    fun animateTo(target: Float, now: Long, animate: Boolean = true) {
        val t = target.coerceIn(min, max)
        if (!animate) {
            jumpTo(t)
            return
        }
        if (mode == Mode.ANIM && animTo == t) return
        if (offset == t && overshoot == 0f) {
            mode = Mode.IDLE
            position = t
            return
        }
        animFrom = offset
        animTo = t
        animStart = now
        animMs = 400
        mode = Mode.ANIM
    }

    /** Advances the motion to [now]; returns true while something still moves. */
    fun update(now: Long): Boolean {
        when (mode) {
            Mode.IDLE, Mode.DRAG -> return false
            Mode.ANIM -> {
                val u = (now - animStart).toFloat() / animMs
                position = animFrom + (animTo - animFrom) * Curves.sCurve(u, 10f)
                if (u >= 1f) {
                    position = animTo
                    mode = Mode.IDLE
                }
            }
            Mode.RETURN -> {
                val u = (now - animStart).toFloat() / animMs
                val edge = if (returnFrom < 0) min else max
                position = edge + returnFrom * Curves.returnCurve(u)
                if (u >= 1f) {
                    position = edge
                    mode = Mode.IDLE
                }
            }
            Mode.FLING -> {
                val t = (now - t0) / 1000.0
                if (edgeTime.isNaN() || t < edgeTime) {
                    val tt = minOf(t, tStop)
                    position = (x0 + v0 * (d.pow(tt) - 1) / lnD).toFloat()
                    if (t >= tStop) {
                        position = position.coerceIn(min, max)
                        mode = Mode.IDLE
                    }
                } else if (t < t1) {
                    val dir = sign(v0)
                    val tb = edgeTime
                    val o = abs(v0) * (d.pow(t) - d.pow(tb)) / lnD - brakeK * (t - tb).pow(brakeC + 1) / (brakeC + 1)
                    position = (edgeValue + dir * maxOf(0.0, o)).toFloat()
                } else {
                    val dir = sign(v0)
                    val tb = edgeTime
                    val o1 = abs(v0) * (d.pow(t1) - d.pow(tb)) / lnD - brakeK * (t1 - tb).pow(brakeC + 1) / (brakeC + 1)
                    val u = if (returnDur <= 0) 1.0 else (t - t1) / returnDur
                    position = (edgeValue + dir * maxOf(0.0, o1) * Curves.returnCurve(u.toFloat())).toFloat()
                    if (u >= 1) {
                        position = edgeValue
                        mode = Mode.IDLE
                    }
                }
            }
        }
        return mode != Mode.IDLE
    }

    /**
     * Maps a content coordinate [x] (distance from the content start) to the displayed coordinate relative to the
     * viewport start, including the compression (spec 02 §7.4.3). [contentLength] is the full content length.
     */
    fun display(x: Float, contentLength: Float): Float {
        val c = compression
        val base = x - offset
        if (c == 0f) return base
        return if (overshoot < 0) {
            base + c * (1 - minOf(1f, x / pivot))
        } else {
            val delta = contentLength - x
            base - c * (1 - minOf(1f, delta / pivot))
        }
    }

    /** Inverse of [display] for hit testing (approximately exact: the map is linear within the pivot range). */
    fun content(displayed: Float, contentLength: Float): Float {
        val c = compression
        if (c == 0f) return displayed + offset
        val scale = (pivot - c) / pivot
        return if (overshoot < 0) {
            val x = (displayed + offset - c) / scale
            if (x > pivot) displayed + offset else x
        } else {
            // displayed = x - offset - c + c * (L - x) / E  →  x * (1 - c/E) = displayed + offset + c - c * L / E
            val x = (displayed + offset + c - c * contentLength / pivot) / scale
            if (contentLength - x > pivot) displayed + offset else x
        }
    }
}
