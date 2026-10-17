package app.metrokeys.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.metrokeys.R
import app.metrokeys.keyboard.SoundSlot
import app.metrokeys.keyboard.SoundSource
import app.metrokeys.settings.ui.BodyText
import app.metrokeys.settings.ui.ButtonPair
import app.metrokeys.settings.ui.FieldHeader
import app.metrokeys.settings.ui.ListItem
import app.metrokeys.settings.ui.ListPicker
import app.metrokeys.settings.ui.LocalPageMargins
import app.metrokeys.settings.ui.MetroPage
import app.metrokeys.settings.ui.PageNote
import app.metrokeys.settings.ui.PickerItem
import app.metrokeys.settings.ui.SteppedSlider
import app.metrokeys.settings.ui.margins
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sounds+vibration page (07 §9.8). */
@Composable
fun SoundPage() {
    val store = LocalStore.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val preview = remember { SoundPreview(context) }
    DisposableEffect(preview) { onDispose { preview.release() } }
    val importer = remember { AssetImporter(context) }
    MetroPage(stringResource(R.string.page_sound)) {
        val sounds = store.bool(Prefs.KEY_SOUNDS, true)
        val silent = store.bool(Prefs.KEY_SOUNDS_SILENT_MODE, false)
        PrefToggle(
            stringResource(R.string.key_sounds), Prefs.KEY_SOUNDS, true,
            note = stringResource(if (silent) R.string.key_sounds_note_silent else R.string.key_sounds_note),
        )
        PrefToggle(
            stringResource(R.string.key_sounds_silent), Prefs.KEY_SOUNDS_SILENT_MODE, false,
            note = stringResource(R.string.key_sounds_silent_note), dimmed = !sounds,
        )
        SteppedSlider(
            stringResource(R.string.key_sound_volume), store.int(Prefs.KEY_SOUND_VOLUME, 100).coerceIn(0, 100), 0, 100, 10,
            { v -> resources.getString(R.string.percent, v) },
            { v ->
                store.set(Prefs.KEY_SOUND_VOLUME, v)
                if (store.bool(Prefs.KEY_SOUNDS, true)) {
                    preview.playSample(v, store.soundSource(), store.bool(Prefs.KEY_SOUNDS_SILENT_MODE, false)) { importer.soundFile(it.file) }
                }
            },
            dimmed = !sounds, note = stringResource(R.string.key_sound_volume_note),
        )
        PrefToggle(stringResource(R.string.vibration), Prefs.VIBRATION, false)
        ListItem(stringResource(R.string.sound_pack), soundSourceName(store.soundSource()), { nav.open(Pages.SOUND_PACK) })
    }
}

@Composable
private fun soundSourceName(v: SoundSource): String = stringResource(
    when (v) {
        SoundSource.METROKEYS -> R.string.sound_source_metrokeys
        SoundSource.SYSTEM -> R.string.sound_source_system
        SoundSource.CUSTOM -> R.string.sound_source_custom
    },
)

private val SOUND_MIME = arrayOf("audio/*", "application/octet-stream")

/** Sound pack page (07 §9.9): the sound source and the user's own WAV files for seven slots. */
@Composable
fun SoundPackPage() {
    val store = LocalStore.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importer = remember { AssetImporter(context) }
    val preview = remember { SoundPreview(context) }
    DisposableEffect(preview) { onDispose { preview.release() } }
    var slotForPick by rememberSaveable { mutableStateOf<String?>(null) }

    fun run(work: () -> AssetImporter.Result) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { work() }
            importer.finish(store, Prefs.SOUND_SOURCE, r)
        }
    }
    val multi = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) run { importer.importSounds(uris) }
    }
    val single = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val slot = slotForPick
        slotForPick = null
        if (uri != null && slot != null) run { importer.importSound(uri, slot) }
    }

    MetroPage(stringResource(R.string.sound_pack)) {
        BodyText(stringResource(R.string.sound_intro))
        val values = SoundSource.entries
        val source = store.soundSource()
        ListPicker(
            stringResource(R.string.source),
            values.map { PickerItem(soundSourceName(it)) },
            values.indexOf(source).coerceAtLeast(0), { i -> store.set(Prefs.SOUND_SOURCE, values[i].value) },
        )
        store.long(Prefs.CUSTOM_ASSETS_STAMP, 0)
        val present = ImportRules.SOUND_SLOTS.filter { importer.soundFile(it).isFile }.toSet()
        ButtonPair(
            stringResource(R.string.sound_import), { multi.launch(SOUND_MIME) },
            stringResource(R.string.remove), {
                importer.removeSounds()
                importer.removed(store, Prefs.SOUND_SOURCE, SoundSource.METROKEYS.value)
            },
        )
        FieldHeader(stringResource(R.string.sound_slots))
        PageNote(stringResource(R.string.sound_slots_note))
        val names = mapOf(
            "letter" to R.string.slot_letter, "space" to R.string.slot_space, "function" to R.string.slot_function,
            "picker" to R.string.slot_picker, "candidate" to R.string.slot_candidate, "dictionary" to R.string.slot_dictionary,
            "paste" to R.string.slot_paste,
        )
        for (slot in ImportRules.SOUND_SLOTS) {
            val has = slot in present
            ListItem(
                stringResource(names.getValue(slot)),
                stringResource(if (has) R.string.asset_present else R.string.asset_missing),
                onClick = {
                    slotForPick = slot
                    single.launch(SOUND_MIME)
                },
                onLongClick = if (has) {
                    { SoundSlot.ofFile(slot)?.let { preview.playFile(it, importer.soundFile(slot), store.int(Prefs.KEY_SOUND_VOLUME, 100)) } }
                } else {
                    null
                },
            )
        }
    }
}
