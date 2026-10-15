package app.metrokeys.settings.ui

import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.metrokeys.R
import app.metrokeys.keyboard.Curves
import java.io.File

/** The accent colours of spec 07 §10; index 5 (cobalt) is the default. */
object Accents {
    val COLORS: List<Color> = listOf(
        0xFFA4C400, 0xFF60A917, 0xFF008A00, 0xFF00ABA9, 0xFF1BA1E2, 0xFF3E65FF, 0xFF6A00FF, 0xFFAA00FF, 0xFFF472D0,
        0xFFD80073, 0xFFA20025, 0xFFE51400, 0xFFFA6800, 0xFFF0A30A, 0xFFE3C800, 0xFF825A2C, 0xFF6D8764, 0xFF647687,
        0xFF76608A, 0xFF87794E,
    ).map { Color(it) }
    const val DEFAULT = 5

    /** Resolves a stored keyboard accent (`-1`…`19`) to an index. */
    fun index(stored: String?): Int = stored?.toIntOrNull()?.takeIf { it in COLORS.indices } ?: DEFAULT
}

/** The colour roles of spec 07 §3. */
@Immutable
data class MetroColors(
    val dark: Boolean,
    val page: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textDisabled: Color,
    val outline: Color,
    val outlineDisabled: Color,
    val panel: Color,
    val textOnPanel: Color,
    val scrim: Color,
    val pickerOpenFill: Color,
    val pickerOpenItem: Color,
    val accent: Color,
    val onAccent: Color,
) {
    companion object {
        fun of(dark: Boolean, accent: Color): MetroColors = if (dark) {
            MetroColors(
                dark = true, page = Color.Black, textPrimary = Color.White, textSecondary = Color(0xFF999999),
                textDisabled = Color(0xFF737373), outline = Color.White, outlineDisabled = Color(0xFF666666),
                panel = Color(0xFF1F1F1F), textOnPanel = Color.White, scrim = Color(0xAA000000),
                pickerOpenFill = Color.White, pickerOpenItem = Color.Black, accent = accent, onAccent = Color.White,
            )
        } else {
            MetroColors(
                dark = false, page = Color.White, textPrimary = Color(0xFF212121), textSecondary = Color(0xFF8C8C8C),
                textDisabled = Color(0xFFBFBFBF), outline = Color.Black, outlineDisabled = Color(0xFFB2B2B2),
                panel = Color(0xFFDDDDDD), textOnPanel = Color(0xFF1D1D1D), scrim = Color(0xAAFFFFFF),
                pickerOpenFill = Color.White, pickerOpenItem = Color.Black, accent = accent, onAccent = Color.White,
            )
        }
    }
}

/** The faces used by the settings app: Selawik, or the user's imported Semilight/Regular/Semibold (07 §4). */
@Immutable
data class MetroFonts(val semilight: FontFamily, val regular: FontFamily, val semibold: FontFamily) {
    companion object {
        val BUNDLED = MetroFonts(
            FontFamily(Font(R.font.selawik_semilight)),
            FontFamily(Font(R.font.selawik_regular)),
            FontFamily(Font(R.font.selawik_semibold)),
        )

        /** Imported faces from [dir] (files named after their slot); a missing face stays Selawik. */
        fun custom(dir: File): MetroFonts {
            fun face(slot: String, fallback: FontFamily): FontFamily {
                val f = File(dir, slot)
                return if (f.isFile) FontFamily(Font(f)) else fallback
            }
            return MetroFonts(
                face("semilight", BUNDLED.semilight),
                face("regular", BUNDLED.regular),
                face("semibold", BUNDLED.semibold),
            )
        }
    }
}

/** The text styles of spec 07 §4 (sizes in sp). Colours are applied by the controls. */
@Immutable
class MetroType(fonts: MetroFonts) {
    private fun style(family: FontFamily, size: TextUnit, lineHeight: TextUnit = TextUnit.Unspecified) = TextStyle(
        fontFamily = family,
        fontSize = size,
        lineHeight = lineHeight,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )

    val appTitle = style(fonts.semibold, 15.2.sp)
    val pageTitle = style(fonts.semilight, 48.sp).copy(letterSpacing = (-0.01).em)
    val itemTitle = style(fonts.semilight, 25.sp)
    val toggleState = style(fonts.semilight, 21.4.sp)
    val boxTitle = style(fonts.semibold, 21.4.sp)
    val pickerValue = style(fonts.regular, 16.9.sp)
    val body = style(fonts.regular, 15.2.sp, 20.2.sp)
    val boxText = style(fonts.regular, 15.2.sp, 17.9.sp)
    val checkLabel = style(fonts.regular, 15.2.sp, 16.8.sp)
    val button = style(fonts.semibold, 15.2.sp)
    val small = style(fonts.regular, 13.4.sp)
    val note = style(fonts.regular, 13.4.sp, 17.sp)
    val license = style(fonts.regular, 13.4.sp, 17.sp)
}

@Immutable
data class Metro(val colors: MetroColors, val type: MetroType)

val LocalMetro = staticCompositionLocalOf<Metro> { error("no MetroTheme") }

object MetroTheme {
    val colors: MetroColors
        @Composable @ReadOnlyComposable get() = LocalMetro.current.colors
    val type: MetroType
        @Composable @ReadOnlyComposable get() = LocalMetro.current.type
}

@Composable
fun MetroTheme(colors: MetroColors, fonts: MetroFonts, content: @Composable () -> Unit) {
    val type = remember(fonts) { MetroType(fonts) }
    val metro = remember(colors, type) { Metro(colors, type) }
    CompositionLocalProvider(LocalMetro provides metro, content = content)
}

/** The curves of spec 07 §7.1 as Compose easings. */
object MetroEasing {
    /** Accelerating curve A(w). */
    fun accelerate(w: Float) = Easing { Curves.exponential(it, w) }

    /** Decelerating curve D(w). */
    fun decelerate(w: Float) = Easing { Curves.logarithmic(it, w) }

    /** S-curve S(w). */
    fun s(w: Float) = Easing { Curves.sCurve(it, w) }
}
