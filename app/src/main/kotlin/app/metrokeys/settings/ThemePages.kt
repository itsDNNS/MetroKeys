package app.metrokeys.settings

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import app.metrokeys.R
import app.metrokeys.settings.ui.AccentGrid
import app.metrokeys.settings.ui.Accents
import app.metrokeys.settings.ui.BodyText
import app.metrokeys.settings.ui.ButtonPair
import app.metrokeys.settings.ui.FieldHeader
import app.metrokeys.settings.ui.ListItem
import app.metrokeys.settings.ui.ListPicker
import app.metrokeys.settings.ui.LocalPageMargins
import app.metrokeys.settings.ui.MetroPage
import app.metrokeys.settings.ui.MetroTheme
import app.metrokeys.settings.ui.PageNote
import app.metrokeys.settings.ui.Overlay
import app.metrokeys.settings.ui.PickerItem
import app.metrokeys.settings.ui.margins
import app.metrokeys.settings.ui.upper
import androidx.compose.foundation.layout.fillMaxWidth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val GRID_KEYBOARD = "keyboard"
private const val GRID_APP = "app"

/** Theme page (07 §9.10). */
@Composable
fun ThemePage() {
    val store = LocalStore.current
    val nav = LocalNavigator.current
    val names = stringArrayResource(R.array.accent_names).toList()
    var grid by rememberSaveable { mutableStateOf<String?>(null) }
    val appColor = store.string(Prefs.APP_COLOR, "keyboard")
    val own = appColor.toIntOrNull()?.takeIf { it in Accents.COLORS.indices }
    MetroPage(stringResource(R.string.page_theme)) {
        BodyText(stringResource(R.string.theme_intro))
        val themes = listOf("system", "light", "dark")
        ListPicker(
            stringResource(R.string.background), themes.map { PickerItem(themeName(it)) },
            themes.indexOf(store.string(Prefs.THEME, "system")).coerceAtLeast(0), { i -> store.set(Prefs.THEME, themes[i]) },
        )

        val kb = store.keyboardAccent()
        val accentItem = PickerItem(keyboardAccentLabel(store), Accents.COLORS[kb])
        ListPicker(
            stringResource(R.string.accent), listOf(accentItem), 0, {}, value = accentItem,
            onOpen = { grid = GRID_KEYBOARD },
        )

        val system = Build.VERSION.SDK_INT >= 31
        val values = buildList {
            add("keyboard")
            if (system) add("system")
            add("custom")
        }
        val items = values.map { v ->
            when (v) {
                "keyboard" -> PickerItem(stringResource(R.string.app_color_keyboard), Accents.COLORS[kb])
                "system" -> PickerItem(stringResource(R.string.app_color_system), store.systemAccent())
                else -> PickerItem(
                    if (own != null) names[own] else stringResource(R.string.app_color_custom),
                    Accents.COLORS[own ?: kb],
                )
            }
        }
        val current = when {
            own != null -> values.indexOf("custom")
            appColor == "system" && system -> values.indexOf("system")
            else -> 0
        }
        ListPicker(stringResource(R.string.app_color), items, current, { i ->
            when (values[i]) {
                "custom" -> grid = GRID_APP
                else -> store.set(Prefs.APP_COLOR, values[i])
            }
        })

        val hc = listOf("system", "on", "off")
        ListPicker(
            stringResource(R.string.high_contrast),
            listOf(
                PickerItem(stringResource(R.string.high_contrast_system)),
                PickerItem(stringResource(R.string.high_contrast_on)),
                PickerItem(stringResource(R.string.high_contrast_off)),
            ),
            hc.indexOf(store.string(Prefs.HIGH_CONTRAST, "system")).coerceAtLeast(0), { i -> store.set(Prefs.HIGH_CONTRAST, hc[i]) },
        )

        val fontSource = store.string(Prefs.FONT_SOURCE, "bundled")
        ListItem(
            stringResource(R.string.font),
            stringResource(if (fontSource == "custom") R.string.font_source_custom else R.string.font_source_bundled),
            { nav.open(Pages.FONT) },
        )
    }
    Overlay(grid != null) {
        val forApp = grid == GRID_APP
        AccentGrid(
            upper(stringResource(R.string.accent_grid_title)), names,
            if (forApp) own ?: kbAccentOf(store) else kbAccentOf(store),
            { i ->
                if (forApp) store.set(Prefs.APP_COLOR, i.toString()) else store.set(Prefs.ACCENT, i.toString())
                grid = null
            },
            { grid = null },
        )
    }
}

private fun kbAccentOf(store: SettingsStore): Int = store.keyboardAccent()

private val FONT_MIME = arrayOf(
    "font/*", "application/x-font-ttf", "application/font-sfnt", "application/vnd.ms-opentype", "application/octet-stream",
)

/** Font page (07 §9.11): the user's own fonts, the weight read from the file. */
@Composable
fun FontPage() {
    val store = LocalStore.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importer = remember { AssetImporter(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) { importer.importFonts(uris) }
            importer.finish(store, Prefs.FONT_SOURCE, r)
        }
    }
    MetroPage(stringResource(R.string.font)) {
        BodyText(stringResource(R.string.font_intro))
        val values = listOf("bundled", "custom")
        ListPicker(
            stringResource(R.string.source),
            listOf(PickerItem(stringResource(R.string.font_source_bundled)), PickerItem(stringResource(R.string.font_source_custom))),
            values.indexOf(store.string(Prefs.FONT_SOURCE, "bundled")).coerceAtLeast(0),
            { i -> store.set(Prefs.FONT_SOURCE, values[i]) },
        )
        ButtonPair(
            stringResource(R.string.font_import), { launcher.launch(FONT_MIME) },
            stringResource(R.string.remove), {
                importer.removeFonts()
                importer.removed(store, Prefs.FONT_SOURCE, "bundled")
            },
        )
        FieldHeader(stringResource(R.string.font_weights))
        PageNote(stringResource(R.string.font_weights_note))
        val stamp = store.long(Prefs.CUSTOM_ASSETS_STAMP, 0)
        // rows in order of importance, each title naming what the weight is used for (07 §9.11 item 5)
        val names = linkedMapOf(
            "regular" to R.string.font_slot_regular, "semilight" to R.string.font_slot_semilight,
            "semibold" to R.string.font_slot_semibold, "bold" to R.string.font_slot_bold,
            "light" to R.string.font_slot_light, "emoji" to R.string.font_slot_emoji,
        )
        for (slot in names.keys) {
            val file = importer.fontFile(slot)
            val has = file.isFile
            val family = remember(slot, stamp, has) { if (has) runCatching { FontFamily(Font(file)) }.getOrNull() else null }
            ListItem(
                stringResource(names.getValue(slot)),
                stringResource(if (has) R.string.asset_present else R.string.asset_missing),
                below = if (family != null) {
                    {
                        BasicText(
                            stringResource(if (slot == "emoji") R.string.font_sample_emoji else R.string.font_sample),
                            Modifier.padding(top = 4.dp, bottom = 8.dp),
                            style = MetroTheme.type.pickerValue.copy(fontFamily = family, fontSize = 22.sp, color = MetroTheme.colors.textPrimary),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}
