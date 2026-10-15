package app.metrokeys.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.metrokeys.R
import app.metrokeys.engine.predict.Params
import app.metrokeys.settings.ui.BodyText
import app.metrokeys.settings.ui.CheckBoxRow
import app.metrokeys.settings.ui.FieldHeader
import app.metrokeys.settings.ui.ListPicker
import app.metrokeys.settings.ui.MessageBox
import app.metrokeys.settings.ui.MetroPage
import app.metrokeys.settings.ui.Overlay
import app.metrokeys.settings.ui.PickerItem

/** Languages page (07 §9.5). */
@Composable
fun LanguagesPage() {
    val store = LocalStore.current
    val context = LocalContext.current
    MetroPage(stringResource(R.string.page_languages)) {
        if (AppLocale.supported) {
            val tags = listOf("", "de", "en")
            val items = listOf(
                PickerItem(stringResource(R.string.app_language_system)),
                PickerItem(stringResource(R.string.app_language_de)),
                PickerItem(stringResource(R.string.app_language_en)),
            )
            store.resumes
            val current = AppLocale.current(context)
            ListPicker(
                stringResource(R.string.app_language), items, tags.indexOf(current).coerceAtLeast(0),
                { i -> AppLocale.set(context, tags[i]) }, note = stringResource(R.string.app_language_note),
            )
        }
        BodyText(stringResource(R.string.languages_intro))
        FieldHeader(stringResource(R.string.languages_header))
        val langs = store.languages()
        // one check box per supported language, always all of them, in the fixed language order (07 §9.5 item 3)
        for (tag in Prefs.SUPPORTED_LANGUAGES) {
            val checked = tag in langs
            val last = checked && langs.size == 1
            CheckBoxRow(
                stringResource(LanguageNames.res(tag)), checked,
                { on -> store.setLanguages(if (on) langs + tag else langs - tag) },
                enabled = !last, note = if (last) stringResource(R.string.languages_min) else null,
            )
        }
        val avail = langs.size >= 2
        PrefToggle(
            stringResource(R.string.multilingual), Prefs.MULTILINGUAL, true, enabled = avail,
            note = stringResource(
                when {
                    !avail -> R.string.multilingual_off
                    langs.size > Params.LANG_ACTIVE_MAX -> R.string.multilingual_note_many
                    else -> R.string.multilingual_note
                },
            ),
        )
        PrefToggle(
            stringResource(R.string.comma_key), Prefs.SHOW_COMMA_KEY, false, enabled = avail,
            note = stringResource(if (avail) R.string.comma_key_note else R.string.multilingual_off),
        )
    }
}

private const val BOX_ASK = "ask"
private const val BOX_DENIED = "denied"
private const val BOX_BLOCKED = "blocked"

/** Suggestions page (07 §9.6) with the contacts permission flow. */
@Composable
fun SuggestionsPage() {
    val store = LocalStore.current
    val context = LocalContext.current
    var box by rememberSaveable { mutableStateOf<String?>(null) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        store.resumed()
        if (granted) {
            store.set(Prefs.CONTACTS_WORD_SOURCE, true)
        } else {
            val again = (context as? Activity)?.shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS) == true
            box = if (again) BOX_DENIED else BOX_BLOCKED
        }
    }
    MetroPage(stringResource(R.string.page_suggestions)) {
        val sugg = store.bool(Prefs.SUGGESTIONS, true)
        PrefToggle(stringResource(R.string.suggestions), Prefs.SUGGESTIONS, true)
        PrefToggle(stringResource(R.string.autocorrect), Prefs.AUTOCORRECT, true, enabled = sugg)
        PrefToggle(stringResource(R.string.space_after), Prefs.SPACE_AFTER_SUGGESTION, true, enabled = sugg)
        PrefToggle(
            stringResource(R.string.spelling_underline), Prefs.SPELLING_UNDERLINE, true, enabled = sugg,
            note = stringResource(R.string.spelling_underline_note),
        )
        val permitted = store.permitted(Manifest.permission.READ_CONTACTS)
        PrefToggle(
            stringResource(R.string.contacts), Prefs.CONTACTS_WORD_SOURCE, false,
            note = stringResource(R.string.contacts_note),
            shown = permitted && store.bool(Prefs.CONTACTS_WORD_SOURCE, false),
        ) { on ->
            when {
                !on -> store.set(Prefs.CONTACTS_WORD_SOURCE, false)
                store.permittedNow(Manifest.permission.READ_CONTACTS) -> store.set(Prefs.CONTACTS_WORD_SOURCE, true)
                else -> box = BOX_ASK
            }
        }
        // independent of word suggestions (07 §9.6 item 6)
        val autofill = Build.VERSION.SDK_INT >= 30
        PrefToggle(
            stringResource(R.string.autofill), Prefs.AUTOFILL_INLINE, true, enabled = autofill,
            note = stringResource(if (autofill) R.string.autofill_note else R.string.autofill_unavailable),
        )
    }
    Overlay(box == BOX_ASK) {
        MessageBox(
            stringResource(R.string.contacts_box_title), stringResource(R.string.contacts_box_text),
            stringResource(R.string.contacts_continue), {
                box = null
                request.launch(Manifest.permission.READ_CONTACTS)
            },
            stringResource(R.string.cancel), { box = null },
        )
    }
    Overlay(box == BOX_DENIED) {
        MessageBox(
            stringResource(R.string.contacts_box_title), stringResource(R.string.contacts_denied),
            stringResource(R.string.done), { box = null }, null, { box = null },
        )
    }
    Overlay(box == BOX_BLOCKED) {
        MessageBox(
            stringResource(R.string.contacts_box_title), stringResource(R.string.contacts_denied),
            stringResource(R.string.contacts_open_settings), {
                box = null
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            },
            stringResource(R.string.cancel), { box = null },
        )
    }
}

/** Typing page (07 §9.7). */
@Composable
fun TypingPage() {
    val store = LocalStore.current
    MetroPage(stringResource(R.string.page_typing)) {
        PrefToggle(stringResource(R.string.shape_writing), Prefs.SHAPE_WRITING, true)
        PrefToggle(stringResource(R.string.auto_caps), Prefs.AUTO_CAPS, true)
        PrefToggle(stringResource(R.string.double_space), Prefs.DOUBLE_SPACE_PERIOD, true)
        PrefToggle(
            stringResource(R.string.autoswitch_emoji), Prefs.AUTOSWITCH_AFTER_EMOJI, true,
            note = stringResource(R.string.autoswitch_emoji_note),
        )
        PrefToggle(
            stringResource(R.string.clip_history), Prefs.CLIPBOARD_HISTORY, true,
            note = stringResource(R.string.clip_history_note),
        ) { on ->
            store.set(Prefs.CLIPBOARD_HISTORY, on)
            if (!on) store.clearClipboardFile()
        }
    }
}
