package app.metrokeys.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/*
 * Vertical spacing of sub-pages (07 §5.3): the content of a page is a sequence of blocks (body text, field header,
 * note, group title, list picker, toggle, slider, check box, list item, button). The gap between two blocks depends
 * on their kinds and is measured from the visual bottom of the upper block (glyph bottom of its last text line, or a
 * control's outer edge) to the visual top of the next (glyph top of its first text line, or a control's outer edge).
 * Blocks report these edges as the alignment lines [InkTop] and [InkBottom]; without them the layout edges count.
 */

/** Visual top of a block (glyph top of its first text line or a control's outer top edge). */
val InkTop = HorizontalAlignmentLine { a, b -> minOf(a, b) }

/** Visual bottom of a block (glyph bottom of its last text line or a control's outer bottom edge). */
val InkBottom = HorizontalAlignmentLine { a, b -> maxOf(a, b) }

enum class BlockKind { BODY, HEADER, NOTE, GROUP_TITLE, PICKER, TOGGLE, SLIDER, CHECK, LIST_ITEM, BUTTON, PREVIEW, GRID, OTHER }

/** A block of a page column; [noteBelow]: the block ends with a note under its control. */
data class Block(val kind: BlockKind, val noteBelow: Boolean = false)

/** Marks the root of a block. */
fun Modifier.block(kind: BlockKind, noteBelow: Boolean = false): Modifier = layoutId(Block(kind, noteBelow))

/** Reports [InkTop]/[InkBottom] at this element's own top and bottom edges (a control's outline). */
fun Modifier.reportEdges(top: Boolean = true, bottom: Boolean = true): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val lines = buildMap<AlignmentLine, Int> {
        if (top) put(InkTop, 0)
        if (bottom) put(InkBottom, p.height)
    }
    layout(p.width, p.height, lines) { p.place(0, 0) }
}

/**
 * Reports the ink top of the first line and (with [bottom]) the ink bottom of the last line of the text measured with
 * [holder]. Must wrap the text directly (last in the modifier chain).
 */
fun Modifier.reportInk(holder: TextLayoutHolder, ink: InkMetrics, top: Boolean = true, bottom: Boolean = true): Modifier =
    layout { measurable, constraints ->
        val p = measurable.measure(constraints)
        val l = holder.layout
        val lines = buildMap<AlignmentLine, Int> {
            if (l != null && top) put(InkTop, ink.firstInkTop(l))
            if (l != null && bottom) put(InkBottom, ink.lastInkBottom(l))
        }
        layout(p.width, p.height, lines) { p.place(0, 0) }
    }

/** Gaps of 07 §5.3 and §6 in px at the 1080 px reference width. */
object BlockGaps {
    /** Page title baseline to the end of the page-title layout (07 §5.1: the first list item's top). */
    const val TITLE_LAYOUT_BELOW_BASELINE = 52.5f

    /**
     * Gap in reference px between [prev] (null: first block) and [next], measured between their visual edges, and
     * whether the visual edges (true) or the layout edges (false) are used.
     */
    fun gap(prev: Block?, next: Block, titled: Boolean): Pair<Float, Boolean> {
        val n = next.kind
        if (n == BlockKind.OTHER || prev?.kind == BlockKind.OTHER) return 0f to false
        val headerLed = n == BlockKind.HEADER || n == BlockKind.PICKER || n == BlockKind.TOGGLE || n == BlockKind.SLIDER
        if (prev == null) {
            if (!titled) return 0f to true // the app title's layout ends 31 dp below its baseline (§5.2)
            return when (n) {
                BlockKind.LIST_ITEM -> 0f to false                                // title baseline 145 px (§5.1)
                BlockKind.BODY -> 116f - TITLE_LAYOUT_BELOW_BASELINE to true      // cap top 116 px (§5.1)
                else -> 98f - TITLE_LAYOUT_BELOW_BASELINE to true
            }
        }
        val p = prev.kind
        if (p == n && (p == BlockKind.LIST_ITEM || p == BlockKind.CHECK)) return 0f to false
        // app bar buttons page (07 §9.12.1, own values): text → preview → mode switch → header → action grid
        if (n == BlockKind.PREVIEW) return 60f to true
        if (p == BlockKind.PREVIEW) return 48f to true
        if (p == BlockKind.HEADER && n == BlockKind.GRID) return 20f to false // layout edge: the grid stays put when the header text changes
        if (n == BlockKind.GROUP_TITLE) return 91f to true
        val noteish = prev.noteBelow || p == BlockKind.NOTE
        val px = when {
            p == BlockKind.GROUP_TITLE -> 67f
            noteish && n == BlockKind.LIST_ITEM -> 76f
            noteish -> 76f
            p == BlockKind.BODY && headerLed -> 60f
            p == BlockKind.BODY && n == BlockKind.LIST_ITEM -> 76f
            p == BlockKind.BODY -> 36f
            p == BlockKind.PICKER && headerLed -> 62f
            p == BlockKind.PICKER && n == BlockKind.BODY -> 54f
            p == BlockKind.PICKER && n == BlockKind.BUTTON -> 67f
            p == BlockKind.PICKER -> 76f
            p == BlockKind.TOGGLE || p == BlockKind.SLIDER -> 76f
            p == BlockKind.HEADER && n == BlockKind.NOTE -> 13f
            p == BlockKind.HEADER && n == BlockKind.BUTTON -> 20f
            p == BlockKind.HEADER && n == BlockKind.BODY -> 20f // data+info: "About" → version line (own value)
            p == BlockKind.HEADER && n == BlockKind.CHECK -> 0f
            p == BlockKind.HEADER -> 13f
            p == BlockKind.BUTTON && n == BlockKind.BODY -> 36f
            p == BlockKind.BUTTON && headerLed -> 68f
            p == BlockKind.BUTTON -> 76f
            p == BlockKind.CHECK -> 47f
            p == BlockKind.LIST_ITEM && headerLed -> 60f // data+info: "learned words" → "Dictionary" (own value)
            p == BlockKind.LIST_ITEM -> 0f
            else -> 60f
        }
        return px to true
    }
}

/**
 * Stacks the blocks of a page with the gaps of [BlockGaps]; children without a [Block] id (or all children when
 * [spacing] is off, as on the main page with its own paddings) are stacked edge to edge.
 */
@Composable
fun BlockColumn(titled: Boolean, modifier: Modifier = Modifier, spacing: Boolean = true, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val c = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val placeables = measurables.map { it.measure(c) }
        val refPx = density / 2.625f
        val ys = IntArray(placeables.size)
        var bottom = 0 // visual bottom of the previous block
        var end = 0    // layout bottom of the previous child
        var prev: Block? = null
        var first = true
        for ((i, p) in placeables.withIndex()) {
            val b = if (spacing) measurables[i].layoutId as? Block else null
            val y: Int
            if (b == null) {
                y = end
                prev = Block(BlockKind.OTHER)
            } else {
                val (g, visual) = BlockGaps.gap(if (first) null else prev, b, titled)
                val topLine = p[InkTop].takeIf { visual && it != AlignmentLine.Unspecified } ?: 0
                val from = if (visual) bottom else end
                y = from + (g * refPx).roundToInt() - topLine
                prev = b
            }
            first = false
            ys[i] = y
            end = y + p.height
            val bl = p[InkBottom]
            bottom = if (bl != AlignmentLine.Unspecified) y + bl else end
        }
        val width = constraints.maxWidth
        layout(width, maxOf(end, 0)) { placeables.forEachIndexed { i, p -> p.place(0, ys[i]) } }
    }
}
