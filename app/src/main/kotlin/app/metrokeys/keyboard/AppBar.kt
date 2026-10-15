package app.metrokeys.keyboard

import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.TextUtils
import app.metrokeys.R
import app.metrokeys.ime.AppBarButton
import kotlin.math.abs

/**
 * The application bar below the keys (03 §12, 02 §5.11): three round buttons with tap and hold bindings, the "…"
 * dots, and the expanded state with a label row and a three-item menu. Coordinates relative to the bar top.
 */
class AppBar(private val v: KeyboardView) {
    private var buttons: List<AppBarButton> = emptyList()
    private var pressed = NONE
    private var touchActive = false
    private var holdFired = false
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bounds = android.graphics.Rect()
    private val holdRunnable = Runnable {
        val b = buttons.getOrNull(pressed) ?: return@Runnable
        holdFired = true
        pressed = NONE
        v.controller.appBarHold(b)
        v.invalidate()
    }

    private val m: Metrics get() = v.metrics
    private val baseH: Float get() = 126f * m.s
    private val expanded: Boolean get() = v.controller.appBarExpanded

    /** Expanded: label row 48, padding 12, three menu rows of 96, padding 12 (02 §5.11), 360 in total. */
    fun height(): Float = baseH + if (expanded) (LABEL_ROW + MENU_PAD + 3 * MENU_ROW + MENU_PAD) * m.s else 0f

    private fun menuTop(top: Float, i: Int): Float = top + baseH + (LABEL_ROW + MENU_PAD + i * MENU_ROW) * m.s

    fun onShown() = refresh()

    fun refresh() {
        buttons = v.controller.appBarButtons()
    }

    private fun centreX(i: Int): Float = v.width / 2f + (i - 1) * 148f * m.s

    fun draw(c: Canvas, @Suppress("UNUSED_PARAMETER") now: Long, top: Float) {
        val t = v.theme
        if (buttons.isEmpty()) refresh()
        val cy = top + baseH / 2
        val r = 41f * m.s
        for (b in buttons) {
            if (b.tap == "none") continue
            val cx = centreX(b.index)
            val isPressed = pressed == b.index
            val color = if (!b.enabled) t.appBarIconDisabled else t.appBarIcon
            if (isPressed) {
                v.fill.color = if (t.highContrast) t.appBarIcon else t.accent
                c.drawCircle(cx, cy, r, v.fill)
            } else {
                stroke.color = color
                stroke.strokeWidth = 6f * m.s
                c.drawCircle(cx, cy, r - 3f * m.s, stroke)
            }
            val iconColor = if (isPressed) (if (t.highContrast) t.keyboardBackground else 0xFFFFFFFF.toInt()) else color
            if (b.appIcon != null) drawAppIcon(c, b.appIcon, cx, cy, r, iconColor)
            else v.glyphs.appBarIcon(c, b.icon, cx, cy, 0.45f * r, 5f * m.s, iconColor)
        }
        // "…" dots: rightmost centre 46 px from the right edge, 26 px below the bar top
        v.fill.color = if (pressed == MORE) t.accent else t.appBarIcon
        val dotY = top + 26f * m.s
        for (i in 0..2) c.drawCircle(v.width - 46f * m.s - i * 18f * m.s, dotY, 4f * m.s, v.fill)
        if (!expanded) return
        // label row: labels top-aligned, glyph top 3 px below the bar; a disabled button's label in the disabled colour
        val tp = v.text
        tp.typeface = v.typeface(TextStyles.appBarLabel.weight)
        tp.textSize = TextStyles.appBarLabel.size * m.s
        tp.getTextBounds("dl", 0, 2, bounds)
        val labelBase = top + baseH + 3f * m.s - bounds.top
        for (b in buttons) {
            if (b.tap == "none") continue
            tp.color = if (b.enabled) t.appBarIcon else t.appBarIconDisabled
            val s = TextUtils.ellipsize(b.label, tp, 136f * m.s, TextUtils.TruncateAt.END).toString()
            c.drawText(s, centreX(b.index) - tp.measureText(s) / 2, labelBase, tp)
        }
        // menu: rows of 96 px after 12 px padding, text cap-height-centred
        val menu = listOf(R.string.menu_switch, R.string.menu_clipboard, R.string.menu_settings)
        tp.typeface = v.typeface(TextStyles.appBarMenu.weight)
        tp.textSize = TextStyles.appBarMenu.size * m.s
        for ((i, id) in menu.withIndex()) {
            val rt = menuTop(top, i)
            if (pressed == MENU0 + i) {
                v.fill.color = t.key
                c.drawRect(0f, rt, v.width.toFloat(), rt + MENU_ROW * m.s, v.fill)
            }
            tp.color = t.appBarIcon
            c.drawText(v.controller.str(id), 32f * m.s, rt + MENU_ROW / 2 * m.s + v.capHeight(tp.typeface, tp.textSize) / 2, tp)
        }
    }

    private fun drawAppIcon(c: Canvas, d: Drawable, cx: Float, cy: Float, r: Float, color: Int) {
        val half = (r * 0.62f).toInt()
        var dr = d
        var tint = false
        if (Build.VERSION.SDK_INT >= 33 && d is AdaptiveIconDrawable && d.monochrome != null) {
            dr = d.monochrome!!
            tint = true
        }
        dr.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        if (tint) {
            dr.colorFilter = if (Build.VERSION.SDK_INT >= 29) BlendModeColorFilter(color, BlendMode.SRC_IN) else PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        dr.draw(c)
    }

    private fun hit(x: Float, y: Float): Int {
        if (y < baseH) {
            for (b in buttons) {
                if (b.tap == "none" || !b.enabled) continue
                if (abs(x - centreX(b.index)) <= 74f * m.s) return b.index
            }
            if (b(x)) return MORE
            return EMPTY
        }
        if (expanded) {
            val my = y - menuTop(0f, 0)
            if (my >= 0) {
                val i = (my / (MENU_ROW * m.s)).toInt()
                if (i in 0..2) return MENU0 + i
            }
            return EMPTY
        }
        return NONE
    }

    private fun b(x: Float): Boolean = x >= v.width - 120f * m.s

    fun down(x: Float, y: Float, @Suppress("UNUSED_PARAMETER") now: Long) {
        touchActive = true
        holdFired = false
        pressed = hit(x, y)
        if (pressed != NONE) v.controller.feedback.play(SoundKind.FUNCTION)
        val btn = buttons.getOrNull(pressed)
        if (btn != null && btn.hold != "none") v.postDelayed(holdRunnable, 450)
        v.invalidate()
    }

    fun move(@Suppress("UNUSED_PARAMETER") x: Float, @Suppress("UNUSED_PARAMETER") y: Float, @Suppress("UNUSED_PARAMETER") now: Long) {}

    fun up(x: Float, y: Float, @Suppress("UNUSED_PARAMETER") now: Long) {
        if (!touchActive) return
        touchActive = false
        v.removeCallbacks(holdRunnable)
        val p = pressed
        pressed = NONE
        v.invalidate()
        if (holdFired) return
        when {
            p >= MENU0 -> if (hit(x, y) == p) v.controller.menuAction(p - MENU0)
            p >= 0 -> if (y >= -40f * m.s) buttons.getOrNull(p)?.let { v.controller.appBarTap(it) }
            p == MORE || p == EMPTY -> v.controller.toggleAppBarExpanded()
        }
    }

    fun cancel() {
        touchActive = false
        v.removeCallbacks(holdRunnable)
        pressed = NONE
    }

    // accessibility: (kind, index, rect) kinds 0 button, 1 more, 2 menu item
    fun accessibilityNodes(top: Float): List<Triple<Int, Int, RectF>> {
        val out = ArrayList<Triple<Int, Int, RectF>>()
        for (b in buttons) {
            if (b.tap == "none") continue
            val cx = centreX(b.index)
            out.add(Triple(0, b.index, RectF(cx - 74f * m.s, top, cx + 74f * m.s, top + baseH)))
        }
        out.add(Triple(1, 0, RectF(v.width - 120f * m.s, top, v.width.toFloat(), top + baseH)))
        if (expanded) for (i in 0..2) {
            val rt = menuTop(top, i)
            out.add(Triple(2, i, RectF(0f, rt, v.width.toFloat(), rt + MENU_ROW * m.s)))
        }
        return out
    }

    fun button(i: Int): AppBarButton? = buttons.getOrNull(i)

    companion object {
        const val NONE = -1
        const val MORE = -2
        const val EMPTY = -3
        const val MENU0 = 100
        private const val LABEL_ROW = 48f
        private const val MENU_PAD = 12f
        private const val MENU_ROW = 96f
    }
}
