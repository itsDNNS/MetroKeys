package app.metrokeys.settings.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.graphics.Color

/** Side margins of the content column: 16 dp, more on wide windows so the column is at most 560 dp (07 §2, §5.1). */
@Immutable
data class PageMargins(val start: Dp, val end: Dp)

val LocalPageMargins = compositionLocalOf { PageMargins(16.dp, 16.dp) }

/** Padding by the page margins of [LocalPageMargins]. */
fun Modifier.margins(m: PageMargins): Modifier = padding(start = m.start, end = m.end)

/**
 * Places the content so that its first baseline lies [top] below the layout's top and makes the layout end [bottom]
 * below its last baseline. The spec gives positions as baseline distances (07 §5, §6).
 */
fun Modifier.baselines(top: Dp, bottom: Dp = 0.dp): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    val first = p[FirstBaseline]
    val last = p[LastBaseline]
    if (first == androidx.compose.ui.layout.AlignmentLine.Unspecified) {
        return@layout layout(p.width, p.height) { p.place(0, 0) }
    }
    val t = top.roundToPx()
    val h = t + (last - first) + bottom.roundToPx()
    layout(p.width, h) { p.place(0, t - first) }
}

/** Upper-cases the app title and full-screen picker titles with the current locale (07 §5.1: "ß" → "SS"). */
@Composable
fun upper(text: String): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(text, locale) { text.uppercase(locale) }
}

/** The small upper-case title above the page title, its baseline 21 dp below the top system inset. */
@Composable
fun AppTitle(text: String, color: Color, modifier: Modifier = Modifier, bottom: Dp = 0.dp) {
    BasicText(
        upper(text), modifier.baselines(21.dp, bottom).semantics { heading() },
        style = MetroTheme.type.appTitle.copy(color = color), maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/** The large page title: one line, shrinking in 1 sp steps down to 28 sp when it does not fit (07 §5.1). */
@Composable
private fun PageTitle(text: String, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val base = MetroTheme.type.pageTitle.copy(color = MetroTheme.colors.textPrimary)
    BoxWithConstraints(modifier) {
        val max = constraints.maxWidth
        val style = remember(text, max, base) {
            var s: TextStyle = base
            for (size in 48 downTo 28) {
                s = base.copy(fontSize = size.sp)
                if (measurer.measure(text, s, maxLines = 1, softWrap = false).size.width <= max) break
            }
            s
        }
        BasicText(
            text, Modifier.baselines(50.dp, 20.dp).semantics { heading() }, style = style, maxLines = 1,
            softWrap = false, overflow = TextOverflow.Clip,
        )
    }
}

/**
 * A page of the settings app (07 §5.1/§5.2): app title and optional page title stay fixed, the content below
 * scrolls, with 63.6 dp bottom padding plus the navigation bar. Content composables apply [LocalPageMargins]; the
 * content is a [BlockColumn], so blocks get the spacing of 07 §5.3.
 */
@Composable
fun MetroPage(
    title: String?,
    modifier: Modifier = Modifier,
    appTitle: String = "MetroKeys",
    scroll: ScrollState = rememberScrollState(),
    scrollable: Boolean = true,
    blockSpacing: Boolean = true,
    content: @Composable () -> Unit,
) {
    val c = MetroTheme.colors
    val sides = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    BoxWithConstraints(modifier.fillMaxSize().background(c.page).windowInsetsPadding(sides)) {
        val margins = remember(maxWidth) {
            val content = minOf(maxWidth - 32.dp, 560.dp)
            val m = maxOf(16.dp, (maxWidth - content) / 2)
            PageMargins(m, m)
        }
        CompositionLocalProvider(LocalPageMargins provides margins) {
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.safeDrawing))
                // without a page title, the first text line's cap top is 31 dp below the app title baseline (§5.2)
                AppTitle(appTitle, c.textPrimary, Modifier.margins(margins), bottom = if (title == null) 31.dp else 0.dp)
                if (title != null) PageTitle(title, Modifier.fillMaxWidth().margins(margins))
                val body = if (scrollable) Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll) else Modifier.weight(1f).fillMaxWidth()
                Column(body) {
                    BlockColumn(titled = title != null, Modifier.fillMaxWidth(), blockSpacing, content)
                    if (scrollable) {
                        Spacer(Modifier.height(63.6.dp))
                        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
                    }
                }
            }
        }
    }
}

/** Converts sp to dp at the current font scale. */
@Composable
fun spToDp(sp: Float): Dp = with(LocalDensity.current) { sp.sp.toDp() }
