package app.metrokeys.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import app.metrokeys.engine.swipe.ShapeTrace
import app.metrokeys.ime.KeyboardController
import app.metrokeys.ime.Shift
import app.metrokeys.ime.StripContent
import app.metrokeys.keyboard.layout.Appearance
import app.metrokeys.keyboard.layout.Key
import app.metrokeys.keyboard.layout.KeyRole
import app.metrokeys.keyboard.layout.Page
import app.metrokeys.keyboard.layout.PageKind
import app.metrokeys.settings.HighContrastChoice
import app.metrokeys.settings.KeyboardSettings
import app.metrokeys.settings.NavMode
import app.metrokeys.settings.ThemeChoice
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The keyboard drawn by the app (spec 02, 03): the transparent overflow strip, the candidate strip, the key area
 * (keys, emoji page or clipboard panel), the application bar and the navigation inset, from top to bottom. It owns
 * the touch state machine of the keys (03 §1–§5), the key-press popup and the alternates picker; strip, emoji
 * grid, clipboard panel, app bar and trace are separate components drawn into this view.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, val controller: KeyboardController) : View(context) {
    var metrics: Metrics = metricsFor(context.resources.displayMetrics.widthPixels)
        private set
    var theme: Theme = Theme.create(dark = true, highContrast = false, accentIndex = Theme.DEFAULT_ACCENT)
        private set
    lateinit var fonts: Fonts
        private set
    val glyphs = Glyphs()

    val strip = StripBar(this)
    val emojiPanel = EmojiPanel(this)
    val clipPanel = ClipboardPanel(this)
    val appBar = AppBar(this)
    val traceOverlay = TraceOverlay(this)
    val a11y = KeyboardAccessibility(this)

    /** Bottom navigation inset (03 §18.1). */
    var navInset = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    /** Gesture navigation (compact mode, 03 §11). */
    var gestureNavigation = false

    var touchExploration = false
        private set

    // ---------------------------------------------------------------- regions (view coordinates)

    var overflowHeight = 0f
        private set
    val stripTop: Float get() = overflowHeight + slideOffset
    val stripBottom: Float get() = stripTop + metrics.stripHeight
    val keyTop: Float get() = stripBottom
    var keyAreaWidth = 0f
        private set
    var keyAreaHeight = 0f
        private set

    /** Height of the body (key area / emoji page / clipboard panel) currently laid out. */
    private var bodyHeight = 0f
    val bodyBottom: Float get() = keyTop + bodyHeight
    val barTop: Float get() = bodyBottom
    var barHeight = 0f
        private set

    /** Mini-keyboard slide (03 §13.3): content drawn shifted down by this offset. */
    private var slideOffset = 0f
    private var slideStart = 0L
    private var slideOpening = false
    private var sliding = false

    // ---------------------------------------------------------------- paints

    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val capCache = HashMap<Long, Float>()
    private val bounds = Rect()
    private val rf = RectF()

    init {
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        androidx.core.view.ViewCompat.setAccessibilityDelegate(this, a11y)
        val am = context.getSystemService(AccessibilityManager::class.java)
        touchExploration = am?.isTouchExplorationEnabled == true
        am?.addTouchExplorationStateChangeListener {
            touchExploration = it
            cancelTouch()
            invalidate()
        }
        reloadStyle()
    }

    /** Theme, accent, fonts and metrics from the settings (02 §8, §9; 07 §9.10). */
    fun reloadStyle() {
        val s = controller.settings
        theme = themeFor(context, s)
        fonts = Fonts(context, s.customFonts)
        capCache.clear()
        requestLayout()
        invalidate()
    }

    // ================================================================ layout (02 §2, 03 §17, §18)

    private fun metricsFor(w: Int): Metrics = metricsFor(context, controller.settings, w)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val dm = resources.displayMetrics
        metrics = metricsFor(w)
        keyAreaWidth = w.toFloat()
        keyAreaHeight = metrics.keyAreaHeight
        bodyHeight = when {
            controller.hardwareMode && !controller.miniKeyboardOpen && !sliding -> 0f
            else -> keyAreaHeight
        }
        barHeight = when {
            controller.settings.navMode == NavMode.APP_BAR -> appBar.height()
            else -> 0f
        }
        val rest = metrics.stripHeight + bodyHeight + barHeight + navInset
        val statusBar = 24f * dm.density
        val free = max(0f, dm.heightPixels - rest - statusBar)
        overflowHeight = min(metrics.maxOverflow, free)
        setMeasuredDimension(w, ceil(overflowHeight + rest).toInt())
        controller.updateGeometry()
        emojiPanel.layout()
        strip.layout()
    }

    /** Top of the visible keyboard in view coordinates (content inset, 03 §18.3). */
    val visibleTop: Int get() = overflowHeight.toInt()

    // ================================================================ controller callbacks

    fun onConfigurationChanged() {
        reloadStyle()
        cancelTouch()
        requestLayout()
        invalidate()
    }

    fun onShown() {
        reloadStyle()
        strip.onShown()
        cancelTouch()
        appBar.onShown()
        a11y.invalidateRoot()
    }

    fun onHidden() {
        cancelTouch()
        strip.onHidden()
        traceOverlay.clear()
    }

    fun onPageChanged() {
        closePicker()
        emojiPanel.onPageShown()
        a11y.invalidateRoot()
        invalidate()
    }

    fun onStrip(c: StripContent) {
        strip.setContent(c)
        a11y.invalidateRoot()
    }

    fun onAutoCorrected(word: String) = strip.flash(word)

    /** A message shown in the strip instead of a suppressed toast. */
    fun showMessage(text: String, durationMs: Long) {
        strip.showMessage(text, durationMs)
        announce(text)
    }

    /** The strip changed its elements (forget confirmation opened or closed). */
    fun onStripStructureChanged() {
        a11y.invalidateRoot()
        invalidate()
    }

    fun onFieldStateChanged() {
        strip.onFieldStateChanged()
        appBar.refresh()
        invalidate()
    }

    fun onClipboardChanged() {
        strip.onClipboardChanged()
        invalidate()
    }

    fun onPanelChanged() {
        cancelTouch()
        clipPanel.onShown()
        strip.onFieldStateChanged()
        a11y.invalidateRoot()
        invalidate()
    }

    fun onEmojiCategoryChanged() {
        emojiPanel.onCategoryChanged()
        a11y.invalidateRoot()
        invalidate()
    }

    fun onAppBarChanged() {
        appBar.refresh()
        requestLayout()
        a11y.invalidateRoot()
        invalidate()
    }

    fun onDictationChanged() {
        strip.onDictationChanged()
        invalidate()
    }

    fun onHardwareModeChanged() {
        cancelTouch()
        requestLayout()
        invalidate()
    }

    /** Mini keyboard above a hardware keyboard opens/closes with a slide (03 §13.3). */
    fun onMiniKeyboardChanged(open: Boolean) {
        cancelTouch()
        sliding = true
        slideOpening = open
        slideStart = SystemClock.uptimeMillis()
        requestLayout()
        postInvalidateOnAnimation()
    }

    private fun updateSlide(now: Long) {
        if (!sliding) {
            slideOffset = 0f
            return
        }
        val ms = if (slideOpening) 300f else 200f
        val t = (now - slideStart) / ms
        val p = if (slideOpening) Curves.logarithmic(t, 150f) else 1f - Curves.exponential(t, 150f)
        slideOffset = (1f - p) * keyAreaHeight
        if (t >= 1f) {
            sliding = false
            slideOffset = 0f
            if (!slideOpening) requestLayout()
        } else {
            postInvalidateOnAnimation()
        }
    }

    fun announce(msg: String) {
        if (touchExploration) a11y.announce(msg)
    }

    // ================================================================ text helpers

    fun typeface(w: Weight): Typeface = fonts.get(w)

    /** Ink height of "H" in [tf] at [size] px (02 §4.3.2). */
    fun capHeight(tf: Typeface, size: Float): Float {
        val k = (System.identityHashCode(tf).toLong() shl 32) or (size * 16).toLong()
        return capCache.getOrPut(k) {
            text.typeface = tf
            text.textSize = size
            text.getTextBounds("H", 0, 1, bounds)
            bounds.height().toFloat()
        }
    }

    /**
     * Draws [s] centred horizontally in [box] and with its cap height centred vertically, scaled down when wider
     * than 96 % of the box (02 §4.3).
     */
    fun drawLabel(c: Canvas, s: String, box: RectF, tf: Typeface, size: Float, color: Int, fitFraction: Float = 0.96f) {
        text.typeface = tf
        text.textSize = size
        text.color = color
        var sz = size
        val w = text.measureText(s)
        val max = box.width() * fitFraction
        if (w > max && w > 0) {
            sz = size * max / w
            text.textSize = sz
        }
        val baseline = box.centerY() + capHeight(tf, sz) / 2f
        val x = box.centerX() - text.measureText(s) / 2f
        c.drawText(s, x, baseline, text)
    }

    // ================================================================ drawing

    override fun onDraw(canvas: Canvas) = app.metrokeys.ime.Perf.section("mk:draw") { drawAll(canvas) }

    private fun drawAll(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        updateSlide(now)
        val m = metrics
        val top = stripTop
        // keyboard background below the overflow strip (02 §5.1)
        fill.color = theme.keyboardBackground
        canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), fill)
        strip.draw(canvas, now)
        if (bodyHeight > 0f || sliding) {
            canvas.save()
            canvas.clipRect(0f, keyTop, width.toFloat(), keyTop + keyAreaHeight)
            when {
                controller.clipboardPanelOpen -> clipPanel.draw(canvas, now)
                else -> drawPage(canvas, now)
            }
            traceOverlay.draw(canvas, now)
            if (touch.picker != null) {
                fill.color = theme.dimmer
                canvas.drawRect(0f, keyTop, width.toFloat(), keyTop + keyAreaHeight, fill)
            }
            canvas.restore()
        }
        if (barHeight > 0f) appBar.draw(canvas, now, barTop)
        drawCompact(canvas)
        drawPopup(canvas)
        drawPicker(canvas, now)
        // the key popup is static: it disappears through popupRunnable, not through animation frames
        if (m.s > 0 && (strip.animating(now) || emojiPanel.animating(now) || clipPanel.animating(now) || traceOverlay.animating(now) ||
                touch.picker?.animating(now) == true)
        ) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawCompact(c: Canvas) {
        if (controller.settings.navMode != NavMode.COMPACT || !gestureNavigation || navInset <= 0) return
        val y = height - navInset / 2f
        val h = navInset * 0.3f
        val col = theme.appBarIcon
        glyphs.appBarIcon(c, "hide", width * 0.10f, y, h, 2.5f * metrics.s, col)
        glyphs.appBarIcon(c, "switch", width * 0.90f, y, h, 2.5f * metrics.s, col)
    }

    /** The visible key face of [k] in view coordinates (02 §3.1). */
    fun faceOf(k: Key, out: RectF = RectF()): RectF {
        val i = metrics.keyInset
        out.set(
            k.rect.left * keyAreaWidth + i,
            keyTop + k.rect.top * keyAreaHeight + i,
            k.rect.right * keyAreaWidth - i,
            keyTop + k.rect.bottom * keyAreaHeight - i,
        )
        return out
    }

    fun cellOf(k: Key, out: RectF = RectF()): RectF {
        out.set(k.rect.left * keyAreaWidth, keyTop + k.rect.top * keyAreaHeight, k.rect.right * keyAreaWidth, keyTop + k.rect.bottom * keyAreaHeight)
        return out
    }

    val page: Page get() = controller.page

    private fun drawPage(c: Canvas, now: Long) {
        val p = page
        if (p.kind == PageKind.EMOJI) emojiPanel.drawGrid(c, now)
        for (k in p.keys) {
            if (k.role == KeyRole.EMOJI_GRID) continue
            drawKey(c, k)
        }
    }

    private fun isPressed(k: Key): Boolean = when {
        k.role == KeyRole.SHIFT -> controller.shift == Shift.CAPS_LOCK || touch.pressedKey === k
        k.role == KeyRole.EMOJI_CATEGORY -> controller.emojiCategory == k.category
        else -> touch.pressedKey === k
    }

    fun drawKey(c: Canvas, k: Key) {
        val face = faceOf(k, rf)
        val t = theme
        if (k.role == KeyRole.BLANK || !k.enabled) {
            if (k.role == KeyRole.BLANK) {
                fill.color = t.key
                c.drawRect(face, fill)
            }
            return
        }
        val pressed = isPressed(k)
        val emphasized = k.appearance == Appearance.EMPHASIZED || (k.role == KeyRole.ENTER && controller.field.enterShowsGo)
        val faceColor = when {
            pressed && emphasized -> t.emphasizedPressed
            pressed -> t.keyPressed
            emphasized -> t.emphasizedKey
            else -> t.key
        }
        val labelColor = when {
            pressed && emphasized -> t.emphasizedPressedLabel
            pressed -> t.keyPressedLabel
            emphasized -> t.emphasizedLabel
            else -> t.keyLabel
        }
        fill.color = faceColor
        c.drawRect(face, fill)
        drawKeyContent(c, k, face, labelColor, if (pressed) t.keyPressedLabel else t.keyHint)
    }

    /** Glyph name of a key, with the Enter key turned into the Go arrow for go/search/send fields (01 §10.4). */
    fun iconOf(k: Key): String? = when {
        k.role == KeyRole.ENTER && controller.field.enterShowsGo -> "go"
        else -> k.icon
    }

    fun labelOf(k: Key): String = when (k.role) {
        KeyRole.LANGUAGE -> controller.languageLabel(controller.language)
        KeyRole.DOMAIN -> controller.domains().first()
        else -> k.labelText(controller.isShifted && controller.page.kind != PageKind.EMOJI)
    }

    private fun drawKeyContent(c: Canvas, k: Key, face: RectF, color: Int, hintColor: Int) {
        val m = metrics
        val ls = m.keyLabelScale
        val icon = iconOf(k)
        if (icon != null) {
            glyphs.drawKeyGlyph(c, icon, face.centerX(), face.centerY(), 60f * ls, color)
            return
        }
        val label = labelOf(k)
        if (label.isEmpty()) return
        val set = TextStyles.of(k.textStyle)
        val style = if (k.caseFollowsShift && !controller.isShifted) set.lowercase ?: set.normal else set.normal
        // label offsets are ref px × s, not multiplied by the keyboard-height factor (02 §4.3.1)
        val box = RectF(
            face.left + k.labelInset[0] * m.s,
            face.top + k.labelInset[1] * m.s,
            face.right - k.labelInset[2] * m.s,
            face.bottom - k.labelInset[3] * m.s,
        )
        if (page.kind == PageKind.DIALPAD && k.hint != null || (page.kind == PageKind.DIALPAD && k.textStyle.startsWith("numeric"))) {
            drawDialpadKey(c, k, face, label, style, set, color, hintColor)
            return
        }
        var s = label
        if (k.role == KeyRole.EMOJI_CATEGORY && k.textStyle == "emoji-category") s = label + "︎"
        drawLabel(c, s, box, typeface(style.weight), style.size * ls, color)
    }

    /** Dial pad keys: digit left of centre from 56 px, hint letters 18 px right of its advance (02 §4.3.5). */
    private fun drawDialpadKey(c: Canvas, k: Key, face: RectF, label: String, style: TextStyle, set: StyleSet, color: Int, hintColor: Int) {
        val m = metrics
        val ls = m.keyLabelScale
        val tf = typeface(style.weight)
        val size = style.size * ls
        text.typeface = tf
        text.textSize = size
        text.color = color
        val left = face.left + (if (m.landscape) 124f else 56f) * m.s
        val baseline = face.centerY() + capHeight(tf, size) / 2
        c.drawText(label, left, baseline, text)
        val adv = text.measureText(label)
        val hint = k.hint ?: return
        val hs = set.hint ?: TextStyles.of("numeric").hint!!
        val htf = typeface(hs.weight)
        val hsize = hs.size * ls
        text.typeface = htf
        text.textSize = hsize
        text.color = hintColor
        c.drawText(hint, left + adv + 18f * m.s, face.centerY() + capHeight(htf, hsize) / 2, text)
    }

    // ================================================================ key-press popup (02 §5.5, 03 §2)

    /** Floors a coordinate to a whole device pixel (02 §5.5.3 pixel snapping). */
    private fun snap(v: Float): Float = kotlin.math.floor(v)

    private fun drawPopup(c: Canvas) {
        val ps = touch.popup ?: return
        val k = ps.key
        val m = metrics
        val t = theme
        val isLang = k.role == KeyRole.LANGUAGE
        val label = if (isLang) "" else ps.frozenLabel ?: labelOf(k)
        val large = k.role == KeyRole.DOMAIN || label.length > 3
        val cw = when {
            isLang -> m.popupLanguageW
            large -> m.popupLargeW
            else -> m.popupContentW
        }
        val ch = if (isLang) m.popupLanguageH else if (large) m.popupLargeH else m.popupContentH
        val cell = ps.cell
        val fr = m.frame
        val outerW = cw + 2 * fr
        val outerH = ch + 2 * fr
        var left = cell.centerX - outerW / 2
        left = left.coerceIn(0f, max(0f, width - outerW))
        val bottom = keyTop + cell.bottom - 0.98f * cell.height
        val top = bottom - outerH
        fill.color = t.popupFrame
        c.drawRect(snap(left), snap(top), snap(left + outerW), snap(bottom), fill)
        val content = RectF(snap(left + fr), snap(top + fr), snap(left + fr + cw), snap(bottom - fr))
        fill.color = t.popupBackground
        c.drawRect(content, fill)
        t.popupOutline?.let { drawOutline(c, content, it, 2f * m.s) }
        if (isLang) {
            drawLanguageCell(c, content, ps.frozenLabel ?: controller.language, t.popupLabel)
            return
        }
        val set = TextStyles.of(k.textStyle)
        val style = set.popup ?: TextStyles.popupFallback
        drawLabel(c, label, content, typeface(style.weight), style.size * m.s, t.popupLabel)
    }

    private fun drawOutline(c: Canvas, r: RectF, color: Int, w: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = w
            this.color = color
        }
        c.drawRect(r.left + w / 2, r.top + w / 2, r.right - w / 2, r.bottom - w / 2, p)
    }

    /** Language label with sub-label or sub-icon (02 §5.5.5, §5.7). */
    private fun drawLanguageCell(c: Canvas, content: RectF, tag: String, color: Int) {
        val m = metrics
        val info = controller.languageInfo(tag)
        val labelStyle = TextStyles.of("language").popup!!
        when {
            info.subLabel != null -> {
                val sub = RectF(content.left, content.top + 56f * m.s, content.right, content.bottom + 9f * m.s)
                val subStyle = TextStyles.languageSublabel
                val labelBox = RectF(content.left, content.top, content.right, sub.top + (sub.height()) * 0.25f)
                drawLabel(c, info.label, labelBox, typeface(labelStyle.weight), labelStyle.size * m.s, color)
                drawLabel(c, info.subLabel, RectF(sub.left, sub.centerY(), sub.right, sub.bottom), typeface(subStyle.weight), subStyle.size * m.s, color)
            }
            info.subIcon != null -> {
                val iconBox = RectF(content.left, content.top + 41f * m.s, content.right, content.bottom + 41f * m.s)
                val labelBox = RectF(content.left, content.top, content.right, content.top + content.height() * 0.5f)
                drawLabel(c, info.label, labelBox, typeface(labelStyle.weight), labelStyle.size * m.s, color)
                glyphs.keyboardPictogram(c, iconBox.centerX(), content.top + content.height() * 0.7f, 70f * m.s, color)
            }
            else -> drawLabel(c, info.label, content, typeface(labelStyle.weight), labelStyle.size * m.s, color)
        }
    }

    // ================================================================ alternates picker (02 §5.6, §7.5, 03 §3)

    /** The open picker, if any (cells in key-area px). */
    val picker: PickerModel? get() = touch.picker

    /** Final cell [i] of the picker in view coordinates (accessibility). */
    fun pickerCellRect(p: PickerModel, i: Int): RectF = p.cells[i].let { RectF(it.left, keyTop + it.top, it.right, keyTop + it.bottom) }

    private fun drawPicker(c: Canvas, now: Long) {
        val p = touch.picker ?: return
        val m = metrics
        val t = theme
        val fr = p.frame
        val long = p.items.any { it.length > 3 }
        val style = if (long) TextStyles.pickerLong else TextStyles.popupFallback
        // every cell with its own frame, the rightmost of a row first (02 §5.6.5, §7.5)
        for (i in p.drawOrder()) {
            val a = p.cellAt(i, now)
            val rc = RectF(snap(a.left), snap(keyTop + a.top), snap(a.right), snap(keyTop + a.bottom))
            fill.color = t.popupFrame
            c.drawRect(snap(a.left - fr), snap(keyTop + a.top - fr), snap(a.right + fr), snap(keyTop + a.bottom + fr), fill)
            val hl = i == p.highlighted
            fill.color = if (hl) t.pickerHighlight else t.pickerItem
            c.drawRect(rc, fill)
            if (hl) t.pickerHighlightOutline?.let { drawOutline(c, rc, it, 2f * m.s) }
            val col = if (hl) t.pickerHighlightLabel else t.pickerItemLabel
            if (p.language) drawLanguageCell(c, rc, p.items[i], col)
            else drawLabel(c, p.items[i], rc, typeface(style.weight), style.size * m.s, col)
        }
    }

    fun closePicker() = touch.closePicker()

    // ================================================================ touch (03 §1)

    private enum class Region { NONE, STRIP, KEYS, EMOJI_GRID, CLIP_PANEL, BAR, COMPACT, PICKER }

    private var region = Region.NONE
    private var pointerId = -1
    private var velocity: android.view.VelocityTracker? = null

    private val touchClock = object : TouchClock {
        override fun now(): Long = SystemClock.uptimeMillis()
        override fun post(task: Runnable, delayMs: Long) {
            removeCallbacks(task)
            postDelayed(task, delayMs)
        }
        override fun cancel(task: Runnable) {
            removeCallbacks(task)
        }
    }

    private val touchHost = object : KeyTouch.Host {
        override val page: Page get() = controller.page
        override val keyAreaWidth: Float get() = this@KeyboardView.keyAreaWidth
        override val keyAreaHeight: Float get() = this@KeyboardView.keyAreaHeight
        override val scale: Float get() = metrics.s
        override val fastTypingField: Boolean get() = controller.field.fastTypingExclusion
        override val shapeWritingActive: Boolean get() = controller.shapeWritingActive
        override fun keyChoiceAllowed(): Boolean = controller.keyChoiceAllowed() && controller.currentWordNonEmpty()
        override fun prefixClass(k: Key): Int = app.metrokeys.ime.Perf.section("mk:chooseKey") { controller.prefixClass(k.output!!.lowercase()) }
        override fun touchDown(k: Key) = controller.keyDown(k)
        override fun pickerTap() = controller.pickerTapFeedback()
        override fun commit(k: Key, x: Float, y: Float) = controller.keyCommit(k, x, y)
        override fun repeat(k: Key) = controller.keyRepeat(k)
        override fun wordDelete() = controller.wordDelete()
        override fun holdOutput(k: Key) = controller.holdOutput(k)
        override fun pickerItems(k: Key): List<String>? = controller.pickerItems(k)
        override fun pickerCellSize(k: Key, items: List<String>): Pair<Float, Float> {
            val m = metrics
            return when {
                k.role == KeyRole.LANGUAGE -> m.popupLanguageW to m.popupLanguageH
                items.any { it.length > 3 } -> m.popupLargeW to m.popupLargeH
                else -> m.popupContentW to m.popupContentH
            }
        }
        override fun pickerOpened(items: List<String>) {
            controller.pickerOpened(items)
            a11y.invalidateRoot()
            controller.onPickerChanged()
        }
        override fun pickerClosed() {
            controller.onPickerChanged()
            a11y.invalidateRoot()
        }
        override fun pickerCommit(k: Key, item: String) = controller.pickerCommit(k, item)
        override fun popupSnapshot(k: Key): String = if (k.role == KeyRole.LANGUAGE) controller.language else labelOf(k)
        override fun traceStart(startKey: Key, xs: FloatArrayList, ys: FloatArrayList, ts: LongArrayList, downTime: Long, drawFrom: Int) {
            traceOverlay.start(xs, ys, ts, downTime, drawFrom)
            controller.swipeStarted(startKey, xs.toArray(), ys.toArray(), ts.toArray(), downTime)
        }
        override fun traceAdd(x: Float, y: Float, t: Long) {
            traceOverlay.add(x, y, t)
            controller.swipeSample(x, y, t)
        }
        override fun traceEnd() = traceOverlay.end()
        override fun traceAborted() = controller.swipeAborted()
        override fun swipe(xs: FloatArray, ys: FloatArray, ts: LongArray) = controller.swipe(ShapeTrace(xs, ys, ts))
        override fun activeKeyChanged(k: Key) = a11y.onActiveKey(k)
        override fun invalidate() = this@KeyboardView.invalidate()
    }

    /** The key-area touch state machine (03 §1–§5). */
    val touch = KeyTouch(touchHost, touchClock)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (sliding) return true
        val now = ev.eventTime
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = ev.actionIndex
                if (pointerId != -1) {
                    val old = ev.findPointerIndex(pointerId).coerceAtLeast(0)
                    release(ev.getX(old), ev.getY(old), now, secondFinger = true)
                }
                pointerId = ev.getPointerId(idx)
                velocity?.recycle()
                velocity = android.view.VelocityTracker.obtain().also { it.addMovement(ev) }
                down(ev.getX(idx), ev.getY(idx), now)
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = ev.findPointerIndex(pointerId)
                if (idx < 0) return true
                velocity?.addMovement(ev)
                for (h in 0 until ev.historySize) move(ev.getHistoricalX(idx, h), ev.getHistoricalY(idx, h), ev.getHistoricalEventTime(h))
                move(ev.getX(idx), ev.getY(idx), now)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = ev.actionIndex
                if (ev.getPointerId(idx) == pointerId) {
                    velocity?.addMovement(ev)
                    release(ev.getX(idx), ev.getY(idx), now, secondFinger = false)
                    pointerId = -1
                }
            }
            MotionEvent.ACTION_CANCEL -> cancelTouch()
        }
        return true
    }

    /** Velocity of the tracked pointer in px/s. */
    fun currentVelocity(): Pair<Float, Float> {
        val v = velocity ?: return 0f to 0f
        v.computeCurrentVelocity(1000)
        return v.getXVelocity(pointerId) to v.getYVelocity(pointerId)
    }

    private fun down(x: Float, y: Float, now: Long) {
        // an open picker takes the next touch-down anywhere on the keyboard (03 §3.4 item 6)
        if (touch.picker != null) {
            region = Region.PICKER
            touch.down(x, y - keyTop, now)
            return
        }
        if (y < stripTop) {
            region = Region.NONE
            return
        }
        if (y < stripBottom) {
            region = Region.STRIP
            strip.down(x, y - stripTop, now)
            return
        }
        if (y < bodyBottom || (bodyHeight > 0 && y <= bodyBottom + metrics.outsideTolerance && barHeight == 0f && y < height - navInset)) {
            when {
                bodyHeight == 0f -> region = Region.NONE
                controller.clipboardPanelOpen -> {
                    region = Region.CLIP_PANEL
                    clipPanel.down(x, y - keyTop, now)
                }
                page.kind == PageKind.EMOJI && y - keyTop < 0.75f * keyAreaHeight -> {
                    region = Region.EMOJI_GRID
                    emojiPanel.down(x, y - keyTop, now)
                }
                else -> {
                    region = Region.KEYS
                    touch.down(x, y - keyTop, now)
                }
            }
            return
        }
        if (barHeight > 0f && y < barTop + barHeight) {
            region = Region.BAR
            appBar.down(x, y - barTop, now)
            return
        }
        if (controller.settings.navMode == NavMode.COMPACT && gestureNavigation && y >= height - navInset) {
            region = Region.COMPACT
            controller.feedback.play(SoundKind.FUNCTION)
            return
        }
        region = Region.NONE
    }

    private fun move(x: Float, y: Float, t: Long) {
        when (region) {
            Region.STRIP -> strip.move(x, y - stripTop, t)
            Region.EMOJI_GRID -> emojiPanel.move(x, y - keyTop, t)
            Region.CLIP_PANEL -> clipPanel.move(x, y - keyTop, t)
            Region.BAR -> appBar.move(x, y - barTop, t)
            Region.KEYS, Region.PICKER -> touch.move(x, y - keyTop, t)
            else -> {}
        }
    }

    private fun release(x: Float, y: Float, t: Long, secondFinger: Boolean) {
        when (region) {
            Region.STRIP -> strip.up(x, y - stripTop, t)
            Region.EMOJI_GRID -> emojiPanel.up(x, y - keyTop, t)
            Region.CLIP_PANEL -> clipPanel.up(x, y - keyTop, t)
            Region.BAR -> appBar.up(x, y - barTop, t)
            Region.KEYS, Region.PICKER -> touch.up(x, y - keyTop, t, secondFinger)
            Region.COMPACT -> {
                if (x < width * 0.25f) controller.runAction("hide") else if (x > width * 0.75f) controller.runAction("switch")
            }
            else -> {}
        }
        region = Region.NONE
    }

    fun cancelTouch() {
        touch.cancel()
        strip.cancel()
        emojiPanel.cancel()
        clipPanel.cancel()
        appBar.cancel()
        region = Region.NONE
        pointerId = -1
        invalidate()
    }

    /** Accessibility activation of a key: down + up at its centre; [long] opens the picker at once (03 §19.3). */
    fun activateKey(k: Key, long: Boolean) {
        val c = touch.cells.cell(k)
        val x = c.centerX
        val y = (c.top + c.bottom) / 2
        val t = SystemClock.uptimeMillis()
        touch.down(x, y, t)
        if (long) {
            if (k.role == KeyRole.SHIFT) {
                controller.lockShift()
                touch.cancel()
                return
            }
            touch.holdNow()
        }
        touch.up(x, y, t)
    }

    fun activatePickerItem(i: Int) = touch.commitPickerItem(i)

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = a11y.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun onDetachedFromWindow() {
        cancelTouch()
        super.onDetachedFromWindow()
    }

    companion object {
        /** The metrics of a keyboard [w] px wide with the settings [s]; also used before the view is laid out. */
        fun metricsFor(context: Context, s: KeyboardSettings, w: Int = context.resources.displayMetrics.widthPixels): Metrics {
            val dm = context.resources.displayMetrics
            val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val shortSide = min(dm.widthPixels, dm.heightPixels)
            return Metrics(w, landscape, shortSide, s.keyboardHeightPercent / 100f, dm.xdpi)
        }

        /** The keyboard theme of the settings [s] (02 §8, §9; 07 §9.10). */
        fun themeFor(context: Context, s: KeyboardSettings): Theme {
            val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val dark = when (s.theme) {
                ThemeChoice.DARK -> true
                ThemeChoice.LIGHT -> false
                ThemeChoice.SYSTEM -> night
            }
            val hc = when (s.highContrast) {
                HighContrastChoice.ON -> true
                HighContrastChoice.OFF -> false
                HighContrastChoice.SYSTEM -> Theme.systemHighContrast(context)
            }
            return Theme.create(dark, hc, s.accentIndex)
        }
    }
}
