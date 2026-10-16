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
import app.metrokeys.engine.swipe.ShapeWriter
import app.metrokeys.ime.KeyboardController
import app.metrokeys.ime.Shift
import app.metrokeys.ime.StripContent
import app.metrokeys.keyboard.layout.Appearance
import app.metrokeys.keyboard.layout.Key
import app.metrokeys.keyboard.layout.KeyRole
import app.metrokeys.keyboard.layout.Page
import app.metrokeys.keyboard.layout.PageKind
import app.metrokeys.keyboard.layout.hitTest
import app.metrokeys.settings.HighContrastChoice
import app.metrokeys.settings.NavMode
import app.metrokeys.settings.ThemeChoice
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
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
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = when (s.theme) {
            ThemeChoice.DARK -> true
            ThemeChoice.LIGHT -> false
            ThemeChoice.SYSTEM -> night
        }
        val hc = when (s.highContrast) {
            HighContrastChoice.ON -> true
            HighContrastChoice.OFF -> false
            HighContrastChoice.SYSTEM -> systemHighContrast()
        }
        theme = Theme.create(dark, hc, s.accentIndex)
        fonts = Fonts(context, s.customFonts)
        capCache.clear()
        requestLayout()
        invalidate()
    }

    private fun systemHighContrast(): Boolean = Theme.systemHighContrast(context)

    // ================================================================ layout (02 §2, 03 §17, §18)

    private fun metricsFor(w: Int): Metrics {
        val dm = resources.displayMetrics
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val shortSide = min(dm.widthPixels, dm.heightPixels)
        return Metrics(w, landscape, shortSide, controller.settings.keyboardHeightPercent / 100f, dm.xdpi)
    }

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
            if (picker != null) {
                fill.color = theme.dimmer
                canvas.drawRect(0f, keyTop, width.toFloat(), keyTop + keyAreaHeight, fill)
            }
            canvas.restore()
        }
        if (barHeight > 0f) appBar.draw(canvas, now, barTop)
        drawCompact(canvas)
        drawPopup(canvas, now)
        drawPicker(canvas, now)
        // the key popup is static: it disappears through popupRunnable, not through animation frames
        if (m.s > 0 && (strip.animating(now) || emojiPanel.animating(now) || clipPanel.animating(now) || traceOverlay.animating(now) ||
                picker?.let { now - it.openTime < 320 } == true)
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
        tracing -> false
        k.role == KeyRole.SHIFT -> controller.shift == Shift.CAPS_LOCK || activeKey === k
        k.role == KeyRole.EMOJI_CATEGORY -> controller.emojiCategory == k.category
        else -> activeKey === k
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

    private var popupKey: Key? = null
    private var popupHideAt = 0L
    private val popupRunnable = Runnable { popupKey = null; invalidate() }

    private fun popupKeyNow(now: Long): Key? {
        if (picker != null || tracing) return null
        val a = activeKey
        if (a != null && a.popup && !pageSwitchLock) return a
        return if (now < popupHideAt) popupKey else null
    }

    private fun drawPopup(c: Canvas, now: Long) {
        val k = popupKeyNow(now) ?: return
        val m = metrics
        val t = theme
        val label = labelOf(k)
        val isLang = k.role == KeyRole.LANGUAGE
        val large = k.role == KeyRole.DOMAIN || label.length > 3
        val cw = when {
            isLang -> m.popupLanguageW
            large -> m.popupLargeW
            else -> m.popupContentW
        }
        val ch = if (isLang) m.popupLanguageH else if (large) m.popupLargeH else m.popupContentH
        val cell = cellOf(k)
        val fr = m.frame
        val outerW = cw + 2 * fr
        val outerH = ch + 2 * fr
        var left = cell.centerX() - outerW / 2
        left = left.coerceIn(0f, max(0f, width - outerW))
        val bottom = cell.bottom - 0.98f * cell.height()
        val top = bottom - outerH
        fill.color = t.popupFrame
        c.drawRect(left, top, left + outerW, bottom, fill)
        val content = RectF(left + fr, top + fr, left + fr + cw, bottom - fr)
        fill.color = t.popupBackground
        c.drawRect(content, fill)
        t.popupOutline?.let { drawOutline(c, content, it, 2f * m.s) }
        if (isLang) {
            drawLanguageCell(c, content, controller.language, t.popupLabel)
            return
        }
        val set = TextStyles.of(k.textStyle)
        val ps = set.popup ?: TextStyles.popupFallback
        drawLabel(c, label, content, typeface(ps.weight), ps.size * m.s, t.popupLabel)
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

    // ================================================================ alternates picker (02 §5.6, 03 §3)

    class Picker(
        val key: Key,
        val items: List<String>,
        val cells: List<RectF>,
        /** Row index per item: 0 = lower row, 1 = upper row. */
        val rows: IntArray,
        val openTime: Long,
        val language: Boolean,
        val downX: Float,
        val downY: Float,
    ) {
        var highlighted = 0
        var moved = false
        var sticky = false
        var stickyUntil = 0L
    }

    var picker: Picker? = null
        private set
    private val pickerTimeout = Runnable {
        if (picker?.sticky == true && !touchExploration) {
            closePicker()
            invalidate()
        }
    }

    private fun openPicker(k: Key, items: List<String>, downX: Float, downY: Float) {
        val m = metrics
        val lang = k.role == KeyRole.LANGUAGE
        val long = items.any { it.length > 3 }
        val cw = if (lang) m.popupLanguageW else if (long) m.popupLargeW else m.popupContentW
        val ch = if (lang) m.popupLanguageH else if (long) m.popupLargeH else m.popupContentH
        val fr = m.frame
        val pitch = cw + fr
        val cap = max(1, ((width - fr) / pitch).toInt())
        val list = items.take(2 * cap)
        val lowerCount = if (list.size > cap) ceil(list.size / 2.0).toInt() else list.size
        val cell = cellOf(k)
        val lowerBottom = cell.bottom - 0.98f * cell.height() - fr
        val cells = ArrayList<RectF>()
        val rows = IntArray(list.size)
        fun placeRow(count: Int, bottom: Float): List<RectF> {
            val x0 = cell.centerX() - cw / 2
            val rightEdge = x0 + (count - 1) * pitch + cw + fr
            val leftEdge = x0 - (count - 1) * pitch - fr
            val out = ArrayList<RectF>()
            when {
                rightEdge <= width && x0 - fr >= 0 -> for (i in 0 until count) out.add(RectF(x0 + i * pitch, bottom - ch, x0 + i * pitch + cw, bottom))
                leftEdge >= 0 && x0 + cw + fr <= width -> for (i in 0 until count) out.add(RectF(x0 - i * pitch, bottom - ch, x0 - i * pitch + cw, bottom))
                else -> {
                    val rowW = count * pitch + fr
                    val start = max(0f, min(x0 - fr, width - rowW)) + fr
                    for (i in 0 until count) out.add(RectF(start + i * pitch, bottom - ch, start + i * pitch + cw, bottom))
                }
            }
            return out
        }
        cells.addAll(placeRow(lowerCount, lowerBottom))
        if (list.size > lowerCount) {
            cells.addAll(placeRow(list.size - lowerCount, lowerBottom - ch - fr))
            for (i in lowerCount until list.size) rows[i] = 1
        }
        picker = Picker(k, list, cells, rows, SystemClock.uptimeMillis(), lang, downX, downY)
        controller.pickerOpened(list)
        a11y.invalidateRoot()
        controller.onPickerChanged()
        invalidate()
    }

    fun closePicker() {
        if (picker == null) return
        picker = null
        removeCallbacks(pickerTimeout)
        controller.onPickerChanged()
        a11y.invalidateRoot()
    }

    /** Current (animated) cell rectangle of picker item [i] (02 §7.5). */
    fun pickerCell(p: Picker, i: Int, now: Long): RectF {
        val f = p.cells[i]
        val first = p.cells[0]
        val u = ((now - p.openTime - 100f) / 200f).coerceIn(0f, 1f)
        val dx = (first.left - f.left) * (1 - u)
        return RectF(f.left + dx, f.top, f.right + dx, f.bottom)
    }

    private fun drawPicker(c: Canvas, now: Long) {
        val p = picker ?: return
        val m = metrics
        val t = theme
        val fr = m.frame
        var l = Float.MAX_VALUE
        var tp = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        val rects = p.items.indices.map { pickerCell(p, it, now) }
        for (rc in rects) {
            l = min(l, rc.left); tp = min(tp, rc.top); r = max(r, rc.right); b = max(b, rc.bottom)
        }
        fill.color = t.popupFrame
        c.drawRect(l - fr, tp - fr, r + fr, b + fr, fill)
        val long = p.items.any { it.length > 3 }
        val style = if (long) TextStyles.pickerLong else TextStyles.popupFallback
        for (i in p.items.indices.reversed()) {
            val rc = rects[i]
            val hl = i == p.highlighted
            fill.color = if (hl) t.pickerHighlight else t.pickerItem
            c.drawRect(rc, fill)
            if (hl) t.pickerHighlightOutline?.let { drawOutline(c, rc, it, 2f * m.s) }
            val col = if (hl) t.pickerHighlightLabel else t.pickerItemLabel
            if (p.language) drawLanguageCell(c, rc, p.items[i], col)
            else drawLabel(c, p.items[i], rc, typeface(style.weight), style.size * m.s, col)
        }
    }

    /** Highlight by x within the active row (03 §3.4). */
    private fun pickerHighlight(p: Picker, x: Float, y: Float) {
        val lowerTop = p.cells[0].top
        val pitch = p.cells[0].height() + metrics.frame
        val row = if (p.rows.any { it == 1 } && y < lowerTop - pitch / 4) 1 else 0
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in p.items.indices) {
            if (p.rows[i] != row) continue
            val rc = p.cells[i]
            val d = if (x >= rc.left && x < rc.right) 0f else min(abs(x - rc.left), abs(x - rc.right))
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        if (best >= 0 && best != p.highlighted) {
            p.highlighted = best
            p.moved = true
            invalidate()
        }
    }

    private fun pickerPanelContains(p: Picker, x: Float, y: Float): Boolean {
        val fr = metrics.frame
        return p.cells.any { x >= it.left - fr && x <= it.right + fr && y >= it.top - fr && y <= it.bottom + fr }
    }

    private fun commitPicker(p: Picker) {
        val item = p.items[p.highlighted]
        closePicker()
        controller.pickerCommit(p.key, item)
        invalidate()
    }

    // ================================================================ touch (03 §1)

    private enum class Region { NONE, STRIP, KEYS, EMOJI_GRID, CLIP_PANEL, BAR, COMPACT, PICKER }

    private var region = Region.NONE
    private var pointerId = -1
    var activeKey: Key? = null
        private set
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private val hysteresis = RectF()
    private var pageSwitchLock = false
    private var pageSwitchThreshold = 0f
    private var consumed = false
    private var repeating = false
    private var repeatStart = 0L
    private var wordDeleting = false
    private var traceCandidate = false
    var tracing = false
        private set
    private val traceX = FloatArrayList()
    private val traceY = FloatArrayList()
    private val traceT = LongArrayList()
    private var stickyPickerTap = false
    private var velocity: android.view.VelocityTracker? = null

    private val holdRunnable = Runnable { onHold() }
    private val repeatRunnable = object : Runnable {
        override fun run() {
            val k = activeKey ?: return
            val now = SystemClock.uptimeMillis()
            if (k.role == KeyRole.BACKSPACE && now - repeatStart >= 3000) {
                wordDeleting = true
                controller.wordDelete()
                postDelayed(this, 200)
            } else {
                controller.keyRepeat(k)
                postDelayed(this, 83)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (sliding) return true
        val now = ev.eventTime
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = ev.actionIndex
                if (pointerId != -1) release(ev.getX(ev.findPointerIndex(pointerId).coerceAtLeast(0)), ev.getY(ev.findPointerIndex(pointerId).coerceAtLeast(0)), now, secondFinger = true)
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
        downX = x
        downY = y
        downTime = now
        val p = picker
        if (p != null) {
            if (p.sticky && pickerPanelContains(p, x, y)) {
                region = Region.PICKER
                stickyPickerTap = true
                removeCallbacks(pickerTimeout)
                pickerHighlightAt(p, x, y)
                return
            }
            closePicker()
            invalidate()
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
        if (y < bodyBottom || (bodyHeight > 0 && y < bodyBottom + metrics.outsideTolerance && barHeight == 0f && y < height - navInset)) {
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
                    keyDown(x, y, now)
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

    private fun pickerHighlightAt(p: Picker, x: Float, y: Float) {
        for (i in p.items.indices) {
            val rc = p.cells[i]
            if (x >= rc.left - metrics.frame && x <= rc.right + metrics.frame && y >= rc.top - metrics.frame && y <= rc.bottom + metrics.frame) {
                p.highlighted = i
                invalidate()
                return
            }
        }
    }

    private fun move(x: Float, y: Float, t: Long) {
        when (region) {
            Region.STRIP -> strip.move(x, y - stripTop, t)
            Region.EMOJI_GRID -> emojiPanel.move(x, y - keyTop, t)
            Region.CLIP_PANEL -> clipPanel.move(x, y - keyTop, t)
            Region.BAR -> appBar.move(x, y - barTop, t)
            Region.KEYS -> keyMove(x, y, t)
            Region.PICKER -> picker?.let { pickerHighlightAt(it, x, y) }
            else -> {}
        }
    }

    private fun release(x: Float, y: Float, t: Long, secondFinger: Boolean) {
        when (region) {
            Region.STRIP -> strip.up(x, y - stripTop, t)
            Region.EMOJI_GRID -> emojiPanel.up(x, y - keyTop, t)
            Region.CLIP_PANEL -> clipPanel.up(x, y - keyTop, t)
            Region.BAR -> appBar.up(x, y - barTop, t)
            Region.KEYS -> keyUp(x, y, t, secondFinger)
            Region.PICKER -> picker?.let { if (stickyPickerTap) commitPicker(it) }
            Region.COMPACT -> {
                if (x < width * 0.25f) controller.runAction("hide") else if (x > width * 0.75f) controller.runAction("switch")
            }
            else -> {}
        }
        stickyPickerTap = false
        region = Region.NONE
    }

    fun cancelTouch() {
        removeCallbacks(holdRunnable)
        removeCallbacks(repeatRunnable)
        if (tracing) traceOverlay.end()
        tracing = false
        traceCandidate = false
        activeKey = null
        repeating = false
        pageSwitchLock = false
        strip.cancel()
        emojiPanel.cancel()
        clipPanel.cancel()
        appBar.cancel()
        closePicker()
        region = Region.NONE
        pointerId = -1
        invalidate()
    }

    // ---------------------------------------------------------------- keys

    private fun hitKey(x: Float, y: Float): Key? =
        page.hitTest(x, y - keyTop, keyAreaWidth, keyAreaHeight, metrics.outsideTolerance) { it.role == KeyRole.EMOJI_GRID }

    private fun keyDown(x: Float, y: Float, now: Long) {
        consumed = false
        repeating = false
        wordDeleting = false
        tracing = false
        pageSwitchLock = false
        var k = hitKey(x, y) ?: run {
            activeKey = null
            return
        }
        k = chooseKey(k, x, y)
        when (k.role) {
            KeyRole.PAGE, KeyRole.EMOJI -> {
                pageSwitchThreshold = k.rect.width * keyAreaWidth / 2
                controller.keyDown(k)
                activeKey = null
                pageSwitchLock = true
                invalidate()
                return
            }
            KeyRole.EMOJI_CATEGORY -> {
                controller.keyDown(k)
                activeKey = null
                consumed = true
                invalidate()
                return
            }
            else -> {}
        }
        controller.keyDown(k)
        setActive(k, x, y)
        if (k.role == KeyRole.SHIFT) consumed = true
        traceCandidate = controller.shapeWritingActive && k.isLetter &&
            ShapeWriter.isSwipeCandidate(y - keyTop, keyAreaHeight, true)
        traceX.clear(); traceY.clear(); traceT.clear()
        if (traceCandidate) addTrace(x, y, now)
    }

    private fun setActive(k: Key, x: Float, y: Float) {
        activeKey = k
        val cell = cellOf(k)
        hysteresis.set(min(cell.left, x), min(cell.top, y), max(cell.right, x), max(cell.bottom, y))
        hysteresis.inset(-metrics.moveTolerance, -metrics.moveTolerance)
        removeCallbacks(holdRunnable)
        removeCallbacks(repeatRunnable)
        repeating = false
        if (k.role != KeyRole.SHIFT) postDelayed(holdRunnable, k.longPressMs.toLong())
        popupKey = if (k.popup) k else null
        a11y.onActiveKey(k)
        invalidate()
    }

    /** Key choice at touch-down (03 §1.1a). */
    private fun chooseKey(touched: Key, x: Float, y: Float): Key = app.metrokeys.ime.Perf.section("mk:chooseKey") { chooseKeyNow(touched, x, y) }

    private fun chooseKeyNow(touched: Key, x: Float, y: Float): Key {
        if (!touched.isLetter || !controller.keyChoiceAllowed() || !controller.currentWordNonEmpty()) return touched
        val own = controller.prefixClass(touched.output!!.lowercase())
        if (own >= 0) return touched
        val max = 13f * metrics.s
        val close = 7f * metrics.s
        var best: Key? = null
        var bestScore = Int.MIN_VALUE
        var bestClass = -1
        val ky = y - keyTop
        for (k in page.keys) {
            if (!k.isLetter || k === touched) continue
            val l = k.rect.left * keyAreaWidth
            val r = k.rect.right * keyAreaWidth
            val t = k.rect.top * keyAreaHeight
            val b = k.rect.bottom * keyAreaHeight
            val dx = max(max(l - x, 0f), x - r)
            val dy = max(max(t - ky, 0f), ky - b)
            val d = max(dx, dy)
            if (d > max) continue
            val score = if (d <= close) -1500 else -2500
            val cls = controller.prefixClass(k.output!!.lowercase())
            if (cls < 0) continue
            if (score > bestScore || (score == bestScore && cls > bestClass)) {
                best = k
                bestScore = score
                bestClass = cls
            }
        }
        return best ?: touched
    }

    private fun addTrace(x: Float, y: Float, t: Long) {
        traceX.add(x)
        traceY.add(y - keyTop)
        traceT.add(t - downTime)
    }

    private fun keyMove(x: Float, y: Float, t: Long) {
        if (picker != null) {
            val p = picker!!
            if (abs(x - p.downX) > 16 * metrics.s || abs(y - p.downY) > 16 * metrics.s) p.moved = true
            pickerHighlight(p, x, y)
            return
        }
        if (tracing) {
            addTrace(x, y, t)
            traceOverlay.add(x, y - keyTop, t)
            return
        }
        if (traceCandidate) {
            addTrace(x, y, t)
            if (ShapeWriter.isSwipeStart(x - downX, y - downY, keyAreaWidth, keyAreaHeight)) {
                tracing = true
                removeCallbacks(holdRunnable)
                removeCallbacks(repeatRunnable)
                activeKey = null
                popupKey = null
                traceOverlay.start(traceX, traceY, traceT, downTime)
                invalidate()
                return
            }
        }
        if (pageSwitchLock) {
            if (hypot(x - downX, y - downY) < pageSwitchThreshold) return
            pageSwitchLock = false
            val k = hitKey(x, y) ?: return
            if (k.role == KeyRole.PAGE || k.role == KeyRole.EMOJI || k.role == KeyRole.SHIFT) return
            setActive(k, x, y)
            return
        }
        val a = activeKey ?: return
        if (hysteresis.contains(x, y)) return
        val k = hitKey(x, y) ?: return
        if (k === a) return
        if (k.role == KeyRole.PAGE || k.role == KeyRole.EMOJI || k.role == KeyRole.SHIFT || k.role == KeyRole.EMOJI_CATEGORY) return
        if (a.role == KeyRole.SHIFT) return
        if (repeating) {
            repeating = false
            consumed = true
        }
        setActive(k, x, y)
        consumed = false
    }

    private fun keyUp(x: Float, y: Float, t: Long, secondFinger: Boolean) {
        removeCallbacks(holdRunnable)
        removeCallbacks(repeatRunnable)
        val p = picker
        if (p != null) {
            if (p.moved || secondFinger) {
                commitPicker(p)
            } else {
                p.sticky = true
                if (!touchExploration) postDelayed(pickerTimeout, 1000)
            }
            activeKey = null
            invalidate()
            return
        }
        if (tracing) {
            tracing = false
            traceOverlay.end()
            if (traceX.size >= 2) controller.swipe(ShapeTrace(traceX.toArray(), traceY.toArray(), traceT.toArray()))
            invalidate()
            return
        }
        val k = activeKey
        activeKey = null
        if (k != null) {
            popupKey = k
            popupHideAt = SystemClock.uptimeMillis() + 75
            removeCallbacks(popupRunnable)
            postDelayed(popupRunnable, 76)
            if (!consumed && !repeating) controller.keyCommit(k, x, y - keyTop)
        }
        repeating = false
        pageSwitchLock = false
        invalidate()
    }

    private fun onHold() {
        val k = activeKey ?: return
        if (tracing) return
        if (k.holdOutput != null) {
            controller.holdOutput(k)
            consumed = true
            invalidate()
            return
        }
        val items = controller.pickerItems(k)
        if (items != null) {
            traceCandidate = false
            openPicker(k, items, downX, downY)
            return
        }
        if (k.repeat) {
            repeating = true
            traceCandidate = false
            repeatStart = SystemClock.uptimeMillis()
            controller.keyRepeat(k)
            postDelayed(repeatRunnable, 83)
        }
    }

    /** Accessibility activation of a key: down + up at its centre; [long] opens the picker at once (03 §19.3). */
    fun activateKey(k: Key, long: Boolean) {
        val c = cellOf(k)
        val t = SystemClock.uptimeMillis()
        region = Region.KEYS
        keyDown(c.centerX(), c.centerY(), t)
        if (long) {
            if (k.role == KeyRole.SHIFT) {
                controller.lockShift()
                activeKey = null
                region = Region.NONE
                return
            }
            removeCallbacks(holdRunnable)
            onHold()
            if (picker != null) {
                picker!!.sticky = true
                activeKey = null
                region = Region.NONE
                return
            }
        }
        keyUp(c.centerX(), c.centerY(), t, secondFinger = false)
        region = Region.NONE
    }

    fun activatePickerItem(i: Int) {
        val p = picker ?: return
        p.highlighted = i
        commitPicker(p)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = a11y.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun onDetachedFromWindow() {
        cancelTouch()
        super.onDetachedFromWindow()
    }
}

/** Growable primitive arrays for trace samples. */
class FloatArrayList {
    private var a = FloatArray(256)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    operator fun get(i: Int): Float = a[i]

    fun clear() {
        size = 0
    }

    fun toArray(): FloatArray = a.copyOf(size)
}

class LongArrayList {
    private var a = LongArray(256)
    var size = 0
        private set

    fun add(v: Long) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    operator fun get(i: Int): Long = a[i]

    fun clear() {
        size = 0
    }

    fun toArray(): LongArray = a.copyOf(size)
}
