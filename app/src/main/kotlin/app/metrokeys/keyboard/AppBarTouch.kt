package app.metrokeys.keyboard

import kotlin.math.abs

/**
 * Touch regions of the application bar (03 §12.2) in reference px scaled by [s], relative to the bar top. Plain Kotlin,
 * so the touch rules can be unit tested.
 */
object AppBarTouch {
    const val NONE = -1
    const val MORE = -2
    const val EMPTY = -3
    const val MENU0 = 100

    const val BASE_H = 126f
    const val LABEL_ROW = 48f
    const val MENU_PAD = 12f
    const val MENU_ROW = 96f
    private const val BUTTON_STEP = 148f
    private const val BUTTON_HALF = 74f
    private const val MORE_W = 120f

    fun centreX(i: Int, width: Float, s: Float): Float = width / 2f + (i - 1) * BUTTON_STEP * s

    /**
     * The region at ([x], [y]): a button index (0…2) among the tappable [buttons], [MORE] ("…"), a menu item
     * ([MENU0] + i, expanded only), [EMPTY] (an empty part of the bar) or [NONE] (outside the bar).
     */
    fun hit(x: Float, y: Float, width: Float, s: Float, expanded: Boolean, buttons: Collection<Int>): Int {
        val baseH = BASE_H * s
        if (y < baseH) {
            for (b in buttons) if (abs(x - centreX(b, width, s)) <= BUTTON_HALF * s) return b
            if (x >= width - MORE_W * s) return MORE
            return EMPTY
        }
        if (expanded) {
            val my = y - baseH - (LABEL_ROW + MENU_PAD) * s
            if (my >= 0) {
                val i = (my / (MENU_ROW * s)).toInt()
                if (i in 0..2) return MENU0 + i
            }
            return EMPTY
        }
        return NONE
    }

    /** Touch-down feedback (click, pressed look): buttons, "…" and menu items; an empty part of the bar gets none. */
    fun hasFeedback(region: Int): Boolean = region != NONE && region != EMPTY

    /**
     * Only "…" toggles the expanded state on release; a touch on an empty part of the bar does nothing (03 §12.2
     * item 5, 0.6.4), but the keyboard still consumes it.
     */
    fun togglesOnRelease(region: Int): Boolean = region == MORE
}
