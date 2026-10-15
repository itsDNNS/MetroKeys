package app.metrokeys.settings.ui

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/*
 * Ink-based vertical spacing (07 §5.3, §6): the spec measures gaps from the glyph bottom of a text's last line to the
 * glyph (cap) top of the next text's first line, or to a control's outer edge. An "ink box" is a text laid out so that
 * its layout starts at the ink top of its first line and ends at the ink bottom of its last line; gaps are then plain
 * paddings between layouts.
 */

/** Ink measurement for one text style: a Paint with the style's resolved typeface and size. */
class InkMetrics(private val paint: Paint) {
    private val rect = Rect()

    /** Ink extent of [text] relative to its baseline: (top ≤ 0, bottom ≥ 0) in px; (−cap height, 0) if blank. */
    fun extent(text: CharSequence): Pair<Int, Int> {
        val s = text.toString().trim()
        if (s.isEmpty()) return -capHeight to 0
        synchronized(rect) {
            paint.getTextBounds(s, 0, s.length, rect)
            return rect.top to maxOf(0, rect.bottom)
        }
    }

    /** Ink height of "H" in px. */
    val capHeight: Int by lazy {
        synchronized(rect) {
            paint.getTextBounds("H", 0, 1, rect)
            -rect.top
        }
    }

    /** Depth of a descender ("g") below the baseline in px. */
    val descender: Int by lazy {
        synchronized(rect) {
            paint.getTextBounds("g", 0, 1, rect)
            rect.bottom
        }
    }

    /** Ink top of the first line of [layout], in px from the layout top. */
    fun firstInkTop(layout: TextLayoutResult): Int {
        if (layout.lineCount == 0) return 0
        val text = layout.layoutInput.text.text
        val line = text.substring(layout.getLineStart(0), layout.getLineEnd(0, visibleEnd = true))
        return (layout.getLineBaseline(0) + extent(line).first).roundToInt()
    }

    /** Ink bottom of the last line of [layout], in px from the layout top. */
    fun lastInkBottom(layout: TextLayoutResult): Int {
        if (layout.lineCount == 0) return 0
        val last = layout.lineCount - 1
        val text = layout.layoutInput.text.text
        val line = text.substring(layout.getLineStart(last), layout.getLineEnd(last, visibleEnd = true))
        return (layout.getLineBaseline(last) + extent(line).second).roundToInt()
    }
}

/** [InkMetrics] for [style] at the current density and font scale. */
@Composable
fun rememberInk(style: TextStyle): InkMetrics {
    val density = LocalDensity.current
    val resolver = LocalFontFamilyResolver.current
    val typeface = remember(style.fontFamily, resolver) {
        resolver.resolve(style.fontFamily, style.fontWeight ?: FontWeight.Normal).value as? Typeface ?: Typeface.DEFAULT
    }
    val px = with(density) { style.fontSize.toPx() }
    return remember(typeface, px) {
        InkMetrics(Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = px
        })
    }
}

/** Holder for the last text layout, filled synchronously while the text measures itself. */
class TextLayoutHolder {
    var layout: TextLayoutResult? = null
}

/**
 * Places a text measured with [holder] so that the layout starts [above] over the first line's ink top and ends
 * [below] under the last line's ink bottom. With [tightTop] false the top keeps the line box (only [above] is
 * added), with [tightBottom] false the bottom keeps it. [span]: fixed layout height from the ink top instead.
 */
fun Modifier.inkBox(
    holder: TextLayoutHolder,
    ink: InkMetrics,
    above: Dp = 0.dp,
    below: Dp = 0.dp,
    tightTop: Boolean = true,
    tightBottom: Boolean = true,
    span: Dp? = null,
): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minHeight = 0))
    val l = holder.layout
    val top = if (l != null && tightTop) ink.firstInkTop(l) else 0
    val bottom = if (l != null && tightBottom) ink.lastInkBottom(l) else p.height
    val a = above.roundToPx()
    val h = if (span != null) a + span.roundToPx() else a + (bottom - top) + below.roundToPx()
    layout(p.width, maxOf(0, h)) { p.place(0, a - top) }
}

/** A text laid out as an ink box (see [inkBox]). */
@Composable
fun InkText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    above: Dp = 0.dp,
    below: Dp = 0.dp,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    span: Dp? = null,
) {
    val holder = remember { TextLayoutHolder() }
    val ink = rememberInk(style)
    BasicText(
        text, modifier.inkBox(holder, ink, above, below, span = span), style = style, maxLines = maxLines, overflow = overflow,
        onTextLayout = { holder.layout = it },
    )
}

/** Converts px at the reference width of 1080 px (07 §2: 1 dp = 2.625 px) to dp. */
fun refPx(px: Int): Dp = (px / 2.625f).dp
