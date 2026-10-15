package app.metrokeys.keyboard

import android.content.Context
import android.view.accessibility.AccessibilityManager

/** Keyboard colours of spec 02 §8/§9 (spec/data/theme.json), with the accent resolved. */
data class Theme(
    val dark: Boolean,
    val highContrast: Boolean,
    val accent: Int,
    val keyboardBackground: Int,
    val key: Int,
    val keyLabel: Int,
    val keyLabelDisabled: Int,
    val keyHint: Int,
    val keyPressed: Int,
    val keyPressedLabel: Int,
    val emphasizedKey: Int,
    val emphasizedLabel: Int,
    val emphasizedPressed: Int,
    val emphasizedPressedLabel: Int,
    val popupFrame: Int,
    val popupBackground: Int,
    val popupLabel: Int,
    val popupOutline: Int?,
    val pickerItem: Int,
    val pickerItemLabel: Int,
    val pickerHighlight: Int,
    val pickerHighlightLabel: Int,
    val pickerHighlightOutline: Int?,
    val dimmer: Int,
    val candidateBar: Int,
    val candidateText: Int,
    val candidatePressed: Int,
    val candidatePressedText: Int,
    val candidateTopLine: Int?,
    val appBarIcon: Int,
    val appBarIconDisabled: Int,
    val trace: Int,
    val traceOpacity: Float,
) {
    companion object {
        /** The 20 accent colours of spec 02 §8.3 / 07 §10, by index. Default: cobalt (5). */
        val ACCENTS = intArrayOf(
            0xFFA4C400.toInt(), 0xFF60A917.toInt(), 0xFF008A00.toInt(), 0xFF00ABA9.toInt(), 0xFF1BA1E2.toInt(),
            0xFF3E65FF.toInt(), 0xFF6A00FF.toInt(), 0xFFAA00FF.toInt(), 0xFFF472D0.toInt(), 0xFFD80073.toInt(),
            0xFFA20025.toInt(), 0xFFE51400.toInt(), 0xFFFA6800.toInt(), 0xFFF0A30A.toInt(), 0xFFE3C800.toInt(),
            0xFF825A2C.toInt(), 0xFF6D8764.toInt(), 0xFF647687.toInt(), 0xFF76608A.toInt(), 0xFF87794E.toInt(),
        )
        const val DEFAULT_ACCENT = 5

        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val BLACK = 0xFF000000.toInt()

        private fun c(v: Long): Int = v.toInt()

        /** Android's "high contrast text" accessibility setting (07 §9.10 "same as system"). */
        fun systemHighContrast(context: Context): Boolean = try {
            val am = context.getSystemService(AccessibilityManager::class.java)
            am?.javaClass?.getMethod("isHighTextContrastEnabled")?.invoke(am) as? Boolean ?: false
        } catch (_: Exception) {
            false
        }

        fun create(dark: Boolean, highContrast: Boolean, accentIndex: Int): Theme {
            val accent = ACCENTS.getOrElse(accentIndex) { ACCENTS[DEFAULT_ACCENT] }
            return when {
                highContrast && dark -> Theme(
                    dark = true, highContrast = true, accent = accent,
                    keyboardBackground = BLACK, key = WHITE, keyLabel = BLACK, keyLabelDisabled = c(0xFF737373),
                    keyHint = BLACK, keyPressed = BLACK, keyPressedLabel = WHITE,
                    emphasizedKey = BLACK, emphasizedLabel = WHITE, emphasizedPressed = WHITE, emphasizedPressedLabel = BLACK,
                    popupFrame = BLACK, popupBackground = BLACK, popupLabel = WHITE, popupOutline = WHITE,
                    pickerItem = WHITE, pickerItemLabel = BLACK, pickerHighlight = BLACK, pickerHighlightLabel = WHITE,
                    pickerHighlightOutline = WHITE, dimmer = 0x80000000.toInt(),
                    candidateBar = BLACK, candidateText = WHITE, candidatePressed = WHITE, candidatePressedText = BLACK,
                    candidateTopLine = WHITE, appBarIcon = WHITE, appBarIconDisabled = c(0xFF737373),
                    trace = BLACK, traceOpacity = 0.745f,
                )
                highContrast -> Theme(
                    dark = false, highContrast = true, accent = accent,
                    keyboardBackground = WHITE, key = BLACK, keyLabel = WHITE, keyLabelDisabled = c(0xFFBFBFBF),
                    keyHint = WHITE, keyPressed = WHITE, keyPressedLabel = BLACK,
                    emphasizedKey = WHITE, emphasizedLabel = BLACK, emphasizedPressed = BLACK, emphasizedPressedLabel = WHITE,
                    popupFrame = WHITE, popupBackground = WHITE, popupLabel = BLACK, popupOutline = BLACK,
                    pickerItem = BLACK, pickerItemLabel = WHITE, pickerHighlight = WHITE, pickerHighlightLabel = BLACK,
                    pickerHighlightOutline = BLACK, dimmer = 0x80FFFFFF.toInt(),
                    candidateBar = WHITE, candidateText = BLACK, candidatePressed = BLACK, candidatePressedText = WHITE,
                    candidateTopLine = BLACK, appBarIcon = BLACK, appBarIconDisabled = c(0xFFBFBFBF),
                    trace = WHITE, traceOpacity = 0.745f,
                )
                dark -> Theme(
                    dark = true, highContrast = false, accent = accent,
                    keyboardBackground = c(0xFF1F1F1F), key = c(0xFF474747), keyLabel = WHITE, keyLabelDisabled = c(0x73FFFFFF),
                    keyHint = c(0x99FFFFFF), keyPressed = accent, keyPressedLabel = WHITE,
                    emphasizedKey = WHITE, emphasizedLabel = BLACK, emphasizedPressed = accent, emphasizedPressedLabel = WHITE,
                    popupFrame = c(0xFF1F1F1F), popupBackground = accent, popupLabel = WHITE, popupOutline = null,
                    pickerItem = c(0xFFA5A5A5), pickerItemLabel = BLACK, pickerHighlight = accent, pickerHighlightLabel = WHITE,
                    pickerHighlightOutline = null, dimmer = 0x80000000.toInt(),
                    candidateBar = c(0xFF1F1F1F), candidateText = WHITE, candidatePressed = WHITE, candidatePressedText = BLACK,
                    candidateTopLine = null, appBarIcon = WHITE, appBarIconDisabled = c(0xFF7C7C7C),
                    trace = accent, traceOpacity = 0.851f,
                )
                else -> Theme(
                    dark = false, highContrast = false, accent = accent,
                    keyboardBackground = c(0xFFDDDDDD), key = WHITE, keyLabel = c(0xDE000000), keyLabelDisabled = c(0x40000000),
                    keyHint = c(0x73000000), keyPressed = accent, keyPressedLabel = WHITE,
                    emphasizedKey = BLACK, emphasizedLabel = WHITE, emphasizedPressed = accent, emphasizedPressedLabel = WHITE,
                    popupFrame = c(0xFFDDDDDD), popupBackground = accent, popupLabel = WHITE, popupOutline = null,
                    pickerItem = c(0xFF797979), pickerItemLabel = WHITE, pickerHighlight = accent, pickerHighlightLabel = WHITE,
                    pickerHighlightOutline = null, dimmer = 0x80FFFFFF.toInt(),
                    candidateBar = c(0xFFDDDDDD), candidateText = BLACK, candidatePressed = BLACK, candidatePressedText = WHITE,
                    candidateTopLine = null, appBarIcon = c(0xDE000000), appBarIconDisabled = c(0xFF818181),
                    trace = accent, traceOpacity = 0.745f,
                )
            }
        }
    }
}
