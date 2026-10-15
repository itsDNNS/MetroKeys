package app.metrokeys.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.withClip
import app.metrokeys.R
import app.metrokeys.keyboard.Glyphs
import app.metrokeys.keyboard.Theme
import app.metrokeys.settings.ui.BlockKind
import app.metrokeys.settings.ui.BodyText
import app.metrokeys.settings.ui.FieldHeader
import app.metrokeys.settings.ui.FullScreenPicker
import app.metrokeys.settings.ui.ListItem
import app.metrokeys.settings.ui.ListPicker
import app.metrokeys.settings.ui.LocalPageMargins
import app.metrokeys.settings.ui.MetroPage
import app.metrokeys.settings.ui.MetroTheme
import app.metrokeys.settings.ui.Overlay
import app.metrokeys.settings.ui.PickerItem
import app.metrokeys.settings.ui.SteppedSlider
import app.metrokeys.settings.ui.TextLayoutHolder
import app.metrokeys.settings.ui.TiltAngles
import app.metrokeys.settings.ui.block
import app.metrokeys.settings.ui.margins
import app.metrokeys.settings.ui.rememberInk
import app.metrokeys.settings.ui.reportEdges
import app.metrokeys.settings.ui.reportInk
import app.metrokeys.settings.ui.tilt
import app.metrokeys.settings.ui.upper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import kotlin.math.roundToInt

/**
 * An app-bar action of 07 §9.12.1: stored id, settings label, the keyboard's pictogram name
 * ([Glyphs.appBarIcon]) and the keyboard's short label of the expanded bar.
 */
private class BarAction(val id: String, @param:StringRes val label: Int, val icon: String, @param:StringRes val barLabel: Int)

/** The 15 actions in the order of 07 §9.12.1. */
private val ACTIONS = listOf(
    BarAction("editor", R.string.action_editor, "send", R.string.bar_send),
    BarAction("hide", R.string.action_hide, "hide", R.string.bar_hide),
    BarAction("switch", R.string.action_switch, "switch", R.string.bar_switch),
    BarAction("home", R.string.action_home, "home", R.string.bar_home),
    BarAction("search", R.string.action_search, "web", R.string.bar_web),
    BarAction("emoji", R.string.action_emoji, "emoji", R.string.bar_emoji),
    BarAction("clipboard", R.string.action_clipboard, "clipboard", R.string.bar_clipboard),
    BarAction("language", R.string.action_language, "language", R.string.bar_language),
    BarAction("left", R.string.action_left, "left", R.string.bar_left),
    BarAction("right", R.string.action_right, "right", R.string.bar_right),
    BarAction("undo", R.string.action_undo, "undo", R.string.bar_undo),
    BarAction("voice", R.string.action_voice, "voice", R.string.bar_voice),
    BarAction("settings", R.string.action_settings, "settings", R.string.bar_settings),
    BarAction("app", R.string.action_app, "app", R.string.bar_app_missing),
    BarAction("none", R.string.action_none, "none", R.string.appbar_none_label),
)

private fun actionOf(value: String): BarAction =
    ACTIONS.firstOrNull { it.id == if (value.startsWith("app:")) "app" else value } ?: ACTIONS.last()

private fun bindingKey(button: Int, hold: Boolean): String = (if (hold) Prefs.NAV_HOLD else Prefs.NAV_TAP) + button

private fun bindingDefault(button: Int, hold: Boolean): String = if (hold) "none" else Prefs.DEFAULT_NAV_TAP[button]

private class LaunchableApp(val pkg: String, val label: String, val icon: ImageBitmap?)

private fun launchableApps(context: Context, iconPx: Int): List<LaunchableApp> {
    val pm = context.packageManager
    val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val collator = Collator.getInstance(context.resources.configuration.locales[0])
    return pm.queryIntentActivities(main, 0).asSequence()
        .filter { it.activityInfo.packageName != context.packageName }
        .distinctBy { it.activityInfo.packageName }
        .map { ri ->
            val icon = runCatching { ri.loadIcon(pm).toBitmap(iconPx, iconPx).asImageBitmap() }.getOrNull()
            LaunchableApp(ri.activityInfo.packageName, ri.loadLabel(pm).toString(), icon)
        }
        .sortedWith { a, b -> collator.compare(a.label, b.label) }
        .toList()
}

/** Label and icon of an installed app, or null when it is no longer installed. */
private class InstalledApp(val label: String, val icon: Drawable)

private fun installedApp(context: Context, pkg: String): InstalledApp? = try {
    val pm = context.packageManager
    val ai = pm.getApplicationInfo(pkg, 0)
    InstalledApp(pm.getApplicationLabel(ai).toString(), pm.getApplicationIcon(ai))
} catch (_: PackageManager.NameNotFoundException) {
    null
}

@Composable
private fun rememberApp(value: String): InstalledApp? {
    val context = LocalContext.current
    val pkg = value.removePrefix("app:").takeIf { value.startsWith("app:") } ?: return null
    // re-read on resume: the app may have been removed meanwhile
    val resumes = LocalStore.current.resumes
    return remember(pkg, resumes) { installedApp(context, pkg) }
}

/** The settings name of a binding: "text field action", "open app: <label>", "emoji" … (07 §9.12 item 3). */
@Composable
private fun bindingName(value: String): String {
    val a = actionOf(value)
    return when (a.id) {
        "editor" -> stringResource(R.string.action_editor_short)
        "app" -> appEntryName(value)
        else -> stringResource(a.label)
    }
}

@Composable
private fun appEntryName(value: String): String {
    if (!value.startsWith("app:")) return stringResource(R.string.action_app)
    val app = rememberApp(value)
    return if (app != null) stringResource(R.string.nav_app_named, app.label) else stringResource(R.string.nav_app_missing)
}

/** Layout page (07 §9.12): keyboard height, what sits below the keys and the app bar buttons. */
@Composable
fun LayoutPage() {
    val store = LocalStore.current
    val nav = LocalNavigator.current
    val resources = LocalResources.current
    MetroPage(stringResource(R.string.page_layout)) {
        val height = keyboardHeight(store)
        SteppedSlider(
            stringResource(R.string.keyboard_height), height, 70, 100, 5,
            { v -> resources.getString(R.string.percent, v) },
            { v -> store.set(Prefs.KEYBOARD_HEIGHT, v) },
            note = stringResource(R.string.keyboard_height_note, height),
        )
        val modes = listOf("appbar", "compact", "off")
        val mode = store.string(Prefs.NAV_MODE, "appbar")
        ListPicker(
            stringResource(R.string.nav_mode),
            listOf(
                PickerItem(stringResource(R.string.nav_mode_appbar)),
                PickerItem(stringResource(R.string.nav_mode_compact)),
                PickerItem(stringResource(R.string.nav_mode_off)),
            ),
            modes.indexOf(mode).coerceAtLeast(0), { i -> store.set(Prefs.NAV_MODE, modes[i]) },
        )
        if (mode == "appbar") {
            val taps = (0..2).map { bindingName(store.string(bindingKey(it, false), bindingDefault(it, false))) }
            ListItem(stringResource(R.string.page_appbar_buttons), taps.joinToString(", "), { nav.open(Pages.APP_BAR_BUTTONS) })
        }
    }
}

/**
 * App bar buttons page (07 §9.12.1): a live preview of the expanded app bar with a selected button, the tap/hold
 * switch and a grid of every action; a tap on a tile binds it at once.
 */
@Composable
fun AppBarButtonsPage() {
    val store = LocalStore.current
    val context = LocalContext.current
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var hold by rememberSaveable { mutableStateOf(false) }
    var chooser by rememberSaveable { mutableStateOf<String?>(null) }
    val noMic = stringResource(R.string.nav_no_mic)
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        store.resumed()
        if (!granted) Toast.makeText(context, noMic, Toast.LENGTH_LONG).show()
    }
    val names = listOf(R.string.nav_left, R.string.nav_center, R.string.nav_right).map { stringResource(it) }
    val values = (0..2).map { store.string(bindingKey(it, hold), bindingDefault(it, hold)) }
    MetroPage(stringResource(R.string.page_appbar_buttons)) {
        BodyText(stringResource(R.string.appbar_buttons_intro))
        AppBarPreview(values, selected, { selected = it }, names)
        ModeSwitch(hold) { hold = it }
        val mode = stringResource(if (hold) R.string.nav_hold else R.string.nav_tap)
        FieldHeader(stringResource(R.string.appbar_grid_header, names[selected], mode))
        ActionGrid(values[selected]) { a ->
            val key = bindingKey(selected, hold)
            when (a.id) {
                "app" -> chooser = key
                else -> {
                    store.set(key, a.id)
                    if (a.id == "voice" && !store.permittedNow(Manifest.permission.RECORD_AUDIO)) {
                        mic.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
        }
    }
    val target = chooser
    Overlay(target != null) {
        val px = with(LocalDensity.current) { 36.dp.roundToPx() }
        val apps by produceState<List<LaunchableApp>?>(null) {
            value = withContext(Dispatchers.IO) { launchableApps(context, px) }
        }
        val list = apps.orEmpty()
        val current = store.string(target.orEmpty(), "").removePrefix("app:")
        FullScreenPicker(
            upper(stringResource(R.string.nav_pick_app)),
            list.map { PickerItem(it.label, icon = it.icon) },
            list.indexOfFirst { it.pkg == current },
            { k ->
                if (target != null) store.set(target, "app:" + list[k].pkg)
                chooser = null
            },
            { chooser = null },
        )
    }
}

/** The keyboard's colours as the keyboard resolves them (theme, high contrast, accent; 02 §8, §9). */
@Composable
private fun keyboardTheme(): Theme {
    val store = LocalStore.current
    val context = LocalContext.current
    val night = isSystemInDarkTheme()
    val dark = when (store.string(Prefs.THEME, "system")) {
        "dark" -> true
        "light" -> false
        else -> night
    }
    store.resumes
    val hc = when (store.string(Prefs.HIGH_CONTRAST, "system")) {
        "on" -> true
        "off" -> false
        else -> Theme.systemHighContrast(context)
    }
    val accent = store.keyboardAccent()
    return remember(dark, hc, accent) { Theme.create(dark, hc, accent) }
}

/** What the keyboard's bar shows for a binding: pictogram, short label, enabled state, app icon. */
private class Shown(val icon: String, val label: String, val enabled: Boolean, val app: Drawable?)

@Composable
private fun shown(value: String): Shown {
    val a = actionOf(value)
    if (a.id == "app") {
        val app = rememberApp(value)
        return if (app != null) Shown("app", app.label.lowercase(), true, app.icon)
        else Shown("app", stringResource(R.string.bar_app_missing), false, null)
    }
    val enabled = a.id != "language" || LocalStore.current.languages().size >= 2
    return Shown(a.icon, stringResource(a.barLabel), enabled, null)
}

/** Draws [d] into the circle of radius [r] around ([cx], [cy]), clipped to it. */
private fun DrawScope.drawClippedIcon(d: Drawable, cx: Float, cy: Float, r: Float) {
    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        val clip = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        nc.withClip(clip) {
            d.setBounds((cx - r).roundToInt(), (cy - r).roundToInt(), (cx + r).roundToInt(), (cy + r).roundToInt())
            d.draw(this)
        }
    }
}

/**
 * A round app-bar button of radius [r]: ring 6/41 of the radius, pictogram stroke 5/41 (02 §5.11). [selected]: filled
 * with [accent] and the pictogram in [onAccent]; an app icon is clipped to the circle.
 */
private fun DrawScope.drawRoundButton(
    glyphs: Glyphs, icon: String, app: Drawable?, cx: Float, cy: Float, r: Float, color: Int, selected: Boolean,
    accent: Int, onAccent: Int, fill: Paint, ring: Paint,
) {
    val k = r / 41f
    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        if (selected) {
            fill.color = accent
            nc.drawCircle(cx, cy, r, fill)
        } else if (app == null) {
            ring.color = color
            ring.strokeWidth = 6f * k
            nc.drawCircle(cx, cy, r - 3f * k, ring)
        }
        if (app == null) glyphs.appBarIcon(nc, icon, cx, cy, 0.45f * r, 5f * k, if (selected) onAccent else color)
    }
    if (app != null) drawClippedIcon(app, cx, cy, if (selected) r - 6f * k else r)
}

/**
 * The live preview of the expanded app bar (07 §9.12.1 item 2): full page width in the keyboard background, three
 * buttons at the keyboard's positions with the label row below and the inactive "…" dots; a tap selects a button.
 */
@Composable
private fun AppBarPreview(values: List<String>, selected: Int, onSelect: (Int) -> Unit, names: List<String>) {
    val theme = keyboardTheme()
    val typeface = regularTypeface()
    val glyphs = remember { Glyphs() }
    val paints = remember { PreviewPaints() }
    val shownAll = values.map { shown(it) }
    val descriptions = values.map { bindingName(it) }
    BoxWithConstraints(Modifier.block(BlockKind.PREVIEW).fillMaxWidth().reportEdges()) {
        val w = constraints.maxWidth.toFloat()
        val s = w / 1080f
        val density = LocalDensity.current
        val barH = 126f * s
        val totalH = barH + 48f * s
        val hDp = with(density) { totalH.toDp() }
        Box(
            Modifier.fillMaxWidth().height(hDp).background(Color(theme.keyboardBackground)).drawBehind {
                // "…" dots: rightmost centre 46 px from the right edge, 26 px below the bar top; inactive
                drawIntoCanvas { c ->
                    paints.fill.color = theme.appBarIcon
                    for (i in 0..2) c.nativeCanvas.drawCircle(size.width - 46f * s - i * 18f * s, 26f * s, 4f * s, paints.fill)
                }
            },
        )
        for (i in 0..2) {
            key(i) {
                val cx = w / 2f + (i - 1) * 148f * s
                val source = remember { MutableInteractionSource() }
                val b = shownAll[i]
                val isSelected = i == selected
                val description = names[i] + ": " + descriptions[i]
                Box(
                    Modifier.offset { IntOffset((cx - 74f * s).roundToInt(), 0) }
                        .width(with(density) { (148f * s).toDp() }).height(hDp)
                        .selectable(isSelected, interactionSource = source, indication = null, role = Role.Tab) { onSelect(i) }
                        .semantics { contentDescription = description }
                        .tilt(source, TiltAngles.BUTTON)
                        .drawBehind {
                            val color = if (b.enabled) theme.appBarIcon else theme.appBarIconDisabled
                            val mid = size.width / 2f
                            drawRoundButton(
                                glyphs, b.icon, b.app, mid, barH / 2f, 41f * s, color, isSelected,
                                theme.accent, 0xFFFFFFFF.toInt(), paints.fill, paints.ring,
                            )
                            // label row: glyph top 3 px below the bar, regular 26 px, truncated to 136 px
                            val tp = paints.text
                            tp.typeface = typeface
                            tp.textSize = 26f * s
                            tp.color = if (isSelected) theme.accent else color
                            tp.getTextBounds("dl", 0, 2, paints.bounds)
                            val baseline = barH + 3f * s - paints.bounds.top
                            val text = TextUtils.ellipsize(b.label, tp, 136f * s, TextUtils.TruncateAt.END).toString()
                            drawIntoCanvas { it.nativeCanvas.drawText(text, mid - tp.measureText(text) / 2f, baseline, tp) }
                        },
                )
            }
        }
    }
}

private class PreviewPaints {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }
    val bounds = Rect()
}

/** The tap/hold switch (07 §9.12.1 item 3): two `itemTitle` texts like pivot headers; a group-title block. */
@Composable
private fun ModeSwitch(hold: Boolean, onChange: (Boolean) -> Unit) {
    val m = LocalPageMargins.current
    val c = MetroTheme.colors
    Row(Modifier.block(BlockKind.GROUP_TITLE).fillMaxWidth().margins(m), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        for (h in listOf(false, true)) {
            key(h) {
                val style = MetroTheme.type.itemTitle.copy(color = if (h == hold) c.textPrimary else c.textSecondary)
                val holder = remember { TextLayoutHolder() }
                val ink = rememberInk(style)
                val source = remember { MutableInteractionSource() }
                BasicText(
                    stringResource(if (h) R.string.nav_hold else R.string.nav_tap),
                    Modifier.selectable(h == hold, interactionSource = source, indication = null, role = Role.Tab) { onChange(h) }
                        .padding(vertical = 8.dp).reportInk(holder, ink),
                    style = style, maxLines = 1, onTextLayout = { holder.layout = it },
                )
            }
        }
    }
}

/**
 * The action grid (07 §9.12.1 item 4): 4 columns of round 41 dp buttons with the keyboard's pictograms and a label
 * below; the bound action is accent-filled. The "open app…" tile shows the bound app's icon.
 */
@Composable
private fun ActionGrid(current: String, onPick: (BarAction) -> Unit) {
    val m = LocalPageMargins.current
    val c = MetroTheme.colors
    val glyphs = remember { Glyphs() }
    val paints = remember { PreviewPaints() }
    val currentAction = actionOf(current)
    val app = rememberApp(current)
    Column(Modifier.block(BlockKind.GRID).fillMaxWidth().margins(m).reportEdges(bottom = false)) {
        for ((row, actions) in ACTIONS.chunked(4).withIndex()) {
            Row(Modifier.fillMaxWidth()) {
                for (a in actions) {
                    key(a.id) {
                        val isSelected = a === currentAction
                        val label = if (a.id == "app") appEntryName(if (isSelected) current else "app") else stringResource(a.label)
                        val icon = if (a.id == "app" && isSelected) app?.icon else null
                        val source = remember { MutableInteractionSource() }
                        Column(
                            Modifier.weight(1f)
                                .selectable(isSelected, interactionSource = source, indication = null, role = Role.RadioButton) { onPick(a) }
                                .tilt(source, TiltAngles.ACCENT_CELL)
                                .padding(top = if (row == 0) 0.dp else 10.dp, bottom = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier.size(41.dp).drawBehind {
                                    drawRoundButton(
                                        glyphs, a.icon, icon, size.width / 2f, size.height / 2f, size.minDimension / 2f,
                                        c.textPrimary.toArgb(), isSelected, c.accent.toArgb(), c.onAccent.toArgb(),
                                        paints.fill, paints.ring,
                                    )
                                },
                            )
                            BasicText(
                                label, Modifier.padding(top = 6.dp, start = 1.dp, end = 1.dp),
                                // long German compounds break at their hyphen or by hyphenation, not mid-syllable
                                style = MetroTheme.type.small.copy(
                                    color = c.textPrimary, textAlign = TextAlign.Center, hyphens = Hyphens.Auto,
                                    lineBreak = LineBreak.Paragraph,
                                ),
                                maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                repeat(4 - actions.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
