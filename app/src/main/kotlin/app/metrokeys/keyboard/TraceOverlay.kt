package app.metrokeys.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The shape-writing trace (06 §6, 02 §5.10): a low-resolution coverage bitmap of the key area (≈ 161 280 px),
 * rings stamped along the path every 28 ms with per-pixel timestamps, full opacity for 250 ms then a linear fade to
 * 450 ms, a 5-tap Gaussian blur, stretched over the key area with filtering.
 */
class TraceOverlay(private val v: KeyboardView) {
    private var bw = 0
    private var bh = 0
    private var k = 1f
    private var stamps = LongArray(0)
    private var cov = FloatArray(0)
    private var tmp = FloatArray(0)
    private var pixels = IntArray(0)
    private var bitmap: Bitmap? = null
    private var ring: IntArray = IntArray(0)
    private var disc: IntArray = IntArray(0)
    private var radius = 0

    // pending samples in key-area px with absolute times
    private val px = FloatArrayList()
    private val py = FloatArrayList()
    private val pt = LongArrayList()
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var lastT = 0L
    private var lastTick = 0L
    private var downTime = 0L
    private var live = false

    // bounding box of stamped pixels
    private var minX = Int.MAX_VALUE
    private var minY = Int.MAX_VALUE
    private var maxX = -1
    private var maxY = -1

    private val filter = Paint(Paint.FILTER_BITMAP_FLAG)

    private fun ensure() {
        val w = v.keyAreaWidth
        val h = v.keyAreaHeight
        if (w <= 0 || h <= 0) return
        val kk = sqrt(161280f / (w * h))
        val nw = max(2, (w * kk).roundToInt())
        val nh = max(2, (h * kk).roundToInt())
        val r = if (v.theme.dark && !v.theme.highContrast) 6 else 5
        if (nw == bw && nh == bh && r == radius) return
        bw = nw
        bh = nh
        k = kk
        radius = r
        stamps = LongArray(bw * bh)
        cov = FloatArray(bw * bh)
        tmp = FloatArray(bw * bh)
        pixels = IntArray(bw * bh)
        bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val rr = ArrayList<Int>()
        val dd = ArrayList<Int>()
        for (dy in -r - 1..r + 1) for (dx in -r - 1..r + 1) {
            val d = sqrt((dx * dx + dy * dy).toDouble())
            if (floor(d).toInt() == r || ceil(d).toInt() == r) {
                rr.add(dx); rr.add(dy)
            }
            if (floor(d) < r + 1) {
                dd.add(dx); dd.add(dy)
            }
        }
        ring = rr.toIntArray()
        disc = dd.toIntArray()
        minX = Int.MAX_VALUE; minY = Int.MAX_VALUE; maxX = -1; maxY = -1
    }

    /** The swipe started: draw the samples since touch-down ([ts] relative to [down]). */
    fun start(xs: FloatArrayList, ys: FloatArrayList, ts: LongArrayList, down: Long) {
        ensure()
        downTime = down
        live = true
        lastX = Float.NaN
        for (i in 0 until xs.size) queue(xs[i], ys[i], down + ts[i])
        lastTick = 0L
        v.invalidate()
    }

    fun add(x: Float, y: Float, t: Long) {
        if (!live) return
        queue(x, y, t)
    }

    private fun queue(x: Float, y: Float, t: Long) {
        px.add(x); py.add(y); pt.add(t)
    }

    fun end() {
        live = false
    }

    fun clear() {
        live = false
        px.clear(); py.clear(); pt.clear()
        stamps.fill(0)
        maxX = -1
    }

    fun animating(now: Long): Boolean = live || px.size > 0 || (maxX >= 0)

    private fun tick() {
        val n = px.size
        if (n == 0) return
        for (i in 0 until n) {
            val x = px[i]
            val y = py[i]
            val t = pt[i]
            val inside = x >= 0 && y >= 0 && x < v.keyAreaWidth && y < v.keyAreaHeight
            if (!inside) {
                lastX = Float.NaN
                continue
            }
            val bx = x * k
            val by = y * k
            if (lastX.isNaN()) {
                stamp(bx.toInt(), by.toInt(), t, ring)
            } else {
                val steps = max(1, ceil(max(abs(bx - lastX), abs(by - lastY))).toInt())
                for (s in 1..steps) {
                    val f = s / steps.toFloat()
                    val sx = lastX + (bx - lastX) * f
                    val sy = lastY + (by - lastY) * f
                    stamp(sx.toInt(), sy.toInt(), lastT + ((t - lastT) * f).toLong(), ring)
                }
            }
            lastX = bx
            lastY = by
            lastT = t
            if (i == n - 1) stamp(bx.toInt(), by.toInt(), t, disc)
        }
        px.clear(); py.clear(); pt.clear()
    }

    private fun stamp(cx: Int, cy: Int, t: Long, offsets: IntArray) {
        var i = 0
        while (i < offsets.size) {
            val x = cx + offsets[i]
            val y = cy + offsets[i + 1]
            i += 2
            if (x <= 0 || y <= 0 || x >= bw - 1 || y >= bh - 1) continue
            val idx = y * bw + x
            if (t > stamps[idx]) stamps[idx] = t
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
    }

    fun draw(c: Canvas, now: Long) {
        if (bitmap == null) ensure()
        val bmp = bitmap ?: return
        if (now - lastTick >= 28 || !live) {
            tick()
            lastTick = now
        }
        if (maxX < 0) return
        val x0 = max(0, minX - 2)
        val x1 = min(bw - 1, maxX + 2)
        val y0 = max(0, minY - 2)
        val y1 = min(bh - 1, maxY + 2)
        var any = false
        var nMinX = Int.MAX_VALUE
        var nMinY = Int.MAX_VALUE
        var nMaxX = -1
        var nMaxY = -1
        for (y in y0..y1) for (x in x0..x1) {
            val i = y * bw + x
            val s = stamps[i]
            var a = 0f
            if (s != 0L) {
                val age = now - s
                a = when {
                    age <= 250 -> 1f
                    age < 450 -> (450 - age) / 200f
                    else -> 0f
                }
                if (a == 0f) stamps[i] = 0 else {
                    any = true
                    if (x < nMinX) nMinX = x
                    if (x > nMaxX) nMaxX = x
                    if (y < nMinY) nMinY = y
                    if (y > nMaxY) nMaxY = y
                }
            }
            cov[i] = a
        }
        // separable 5-tap Gaussian blur, σ 1.5 (06 §6.4)
        val w0 = 0.2921f
        val w1 = 0.2339f
        val w2 = 0.1201f
        for (y in y0..y1) for (x in x0..x1) {
            val i = y * bw + x
            fun at(xx: Int) = if (xx < x0 || xx > x1) 0f else cov[y * bw + xx]
            tmp[i] = w0 * cov[i] + w1 * (at(x - 1) + at(x + 1)) + w2 * (at(x - 2) + at(x + 2))
        }
        val t = v.theme
        val rgb = t.trace and 0xFFFFFF
        val op = t.traceOpacity
        for (y in y0..y1) for (x in x0..x1) {
            val i = y * bw + x
            fun at(yy: Int) = if (yy < y0 || yy > y1) 0f else tmp[yy * bw + x]
            val b = w0 * tmp[i] + w1 * (at(y - 1) + at(y + 1)) + w2 * (at(y - 2) + at(y + 2))
            val alpha = (b * op * 255f).roundToInt().coerceIn(0, 255)
            pixels[i] = (alpha shl 24) or rgb
        }
        bmp.setPixels(pixels, y0 * bw + x0, bw, x0, y0, x1 - x0 + 1, y1 - y0 + 1)
        val src = Rect(0, 0, bw, bh)
        val dst = RectF(0f, v.keyTop, v.keyAreaWidth, v.keyTop + v.keyAreaHeight)
        c.drawBitmap(bmp, src, dst, filter)
        if (any) {
            minX = nMinX; minY = nMinY; maxX = nMaxX; maxY = nMaxY
        } else {
            // clear the last frame's pixels
            for (y in y0..y1) for (x in x0..x1) pixels[y * bw + x] = 0
            bmp.setPixels(pixels, y0 * bw + x0, bw, x0, y0, x1 - x0 + 1, y1 - y0 + 1)
            maxX = -1
            minX = Int.MAX_VALUE
            minY = Int.MAX_VALUE
            maxY = -1
        }
    }
}
