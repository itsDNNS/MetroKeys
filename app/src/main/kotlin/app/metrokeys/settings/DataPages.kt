package app.metrokeys.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.metrokeys.R
import app.metrokeys.engine.lexicon.Dictionary
import app.metrokeys.engine.predict.LearnedWordList
import app.metrokeys.engine.predict.UserModel
import app.metrokeys.ime.LearnedFiles
import app.metrokeys.settings.ui.BlockKind
import app.metrokeys.settings.ui.BodyText
import app.metrokeys.settings.ui.TextLayoutHolder
import app.metrokeys.settings.ui.block
import app.metrokeys.settings.ui.rememberInk
import app.metrokeys.settings.ui.reportInk
import app.metrokeys.settings.ui.FieldHeader
import app.metrokeys.settings.ui.ListItem
import app.metrokeys.settings.ui.LocalPageMargins
import app.metrokeys.settings.ui.MessageBox
import app.metrokeys.settings.ui.MetroPage
import app.metrokeys.settings.ui.MetroTheme
import app.metrokeys.settings.ui.Overlay
import app.metrokeys.settings.ui.PageButton
import app.metrokeys.settings.ui.StandaloneLink
import app.metrokeys.settings.ui.margins
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Opens [url] in a browser with ACTION_VIEW (the app has no internet permission); a toast if nothing can open it. */
fun openLink(context: Context, url: String, noBrowser: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, noBrowser, Toast.LENGTH_LONG).show()
    }
}

/** Deletes the learned data (07 §9.13): emoji history, reset stamp for the running keyboard, learned files. */
fun clearLearnedWords(context: Context, store: SettingsStore) {
    store.edit {
        remove(Prefs.EMOJI_HISTORY)
        putLong(Prefs.LEARNED_RESET_AT, System.currentTimeMillis())
    }
    File(context.noBackupFilesDir, "learned").deleteRecursively()
    Toast.makeText(context, R.string.cleared, Toast.LENGTH_LONG).show()
}

/**
 * The learned words of 05 §12.5 as the settings see them (07 §9.13 item 5): the learned data of the enabled
 * languages, read from the files the keyboard writes, without any dictionary (05b §6 "Memory"). Whether a dictionary
 * knows a word is answered by the dictionary the running keyboard has loaded in this process, else by streaming
 * through the compressed word list ([Dictionary.bundledContains]); the full word lists are never loaded here. Call
 * from a background thread.
 */
class LearnedWords private constructor(private val models: Map<String, UserModel>, private val files: LearnedFiles) {
    /** Every stored word no dictionary knows, with its use count (summed over the languages), alphabetically. */
    fun words(): List<Pair<String, Int>> = LearnedWordList.of(models.values.toList()) { keys -> knownKeys(models.keys, keys) }

    /** Forget(word) in every language; changed languages are saved. */
    fun forget(word: String) {
        for ((tag, m) in models) if (m.forget(word)) files.save(tag, m.serialize())
    }

    companion object {
        /** Keys already looked up, per language: key → known to that dictionary. */
        private val lookups = HashMap<String, HashMap<String, Boolean>>()

        @Synchronized
        private fun knownKeys(languages: Collection<String>, keys: Set<String>): Set<String> {
            val known = HashSet<String>()
            for (tag in languages) {
                val cache = lookups.getOrPut(tag) { HashMap() }
                val missing = keys.filterTo(HashSet()) { it !in cache }
                if (missing.isNotEmpty()) {
                    val found = try {
                        Dictionary.bundledContains(tag, missing)
                    } catch (_: java.io.IOException) {
                        emptySet()
                    }
                    for (k in missing) cache[k] = k in found
                }
                for (k in keys) if (cache[k] == true) known.add(k)
            }
            return known
        }

        /** Opens the learned words of the enabled languages with the current files. */
        fun open(context: Context): LearnedWords {
            val files = LearnedFiles(context.applicationContext)
            val langs = Prefs.languages(context)
            return LearnedWords(langs.associateWith { UserModel.read(files.load(it), null) }, files)
        }
    }
}

/** Data+info page (07 §9.13). */
@Composable
fun DataPage() {
    val store = LocalStore.current
    val nav = LocalNavigator.current
    val context = LocalContext.current
    var ask by rememberSaveable { mutableStateOf(false) }
    // re-read after forgetting or clearing (both change a preference)
    val forgetKey = store.string(Prefs.FORGET_WORDS, "")
    val resetKey = store.long(Prefs.LEARNED_RESET_AT, 0)
    val learned by produceState<Int?>(null, forgetKey, resetKey, store.resumes) {
        value = withContext(Dispatchers.IO) { LearnedWords.open(context).words().size }
    }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    // order of 07 §9.13 (revised in 0.5.2): learned words, dictionary, licenses, about
    MetroPage(stringResource(R.string.page_data)) {
        ListItem(
            stringResource(R.string.learned_words),
            learned?.let { pluralStringResource(R.plurals.learned_words_count, it, it) } ?: "",
            { nav.open(Pages.LEARNED_WORDS) },
        )
        FieldHeader(stringResource(R.string.dictionary_header))
        PageButton(stringResource(R.string.clear_button), { ask = true })
        BodyText(stringResource(R.string.clear_text))
        ListItem(stringResource(R.string.licenses), stringResource(R.string.licenses_summary), { nav.open(Pages.LICENSES) })
        FieldHeader(stringResource(R.string.about_header))
        BodyText(
            listOf(
                stringResource(R.string.about_version, version),
                stringResource(R.string.about_copyright),
                stringResource(R.string.about_trademark),
            ).joinToString("\n"),
        )
        val url = stringResource(R.string.about_link_url)
        val noBrowser = stringResource(R.string.about_no_browser)
        StandaloneLink(stringResource(R.string.about_link)) { openLink(context, url, noBrowser) }
    }
    Overlay(ask) {
        MessageBox(
            stringResource(R.string.clear_box_title), stringResource(R.string.clear_box_text),
            stringResource(R.string.delete), {
                ask = false
                clearLearnedWords(context, store)
            },
            stringResource(R.string.cancel), { ask = false },
        )
    }
}

/**
 * Learned words page (07 §9.13 item 5): an intro, then every stored word the dictionaries do not know, alphabetically,
 * with its use count; tapping one asks whether to forget it. Forgetting removes it from the files here and tells the
 * running keyboard through [Prefs.FORGET_WORDS].
 */
@Composable
fun LearnedWordsPage() {
    val store = LocalStore.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var words by remember { mutableStateOf<List<Pair<String, Int>>?>(null) }
    val resetKey = store.long(Prefs.LEARNED_RESET_AT, 0)
    androidx.compose.runtime.LaunchedEffect(resetKey) {
        words = withContext(Dispatchers.IO) { LearnedWords.open(context).words() }
    }
    var ask by rememberSaveable { mutableStateOf<String?>(null) }
    MetroPage(null, appTitle = stringResource(R.string.learned_words)) {
        val list = words
        BodyText(stringResource(R.string.learned_intro))
        if (list != null && list.isEmpty()) BodyText(stringResource(R.string.learned_words_empty))
        for ((w, n) in list.orEmpty()) {
            ListItem(w, pluralStringResource(R.plurals.learned_used, n, n), { ask = w })
        }
    }
    Overlay(ask != null) {
        val w = ask ?: return@Overlay
        MessageBox(
            stringResource(R.string.forget_box_title, w), stringResource(R.string.forget_box_text),
            stringResource(R.string.forget_confirm), {
                ask = null
                scope.launch {
                    words = withContext(Dispatchers.IO) { LearnedWords.open(context).apply { forget(w) }.words() }
                    val old = store.prefs.getString(Prefs.FORGET_WORDS, null)
                    store.edit { putString(Prefs.FORGET_WORDS, Prefs.withForgetRequest(old, System.currentTimeMillis(), w)) }
                }
            },
            stringResource(R.string.cancel), { ask = null },
        )
    }
}

/** Licenses page (07 §9.14): the generated licence text, paragraphs 12 dp apart, selectable, loaded off the main thread. */
@Composable
fun LicensesPage() {
    val context = LocalContext.current
    val paragraphs by produceState<List<String>>(emptyList()) {
        value = withContext(Dispatchers.IO) {
            val text = context.assets.open("licenses.txt").bufferedReader().use { it.readText() }
            text.split(Regex("\\n\\s*\\n")).map { it.trimEnd() }.filter { it.isNotBlank() }
        }
    }
    MetroPage(null, appTitle = stringResource(R.string.licenses)) {
        val m = LocalPageMargins.current
        val style = MetroTheme.type.license.copy(color = MetroTheme.colors.textPrimary)
        val holder = remember { TextLayoutHolder() }
        val ink = rememberInk(style)
        // the first paragraph's cap top is 31 dp below the app title baseline (07 §5.2)
        SelectionContainer(Modifier.block(BlockKind.BODY)) {
            Column {
                for ((i, p) in paragraphs.withIndex()) {
                    if (i == 0) {
                        BasicText(
                            p, Modifier.fillMaxWidth().margins(m).reportInk(holder, ink, bottom = false), style = style,
                            onTextLayout = { holder.layout = it },
                        )
                    } else {
                        BasicText(p, Modifier.fillMaxWidth().padding(top = 12.dp).margins(m), style = style)
                    }
                }
            }
        }
    }
}
