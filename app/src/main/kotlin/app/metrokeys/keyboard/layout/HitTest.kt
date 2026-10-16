package app.metrokeys.keyboard.layout

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** A rectangle in key-area pixels; edges inclusive where it is used for hit tests. */
data class PxRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2

    /** Inclusive on all four edges (03 §1.2: "a point exactly on the boundary is inside"). */
    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom

    fun offsetX(dx: Float) = PxRect(left + dx, top, right + dx, bottom)

    fun grow(d: Float) = PxRect(left - d, top - d, right + d, bottom + d)
}

/**
 * The key cells of a page (03 §1.1 rule 1): every layout rectangle multiplied by the key-area size [width] × [height]
 * and each edge truncated to a whole pixel. The fractions of the layout data are not exact binary numbers (0.35 × 1080
 * gives 377.99999 in float), so a product within 1/1000 px below a whole number counts as that number.
 */
class KeyCells(val page: Page, val width: Float, val height: Float) {
    val keys: List<Key> = page.keys
    private val l = IntArray(keys.size)
    private val t = IntArray(keys.size)
    private val r = IntArray(keys.size)
    private val b = IntArray(keys.size)

    init {
        for ((i, k) in keys.withIndex()) {
            l[i] = trunc(k.rect.left * width)
            t[i] = trunc(k.rect.top * height)
            r[i] = trunc(k.rect.right * width)
            b[i] = trunc(k.rect.bottom * height)
        }
    }

    fun left(i: Int): Int = l[i]
    fun top(i: Int): Int = t[i]
    fun right(i: Int): Int = r[i]
    fun bottom(i: Int): Int = b[i]

    fun indexOf(k: Key): Int = keys.indexOfFirst { it === k }

    fun cell(i: Int): PxRect = PxRect(l[i].toFloat(), t[i].toFloat(), r[i].toFloat(), b[i].toFloat())

    fun cell(k: Key): PxRect = cell(indexOf(k))

    /** Horizontal and vertical distance of (x, y) to cell [i] (03 §1.1 rule 3); 0 inside or on an edge. */
    fun dx(i: Int, x: Float): Float = when {
        x < l[i] -> l[i] - x
        x > r[i] -> x - r[i]
        else -> 0f
    }

    fun dy(i: Int, y: Float): Float = when {
        y < t[i] -> t[i] - y
        y > b[i] -> y - b[i]
        else -> 0f
    }

    /** Inside the key area, bounds inclusive. */
    fun inArea(x: Float, y: Float): Boolean = x >= 0f && x <= width && y >= 0f && y <= height

    companion object {
        fun trunc(v: Float): Int = floor(v.toDouble() + 1e-3).toInt()
    }
}

/** The key found by [HitTester.hit]: [distance] is `d` of 03 §1.1 rule 3 (0 = the point lies in the cell). */
class Hit(val key: Key, val index: Int, val distance: Float) {
    val inCell: Boolean get() = distance == 0f

    /** A disabled key, the PIN pad's blank key or the emoji grid: no key becomes active (03 §1.1 rule 6). */
    val inert: Boolean get() = !key.enabled || key.role == KeyRole.BLANK || key.role == KeyRole.EMOJI_GRID
}

/**
 * Hit testing of spec 03 §1.1 with the fast-typing backspace exclusion of rule 5. One instance per keyboard view: it
 * keeps the times of the last three evaluations. Coordinates are key-area pixels, times milliseconds.
 */
class HitTester {
    private val times = LongArray(3)
    private var count = 0

    /** Clears the fast-typing history (rule 5). */
    fun clearHistory() {
        count = 0
    }

    /**
     * The key at (x, y), or null. [touchDown] allows the touch-down tolerance [outside] (rule 7) left of, right of and
     * below the key area; a moving finger outside the key area hits no key (rule 8). [fastTyping] says whether the
     * field takes part in rule 5 (input type text without the short-message variation); then [time] is recorded.
     * A returned hit can be [Hit.inert]; the caller then activates no key.
     */
    fun hit(cells: KeyCells, x: Float, y: Float, time: Long, touchDown: Boolean, outside: Float, fastTyping: Boolean): Hit? {
        val w = cells.width
        val h = cells.height
        val inArea = cells.inArea(x, y)
        if (!inArea) {
            if (!touchDown) return null
            if (y < 0f || x < -outside || x > w + outside || y > h + outside) return null
        }
        val fast = fastTyping && record(time)
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in cells.keys.indices) {
            val k = cells.keys[i]
            if (fast && k.role == KeyRole.BACKSPACE) continue
            val dx = cells.dx(i, x)
            val dy = cells.dy(i, y)
            val d = if (inArea) max(dx, 2f * dy) else floor(sqrt(dx * dx + dy * dy))
            if (d < bestD) {
                bestD = d
                best = i
                if (d == 0f) break
            }
        }
        if (best < 0) return null
        val hit = Hit(cells.keys[best], best, bestD)
        if (fastTyping && (bestD > 0f || !keepsHistory(hit.key))) clearHistory()
        return hit
    }

    /** Records [time]; true if the last three evaluations lie within `fastTapWindow` = 375 ms (rule 5). */
    private fun record(time: Long): Boolean {
        if (count == 3) {
            times[0] = times[1]
            times[1] = times[2]
            times[2] = time
        } else {
            times[count++] = time
        }
        return count == 3 && time - times[0] < FAST_TAP_WINDOW_MS
    }

    private fun keepsHistory(k: Key): Boolean =
        k.enabled && (k.role == KeyRole.CHAR || k.role == KeyRole.DOMAIN || k.role == KeyRole.SPACE || k.role == KeyRole.ENTER)

    companion object {
        const val FAST_TAP_WINDOW_MS = 375L
    }
}

/** Letter correction at touch-down (03 §1.1a) on the key [touched] found by §1.1. */
object LetterCorrection {
    /**
     * The chosen key: [prefixClass] gives the frequency class of the best completion with the key's letter, or a
     * negative value when no word continues with it. [hitMax] and [hitClose] are 13 and 7 px scaled to the view.
     */
    fun choose(cells: KeyCells, touched: Hit, x: Float, y: Float, hitMax: Float, hitClose: Float, prefixClass: (Key) -> Int): Key {
        val inside = touched.inCell
        val classes = HashMap<Key, Int>()
        fun cls(k: Key): Int = classes.getOrPut(k) { prefixClass(k) }
        if (inside && cls(touched.key) >= 0) return touched.key
        var best: Key? = null
        var bestScore = Int.MIN_VALUE
        var bestClass = Int.MIN_VALUE
        for (i in cells.keys.indices) {
            val k = cells.keys[i]
            if (!k.isLetter || !k.enabled) continue
            if (inside && k === touched.key) continue
            val c = max(cells.dx(i, x), cells.dy(i, y))
            if (c > hitMax) continue
            val score = when {
                c == 0f -> 0
                c <= hitClose -> -1500
                else -> -2500
            }
            val kc = cls(k)
            if (kc < 0) continue
            // ties: higher completion class, then layout order (the earlier key stays)
            if (score > bestScore || (score == bestScore && kc > bestClass)) {
                best = k
                bestScore = score
                bestClass = kc
            }
        }
        return best ?: touched.key
    }
}

/** Hysteresis areas of 03 §1.2 and §1.1a. */
object Hysteresis {
    /**
     * The area of a key selected at ([px], [py]) by a touch-down or a re-hit: the cell with every side moved out by
     * [tolerance] (10 px), except sides where the point lies outside the cell, which are moved to [beyond] (20 px)
     * past the point.
     */
    fun of(cell: PxRect, px: Float, py: Float, tolerance: Float, beyond: Float): PxRect = PxRect(
        if (px < cell.left) px - beyond else cell.left - tolerance,
        if (py < cell.top) py - beyond else cell.top - tolerance,
        if (px > cell.right) px + beyond else cell.right + tolerance,
        if (py > cell.bottom) py + beyond else cell.bottom + tolerance,
    )

    /**
     * The area of a key chosen by letter correction instead of the touched key (03 §1.1a): its cell, with the sides
     * where the touch point lies outside moved to [tolerance] past the point; the other sides stay at the cell edge.
     */
    fun ofCorrected(cell: PxRect, px: Float, py: Float, tolerance: Float): PxRect = PxRect(
        if (px < cell.left) px - tolerance else cell.left,
        if (py < cell.top) py - tolerance else cell.top,
        if (px > cell.right) px + tolerance else cell.right,
        if (py > cell.bottom) py + tolerance else cell.bottom,
    )
}
