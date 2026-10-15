package app.metrokeys.ime

import android.graphics.drawable.Drawable

/** One application-bar button as drawn (03 §12, 02 §5.11). */
data class AppBarButton(
    val index: Int,
    val tap: String,
    val hold: String,
    /** Pictogram name for [app.metrokeys.keyboard.Glyphs.appBarIcon]. */
    val icon: String,
    /** Short lower-case label of the expanded bar. */
    val label: String,
    val enabled: Boolean,
    /** Icon of an "open app" binding. */
    val appIcon: Drawable? = null,
    /** Accessibility name. */
    val description: String = label,
)

/** Dictation state shown in the strip (03 §14). */
data class DictationState(val listening: Boolean, val levelDb: Float, val text: String?)

enum class Shift { OFF, ONE_SHOT, CAPS_LOCK }
