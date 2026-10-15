package app.metrokeys.settings.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Alpha of disabled toggles, pickers and dimmed sliders (07 §6.3, §6.6, §9.8). */
const val DISABLED_ALPHA = 0.4f

private val OUTLINE = 1.9.dp

// ==================================================================== text elements (07 §6.2)

/** A field header above a control: `small`, `text.secondary`, a heading for accessibility; a [BlockKind.HEADER] block. */
@Composable
fun FieldHeader(text: String, top: Dp = 0.dp, bottom: Dp = 0.dp) {
    val m = LocalPageMargins.current
    val style = MetroTheme.type.small.copy(color = MetroTheme.colors.textSecondary)
    val holder = remember { TextLayoutHolder() }
    val ink = rememberInk(style)
    BasicText(
        text, Modifier.block(BlockKind.HEADER).fillMaxWidth().padding(top = top, bottom = bottom).margins(m).semantics { heading() }
            .reportInk(holder, ink),
        style = style, onTextLayout = { holder.layout = it },
    )
}

/** Body text (`body`, line pitch 20.2 sp); a [BlockKind.BODY] block. */
@Composable
fun BodyText(text: String, top: Dp = 0.dp, color: Color = MetroTheme.colors.textPrimary) {
    val m = LocalPageMargins.current
    val style = MetroTheme.type.body.copy(color = color)
    val holder = remember { TextLayoutHolder() }
    val ink = rememberInk(style)
    BasicText(
        text, Modifier.block(BlockKind.BODY).fillMaxWidth().padding(top = top).margins(m).reportInk(holder, ink), style = style,
        onTextLayout = { holder.layout = it },
    )
}

/**
 * A note under a control: `small`, `text.secondary`; the cap top of its first line [above] (36 px, 14 dp) below the
 * control's bottom edge (07 §6.2). The layout ends at the glyph bottom of its last line.
 */
@Composable
fun Note(text: String, modifier: Modifier = Modifier, above: Dp = refPx(36)) {
    val style = MetroTheme.type.note.copy(color = MetroTheme.colors.textSecondary)
    val holder = remember { TextLayoutHolder() }
    val ink = rememberInk(style)
    BasicText(
        text, modifier.reportEdges(top = false).inkBox(holder, ink, above = above), style = style,
        onTextLayout = { holder.layout = it },
    )
}

/** A note under a field header on the page itself (font weights, sound slots); a [BlockKind.NOTE] block. */
@Composable
fun PageNote(text: String) {
    val m = LocalPageMargins.current
    val style = MetroTheme.type.note.copy(color = MetroTheme.colors.textSecondary)
    val holder = remember { TextLayoutHolder() }
    val ink = rememberInk(style)
    BasicText(
        text, Modifier.block(BlockKind.NOTE).fillMaxWidth().margins(m).reportInk(holder, ink), style = style,
        onTextLayout = { holder.layout = it },
    )
}

/** A group title in `itemTitle` (07 §9.12); a [BlockKind.GROUP_TITLE] block (spacing §5.3). */
@Composable
fun GroupTitle(text: String) {
    val m = LocalPageMargins.current
    val style = MetroTheme.type.itemTitle.copy(color = MetroTheme.colors.textPrimary)
    val holder = remember { TextLayoutHolder() }
    val ink = rememberInk(style)
    BasicText(
        text, Modifier.block(BlockKind.GROUP_TITLE).fillMaxWidth().margins(m).semantics { heading() }.reportInk(holder, ink),
        style = style, maxLines = 2, overflow = TextOverflow.Ellipsis, onTextLayout = { holder.layout = it },
    )
}

/**
 * A stand-alone link (07 §6.2): `body` in `text.primary`, underlined with a 2 px line 3 px below the baseline; tilt
 * like a button, at least 48 dp high to touch. A [BlockKind.BODY] block measured to the text's ink.
 */
@Composable
fun StandaloneLink(text: String, onClick: () -> Unit) {
    val m = LocalPageMargins.current
    val c = MetroTheme.colors
    val style = MetroTheme.type.body.copy(color = c.textPrimary)
    val holder = remember { TextLayoutHolder() }
    val ink = rememberInk(style)
    val source = remember { MutableInteractionSource() }
    Row(Modifier.block(BlockKind.BODY).fillMaxWidth().margins(m)) {
        Box(Modifier.combinedClickableNoIndication(source, true, onClick).tilt(source, TiltAngles.BUTTON).padding(vertical = 12.dp)) {
            BasicText(
                text,
                Modifier.drawBehind {
                    val l = holder.layout ?: return@drawBehind
                    val ref = density / 2.625f
                    for (i in 0 until l.lineCount) {
                        val y = l.getLineBaseline(i) + 3f * ref + ref
                        drawLine(style.color, Offset(l.getLineLeft(i), y), Offset(l.getLineRight(i), y), strokeWidth = 2f * ref)
                    }
                }.reportInk(holder, ink),
                style = style, onTextLayout = { holder.layout = it },
            )
        }
    }
}

// ==================================================================== list item (07 §6.1)

/**
 * A list item with title and optional subtitle: title baseline 35 dp below the item top, subtitle baseline 17.1 dp
 * below, 5 dp to the bottom (22 dp without subtitle). The touch area spans the content width and extends to the
 * left screen edge. [below] adds content under the texts (font samples).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListItem(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    below: (@Composable () -> Unit)? = null,
) {
    val m = LocalPageMargins.current
    val c = MetroTheme.colors
    val t = MetroTheme.type
    val source = remember { MutableInteractionSource() }
    val click = if (onClick != null) {
        Modifier.combinedClickable(
            interactionSource = source, indication = null, enabled = enabled, role = Role.Button,
            onLongClick = onLongClick, onClick = onClick,
        )
    } else {
        Modifier.semantics(mergeDescendants = true) {}
    }
    val titleStyle = t.itemTitle.copy(color = if (enabled) c.textPrimary else c.textDisabled)
    val holder = remember { TextLayoutHolder() }
    val ink = rememberInk(titleStyle)
    Box(Modifier.block(BlockKind.LIST_ITEM).fillMaxWidth().padding(end = m.end).then(click)) {
        Column(Modifier.fillMaxWidth().tilt(source, TiltAngles.LIST_ITEM, onClick != null).padding(start = m.start)) {
            // the title's glyph top is reported so that gaps to the item are measured to it (07 §5.3)
            BasicText(
                title, Modifier.baselines(35.dp, if (subtitle == null && below == null) 22.dp else 0.dp).reportInk(holder, ink, bottom = false),
                style = titleStyle, maxLines = 2, overflow = TextOverflow.Ellipsis, onTextLayout = { holder.layout = it },
            )
            if (subtitle != null) {
                BasicText(
                    subtitle, Modifier.baselines(17.1.dp, 5.dp),
                    style = t.small.copy(color = if (enabled) c.textSecondary else c.textDisabled), maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            below?.invoke()
        }
    }
}

// ==================================================================== toggle switch (07 §6.3)

/**
 * A toggle block: header, a row with the "On"/"Off" label and the switch, optional note. The whole block toggles;
 * no tilt. Disabled: 40 % alpha, no reaction.
 */
@Composable
fun ToggleBlock(
    header: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    note: String? = null,
    onLabel: String,
    offLabel: String,
) {
    val m = LocalPageMargins.current
    val c = MetroTheme.colors
    val t = MetroTheme.type
    val progress by animateFloatAsState(
        if (checked) 1f else 0f, tween(100, easing = MetroEasing.decelerate(150f)), label = "thumb",
    )
    val headerStyle = t.small.copy(color = c.textSecondary)
    val labelStyle = t.toggleState.copy(color = c.textPrimary)
    val holder = remember { TextLayoutHolder() }
    val headerInk = rememberInk(headerStyle)
    val labelInk = rememberInk(labelStyle)
    Layout(
        content = {
            BasicText(header, style = headerStyle, onTextLayout = { holder.layout = it })
            Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                // the state label is cap-centred in the 84 px row (07 §6.3)
                BasicText(
                    if (checked) onLabel else offLabel,
                    Modifier.weight(1f).align(Alignment.Top).clearAndSetSemantics {}.baselines(16.dp + with(LocalDensity.current) { (labelInk.capHeight / 2f).toDp() }),
                    style = labelStyle, maxLines = 1,
                )
                SwitchGlyph(progress)
            }
            if (note != null) Note(note)
        },
        modifier = Modifier.block(BlockKind.TOGGLE, note != null).fillMaxWidth().alpha(if (enabled) 1f else DISABLED_ALPHA)
            .toggleable(checked, remember { MutableInteractionSource() }, null, enabled, Role.Switch, onChange)
            .margins(m),
    ) { measurables, constraints ->
        val c0 = constraints.copy(minHeight = 0)
        val hp = measurables[0].measure(c0)
        val rp = measurables[1].measure(c0)
        val np = measurables.getOrNull(2)?.measure(c0)
        val l = holder.layout
        val inkTop = if (l != null) headerInk.firstInkTop(l) else 0
        val headerY = -inkTop
        val lastBaseline = headerY + (l?.let { it.getLineBaseline(it.lineCount - 1).toInt() } ?: hp.height)
        // header glyph top to row bottom: 123 px, so that two toggles without notes are 199 px apart (07 §6.3)
        val rowY = lastBaseline + (refPx(123 - 84).toPx() - headerInk.capHeight).toInt()
        val rowBottom = rowY + rp.height
        val h = if (np != null) rowBottom + np.height else rowBottom
        layout(constraints.maxWidth, h, mapOf(InkTop to 0, InkBottom to h)) {
            hp.place(0, headerY)
            rp.place(0, rowY)
            np?.place(0, rowBottom)
        }
    }
}

/** The switch: square track with 2.3 dp outline, accent indicator, thumb with a clear zone (07 §6.3). */
@Composable
private fun SwitchGlyph(progress: Float) {
    val c = MetroTheme.colors
    Canvas(Modifier.size(59.4.dp, 25.5.dp)) {
        val w = 59.4.dp.toPx()
        val h = 22.9.dp.toPx()
        val ty = (size.height - h) / 2
        val s = 2.3.dp.toPx()
        drawRect(c.outline, Offset(s / 2, ty + s / 2), Size(w - s, h - s), style = Stroke(s))
        val thumbW = 13.3.dp.toPx()
        val clear = 2.6.dp.toPx()
        val thumbX = progress * (w - thumbW)
        val inset = 4.6.dp.toPx()
        val indH = 13.3.dp.toPx()
        val right = thumbX - clear
        if (progress > 0f && right > inset) drawRect(c.accent, Offset(inset, ty + (h - indH) / 2), Size(right - inset, indH))
        drawRect(c.page, Offset(thumbX - clear, 0f), Size(thumbW + 2 * clear, size.height))
        drawRect(c.textPrimary, Offset(thumbX, 0f), Size(thumbW, size.height))
    }
}

// ==================================================================== check box and radio button (07 §6.4, §6.5)

@Composable
private fun MarkRow(
    label: String,
    note: String?,
    enabled: Boolean,
    modifier: Modifier,
    source: MutableInteractionSource,
    mark: @Composable (pressed: Boolean) -> Unit,
) {
    val m = LocalPageMargins.current
    val c = MetroTheme.colors
    val pressed by source.collectIsPressedAsState()
    val line = spToDp(16.8f)
    val holder = remember { TextLayoutHolder() }
    // row pitch exactly 48 dp (126 px) for a one-line label, plus the line pitch per further line (07 §6.4)
    Box(Modifier.block(BlockKind.CHECK).fillMaxWidth().then(modifier)) {
        Row(Modifier.fillMaxWidth().tilt(source, TiltAngles.CHECK_BOX, enabled).margins(m)) {
            Box(Modifier.padding(top = 24.dp - 11.45.dp).size(22.9.dp), contentAlignment = Alignment.Center) { mark(pressed) }
            Layout(
                content = {
                    BasicText(
                        label, style = MetroTheme.type.checkLabel.copy(color = if (enabled) c.textPrimary else c.textDisabled),
                        maxLines = 3, overflow = TextOverflow.Ellipsis, onTextLayout = { holder.layout = it },
                    )
                    if (note != null) Note(note, above = 0.dp)
                },
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            ) { measurables, constraints ->
                val c0 = constraints.copy(minHeight = 0)
                val lp = measurables[0].measure(c0)
                val np = measurables.getOrNull(1)?.measure(c0)
                val lines = holder.layout?.lineCount ?: 1
                val linePx = line.toPx()
                // the first line's box is centred in the first 48 dp
                val labelY = ((48.dp.toPx() - linePx) / 2).roundToInt()
                val rows = 48.dp.toPx() + (lines - 1) * linePx
                val noteY = labelY + lp.height
                val h = maxOf(rows.roundToInt(), if (np != null) noteY + np.height + labelY else 0)
                layout(constraints.maxWidth, h) {
                    lp.place(0, labelY)
                    np?.place(0, noteY)
                }
            }
        }
    }
}

/** A check box row; the whole row toggles. Pressed: box filled with the accent, tilt. */
@Composable
fun CheckBoxRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true, note: String? = null) {
    val source = remember { MutableInteractionSource() }
    val c = MetroTheme.colors
    MarkRow(label, note, enabled, Modifier.toggleable(checked, source, null, enabled, Role.Checkbox, onChange), source) { pressed ->
        val col = if (enabled) c.outline else c.outlineDisabled
        Canvas(Modifier.size(22.1.dp)) {
            val s = OUTLINE.toPx()
            if (pressed) drawRect(c.accent)
            drawRect(col, Offset(s / 2, s / 2), Size(size.width - s, size.height - s), style = Stroke(s))
            if (checked) {
                val p = Path().apply {
                    moveTo(0.20f * size.width, 0.52f * size.height)
                    lineTo(0.42f * size.width, 0.74f * size.height)
                    lineTo(0.82f * size.width, 0.28f * size.height)
                }
                drawPath(p, col, style = Stroke(3.dp.toPx(), cap = StrokeCap.Square, join = StrokeJoin.Miter))
            }
        }
    }
}

/** A radio button row (07 §6.5): ring 22.9 dp, selected dot 44 %, pressed ring in the accent. */
@Composable
fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit, enabled: Boolean = true, note: String? = null) {
    val source = remember { MutableInteractionSource() }
    val c = MetroTheme.colors
    MarkRow(label, note, enabled, Modifier.selectable(selected, source, null, enabled, Role.RadioButton, onSelect), source) { pressed ->
        val col = if (enabled) c.outline else c.outlineDisabled
        Canvas(Modifier.size(22.9.dp)) {
            val s = OUTLINE.toPx()
            drawCircle(if (pressed) c.accent else col, radius = size.minDimension / 2 - s / 2, style = Stroke(s))
            if (selected) drawCircle(col, radius = size.minDimension * 0.22f)
        }
    }
}

// ==================================================================== button (07 §6.8)

/** A button: 32 dp high, at least 91 dp wide, outline 1.9 dp; pressed: accent fill and white label, tilt. */
@Composable
fun MetroButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onPanel: Boolean = false,
    fill: Boolean = false,
) {
    val c = MetroTheme.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val outline = if (enabled) c.outline else c.outline.copy(alpha = DISABLED_ALPHA)
    val label = when {
        !enabled -> c.textDisabled
        pressed -> c.onAccent
        onPanel -> c.textOnPanel
        else -> c.textPrimary
    }
    Box(
        modifier.height(48.dp).combinedClickableNoIndication(source, enabled, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            (if (fill) Modifier.fillMaxWidth() else Modifier).reportEdges().defaultMinSize(minWidth = 91.dp).height(32.dp)
                .tilt(source, TiltAngles.BUTTON, enabled)
                .background(if (pressed && enabled) c.accent else Color.Transparent).border(OUTLINE, outline),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text, Modifier.padding(horizontal = 9.9.dp), style = MetroTheme.type.button.copy(color = label, textAlign = TextAlign.Center),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableNoIndication(source: MutableInteractionSource, enabled: Boolean, onClick: () -> Unit): Modifier =
    combinedClickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)

/** Two buttons side by side with equal width and a 16 dp gap ("import" + "remove"). */
@Composable
fun ButtonPair(first: String, onFirst: () -> Unit, second: String, onSecond: () -> Unit, secondEnabled: Boolean = true) {
    val m = LocalPageMargins.current
    Row(Modifier.block(BlockKind.BUTTON).fillMaxWidth().margins(m), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        MetroButton(first, onFirst, Modifier.weight(1f), fill = true)
        MetroButton(second, onSecond, Modifier.weight(1f), enabled = secondEnabled, fill = true)
    }
}

/** A single button at the start of the content column. */
@Composable
fun PageButton(text: String, onClick: () -> Unit, enabled: Boolean = true, top: Dp = 0.dp) {
    val m = LocalPageMargins.current
    Row(Modifier.block(BlockKind.BUTTON).fillMaxWidth().padding(top = top).margins(m)) { MetroButton(text, onClick, enabled = enabled) }
}

// ==================================================================== stepped slider (07 §6.9)

/**
 * A stepped slider from [min] to [max] in [step]s: header row with the value caption, 8 dp `panel` track cut by
 * 1.9 dp gaps at every step boundary, accent fill up to the thumb. Tap or drag moves to the nearest step; the value
 * is committed on lift ([onCommit]); the caption follows the drag live. [dimmed] shows the block at 40 % alpha.
 */
@Composable
fun SteppedSlider(
    header: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    valueText: (Int) -> String,
    onCommit: (Int) -> Unit,
    dimmed: Boolean = false,
    note: String? = null,
) {
    val m = LocalPageMargins.current
    val c = MetroTheme.colors
    val t = MetroTheme.type
    val steps = (max - min) / step
    var live by remember { mutableStateOf<Int?>(null) }
    val shown = live ?: value
    val commit by rememberUpdatedState(onCommit)
    Column(
        Modifier.block(BlockKind.SLIDER, note != null).fillMaxWidth().alpha(if (dimmed) DISABLED_ALPHA else 1f).margins(m)
            .semantics(mergeDescendants = true) {
                contentDescription = header
                stateDescription = valueText(shown)
                progressBarRangeInfo = ProgressBarRangeInfo(shown.toFloat(), min.toFloat()..max.toFloat(), steps - 1)
                setProgress { target ->
                    val v = (min + ((target - min) / step).roundToInt() * step).coerceIn(min, max)
                    commit(v)
                    true
                }
            },
    ) {
        // header cap top at the block top, track centre 96 px below it, 48 dp touch box centred on the track
        val capDp = with(LocalDensity.current) { rememberInk(t.small).capHeight.toDp() }
        Row(Modifier.fillMaxWidth().reportEdges(bottom = false).clearAndSetSemantics {}) {
            BasicText(header, Modifier.weight(1f).baselines(capDp), style = t.small.copy(color = c.textSecondary))
            BasicText(valueText(shown), Modifier.baselines(capDp), style = t.small.copy(color = c.textSecondary))
        }
        Spacer(Modifier.height(refPx(96 - 63) - capDp))
        Box(
            Modifier.fillMaxWidth().height(48.dp).layout { measurable, constraints ->
                val p = measurable.measure(constraints)
                // the track's bottom edge counts as the control's bottom (07 §6.2)
                layout(p.width, p.height, mapOf(InkBottom to p.height / 2 + 4.dp.roundToPx())) { p.place(0, 0) }
            }.pointerInput(min, max, step) {
                val thumbW = 10.7.dp.toPx()
                fun nearest(x: Float): Int {
                    val f = ((x - thumbW / 2) / (size.width - thumbW)).coerceIn(0f, 1f)
                    return min + (f * steps).roundToInt() * step
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    if (drag == null) {
                        val up = currentEvent.changes.firstOrNull { it.id == down.id }
                        if (up != null && !up.pressed && !up.isConsumed) commit(nearest(down.position.x))
                        return@awaitEachGesture
                    }
                    live = nearest(drag.position.x)
                    val completed = horizontalDrag(drag.id) { change ->
                        live = nearest(change.position.x)
                        change.consume()
                    }
                    val v = live
                    live = null
                    if (completed && v != null) commit(v)
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxWidth().height(21.dp)) {
                val thumbW = 10.7.dp.toPx()
                val trackH = 8.dp.toPx()
                val ty = (size.height - trackH) / 2
                val usable = size.width - thumbW
                fun x(k: Int) = thumbW / 2 + usable * k / steps
                val k = ((shown - min) / step).coerceIn(0, steps)
                drawRect(c.panel, Offset(0f, ty), Size(size.width, trackH))
                drawRect(c.accent, Offset(0f, ty), Size(x(k), trackH))
                val gap = OUTLINE.toPx()
                for (i in 1 until steps) drawRect(c.page, Offset(x(i) - gap / 2, ty), Size(gap, trackH))
                drawRect(c.outline, Offset(x(k) - thumbW / 2, 0f), Size(thumbW, size.height))
            }
        }
        if (note != null) Note(note, above = refPx(36) - 20.dp)
    }
}

/** Fixed-width spacer helper. */
@Composable
fun HSpace(w: Dp) = Spacer(Modifier.width(w))
