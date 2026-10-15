package app.metrokeys.settings.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlin.math.roundToInt
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown

// ==================================================================== overlay host

/**
 * Full-screen pickers, the accent grid and message boxes are drawn above the pages (they cover the status bar and
 * do not take part in the page transition). A control registers its overlay while it is open; the activity renders
 * them after the page display, so their Back handlers take precedence (07 §8).
 */
class OverlayHost {
    internal class Entry(val content: @Composable () -> Unit)

    internal val entries = mutableStateListOf<Entry>()

    @Composable
    fun Render() {
        for (e in entries) key(e) { e.content() }
    }
}

val LocalOverlayHost = staticCompositionLocalOf<OverlayHost> { error("no OverlayHost") }

/** Shows [content] above all pages while [visible]. */
@Composable
fun Overlay(visible: Boolean, content: @Composable () -> Unit) {
    if (!visible) return
    val host = LocalOverlayHost.current
    val latest = rememberUpdatedState(content)
    DisposableEffect(host) {
        val e = OverlayHost.Entry { latest.value() }
        host.entries.add(e)
        onDispose { host.entries.remove(e) }
    }
}

/** Collapses open inline pickers on a touch outside them (07 §6.6). */
class DismissRegistry {
    private class Entry(var bounds: Rect, val dismiss: () -> Unit)

    private val entries = LinkedHashMap<Any, Entry>()

    fun set(owner: Any, bounds: Rect, dismiss: () -> Unit) {
        val e = entries[owner]
        if (e == null) entries[owner] = Entry(bounds, dismiss) else e.bounds = bounds
    }

    fun remove(owner: Any) {
        entries.remove(owner)
    }

    /** Collapses every open picker outside [p]; true if one was collapsed (the gesture is then swallowed). */
    fun onDown(p: Offset): Boolean {
        var any = false
        for (e in entries.values.toList()) {
            if (!e.bounds.contains(p)) {
                e.dismiss()
                any = true
            }
        }
        return any
    }
}

val LocalDismissRegistry = staticCompositionLocalOf { DismissRegistry() }

/** Observes every touch-down of the window (without consuming it) for [DismissRegistry]. */
fun Modifier.observeDowns(registry: DismissRegistry): Modifier = pointerInput(registry) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (registry.onDown(down.position)) {
            down.consume()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
}

/** A full-screen `panel` surface with the content column of the pages; swallows touches. */
@Composable
private fun PanelSurface(onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onBack)
    val c = MetroTheme.colors
    val sides = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    BoxWithConstraints(
        Modifier.fillMaxSize().background(c.panel).pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
            }
        }.windowInsetsPadding(sides),
    ) {
        val content = minOf(maxWidth - 32.dp, 560.dp)
        val m = maxOf(16.dp, (maxWidth - content) / 2)
        CompositionLocalProvider(LocalPageMargins provides PageMargins(m, m)) {
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.safeDrawing))
                content()
            }
        }
    }
}

// ==================================================================== full-screen picker (07 §6.6)

/** One entry of a list picker; [swatch] and [icon] are optional. */
class PickerItem(val label: String, val swatch: Color? = null, val icon: ImageBitmap? = null)

/**
 * The full-screen list picker: `panel` over the whole screen, the title like the app title, items in `itemTitle`
 * with the selected one in the accent, rows of at least 57 dp. Tapping selects and closes; Back closes.
 */
@Composable
fun FullScreenPicker(title: String, items: List<PickerItem>, selected: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    PanelSurface(onDismiss) {
        val c = MetroTheme.colors
        val m = LocalPageMargins.current
        AppTitle(title, c.textOnPanel, Modifier.margins(m), bottom = 16.dp)
        val state = rememberLazyListState(initialFirstVisibleItemIndex = 0)
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = state) {
            itemsIndexed(items) { i, item ->
                val source = remember { MutableInteractionSource() }
                Box(
                    Modifier.fillMaxWidth().padding(end = m.end)
                        .selectable(i == selected, source, null, role = Role.Button) { onPick(i) },
                ) {
                    Row(
                        Modifier.fillMaxWidth().tilt(source, TiltAngles.LIST_ITEM).heightIn(min = 57.dp).padding(start = m.start),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (item.icon != null) {
                            Image(item.icon, null, Modifier.size(36.dp))
                            Spacer(Modifier.width(14.dp))
                        } else if (item.swatch != null) {
                            Box(Modifier.size(25.dp).background(item.swatch))
                            Spacer(Modifier.width(14.dp))
                        }
                        BasicText(
                            item.label, Modifier.padding(vertical = 8.dp),
                            style = MetroTheme.type.itemTitle.copy(color = if (i == selected) c.accent else c.textOnPanel),
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            item { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing).padding(bottom = 16.dp)) }
        }
    }
}

// ==================================================================== accent grid (07 §6.7)

/**
 * The accent colour grid: 4 columns of square cells (84.6 dp at the reference width, 13.7 dp gaps, at most 420 dp
 * wide), no labels, no visible selection; each cell exposes its colour name and selected state.
 */
@Composable
fun AccentGrid(title: String, names: List<String>, selected: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    PanelSurface(onDismiss) {
        val c = MetroTheme.colors
        val m = LocalPageMargins.current
        AppTitle(title, c.textOnPanel, Modifier.margins(m))
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).margins(m)) {
            val gap = 13.7.dp
            val gridW = minOf(maxWidth, 420.dp)
            val cell = (gridW - gap * 3) / 4
            Column(Modifier.padding(top = 31.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
                for (row in 0 until (Accents.COLORS.size + 3) / 4) {
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        for (col in 0 until 4) {
                            val i = row * 4 + col
                            if (i >= Accents.COLORS.size) break
                            val source = remember { MutableInteractionSource() }
                            Box(
                                Modifier.size(cell)
                                    .selectable(i == selected, source, null, role = Role.Button) { onPick(i) }
                                    .semantics { contentDescription = names[i] }
                                    .tilt(source, TiltAngles.ACCENT_CELL)
                                    .background(Accents.COLORS[i]),
                            )
                        }
                    }
                }
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
            }
        }
    }
}

// ==================================================================== message box (07 §6.10)

/**
 * A message box: `panel` from the top of the screen down to its content (min 168 dp), the rest dimmed by the
 * scrim. Left button confirms, right cancels; tapping the scrim or Back cancels.
 */
@Composable
fun MessageBox(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    cancel: String?,
    onCancel: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    val c = MetroTheme.colors
    val t = MetroTheme.type
    Column(Modifier.fillMaxSize()) {
        val sides = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
        BoxWithConstraints(Modifier.fillMaxWidth().background(c.panel).pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } }) {
            val contentW = minOf(maxWidth - 32.dp, 560.dp)
            val margin = maxOf(16.dp, (maxWidth - contentW) / 2)
            Column(Modifier.fillMaxWidth().heightIn(min = 168.dp).windowInsetsPadding(sides).padding(horizontal = margin)) {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.safeDrawing))
                BasicText(
                    title, Modifier.baselines(23.dp).semantics { heading() }, style = t.boxTitle.copy(color = c.textOnPanel),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                // the buttons' outline is 76 px (29 dp) below the glyph bottom of the last text line (07 §6.10);
                // their 48 dp touch boxes start 8 dp above the outline
                val textStyle = t.boxText.copy(color = c.textOnPanel)
                val holder = remember { TextLayoutHolder() }
                val ink = rememberInk(textStyle)
                BasicText(
                    text,
                    Modifier.layout { measurable, constraints ->
                        val p = measurable.measure(constraints)
                        val l = holder.layout
                        val depth = if (l != null) ink.lastInkBottom(l) - l.getLineBaseline(l.lineCount - 1).roundToInt() else 0
                        val last = p[LastBaseline].takeIf { it != AlignmentLine.Unspecified } ?: p.height
                        layout(p.width, last + depth + (refPx(76) - 8.dp).roundToPx()) { p.place(0, 0) }
                    }.baselines(41.dp),
                    style = textStyle, onTextLayout = { holder.layout = it },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MetroButton(confirm, onConfirm, Modifier.weight(1f), onPanel = true, fill = true)
                    if (cancel != null) MetroButton(cancel, onCancel, Modifier.weight(1f), onPanel = true, fill = true) else Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).background(c.scrim).clickable(remember { MutableInteractionSource() }, null, onClick = onCancel))
    }
}
