package app.metrokeys.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextUtils
import app.metrokeys.R
import app.metrokeys.ime.ClipEntry
import kotlin.math.abs
import kotlin.math.max

/**
 * The clipboard history panel (03 §10.3, 02 §5.12): it replaces the key area; rows of 150 px drawn as key faces,
 * pinned rows with an accent bar, a delete cross per row, and a bottom bar with "abc", a hint and "clear".
 * Coordinates relative to the key area.
 */
class ClipboardPanel(private val v: KeyboardView) {
    private var scroller: ListScroller? = null
    private var entries: List<ClipEntry> = emptyList()
    private var pressedRow = -1
    private var pressedCross = false
    private var pressedBar = -1
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var dragOrigin = 0f
    private var touchActive = false
    private var holdFired = false
    private val rf = RectF()
    private val src = Rect()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val holdRunnable = Runnable {
        val e = entries.getOrNull(pressedRow) ?: return@Runnable
        if (!pressedCross && !dragging) {
            holdFired = true
            v.controller.clipTogglePin(e)
            reload()
        }
    }

    private val m: Metrics get() = v.metrics
    private val rowH: Float get() = 150f * m.s
    private val barH: Float get() = v.keyAreaHeight / 4f
    private val listH: Float get() = v.keyAreaHeight - barH

    fun onShown() {
        if (scroller == null) scroller = ListScroller(m.s, m.dragStart)
        reload()
        scroller?.jumpTo(0f)
    }

    private fun reload() {
        entries = v.controller.clipEntries()
        scroller?.setRange(0f, max(0f, entries.size * rowH - listH))
    }

    fun animating(now: Long): Boolean = scroller?.isMoving == true

    private fun rowRect(i: Int, out: RectF): RectF {
        val s = scroller!!
        val total = entries.size * rowH
        out.set(0f, v.keyTop + s.display(i * rowH, total), v.width.toFloat(), v.keyTop + s.display((i + 1) * rowH, total))
        return out
    }

    fun draw(c: Canvas, now: Long) {
        if (scroller == null) onShown()
        val s = scroller!!
        s.update(now)
        if (entries.size != v.controller.clipEntries().size) reload()
        val t = v.theme
        val inset = m.keyInset
        val tp = v.text
        c.save()
        c.clipRect(0f, v.keyTop, v.width.toFloat(), v.keyTop + listH)
        if (entries.isEmpty()) {
            v.drawLabel(c, v.controller.str(R.string.clip_empty), RectF(0f, v.keyTop, v.width.toFloat(), v.keyTop + listH),
                v.typeface(Weight.REGULAR), TextStyles.candidate.size * m.s, t.keyHint)
        }
        for ((i, e) in entries.withIndex()) {
            val r = rowRect(i, rf)
            if (r.bottom < v.keyTop || r.top > v.keyTop + listH) continue
            val face = RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)
            val pressed = i == pressedRow && !pressedCross && !dragging
            v.fill.color = if (pressed) t.keyPressed else t.key
            c.drawRect(face, v.fill)
            if (e.pinned) {
                v.fill.color = t.accent.takeUnless { t.highContrast } ?: t.keyLabel
                c.drawRect(face.left, face.top, face.left + 10f * m.s, face.bottom, v.fill)
            }
            val crossW = face.height()
            val crossL = face.right - crossW
            if (i == pressedRow && pressedCross) {
                v.fill.color = t.keyPressed
                c.drawRect(crossL, face.top, face.right, face.bottom, v.fill)
            }
            v.glyphs.cross(c, crossL + crossW / 2, face.centerY(), m.s, if (i == pressedRow && pressedCross) t.keyPressedLabel else t.keyLabel)
            val avail = crossL - face.left - 40f * m.s - 10f * m.s
            if (e.isImage) {
                // image entries: a down-sampled, cached thumbnail instead of the text (03 §10.3 item 7)
                val th = (face.height() - 24f * m.s).toInt().coerceAtLeast(1)
                val b = v.controller.clipThumbnail(e.image!!, th)
                if (b != null) {
                    val w = minOf(b.width * th.toFloat() / b.height, avail)
                    src.set(0, 0, b.width, b.height)
                    val l = face.left + 40f * m.s
                    c.drawBitmap(b, src, RectF(l, face.centerY() - th / 2f, l + w, face.centerY() + th / 2f), bitmapPaint)
                }
                continue
            }
            tp.typeface = v.typeface(Weight.REGULAR)
            tp.textSize = TextStyles.candidate.size * m.s
            tp.color = if (pressed) t.keyPressedLabel else t.keyLabel
            val oneLine = e.text.replace(Regex("\\s+"), " ").trim()
            val shown = TextUtils.ellipsize(oneLine, tp, avail, TextUtils.TruncateAt.END).toString()
            c.drawText(shown, face.left + 40f * m.s, face.centerY() + v.capHeight(tp.typeface, tp.textSize) / 2, tp)
        }
        c.restore()
        // bottom bar
        val top = v.keyTop + listH
        val cells = barCells(top)
        for ((i, r) in cells.withIndex()) {
            val face = RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)
            if (i == 1) {
                v.drawLabel(c, v.controller.str(R.string.clip_hint), face, v.typeface(TextStyles.smallText.weight), TextStyles.smallText.size * m.keyLabelScale, t.keyHint)
                continue
            }
            val pressed = pressedBar == i
            v.fill.color = if (pressed) t.keyPressed else t.key
            c.drawRect(face, v.fill)
            val label = v.controller.str(if (i == 0) R.string.clip_letters else R.string.clip_clear)
            // the bottom-row labels and the hint scale with the keyboard height like key labels (02 §5.12)
            v.drawLabel(c, label, face, v.typeface(TextStyles.functionText.weight), TextStyles.functionText.size * m.keyLabelScale,
                if (pressed) t.keyPressedLabel else t.keyLabel)
        }
    }

    private fun barCells(top: Float): List<RectF> {
        val w = v.width.toFloat()
        return listOf(RectF(0f, top, 0.25f * w, top + barH), RectF(0.25f * w, top, 0.75f * w, top + barH), RectF(0.75f * w, top, w, top + barH))
    }

    private fun rowAt(y: Float): Int {
        val s = scroller ?: return -1
        if (y < 0 || y >= listH) return -1
        val cy = s.content(y, entries.size * rowH)
        val i = (cy / rowH).toInt()
        return if (cy >= 0 && i in entries.indices) i else -1
    }

    fun down(x: Float, y: Float, now: Long) {
        touchActive = true
        downX = x
        downY = y
        dragging = false
        holdFired = false
        pressedRow = -1
        pressedCross = false
        pressedBar = -1
        v.controller.feedback.play(SoundKind.FUNCTION)
        if (y >= listH) {
            pressedBar = when {
                x < 0.25f * v.width -> 0
                x >= 0.75f * v.width -> 2
                else -> -1
            }
            v.invalidate()
            return
        }
        scroller?.touchDown(now)
        pressedRow = rowAt(y)
        if (pressedRow >= 0) {
            val face = rowRect(pressedRow, rf)
            pressedCross = x >= face.right - m.keyInset - (face.height() - 2 * m.keyInset)
            if (!pressedCross) v.postDelayed(holdRunnable, 450)
        }
        v.invalidate()
    }

    fun move(x: Float, y: Float, @Suppress("UNUSED_PARAMETER") now: Long) {
        if (!touchActive || pressedBar >= 0) return
        val s = scroller ?: return
        if (!dragging && (abs(y - downY) > m.dragStart || abs(x - downX) > m.dragStart)) {
            dragging = true
            v.removeCallbacks(holdRunnable)
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
        v.removeCallbacks(holdRunnable)
        val s = scroller ?: return
        if (pressedBar >= 0) {
            val b = pressedBar
            pressedBar = -1
            if (y >= listH && ((b == 0 && x < 0.25f * v.width) || (b == 2 && x >= 0.75f * v.width))) {
                if (b == 0) v.controller.closeClipboardPanel() else {
                    v.controller.clipClearUnpinned()
                    reload()
                }
            }
            v.invalidate()
            return
        }
        if (dragging) {
            dragging = false
            val (_, vy) = v.currentVelocity()
            s.release(-vy, now)
            v.invalidate()
            return
        }
        s.releaseWithoutDrag(now)
        val row = pressedRow
        pressedRow = -1
        if (row >= 0 && !holdFired && row == rowAt(y)) {
            val e = entries[row]
            if (pressedCross) {
                v.controller.clipDelete(e)
                reload()
            } else {
                v.controller.clipInsert(e)
            }
        }
        pressedCross = false
        v.invalidate()
    }

    fun cancel() {
        touchActive = false
        v.removeCallbacks(holdRunnable)
        dragging = false
        pressedRow = -1
        pressedBar = -1
    }

    // accessibility: (kind, index, rect); kinds 0 row, 1 cross, 2 bar cell, 3 empty hint
    fun accessibilityNodes(): List<Triple<Int, Int, RectF>> {
        if (scroller == null) return emptyList()
        val out = ArrayList<Triple<Int, Int, RectF>>()
        if (entries.isEmpty()) out.add(Triple(3, 0, RectF(0f, v.keyTop, v.width.toFloat(), v.keyTop + listH)))
        for (i in entries.indices) {
            val r = rowRect(i, RectF())
            if (r.bottom <= v.keyTop || r.top >= v.keyTop + listH) continue
            r.top = max(r.top, v.keyTop)
            r.bottom = minOf(r.bottom, v.keyTop + listH)
            val cross = RectF(r.right - r.height(), r.top, r.right, r.bottom)
            out.add(Triple(0, i, RectF(r.left, r.top, cross.left, r.bottom)))
            out.add(Triple(1, i, cross))
        }
        barCells(v.keyTop + listH).forEachIndexed { i, r -> out.add(Triple(2, i, r)) }
        return out
    }

    fun entry(i: Int): ClipEntry? = entries.getOrNull(i)

    fun activate(kind: Int, i: Int, long: Boolean) {
        when (kind) {
            0 -> entries.getOrNull(i)?.let { if (long) v.controller.clipTogglePin(it) else v.controller.clipInsert(it) }
            1 -> entries.getOrNull(i)?.let { v.controller.clipDelete(it) }
            2 -> if (i == 0) v.controller.closeClipboardPanel() else if (i == 2) v.controller.clipClearUnpinned()
        }
        reload()
    }

    fun scrollPage(forward: Boolean) {
        val s = scroller ?: return
        s.animateTo(s.offset + (if (forward) 1 else -1) * listH, SystemClock.uptimeMillis())
        v.invalidate()
    }
}
