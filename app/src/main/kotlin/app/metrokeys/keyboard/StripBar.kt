package app.metrokeys.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextUtils
import app.metrokeys.R
import app.metrokeys.ime.KeyboardController
import app.metrokeys.ime.StripContent
import app.metrokeys.ime.StripItem
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * The candidate strip (03 §6, 02 §5.8): paste header, the optional recently-copied chip (§6.11) and
 * clipboard-history button (§6.7), the candidates in one horizontally scrolling row with the shared physics, the
 * autocorrection flash, the dictation state and the confirmation for forgetting a learned word (§6.3 item 4).
 * Coordinates in this class are relative to the strip top.
 */
class StripBar(private val v: KeyboardView) {
    private var content: StripContent = StripContent.EMPTY
    private var pending: StripContent? = null
    private var flashWord: String? = null
    private var flashUntil = 0L
    private var scroller: ListScroller? = null

    private var itemLeft = FloatArray(0)
    private var itemWidth = FloatArray(0)
    private var contentLength = 0f

    // paste header state (03 §6.6)
    private var candidatesShown = false
    private var dragged = false
    private var recentlyPasted = false
    private var lastDragInView = true
    private var drawnSinceShown = false
    private var lastCanPaste = HEADER_SUPPRESSED
    private var awaitingRest = false

    // clipboard slot (03 §6.7.3)
    private var slot = 0f
    private var slotFrom = 0f
    private var slotTo = 0f
    private var slotStart = 0L

    // recently-copied chip slot (03 §6.11.3): opens and closes like the clipboard slot
    private var chip: KeyboardController.Chip? = null
    private var chipSlot = 0f
    private var chipFrom = 0f
    private var chipTo = 0f
    private var chipStart = 0L
    private var chipW = 0f
    private var chipText = ""
    private var chipWidthKey: Any? = null

    // a message in place of a suppressed toast
    private var message: String? = null
    private var messageUntil = 0L
    private val messageEnd = Runnable {
        message = null
        v.invalidate()
    }

    // forgetting a learned word (03 §6.3 item 4)
    private var forgetWord: String? = null
    private val forgetYes = RectF()
    private val forgetNo = RectF()
    private var holdFired = false
    private val holdRunnable = Runnable { onHold() }

    // touch
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var dragOrigin = 0f
    private var pressed = NONE
    private var touchActive = false

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()
    private val src = Rect()

    private val m: Metrics get() = v.metrics

    fun layout() {
        val old = scroller
        val s = ListScroller(m.s, m.dragStart)
        scroller = s
        // the chip's preview depends on the view width (03 §6.11 item 2)
        chip = v.controller.chip
        chip?.let { chipW = chipWidth(it) }
        chipSlot = if (!drawnSinceShown) (if (chip != null) 1f else 0f) else chipSlot
        if (!drawnSinceShown) chipTo = chipSlot
        // the range first, so the position is not clamped to an empty range
        relayout()
        if (old != null) s.jumpTo(old.offset)
        if (!drawnSinceShown) s.jumpTo(headerTarget())
    }

    private val headerW: Float get() = m.pasteHeaderWidth

    private fun relayout() {
        val items = content.items
        itemLeft = FloatArray(items.size)
        itemWidth = FloatArray(items.size)
        var x = headerW + chipSlot * chipW + slot * m.clipSlotWidth
        for ((i, it) in items.withIndex()) {
            itemLeft[i] = x
            itemWidth[i] = widthOf(it, i)
            x += itemWidth[i]
        }
        contentLength = headerW + max(v.width.toFloat(), x - headerW)
        scroller?.setRange(0f, max(0f, contentLength - v.width))
    }

    private fun boldOf(it: StripItem, i: Int): Boolean = it.bold || (i == 0 && flashWord != null)

    private fun widthOf(it: StripItem, i: Int): Float {
        val t = v.text
        return when {
            it.addWord -> {
                t.typeface = v.typeface(TextStyles.candidatePlus.weight)
                t.textSize = TextStyles.candidatePlus.size * m.s
                val plus = t.measureText("+")
                t.typeface = v.typeface(Weight.REGULAR)
                t.textSize = TextStyles.candidate.size * m.s
                (47 + 5 + 47) * m.s + plus + t.measureText(it.text)
            }
            it.emoji -> {
                t.typeface = v.fonts.emoji
                t.textSize = TextStyles.candidateEmoji.size * m.s
                t.measureText(it.text) + 2 * m.itemPadding
            }
            else -> {
                val st = if (boldOf(it, i)) TextStyles.candidateBold else TextStyles.candidate
                t.typeface = v.typeface(st.weight)
                t.textSize = st.size * m.s
                t.measureText(it.text) + 2 * m.itemPadding
            }
        }
    }

    // ================================================================ content

    fun setContent(c: StripContent) {
        val now = SystemClock.uptimeMillis()
        if (flashWord != null && now < flashUntil) {
            pending = c
            return
        }
        apply(c)
    }

    private fun apply(c: StripContent) {
        content = c
        // any new candidate list restores the strip (03 §6.3 item 4)
        forgetWord = null
        if (c.items.isNotEmpty()) candidatesShown = true
        relayout()
        updateSlotTarget()
        scrollHome()
        v.invalidate()
    }

    /** Autocorrection flash of 03 §6.9 / 02 §5.8.4. */
    fun flash(word: String) {
        val first = content.items.firstOrNull()
        if (first == null || first.text != word) {
            content = StripContent(content.kind, listOf(StripItem(word, bold = true)))
        }
        flashWord = word
        flashUntil = SystemClock.uptimeMillis() + 300
        pending = null
        scroller?.jumpTo(scroller!!.offset)
        relayout()
        v.postDelayed({ endFlash() }, 300)
        v.invalidate()
    }

    private fun endFlash() {
        if (flashWord == null) return
        flashWord = null
        val next = pending ?: StripContent.EMPTY
        pending = null
        // the strip is emptied for one frame, then the waiting list appears
        content = StripContent.EMPTY
        relayout()
        v.invalidate()
        v.postOnAnimation { apply(next) }
    }

    fun onShown() {
        candidatesShown = false
        drawnSinceShown = false
        dragged = false
        recentlyPasted = false
        lastDragInView = true
        lastCanPaste = headerState()
        slot = if (v.controller.clipButtonVisible) 1f else 0f
        slotTo = slot
        forgetWord = null
        chip = v.controller.chip
        if (stripWidth > 0) chip?.let { chipW = chipWidth(it) }
        chipSlot = if (chip != null) 1f else 0f
        chipTo = chipSlot
        relayout()
        scroller?.jumpTo(headerTarget())
    }

    fun onHidden() {
        if (flashWord != null) {
            flashWord = null
            pending?.let { content = it }
            pending = null
        }
        cancel()
    }

    fun onFieldStateChanged() {
        val cp = headerState()
        if (cp != lastCanPaste) {
            lastCanPaste = cp
            scrollHome()
        }
        updateSlotTarget()
    }

    fun onClipboardChanged() {
        recentlyPasted = false
        dragged = false
        lastCanPaste = headerState()
        updateSlotTarget()
        scrollHome()
    }

    fun onDictationChanged() = v.invalidate()

    /** Shows [text] in the strip for [durationMs]; a touch on the strip ends it. */
    fun showMessage(text: String, durationMs: Long) {
        message = text
        messageUntil = SystemClock.uptimeMillis() + durationMs
        v.removeCallbacks(messageEnd)
        v.postDelayed(messageEnd, durationMs)
        v.invalidate()
    }

    /** State of the paste header (03 §6.6 item 2): normal, dimmed (empty clipboard) or suppressed. */
    private fun headerState(): Int = when {
        v.controller.canPaste -> HEADER_NORMAL
        v.controller.pasteDimmed -> HEADER_DIMMED
        else -> HEADER_SUPPRESSED
    }

    /** Header target of 03 §6.6.3. */
    private fun headerTarget(): Float {
        if (!v.controller.canPaste) {
            // dimmed: home on a freshly shown keyboard before the first candidates, else hidden; suppressed: hidden
            return if (v.controller.pasteDimmed && !candidatesShown) 0f else headerW
        }
        if (!candidatesShown) return 0f
        if (!dragged && !recentlyPasted) return 0f
        if (dragged && lastDragInView && !recentlyPasted) return 0f
        return headerW / 2
    }

    private fun scrollHome() {
        val s = scroller ?: return
        if (flashWord != null) return
        s.animateTo(headerTarget(), SystemClock.uptimeMillis(), animate = drawnSinceShown)
        v.invalidate()
    }

    private fun updateSlotTarget() {
        updateChipTarget()
        val t = if (v.controller.clipButtonVisible) 1f else 0f
        if (t == slotTo) return
        slotFrom = slot
        slotTo = t
        slotStart = SystemClock.uptimeMillis()
        if (!drawnSinceShown) {
            slot = t
            relayout()
        }
        v.invalidate()
    }

    /** The chip slot follows 03 §6.11 item 1; its width follows the chip's content. */
    private fun updateChipTarget() {
        val c = v.controller.chip
        chip = c
        // the width is measured again only when the chip's content or the strip width changed (not per key)
        val key = c?.let { Triple(it.text, it.image?.image, stripWidth) }
        if (c != null && (key != chipWidthKey || c.image != null)) {
            chipWidthKey = key
            val w = chipWidth(c)
            if (w != chipW) {
                chipW = w
                relayout()
            }
        }
        val t = if (c != null) 1f else 0f
        if (t == chipTo) return
        chipFrom = chipSlot
        chipTo = t
        chipStart = SystemClock.uptimeMillis()
        if (!drawnSinceShown) {
            chipSlot = t
            relayout()
        }
        v.invalidate()
    }

    /** The strip width, also while the view is measured but not yet laid out. */
    private val stripWidth: Float get() = if (v.width > 0) v.width.toFloat() else v.keyAreaWidth

    // chip geometry of 02 §5.8.9 (ref px × s): slot margin 12 each side, square box 0.64 × the strip height,
    // pictogram slot 54 (12 inside the box), text 10 after it at 0.8 × the candidate size, right padding 24
    private val chipGap: Float get() = 12f * m.s
    private val chipBoxHeight: Float get() = 0.64f * m.stripHeight
    private val chipPictoLeft: Float get() = 12f * m.s
    private val chipPictoWidth: Float get() = 54f * m.s
    private val chipTextGap: Float get() = 10f * m.s
    private val chipRightPad: Float get() = 24f * m.s
    private val chipTextSize: Float get() = 0.8f * TextStyles.candidate.size * m.s
    private val thumbHeight: Int get() = chipBoxHeight.toInt().coerceAtLeast(1)

    /** Slot width of the chip: margin, box (pictogram slot, gap, preview or thumbnail, right padding), margin. */
    private fun chipWidth(c: KeyboardController.Chip): Float {
        val content = if (c.image != null) {
            val th = thumbHeight
            val b = c.image.image?.let { v.controller.clipThumbnail(it, th) }
            if (b != null && b.height > 0) minOf(b.width * th.toFloat() / b.height, 0.45f * stripWidth) else th.toFloat()
        } else {
            val t = v.text
            t.typeface = v.typeface(Weight.REGULAR)
            t.textSize = chipTextSize
            val oneLine = c.text.orEmpty().replace(Regex("\\s+"), " ").trim()
            chipText = TextUtils.ellipsize(oneLine, t, 0.45f * stripWidth, TextUtils.TruncateAt.END).toString()
            t.measureText(chipText)
        }
        return 2 * chipGap + chipPictoLeft + chipPictoWidth + chipTextGap + content + chipRightPad
    }

    fun animating(now: Long): Boolean =
        // the autocorrection flash is static: endFlash redraws when it is over
        (scroller?.isMoving == true) || slot != slotTo || chipSlot != chipTo

    // ================================================================ drawing

    fun draw(c: Canvas, now: Long) {
        val t = v.theme
        val top = v.stripTop
        val h = m.stripHeight
        v.fill.color = t.candidateBar
        c.drawRect(0f, top, v.width.toFloat(), top + h, v.fill)
        t.candidateTopLine?.let {
            v.fill.color = it
            c.drawRect(0f, top, v.width.toFloat(), top + m.s, v.fill)
        }
        if (v.controller.autofillActive) {
            drawnSinceShown = true
            return
        }
        val dict = v.controller.dictationState
        if (dict != null) {
            drawDictation(c, top, h, dict.levelDb, dict.text)
            drawnSinceShown = true
            return
        }
        val s = scroller ?: return
        val msg = message
        if (msg != null && now < messageUntil) {
            val tp = v.text
            tp.typeface = v.typeface(Weight.REGULAR)
            tp.textSize = TextStyles.candidate.size * m.s
            tp.color = t.candidateText
            val pad = m.itemPadding
            val shown = TextUtils.ellipsize(msg, tp, v.width - 2 * pad, TextUtils.TruncateAt.END).toString()
            c.drawText(shown, pad, top + h / 2 + v.capHeight(tp.typeface, tp.textSize) / 2, tp)
            drawnSinceShown = true
            return
        }
        val fw = forgetWord
        if (fw != null) {
            drawForget(c, fw, top, h)
            drawnSinceShown = true
            return
        }
        if (flashWord == null) {
            if (slot != slotTo) {
                val u = (now - slotStart) / 400f
                slot = slotFrom + (slotTo - slotFrom) * Curves.sCurve(u, 10f)
                if (u >= 1f) slot = slotTo
                relayout()
            }
            if (chipSlot != chipTo) {
                val u = (now - chipStart) / 400f
                chipSlot = chipFrom + (chipTo - chipFrom) * Curves.sCurve(u, 10f)
                if (u >= 1f) chipSlot = chipTo
                relayout()
            }
            val wasMoving = s.isMoving
            s.update(now)
            if (wasMoving && !s.isMoving && awaitingRest) {
                awaitingRest = false
                lastDragInView = s.offset <= 0.5f
            }
        }
        c.save()
        c.clipRect(0f, top, v.width.toFloat(), top + h)
        // paste header (03 §6.6 item 8): at 50 % opacity with an empty clipboard, not drawn when suppressed
        val hx = s.display(96f * m.s, contentLength)
        val cy = top + h / 2
        if (hx > -60 * m.s) {
            if (v.controller.canPaste) {
                drawRoundButton(c, hx, cy, pressed == HEADER) { col, bg -> v.glyphs.pastePictogram(c, hx, cy, m.s, col, bg) }
            } else if (v.controller.pasteDimmed) {
                val r = 60f * m.s
                c.saveLayerAlpha(hx - r, cy - r, hx + r, cy + r, 128)
                drawRoundButton(c, hx, cy, false) { col, bg -> v.glyphs.pastePictogram(c, hx, cy, m.s, col, bg) }
                c.restore()
            }
        }
        // recently-copied chip
        if (chipSlot > 0f) {
            val l = s.display(headerW, contentLength)
            c.save()
            c.clipRect(l, top, l + chipSlot * chipW, top + h)
            drawChip(c, l, top)
            c.restore()
        }
        // clipboard-history button
        if (slot > 0f) {
            val sx = s.display(headerW + chipSlot * chipW + 95f * m.s, contentLength)
            c.save()
            val l = s.display(headerW + chipSlot * chipW, contentLength)
            c.clipRect(l, top, l + slot * m.clipSlotWidth, top + h)
            // no selected look while the panel is open (03 §6.7 item 5)
            drawRoundButton(c, sx, cy, pressed == CLIP) { col, bg ->
                v.glyphs.clipboardPictogram(c, sx, cy, m.s, col, bg)
            }
            c.restore()
        }
        // candidates
        for ((i, it) in content.items.withIndex()) {
            val l = s.display(itemLeft[i], contentLength)
            val r = s.display(itemLeft[i] + itemWidth[i], contentLength)
            if (r < 0 || l > v.width) continue
            val inverted = pressed == i || (i == 0 && flashWord != null)
            if (inverted) {
                // the item's full cell over the full strip height (02 §5.8.3)
                v.fill.color = t.candidatePressed
                c.drawRect(l, top, r, top + h, v.fill)
            }
            val col = if (inverted) t.candidatePressedText else t.candidateText
            drawItem(c, it, i, l, r, top, h, col)
        }
        c.restore()
        drawnSinceShown = true
    }

    /**
     * The chip of 03 §6.11 item 2 and 02 §5.8.9: a square key-coloured box, an outline clipboard pictogram, then a
     * one-line preview or a thumbnail.
     */
    private fun drawChip(c: Canvas, slotLeft: Float, top: Float) {
        val ch = chip ?: return
        val t = v.theme
        val isPressed = pressed == CHIP
        val boxTop = top + (m.stripHeight - chipBoxHeight) / 2
        val box = RectF(slotLeft + chipGap, boxTop, slotLeft + chipW - chipGap, boxTop + chipBoxHeight)
        v.fill.color = if (isPressed) t.keyPressed else t.key
        c.drawRect(box, v.fill)
        val col = if (isPressed) t.keyPressedLabel else t.keyLabel
        val cy = box.centerY()
        v.glyphs.clipboardOutline(c, box.left + chipPictoLeft + chipPictoWidth / 2, cy, m.s, col)
        val x = box.left + chipPictoLeft + chipPictoWidth + chipTextGap
        val img = ch.image
        if (img != null) {
            val th = thumbHeight
            val b: Bitmap? = img.image?.let { v.controller.clipThumbnail(it, th) }
            if (b != null) {
                val w = minOf(b.width * th.toFloat() / b.height, 0.45f * v.width)
                src.set(0, 0, b.width, b.height)
                c.drawBitmap(b, src, RectF(x, cy - th / 2f, x + w, cy + th / 2f), bitmapPaint)
                // the width may change once the thumbnail is decoded
                if (chipWidth(ch) != chipW) updateChipTarget()
            }
        } else {
            val tp = v.text
            tp.typeface = v.typeface(Weight.REGULAR)
            tp.textSize = chipTextSize
            tp.color = col
            c.drawText(chipText, x, cy + v.capHeight(tp.typeface, tp.textSize) / 2, tp)
        }
    }

    /** Confirmation of 03 §6.3 item 4: the word, then the accent-coloured text buttons "forget" and "cancel". */
    private fun drawForget(c: Canvas, word: String, top: Float, h: Float) {
        val t = v.theme
        val tp = v.text
        val regular = v.typeface(Weight.REGULAR)
        val size = TextStyles.candidate.size * m.s
        val baseline = top + h / 2 + v.capHeight(regular, size) / 2
        tp.typeface = regular
        tp.textSize = size
        tp.color = t.candidateText
        val yes = v.controller.str(R.string.forget)
        val no = v.controller.str(R.string.forget_cancel)
        val pad = m.itemPadding
        val buttonsW = tp.measureText(yes) + tp.measureText(no) + 4 * pad
        val wordAvail = v.width - buttonsW - 2 * pad
        val shown = TextUtils.ellipsize(word, tp, maxOf(0f, wordAvail), TextUtils.TruncateAt.END).toString()
        c.drawText(shown, pad, baseline, tp)
        var x = pad + tp.measureText(shown) + pad
        for ((i, label) in listOf(yes, no).withIndex()) {
            val w = tp.measureText(label) + 2 * pad
            val rect = if (i == 0) forgetYes else forgetNo
            rect.set(x, 0f, x + w, h)
            val isPressed = pressed == (if (i == 0) FORGET_YES else FORGET_NO)
            if (isPressed) {
                v.fill.color = t.candidatePressed
                c.drawRect(x + 4 * m.s, top + 11 * m.s, x + w - 4 * m.s, top + h - 5 * m.s, v.fill)
            }
            tp.color = if (isPressed) t.candidatePressedText else if (t.highContrast) t.candidateText else t.accent
            c.drawText(label, x + pad, baseline, tp)
            x += w
        }
    }

    private inline fun drawRoundButton(c: Canvas, cx: Float, cy: Float, isPressed: Boolean, pictogram: (Int, Int) -> Unit) {
        val t = v.theme
        val r = 50f * m.s
        if (isPressed) {
            v.fill.color = t.candidateText
            c.drawCircle(cx, cy, r + 2.5f * m.s, v.fill)
            pictogram(t.candidateBar, t.candidateText)
        } else {
            stroke.color = t.candidateText
            stroke.strokeWidth = 5f * m.s
            c.drawCircle(cx, cy, r, stroke)
            pictogram(t.candidateText, t.candidateBar)
        }
    }

    private fun drawItem(c: Canvas, it: StripItem, i: Int, l: Float, r: Float, top: Float, h: Float, col: Int) {
        val tp = v.text
        // all items share the baseline of the candidate style (cap height centred in the bar)
        val regular = v.typeface(Weight.REGULAR)
        val baseline = top + h / 2 + v.capHeight(regular, TextStyles.candidate.size * m.s) / 2
        when {
            it.addWord -> {
                val plusTf = v.typeface(TextStyles.candidatePlus.weight)
                tp.typeface = plusTf
                tp.textSize = TextStyles.candidatePlus.size * m.s
                tp.color = col
                val x = l + 47 * m.s
                // the cap top of "+" is 12 px above the cap top of the word
                val wordCap = v.capHeight(regular, TextStyles.candidate.size * m.s)
                val plusCap = v.capHeight(plusTf, tp.textSize)
                val plusBaseline = baseline - wordCap - 12 * m.s + plusCap
                c.drawText("+", x, plusBaseline, tp)
                val px = tp.measureText("+")
                tp.typeface = regular
                tp.textSize = TextStyles.candidate.size * m.s
                c.drawText(it.text, x + px + 5 * m.s, baseline, tp)
            }
            it.emoji -> {
                tp.typeface = v.fonts.emoji
                tp.textSize = TextStyles.candidateEmoji.size * m.s
                tp.color = col
                val w = tp.measureText(it.text)
                val fm = tp.fontMetrics
                val cy = top + 11 * m.s + (h - 11 * m.s) / 2
                c.drawText(it.text, (l + r) / 2 - w / 2, cy - (fm.ascent + fm.descent) / 2, tp)
            }
            else -> {
                val st = if (boldOf(it, i)) TextStyles.candidateBold else TextStyles.candidate
                tp.typeface = v.typeface(st.weight)
                tp.textSize = st.size * m.s
                tp.color = col
                val w = tp.measureText(it.text)
                c.drawText(it.text, (l + r) / 2 - w / 2, baseline, tp)
            }
        }
    }

    private fun drawDictation(c: Canvas, top: Float, h: Float, level: Float, partial: String?) {
        val t = v.theme
        val cx = 96f * m.s
        val cy = top + h / 2
        val scale = 1f + 0.6f * level.coerceIn(0f, 10f) / 10f
        v.fill.color = t.accent
        c.drawCircle(cx, cy, 40f * m.s * scale, v.fill)
        v.glyphs.microphone(c, cx, cy, 18f * m.s, 4f * m.s, 0xFFFFFFFF.toInt())
        val tp = v.text
        tp.typeface = v.typeface(Weight.REGULAR)
        tp.textSize = TextStyles.candidate.size * m.s
        tp.color = t.candidateText
        val avail = v.width - cx - 80f * m.s
        val s = partial ?: v.controller.str(app.metrokeys.R.string.dictation_speak)
        val shown = if (tp.measureText(s) > avail) {
            // cut at the start with an ellipsis
            var k = 0
            while (k < s.length && tp.measureText("…" + s.substring(k)) > avail) k++
            "…" + s.substring(k)
        } else s
        c.drawText(TextUtils.ellipsize(shown, tp, avail, TextUtils.TruncateAt.START).toString(), cx + 72f * m.s,
            cy + v.capHeight(tp.typeface, tp.textSize) / 2, tp)
    }

    // ================================================================ touch

    private fun hitAt(x: Float, y: Float): Int {
        val s = scroller ?: return NONE
        if (forgetWord != null) {
            return when {
                forgetYes.contains(x, y) -> FORGET_YES
                forgetNo.contains(x, y) -> FORGET_NO
                else -> NONE
            }
        }
        val cy = m.stripHeight / 2
        val hx = s.display(96f * m.s, contentLength)
        if (hypot(x - hx, y - cy) <= 60f * m.s && v.controller.canPaste) return HEADER
        if (chipSlot > 0.5f && chip != null) {
            val l = s.display(headerW, contentLength)
            if (x >= l + chipGap && x <= l + chipW - chipGap) return CHIP
        }
        if (slot > 0.5f) {
            val sx = s.display(headerW + chipSlot * chipW + 95f * m.s, contentLength)
            if (hypot(x - sx, y - cy) <= 60f * m.s) return CLIP
        }
        val cx = s.content(x, contentLength)
        for (i in itemLeft.indices) if (cx >= itemLeft[i] && cx < itemLeft[i] + itemWidth[i]) return i
        return NONE
    }

    fun down(x: Float, y: Float, now: Long) {
        touchActive = true
        downX = x
        downY = y
        dragging = false
        pressed = NONE
        if (v.controller.autofillActive) return
        if (message != null) {
            v.removeCallbacks(messageEnd)
            message = null
            touchActive = false
            v.invalidate()
            return
        }
        if (flashWord != null) {
            endFlash()
            touchActive = false
            return
        }
        if (v.controller.dictationState != null) {
            touchActive = false
            v.controller.toggleDictation()
            return
        }
        holdFired = false
        if (forgetWord == null) scroller?.touchDown(now)
        pressed = hitAt(x, y)
        if (pressed >= 0) {
            val item = content.items.getOrNull(pressed)
            if (item != null && !item.emoji && !item.addWord) v.postDelayed(holdRunnable, 450)
        }
        v.invalidate()
    }

    /** Hold on a candidate (03 §6.3 item 4): a learned word the dictionary does not know asks to be forgotten. */
    private fun onHold() {
        val i = pressed
        val item = content.items.getOrNull(i) ?: return
        if (dragging || !touchActive) return
        holdFired = true
        pressed = NONE
        if (v.controller.isForgettable(item.text)) openForget(item.text)
        v.invalidate()
    }

    private fun openForget(word: String) {
        forgetWord = word
        v.controller.feedback.play(SoundKind.FUNCTION)
        v.announce(v.controller.str(R.string.a11y_forget_question, word))
        v.onStripStructureChanged()
    }

    private fun closeForget() {
        forgetWord = null
        v.onStripStructureChanged()
    }

    fun move(x: Float, @Suppress("UNUSED_PARAMETER") y: Float, @Suppress("UNUSED_PARAMETER") now: Long) {
        if (!touchActive) return
        val s = scroller ?: return
        if (forgetWord != null) return
        if (!dragging && abs(x - downX) > m.dragStart) {
            dragging = true
            v.removeCallbacks(holdRunnable)
            pressed = NONE
            dragOrigin = x
            s.startDrag()
        }
        if (dragging) {
            s.dragTo(x - dragOrigin)
            v.invalidate()
        }
    }

    fun up(x: Float, y: Float, now: Long) {
        if (!touchActive) return
        touchActive = false
        v.removeCallbacks(holdRunnable)
        val s = scroller ?: return
        if (forgetWord != null) {
            val hit = hitAt(x, y)
            val p = pressed
            pressed = NONE
            if (hit == p) {
                when (p) {
                    FORGET_YES -> {
                        val w = forgetWord!!
                        v.controller.feedback.play(SoundKind.FUNCTION)
                        closeForget()
                        v.controller.forgetWord(w)
                    }
                    FORGET_NO -> {
                        v.controller.feedback.play(SoundKind.FUNCTION)
                        closeForget()
                    }
                }
            }
            v.invalidate()
            return
        }
        if (holdFired) {
            // a hold never picks the candidate
            holdFired = false
            s.releaseWithoutDrag(now)
            pressed = NONE
            v.invalidate()
            return
        }
        if (dragging) {
            dragging = false
            val (vx, _) = v.currentVelocity()
            s.release(-vx, now)
            dragged = true
            awaitingRest = true
            if (!s.isMoving) {
                awaitingRest = false
                lastDragInView = s.offset <= 0.5f
            }
            v.invalidate()
            return
        }
        s.releaseWithoutDrag(now)
        val hit = hitAt(x, y)
        val p = pressed
        pressed = NONE
        if (hit != p) {
            v.invalidate()
            return
        }
        when (p) {
            HEADER -> {
                if (s.offset <= 0.5f) {
                    v.controller.paste()
                    recentlyPasted = true
                    scrollHome()
                } else {
                    // partly visible: only scroll it fully into view
                    dragged = true
                    lastDragInView = true
                    recentlyPasted = false
                    s.animateTo(0f, now)
                }
            }
            CLIP -> {
                v.controller.feedback.play(SoundKind.FUNCTION)
                v.controller.toggleClipboardPanel()
            }
            CHIP -> pasteChip()
            NONE -> {}
            else -> v.controller.pick(content, p)
        }
        v.invalidate()
    }

    /** Chip tap (03 §6.11 item 4): the chip disappears and the paste header returns as "recently pasted". */
    private fun pasteChip() {
        if (!v.controller.chipPaste()) return
        recentlyPasted = true
        dragged = false
        lastCanPaste = headerState()
        updateSlotTarget()
        scrollHome()
    }

    fun cancel() {
        touchActive = false
        v.removeCallbacks(holdRunnable)
        holdFired = false
        if (dragging) scroller?.release(0f, SystemClock.uptimeMillis())
        dragging = false
        pressed = NONE
    }

    // ================================================================ accessibility

    /** Visible elements for the accessibility tree: (kind, index, bounds in view coordinates). */
    fun accessibilityNodes(): List<Triple<Int, Int, RectF>> {
        val out = ArrayList<Triple<Int, Int, RectF>>()
        val s = scroller ?: return out
        if (v.controller.autofillActive) return out
        val top = v.stripTop
        val h = m.stripHeight
        if (v.controller.dictationState != null) {
            out.add(Triple(DICTATION, 0, RectF(0f, top, v.width.toFloat(), top + h)))
            return out
        }
        if (forgetWord != null) {
            out.add(Triple(FORGET, 0, RectF(0f, top, forgetYes.left, top + h)))
            out.add(Triple(FORGET, 1, RectF(forgetYes).apply { offset(0f, top) }))
            out.add(Triple(FORGET, 2, RectF(forgetNo).apply { offset(0f, top) }))
            return out
        }
        val hx = s.display(96f * m.s, contentLength)
        if ((v.controller.canPaste || v.controller.pasteDimmed) && hx > 0) {
            out.add(Triple(HEADER, 0, RectF(hx - 50 * m.s, top, hx + 50 * m.s, top + h)))
        }
        if (chipSlot > 0.5f && chip != null) {
            val l = maxOf(0f, s.display(headerW, contentLength) + chipGap)
            val r = minOf(v.width.toFloat(), s.display(headerW + chipW, contentLength) - chipGap)
            if (r > l) out.add(Triple(CHIP, 0, RectF(l, top, r, top + h)))
        }
        if (slot > 0.5f) {
            val sx = s.display(headerW + chipSlot * chipW + 95f * m.s, contentLength)
            out.add(Triple(CLIP, 0, RectF(sx - 50 * m.s, top, sx + 50 * m.s, top + h)))
        }
        for (i in content.items.indices) {
            val l = max(0f, s.display(itemLeft[i], contentLength))
            val r = minOf(v.width.toFloat(), s.display(itemLeft[i] + itemWidth[i], contentLength))
            if (r > l) out.add(Triple(ITEM, i, RectF(l, top, r, top + h)))
        }
        return out
    }

    val items: List<StripItem> get() = content.items

    /** The word of the open forget confirmation, or null. */
    val forgetting: String? get() = forgetWord

    /** The chip's preview for accessibility ("Recently copied: …"). */
    val chipDescription: String?
        get() = chip?.let { if (it.image != null) v.controller.str(R.string.a11y_image) else it.text?.replace(Regex("\\s+"), " ")?.trim()?.take(200) }

    /** True if strip item [index] offers the "forget word" long-click action. */
    fun canForget(index: Int): Boolean {
        val item = content.items.getOrNull(index) ?: return false
        return !item.emoji && !item.addWord && v.controller.isForgettable(item.text)
    }

    fun activate(kind: Int, index: Int, long: Boolean = false) {
        when (kind) {
            HEADER -> if (v.controller.canPaste) {
                v.controller.paste()
                recentlyPasted = true
                scrollHome()
            }
            CHIP -> pasteChip()
            CLIP -> v.controller.toggleClipboardPanel()
            ITEM -> if (long) {
                if (canForget(index)) openForget(content.items[index].text)
            } else {
                v.controller.pick(content, index)
            }
            FORGET -> when (index) {
                1 -> forgetWord?.let {
                    closeForget()
                    v.controller.forgetWord(it)
                }
                2 -> closeForget()
            }
            DICTATION -> v.controller.toggleDictation()
        }
    }

    /** Scrolls the strip by one view width (accessibility scroll actions). */
    fun scrollBy(forward: Boolean) {
        val s = scroller ?: return
        s.animateTo(s.offset + (if (forward) 1 else -1) * v.width * 0.8f, SystemClock.uptimeMillis())
        v.invalidate()
    }

    companion object {
        const val NONE = -1
        const val HEADER = -2
        const val CLIP = -3
        const val FORGET_YES = -4
        const val FORGET_NO = -5
        const val CHIP = -6
        const val ITEM = 1
        const val DICTATION = 2
        const val FORGET = 3

        private const val HEADER_SUPPRESSED = 0
        private const val HEADER_DIMMED = 1
        private const val HEADER_NORMAL = 2
    }
}
