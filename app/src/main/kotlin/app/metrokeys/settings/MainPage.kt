package app.metrokeys.settings

import android.content.Intent
import android.graphics.Typeface
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.metrokeys.R
import app.metrokeys.keyboard.UserAssets
import app.metrokeys.settings.ui.Accents
import app.metrokeys.settings.ui.BodyText
import app.metrokeys.settings.ui.FieldHeader
import app.metrokeys.settings.ui.ListItem
import app.metrokeys.settings.ui.MetroPage
import app.metrokeys.settings.ui.MetroTheme
import app.metrokeys.settings.ui.PageButton
import app.metrokeys.settings.ui.TextBox
import app.metrokeys.settings.ui.ToggleBlock
import java.io.File

/** The pages of the settings app (07 §9); the keys are the entries of the saved back stack. */
object Pages {
    const val MAIN = "main"
    const val LANGUAGES = "languages"
    const val SUGGESTIONS = "suggestions"
    const val TYPING = "typing"
    const val LAYOUT = "layout"
    const val APP_BAR_BUTTONS = "appbarbuttons"
    const val SOUND = "sound"
    const val SOUND_PACK = "soundpack"
    const val THEME = "theme"
    const val FONT = "font"
    const val DATA = "data"
    const val LICENSES = "licenses"
    const val LEARNED_WORDS = "learned"

    val ALL = setOf(
        MAIN, LANGUAGES, SUGGESTIONS, TYPING, LAYOUT, APP_BAR_BUTTONS, SOUND, SOUND_PACK, THEME, FONT, DATA, LICENSES,
        LEARNED_WORDS,
    )
}

fun interface Navigator {
    fun open(page: String)
}

val LocalStore = staticCompositionLocalOf<SettingsStore> { error("no SettingsStore") }
val LocalNavigator = staticCompositionLocalOf<Navigator> { error("no Navigator") }

/** State word of summaries: "on"/"off", "ein"/"aus" (07 §9.2). */
@Composable
fun stateWord(on: Boolean): String = stringResource(if (on) R.string.on_lc else R.string.off_lc)

@Composable
private fun pair(label: Int, on: Boolean): String = stringResource(R.string.state_pair, stringResource(label), stateWord(on))

/** A toggle bound to a boolean preference; [shown] overrides the displayed state (dependencies, 07 §6.3). */
@Composable
fun PrefToggle(
    header: String,
    key: String,
    default: Boolean,
    enabled: Boolean = true,
    note: String? = null,
    shown: Boolean? = null,
    dimmed: Boolean = false,
    onChange: ((Boolean) -> Unit)? = null,
) {
    val store = LocalStore.current
    val stored = store.bool(key, default)
    ToggleBlock(
        header = header, checked = shown ?: (enabled && stored),
        onChange = { v -> if (onChange != null) onChange(v) else store.set(key, v) },
        enabled = enabled, note = note, onLabel = stringResource(R.string.on), offLabel = stringResource(R.string.off),
        dimmed = dimmed,
    )
}

/** The regular face for the platform text box (07 §6.11). */
@Composable
fun regularTypeface(): Typeface {
    val context = LocalContext.current
    val resources = LocalResources.current
    val store = LocalStore.current
    val custom = store.string(Prefs.FONT_SOURCE, "bundled") == "custom"
    val stamp = store.long(Prefs.CUSTOM_ASSETS_STAMP, 0)
    return remember(custom, stamp) {
        val f = File(UserAssets.fontsDir(context), "regular")
        val face = if (custom && f.isFile) runCatching { Typeface.createFromFile(f) }.getOrNull() else null
        face ?: resources.getFont(R.font.selawik_regular)
    }
}

/** The main page (07 §9.2): setup status, try-it field and the list of sub-pages with live summaries. */
@Composable
fun MainPage(setup: SetupState) {
    val store = LocalStore.current
    val nav = LocalNavigator.current
    // the main page keeps its own spacing (07 §9.2); block spacing (§5.3) applies to the sub-pages
    MetroPage(stringResource(R.string.main_title), blockSpacing = false) {
        SetupStatus(setup)
        FieldHeader(stringResource(R.string.try_header), top = 18.dp)
        var text by rememberSaveable { mutableStateOf("") }
        TextBox(text, { text = it }, stringResource(R.string.try_hint), regularTypeface())
        Spacer(Modifier.height(12.dp))

        val langs = store.languages()
        val names = langs.map { stringResource(LanguageNames.res(it)) }
        val multi = langs.size >= 2 && store.bool(Prefs.MULTILINGUAL, true)
        val langSummary = names.joinToString(", ") + if (multi) stringResource(R.string.sum_multilingual) else ""
        ListItem(stringResource(R.string.page_languages), langSummary, { nav.open(Pages.LANGUAGES) })

        val sugg = store.bool(Prefs.SUGGESTIONS, true)
        val suggestions = listOf(
            pair(R.string.sum_suggestions, sugg),
            pair(R.string.sum_autocorrect, sugg && store.bool(Prefs.AUTOCORRECT, true)),
        ).joinToString(", ")
        ListItem(stringResource(R.string.page_suggestions), suggestions, { nav.open(Pages.SUGGESTIONS) })

        val typing = listOf(
            pair(R.string.sum_shape, store.bool(Prefs.SHAPE_WRITING, true)),
            pair(R.string.sum_history, store.bool(Prefs.CLIPBOARD_HISTORY, true)),
        ).joinToString(", ")
        ListItem(stringResource(R.string.page_typing), typing, { nav.open(Pages.TYPING) })

        val mode = when (store.string(Prefs.NAV_MODE, "appbar")) {
            "compact" -> R.string.sum_compact
            "off" -> R.string.nav_mode_off
            else -> R.string.nav_mode_appbar
        }
        val layout = stringResource(R.string.sum_layout, stringResource(R.string.percent, keyboardHeight(store)), stringResource(mode))
        ListItem(stringResource(R.string.page_layout), layout, { nav.open(Pages.LAYOUT) })

        // repeats the closed pickers' texts of the theme page, including "(default)" (07 §9.2)
        val theme = themeName(store.string(Prefs.THEME, "system")) + ", " + keyboardAccentLabel(store)
        ListItem(stringResource(R.string.page_theme), theme, { nav.open(Pages.THEME) })

        val sound = listOf(
            pair(R.string.sum_sounds, store.bool(Prefs.KEY_SOUNDS, true)),
            pair(R.string.sum_vibration, store.bool(Prefs.VIBRATION, false)),
        ).joinToString(", ")
        ListItem(stringResource(R.string.page_sound), sound, { nav.open(Pages.SOUND) })
        ListItem(stringResource(R.string.page_data), stringResource(R.string.sum_data), { nav.open(Pages.DATA) })
    }
}

/** The stored keyboard height in 5 % steps from 70 to 100 (07 §9.12 item 1). */
fun keyboardHeight(store: SettingsStore): Int = store.int(Prefs.KEYBOARD_HEIGHT, 100).coerceIn(70, 100) / 5 * 5

/** The text of the closed keyboard accent picker (07 §9.10): "cobalt (default)" for the default, else the name. */
@Composable
fun keyboardAccentLabel(store: SettingsStore): String {
    val stored = store.string(Prefs.ACCENT, "-1").toIntOrNull()?.takeIf { it in Accents.COLORS.indices }
    return if (stored == null || stored == Accents.DEFAULT) stringResource(R.string.accent_default)
    else stringArrayResource(R.array.accent_names)[store.keyboardAccent()]
}

@Composable
fun themeName(v: String): String = stringResource(
    when (v) {
        "light" -> R.string.theme_light
        "dark" -> R.string.theme_dark
        else -> R.string.theme_system
    },
)

/** Keyboard setup status (07 §9.3): a single list item when ready, else two numbered steps with buttons. */
@Composable
private fun SetupStatus(setup: SetupState) {
    val context = LocalContext.current
    val c = MetroTheme.colors
    fun picker() = context.getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    if (setup.ready) {
        ListItem(stringResource(R.string.setup_ready_title), stringResource(R.string.setup_ready_summary), { picker() })
        return
    }
    Column {
        FieldHeader(stringResource(R.string.setup_header), top = 18.dp)
        val step1 = stringResource(R.string.setup_step_number, 1, stringResource(R.string.setup_step1))
        val step2 = stringResource(R.string.setup_step_number, 2, stringResource(R.string.setup_step2))
        if (setup.enabled) {
            BodyText(stringResource(R.string.setup_done, step1), top = 4.dp, color = c.textSecondary)
        } else {
            BodyText(step1, top = 4.dp)
            PageButton(stringResource(R.string.setup_step1_button), {
                context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }, top = 2.dp)
        }
        if (setup.selected) {
            BodyText(stringResource(R.string.setup_done, step2), top = 10.dp, color = c.textSecondary)
        } else {
            BodyText(step2, top = if (setup.enabled) 10.dp else 2.dp)
            PageButton(stringResource(R.string.setup_step2_button), { picker() }, enabled = setup.enabled, top = 2.dp)
        }
    }
}
