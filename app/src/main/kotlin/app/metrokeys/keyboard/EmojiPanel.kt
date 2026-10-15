package app.metrokeys.keyboard

import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * The scrollable emoji grid of the emoji page (01 §7, 02 §5.9, 03 §8): 6 (landscape 8) columns, the shared
 * vertical physics, category jumps, the recent list with its empty hint. Coordinates relative to the key area.
 */
class EmojiPanel(private val v: KeyboardView) {
    private var scroller: ListScroller? = null
    private var items: List<String> = emptyList()
    private var category = ""
    private var pressed = -1
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var dragOrigin = 0f
    private var touchActive = false
    private val rf = RectF()

    private val m: Metrics get() = v.metrics

    private val gridHeight: Float get() = 0.75f * v.keyAreaHeight
    private val originX: Float get() = (if (m.landscape) 2f else 7f) * m.s
    private val originY: Float get() = 2f * m.s

    fun layout() {
        val old = scroller
        scroller = ListScroller(m.s, m.dragStart).also { if (old != null) it.jumpTo(old.offset) }
        reload(keepScroll = true)
    }

    fun onPageShown() = reload(keepScroll = false)

    fun onCategoryChanged() = reload(keepScroll = false)

    private fun reload(keepScroll: Boolean) {
        val c = v.controller.emojiCategory
        val limit = if (c == "recent") m.emojiColumns * m.emojiRecentRows else Int.MAX_VALUE
        items = v.controller.emojiItems(c, limit)
        val changed = c != category
        category = c
        val rows = ceil(items.size / m.emojiColumns.toFloat())
        val content = originY + rows * m.emojiCellH + 7f * m.s
        scroller?.setRange(0f, max(0f, content - gridHeight))
        if (!keepScroll || changed) scroller?.jumpTo(0f)
    }

    private val isText: Boolean get() = category == "emoticons"

    fun animating(now: Long): Boolean = scroller?.isMoving == true

    private fun cellRect(i: Int, out: RectF): RectF {
        val s = scroller!!
        val col = i % m.emojiColumns
        val row = i / m.emojiColumns
        val y = originY + row * m.emojiCellH
        val top = s.display(y, contentHeight())
        val bottom = s.display(y + m.emojiCellH, contentHeight())
        out.set(originX + col * m.emojiCellW, v.keyTop + top, originX + (col + 1) * m.emojiCellW, v.keyTop + bottom)
        return out
    }

    private fun contentHeight(): Float = originY + ceil(items.size / m.emojiColumns.toFloat()) * m.emojiCellH + 7f * m.s

    fun drawGrid(c: Canvas, now: Long) {
        val s = scroller ?: return
        s.update(now)
        val t = v.theme
        c.save()
        c.clipRect(0f, v.keyTop, v.width.toFloat(), v.keyTop + gridHeight)
        if (items.isEmpty() && category == "recent") {
            drawEmptyHint(c)
            c.restore()
            return
        }
        val tp = v.text
        if (isText) {
            tp.typeface = v.typeface(TextStyles.emojiItemText.weight)
            tp.textSize = TextStyles.emojiItemText.size * m.s
        } else {
            tp.typeface = v.fonts.emoji
            tp.textSize = TextStyles.emojiItem.size * m.s
        }
        val pad = (if (isText) 18f else 27f) * m.s
        for (i in items.indices) {
            val r = cellRect(i, rf)
            if (r.bottom < v.keyTop || r.top > v.keyTop + gridHeight) continue
            if (i == pressed) {
                v.fill.color = t.keyPressed
                c.drawRect(r, v.fill)
            }
            tp.color = if (isText) (if (i == pressed) t.keyPressedLabel else t.keyLabel) else 0xFF000000.toInt()
            val w = tp.measureText(items[i])
            val fm = tp.fontMetrics
            val cy = (r.top + r.bottom - pad) / 2
            val size = tp.textSize
            if (w > r.width() * 0.96f && w > 0) tp.textSize = size * r.width() * 0.96f / w
            val ww = tp.measureText(items[i])
            val fm2 = if (tp.textSize != size) tp.fontMetrics else fm
            c.drawText(items[i], r.centerX() - ww / 2, cy - (fm2.ascent + fm2.descent) / 2, tp)
            tp.textSize = size
        }
        c.restore()
    }

    private fun drawEmptyHint(c: Canvas) {
        val ins = if (m.landscape) floatArrayOf(113f, 45f, 113f, 0f) else floatArrayOf(113f, 90f, 113f, 0f)
        val box = RectF(ins[0] * m.s, v.keyTop + ins[1] * m.s, v.width - ins[2] * m.s, v.keyTop + gridHeight - ins[3] * m.s)
        val tp = android.text.TextPaint(v.text)
        tp.typeface = v.typeface(TextStyles.emojiEmptyMessage.weight)
        tp.textSize = TextStyles.emojiEmptyMessage.size * m.s
        tp.color = v.theme.keyHint
        val msg = v.controller.str(app.metrokeys.R.string.emoji_recent_empty)
        val sl = StaticLayout.Builder.obtain(msg, 0, msg.length, tp, box.width().toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        c.save()
        c.translate(box.left, box.top + (box.height() - sl.height) / 2)
        sl.draw(c)
        c.restore()
    }

    private fun indexAt(x: Float, y: Float): Int {
        val s = scroller ?: return -1
        if (y < 0 || y > gridHeight) return -1
        val cy = s.content(y, contentHeight()) - originY
        val cx = x - originX
        if (cx < 0 || cy < 0) return -1
        val col = (cx / m.emojiCellW).toInt()
        if (col >= m.emojiColumns) return -1
        val i = (cy / m.emojiCellH).toInt() * m.emojiColumns + col
        return if (i in items.indices) i else -1
    }

    fun down(x: Float, y: Float, now: Long) {
        touchActive = true
        downX = x
        downY = y
        dragging = false
        scroller?.touchDown(now)
        pressed = indexAt(x, y)
        if (pressed >= 0) v.controller.feedback.play(SoundKind.LETTER)
        v.invalidate()
    }

    fun move(x: Float, y: Float, @Suppress("UNUSED_PARAMETER") now: Long) {
        if (!touchActive) return
        val s = scroller ?: return
        if (!dragging && (abs(y - downY) > m.dragStart || abs(x - downX) > m.dragStart)) {
            dragging = true
            pressed = -1
            dragOrigin = y
            s.startDrag()
        }
        if (dragging) {
            s.dragTo(y - dragOrigin)
            v.invalidate()
        }
    }

    fun up(x: Float, y: Float, now: Long) {
        if (!touchActive) return
        touchActive = false
        val s = scroller ?: return
        if (dragging) {
            dragging = false
            val (_, vy) = v.currentVelocity()
            s.release(-vy, now)
            v.invalidate()
            return
        }
        s.releaseWithoutDrag(now)
        val p = pressed
        pressed = -1
        if (p >= 0 && p == indexAt(x, y)) v.controller.emojiPicked(items[p])
        v.invalidate()
    }

    fun cancel() {
        touchActive = false
        dragging = false
        pressed = -1
    }

    // accessibility
    fun visibleCells(): List<Pair<Int, RectF>> {
        if (scroller == null) return emptyList()
        val out = ArrayList<Pair<Int, RectF>>()
        for (i in items.indices) {
            val r = cellRect(i, RectF())
            if (r.bottom <= v.keyTop || r.top >= v.keyTop + gridHeight) continue
            r.top = max(r.top, v.keyTop)
            r.bottom = minOf(r.bottom, v.keyTop + gridHeight)
            out.add(i to r)
        }
        return out
    }

    fun item(i: Int): String? = items.getOrNull(i)

    fun pick(i: Int) {
        items.getOrNull(i)?.let { v.controller.emojiPicked(it) }
    }

    fun scrollPage(forward: Boolean) {
        val s = scroller ?: return
        s.animateTo(s.offset + (if (forward) 1 else -1) * gridHeight, SystemClock.uptimeMillis())
        v.invalidate()
    }
}
