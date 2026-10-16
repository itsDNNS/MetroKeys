package app.metrokeys.keyboard

import app.metrokeys.keyboard.layout.Key
import app.metrokeys.keyboard.layout.PxRect
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The alternates or language picker (02 §5.6, §7.5; 03 §3.3, §3.4) in key-area pixels: the cells at their final
 * places, the hold zone and the selection state. Pure Kotlin, driven by [KeyTouch].
 */
class PickerModel(
    val key: Key,
    val items: List<String>,
    /** Final cell rectangles (content boxes without frame). */
    val cells: List<PxRect>,
    /** Row per item: 0 = lower row, 1 = upper row. */
    val rows: IntArray,
    val language: Boolean,
    /** The 9 px frame (scaled). */
    val frame: Float,
    /** 03 §3.4 hold zone, edges inclusive. */
    val holdZone: PxRect,
    /** Bottom edge of the lower row's frame. */
    val pickerBottom: Float,
    /** Cell height + 2 × frame. */
    val rowHitHeight: Float,
    val openTime: Long,
) {
    /** Highlighted item, −1 for none (03 §3.4 item 4). */
    var highlighted = 0

    /** Drag phase (03 §3.4 items 3, 4); false = hold phase. */
    var drag = false

    /** Released in the hold phase: open until the next touch-down (item 6). */
    var sticky = false

    val rowCount: Int get() = if (rows.any { it == 1 }) 2 else 1

    /** The row of a finger at [y] (03 §3.4 "row choice"). */
    fun rowAt(y: Float): Int = if (rowCount == 2 && y < pickerBottom - rowHitHeight) 1 else 0

    /** The item whose slot of the chosen row contains [x], −1 if none (slot = the cell with its left frame). */
    fun slotAt(x: Float, y: Float): Int {
        val row = rowAt(y)
        for (i in items.indices) {
            if (rows[i] != row) continue
            val c = cells[i]
            if (x >= c.left - frame && x < c.right) return i
        }
        return -1
    }

    /** Inside the hit band of a sticky picker (item 6). */
    fun inBand(y: Float): Boolean = y > pickerBottom - rowCount * rowHitHeight && y < pickerBottom

    /** A move of the finger that holds the picker (items 2–4); true if the highlight changed. */
    fun move(x: Float, y: Float): Boolean {
        if (!drag) {
            if (holdZone.contains(x, y)) return false
            drag = true
            return false
        }
        val i = slotAt(x, y)
        if (i == highlighted) return false
        highlighted = i
        return true
    }

    /** Animated cell of item [i] at [now] (02 §7.5), not yet snapped. */
    fun cellAt(i: Int, now: Long): PxRect {
        val f = cells[i]
        val first = cells[0]
        val u = ((now - openTime - 100f) / 200f).coerceIn(0f, 1f)
        return f.offsetX((first.left - f.left) * (1 - u))
    }

    /** True while the opening animation runs. */
    fun animating(now: Long): Boolean = now - openTime < 300

    /**
     * Drawing order of 02 §7.5: per row from the rightmost final position to the leftmost, so every cell with its
     * frame covers the cells to its right.
     */
    fun drawOrder(): List<Int> = items.indices.sortedWith(compareBy({ rows[it] }, { -cells[it].left }))

    companion object {
        /**
         * Places the picker for [key] with cell [keyCell] (02 §5.6.2–§5.6.4): cell size [cw] × [ch], frame [fr], key
         * area [width].
         */
        fun place(key: Key, keyCell: PxRect, items: List<String>, cw: Float, ch: Float, fr: Float, width: Float, language: Boolean, openTime: Long): PickerModel {
            val pitch = cw + fr
            val cap = max(1, ((width - fr) / pitch).toInt())
            val list = items.take(2 * cap)
            val lowerCount = if (list.size > cap) ceil(list.size / 2.0).toInt() else list.size
            val pickerBottom = keyCell.bottom - 0.98f * keyCell.height
            val lowerBottom = pickerBottom - fr
            val cells = ArrayList<PxRect>()
            val rows = IntArray(list.size)
            fun placeRow(count: Int, bottom: Float) {
                val x0 = keyCell.centerX - cw / 2
                val rightEdge = x0 + (count - 1) * pitch + cw + fr
                val leftEdge = x0 - (count - 1) * pitch - fr
                val top = bottom - ch
                when {
                    rightEdge <= width && x0 - fr >= 0 -> for (i in 0 until count) cells.add(PxRect(x0 + i * pitch, top, x0 + i * pitch + cw, bottom))
                    leftEdge >= 0 && x0 + cw + fr <= width -> for (i in 0 until count) cells.add(PxRect(x0 - i * pitch, top, x0 - i * pitch + cw, bottom))
                    else -> {
                        val rowW = count * pitch + fr
                        val start = max(0f, min(x0 - fr, width - rowW)) + fr
                        for (i in 0 until count) cells.add(PxRect(start + i * pitch, top, start + i * pitch + cw, bottom))
                    }
                }
            }
            placeRow(lowerCount, lowerBottom)
            if (list.size > lowerCount) {
                placeRow(list.size - lowerCount, lowerBottom - ch - fr)
                for (i in lowerCount until list.size) rows[i] = 1
            }
            return PickerModel(key, list, cells, rows, language, fr, holdZone(keyCell, cw + 2 * fr, width), pickerBottom, ch + 2 * fr, openTime)
        }

        /**
         * 03 §3.4 hold zone: the key cell widened symmetrically to [outerWidth] (unless the key is wider), shifted inwards
         * with unchanged width at the key-area edges; vertically the key cell.
         */
        fun holdZone(keyCell: PxRect, outerWidth: Float, width: Float): PxRect {
            if (keyCell.width >= outerWidth) return keyCell
            var l = keyCell.centerX - outerWidth / 2
            if (l < 0f) l = 0f
            if (l + outerWidth > width) l = width - outerWidth
            return PxRect(l, keyCell.top, l + outerWidth, keyCell.bottom)
        }
    }
}
