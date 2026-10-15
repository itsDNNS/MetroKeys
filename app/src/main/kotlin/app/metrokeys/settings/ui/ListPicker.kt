package app.metrokeys.settings.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp

/**
 * A list picker (07 §6.6). Closed: field header and an outlined 32 dp box with the value (and an optional swatch).
 * With up to 5 items it opens inline: the box grows to n × 43 dp in 250 ms with S(10), white fill, accent outline,
 * the selected item in the accent. With more items (or [fullScreen]) it opens the full-screen picker titled
 * [fullScreenTitle]. [onOpen] replaces the list (accent grid, app chooser). Disabled: 40 % alpha.
 */
@Composable
fun ListPicker(
    header: String,
    items: List<PickerItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    enabled: Boolean = true,
    value: PickerItem? = null,
    fullScreen: Boolean = items.size > 5,
    fullScreenTitle: String = header,
    onOpen: (() -> Unit)? = null,
    note: String? = null,
) {
    val m = LocalPageMargins.current
    val c = MetroTheme.colors
    val t = MetroTheme.type
    var open by rememberSaveable { mutableStateOf(false) }
    val inline = !fullScreen && onOpen == null
    val shown = value ?: items.getOrNull(selected) ?: items.firstOrNull()
    val progress by animateFloatAsState(
        if (open && inline) 1f else 0f, tween(250, easing = MetroEasing.s(10f)), label = "picker",
    )
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val registry = LocalDismissRegistry.current
    val owner = remember { Any() }
    if (inline && open) {
        BackHandler { open = false }
        DisposableEffect(owner) { onDispose { registry.remove(owner) } }
    }
    Overlay(open && fullScreen && onOpen == null) {
        FullScreenPicker(upper(fullScreenTitle), items, selected, { i -> open = false; onSelect(i) }, { open = false })
    }

    Column(Modifier.block(BlockKind.PICKER, note != null).fillMaxWidth().alpha(if (enabled) 1f else DISABLED_ALPHA).margins(m)) {
        // from the header's glyph top to the box's outer top edge: 48 px (18.3 dp)
        val headerStyle = t.small.copy(color = c.textSecondary)
        val holder = remember { TextLayoutHolder() }
        val ink = rememberInk(headerStyle)
        BasicText(
            header, Modifier.reportEdges(bottom = false).inkBox(holder, ink, span = refPx(48)).clearAndSetSemantics {},
            style = headerStyle, maxLines = 1, onTextLayout = { holder.layout = it },
        )
        val rowH = 43.dp
        val closedH = 32.dp
        val openH = rowH * items.size
        val h = lerp(closedH, openH, progress)
        val expanded = progress > 0f || (open && inline)
        val boxModifier = Modifier.reportEdges(top = false).fillMaxWidth().height(h)
            .onGloballyPositioned { if (open && inline) registry.set(owner, it.boundsInRoot()) { open = false } }
        if (!expanded) {
            val label = shown?.label.orEmpty()
            Box(
                boxModifier
                    .clickable(source, null, enabled, role = Role.DropdownList) {
                        if (onOpen != null) onOpen() else open = true
                    }
                    .clearAndSetSemantics {
                        contentDescription = "$header: $label"
                        role = Role.DropdownList
                        if (!enabled) disabled()
                        onClick {
                            if (onOpen != null) onOpen() else open = true
                            true
                        }
                    }
                    .tilt(source, TiltAngles.LIST_PICKER, enabled)
                    .background(if (pressed) c.pickerOpenFill else Color.Transparent)
                    .border(1.9.dp, if (pressed) c.accent else c.outline),
                contentAlignment = Alignment.CenterStart,
            ) {
                PickerRow(shown, if (pressed) c.pickerOpenItem else c.textPrimary)
            }
        } else {
            // the list slides so that the selected row starts where the closed value was
            val shift = -(rowH * selected.coerceAtLeast(0)) * (1f - progress) - (rowH - closedH) / 2 * (1f - progress)
            Box(boxModifier.clipToBounds().background(c.pickerOpenFill).border(1.9.dp, c.accent)) {
                Column(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).offset(y = shift)) {
                    items.forEachIndexed { i, item ->
                        Box(
                            Modifier.fillMaxWidth().height(rowH)
                                .selectable(i == selected, remember { MutableInteractionSource() }, null, enabled = open) {
                                    open = false
                                    onSelect(i)
                                },
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            PickerRow(item, if (i == selected) c.accent else c.pickerOpenItem)
                        }
                    }
                }
            }
        }
        if (note != null) Note(note)
    }
}

@Composable
private fun PickerRow(item: PickerItem?, color: Color) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 7.2.dp), verticalAlignment = Alignment.CenterVertically) {
        if (item?.icon != null) {
            Image(item.icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(9.dp))
        } else if (item?.swatch != null) {
            Box(Modifier.size(13.3.dp).background(item.swatch))
            Spacer(Modifier.width(9.dp))
        }
        BasicText(
            item?.label.orEmpty(), style = MetroTheme.type.pickerValue.copy(color = color), maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun lerp(a: Dp, b: Dp, f: Float): Dp = Dp(lerp(a.value, b.value, f))
