package app.metrokeys.ime

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.widget.Toast
import app.metrokeys.R
import app.metrokeys.engine.EngineSettings
import app.metrokeys.engine.PredictionEngine
import app.metrokeys.engine.swipe.ShapeTrace
import app.metrokeys.engine.swipe.ShiftState
import app.metrokeys.engine.text.TextModel
import app.metrokeys.engine.text.TextRules
import app.metrokeys.engine.touch.KeyboardGeometry
import app.metrokeys.engine.touch.LetterKey
import app.metrokeys.keyboard.Feedback
import app.metrokeys.keyboard.KeyboardView
import app.metrokeys.keyboard.SoundKind
import app.metrokeys.keyboard.layout.Key
import app.metrokeys.keyboard.layout.KeyRole
import app.metrokeys.keyboard.layout.LayoutSet
import app.metrokeys.keyboard.layout.Page
import app.metrokeys.keyboard.layout.PageKind
import app.metrokeys.settings.KeyboardSettings
import app.metrokeys.settings.Prefs
import app.metrokeys.settings.SettingsActivity
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * State and actions of the keyboard (spec 01, 03): layouts and pages, shift, keyboard languages, the emoji page and
 * the clipboard panel, the application bar, dictation and the hand-off to [InputLogic]. Lives as long as the service.
 */
class KeyboardController(private val service: MetroKeysService) : InputLogic.Listener {
    private val context: Context get() = service
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "metrokeys-engine").apply { isDaemon = true } }

    val scheduler = object : Scheduler {
        override fun now(): Long = SystemClock.uptimeMillis()

        override fun background(task: () -> Unit) {
            worker.execute {
                try {
                    task()
                } catch (e: RuntimeException) {
                    android.util.Log.e("MetroKeys", "engine task failed", e)
                }
            }
        }

        override fun main(delayMs: Long, task: () -> Unit): () -> Unit {
            val r = Runnable { task() }
            if (delayMs <= 0) handler.post(r) else handler.postDelayed(r, delayMs)
            return { handler.removeCallbacks(r) }
        }
    }

    val editor = InputConnectionEditor(service, service)
    val logic = InputLogic(editor, scheduler, this)
    val feedback = Feedback(service)

    /** Contact names as a word source (05 §13); the engine gets them on the worker thread. */
    private val contacts = ContactsWordSource(service, scheduler) { source ->
        scheduler.background { engine?.refreshContacts(source) }
    }

    var view: KeyboardView? = null
    var settings: KeyboardSettings = KeyboardSettings.read(Prefs.of(service))
        private set

    /** Localised resources for the keyboard's own texts (app language, 07 §9.5). */
    var texts: Context = service
        private set

    // ---------------------------------------------------------------- engine

    @Volatile
    var engine: PredictionEngine? = null
        private set
    private var engineLanguages: List<String> = emptyList()
    private var engineGeometryKey = ""

    private val learnedStore = LearnedFiles(service)

    // ---------------------------------------------------------------- layouts

    private val layoutCache = HashMap<String, LayoutSet>()
    var language: String = Prefs.GERMAN
        private set
    lateinit var layout: LayoutSet
        private set
    lateinit var page: Page
        private set
    private var pageViaKey = false
    private var symbolUsed = false
    var field: FieldState = FieldState.EMPTY
        private set

    // ---------------------------------------------------------------- shift (03 §4)

    var shift = Shift.OFF
        private set
    private var shiftManual = false
    private var justUnlocked = false
    private var lastShiftDown = 0L
    val isShifted: Boolean get() = shift != Shift.OFF

    // ---------------------------------------------------------------- panels and strip

    var clipboardPanelOpen = false
        private set
    var emojiCategory = "recent"
        private set
    lateinit var emojiCategories: List<EmojiCategory>
        private set
    private var emojiHistory = EmojiHistory(null)
    var clipHistory = ClipboardHistory()
        private set
    var strip: StripContent = StripContent.EMPTY
        private set
    var appBarExpanded = false
        private set

    /** Hardware keyboard: only the strip is shown, unless the mini keyboard is open (03 §13). */
    var hardwareMode = false
        private set
    var miniKeyboardOpen = false
        private set

    var dictationState: DictationState? = null
        private set
    private val dictation = Dictation(service, this)

    private val clipboard: ClipboardManager = service.getSystemService(ClipboardManager::class.java)
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { onClipChanged() }
    private val clipImages = ClipImages(service)

    /** The newest system clip as the keyboard saw it (03 §6.11): [seenAt] is its copy time (wall clock). */
    private data class CurrentClip(val stamp: Long, val seenAt: Long, val text: String?, val image: String?, val sensitive: Boolean)

    private var currentClip: CurrentClip? = null
    private var chipPastedStamp = Long.MIN_VALUE
    private val chipExpiry = Runnable { view?.onFieldStateChanged() }

    // ================================================================ lifecycle

    fun create() {
        loadTexts()
        emojiHistory = EmojiHistory(Prefs.of(context).getString(Prefs.EMOJI_HISTORY, null))
        clipHistory = ClipboardHistory.read(clipFile().takeIf { it.isFile }?.readBytes())
        clipImages.sweep(clipHistory.imageFiles())
        emojiCategories = loadEmoji()
        applySettings(KeyboardSettings.read(Prefs.of(context)), initial = true)
        clipboard.addPrimaryClipChangedListener(clipListener)
    }

    fun destroy() {
        clipboard.removePrimaryClipChangedListener(clipListener)
        handler.removeCallbacks(chipExpiry)
        clipImages.release()
        contacts.release()
        dictation.release()
        feedback.release()
        saveLearned()
        worker.shutdown()
    }

    private fun loadTexts() {
        val locale = appLocale()
        val conf = Configuration(service.resources.configuration)
        conf.setLocale(locale)
        texts = service.createConfigurationContext(conf)
    }

    /** The per-app locale (Android 13+), else the system language (07 §9.5). */
    private fun appLocale(): Locale {
        if (Build.VERSION.SDK_INT >= 33) {
            val lm = service.getSystemService(android.app.LocaleManager::class.java)
            val l = lm?.applicationLocales
            if (l != null && !l.isEmpty) return l[0]
        }
        return service.resources.configuration.locales[0]
    }

    fun str(id: Int, vararg args: Any): String = texts.getString(id, *args)

    /** Settings changed (07 §9.1: applied without restart). */
    fun applySettings(s: KeyboardSettings, initial: Boolean = false) {
        val old = settings
        settings = s
        logic.settings = s
        feedback.configure(s.keySounds, s.keySoundVolume, s.vibration, s.customSounds)
        if (!s.clipboardHistory && old.clipboardHistory) {
            clipHistory.clearAll()
            saveClips()
            clipboardPanelOpen = false
        }
        if (initial) forgetHandled = s.forgetRequests.maxOfOrNull { it.first } ?: 0 else applyForgetRequests(s)
        val langs = s.languages
        if (initial || langs != engineLanguages) loadEngine(langs)
        engine?.settings = engineSettings(s)
        contacts.update(s.contactsWordSource)
        if (!initial && old.learnedResetAt != s.learnedResetAt) {
            scheduler.background {
                engine?.deleteLearnedData()
                File(service.noBackupFilesDir, "learned").deleteRecursively()
            }
            emojiHistory = EmojiHistory(null)
        }
        val wanted = s.activeLanguage?.takeIf { it in langs } ?: if (language in langs) language else langs.first()
        language = wanted
        loadLayout()
        if (!initial) {
            showStartPage()
            view?.onConfigurationChanged()
        }
    }

    private fun engineSettings(s: KeyboardSettings) = EngineSettings(
        suggestions = s.suggestions,
        autocorrect = s.effectiveAutocorrect,
        multilingual = s.isMultilingual,
        shapeWriting = s.shapeWriting,
    )

    private fun loadEngine(langs: List<String>) {
        engineLanguages = langs
        val old = engine
        engine = null
        logic.engine = null
        val s = settings
        scheduler.background {
            if (old != null) old.saveLearnedData(learnedStore)
            val e = PredictionEngine.withBundledDictionaries(langs, engineSettings(s))
            e.loadLearnedData(learnedStore)
            e.refreshContacts(contacts)
            scheduler.main(0) {
                if (engineLanguages != langs) return@main
                if (language in langs) e.activeLanguage = language
                engine = e
                logic.engine = e
                engineGeometryKey = ""
                updateGeometry()
                logic.refresh()
                applyForgetRequests(settings)
            }
        }
    }

    fun saveLearned() {
        val e = engine ?: return
        scheduler.background { e.saveLearnedData(learnedStore) }
    }

    /** layouts-de.json / layouts-de-multi.json for "de-DE" (01 §11.1). */
    private fun layoutFile(tag: String): String {
        val lang = tag.substringBefore('-')
        return if (settings.languages.size >= 2) "layouts-$lang-multi.json" else "layouts-$lang.json"
    }

    private fun loadLayout() {
        val name = layoutFile(language)
        val base = layoutCache.getOrPut(name) {
            LayoutSet.parse(context.assets.open(name).bufferedReader().use { it.readText() })
        }
        layout = if (settings.showCommaKey) layoutCache.getOrPut("$name+comma") { base.withOptionalComma() } else base
        if (!this::page.isInitialized) page = layout.page(layout.pagesFor("text").first())
    }

    private fun loadEmoji(): List<EmojiCategory> {
        val spec = context.assets.open("emoji.json").bufferedReader().use { it.readText() }
        val items = context.assets.open("emoji/emoji.txt").bufferedReader().use { it.readText() }
        val paint = android.graphics.Paint()
        return EmojiData.parse(spec, items) { paint.hasGlyph(it) }
    }

    // ================================================================ input start / finish (03 §20)

    fun startInput(info: EditorInfo?, restarting: Boolean) {
        val f = if (info == null) FieldState.EMPTY else FieldState.from(
            info.inputType, info.imeOptions, hasEditorInfo = true, hasActionLabel = info.actionLabel != null,
        )
        field = f
        if (!restarting) {
            shift = Shift.OFF
            shiftManual = false
            justUnlocked = false
        }
        clipboardPanelOpen = false
        miniKeyboardOpen = false
        appBarExpanded = false
        logic.startInput(f, info?.initialSelStart ?: 0, info?.initialSelEnd ?: 0)
        showStartPage()
        recordCurrentClip()
    }

    fun onShow() {
        loadTexts()
        // the permission may have been granted or revoked since (05 §13)
        contacts.update(settings.contactsWordSource)
        view?.onShown()
        updateAutoShift()
        updateGeometry()
    }

    fun onHide() {
        dictation.cancel()
        dictationState = null
        logic.finishInput()
        saveLearned()
        view?.onHidden()
    }

    private fun showStartPage() {
        if (!this::layout.isInitialized) return
        val id = layout.pagesFor(field.inputKind).first()
        setPage(layout.page(id), viaKey = false)
    }

    private fun setPage(p: Page, viaKey: Boolean) {
        val wasEmoji = this::page.isInitialized && page.kind == PageKind.EMOJI
        page = p
        pageViaKey = viaKey && p.isSymbols
        symbolUsed = false
        if (p.kind == PageKind.EMOJI && !wasEmoji) emojiCategory = emojiCategories.firstOrNull()?.id ?: "recent"
        if (!p.isLetters && shift == Shift.ONE_SHOT && !shiftManual) shift = Shift.OFF
        updateGeometry()
        updateAutoShift()
        view?.onPageChanged()
    }

    /** The letter geometry for the touch model and shape writing (05 §4, 06 §3). */
    fun updateGeometry() {
        val e = engine ?: return
        val v = view ?: return
        val w = v.keyAreaWidth
        val h = v.keyAreaHeight
        if (w <= 0f || h <= 0f) return
        val letters = layout.pages["${page.family}.letters"] ?: layout.pages["text.letters"] ?: return
        val key = "${layout.language}/${letters.id}/$w/$h"
        if (key == engineGeometryKey) return
        engineGeometryKey = key
        val keys = letters.keys.filter { it.isLetter }.map {
            LetterKey(it.output!![0], it.rect.left * w, it.rect.top * h, it.rect.right * w, it.rect.bottom * h)
        }
        e.setGeometry(KeyboardGeometry(w, h, keys))
    }

    // ================================================================ InputLogic.Listener

    override fun onStrip(content: StripContent) {
        strip = content
        view?.onStrip(content)
    }

    override fun onAutoCorrected(correction: String) {
        view?.onAutoCorrected(correction)
        feedback.play(SoundKind.CANDIDATE)
        view?.announce(str(R.string.a11y_corrected, correction))
    }

    override fun onTextChanged() {
        updateAutoShift()
        view?.onFieldStateChanged()
    }

    override fun onReleaseShift() {
        if (shift == Shift.ONE_SHOT) {
            shift = Shift.OFF
            shiftManual = false
            view?.invalidate()
        }
    }

    fun onSelectionUpdate(oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int, candStart: Int, candEnd: Int) {
        logic.onSelectionUpdate(newStart, newEnd, candStart, candEnd)
        view?.onFieldStateChanged()
    }

    // ================================================================ shift (03 §4, 04 §1)

    fun shiftDown() {
        val now = SystemClock.uptimeMillis()
        val double = now - lastShiftDown < 500
        lastShiftDown = now
        val old = shift
        shift = when (shift) {
            Shift.OFF -> if (double) (if (justUnlocked) Shift.OFF else Shift.CAPS_LOCK) else Shift.ONE_SHOT
            Shift.ONE_SHOT -> if (double) Shift.CAPS_LOCK else Shift.OFF
            Shift.CAPS_LOCK -> {
                justUnlocked = true
                Shift.OFF
            }
        }
        if (shift != Shift.OFF) justUnlocked = false
        shiftManual = shift == Shift.ONE_SHOT
        if (old != shift) announceShift()
        view?.invalidate()
    }

    /** Long press on shift under touch exploration locks caps (03 §19.3.4). */
    fun lockShift() {
        shift = Shift.CAPS_LOCK
        justUnlocked = false
        announceShift()
        view?.invalidate()
    }

    private fun announceShift() {
        val state = when (shift) {
            Shift.OFF -> R.string.a11y_state_off
            Shift.ONE_SHOT -> R.string.a11y_state_on
            Shift.CAPS_LOCK -> R.string.a11y_state_locked
        }
        view?.announce(str(R.string.a11y_shift_state, str(state)))
    }

    fun updateAutoShift() {
        if (!this::page.isInitialized) return
        if (shift == Shift.CAPS_LOCK || (shift == Shift.ONE_SHOT && shiftManual)) return
        val before = editor.textBefore(3)
        val caps = field.capsSentences || field.capsWordsOrChars
        val editorCaps = if (field.capsWordsOrChars) editor.capsMode(field.inputType) != 0 else false
        val want = caps && TextRules.autoShift(
            before, settings.autoCaps, page.isLetters, field.policy, field.capsSentences, field.capsWordsOrChars, editorCaps,
        ) && logic.selStart == logic.selEnd
        val n = if (want) Shift.ONE_SHOT else Shift.OFF
        if (n != shift) {
            shift = n
            shiftManual = false
            if (n != Shift.OFF) justUnlocked = false
            view?.invalidate()
        }
    }

    // ================================================================ keys

    /** Touch-down feedback and the keys that act on touch-down (03 §1.2, §4, §7.1, §8.2). */
    fun keyDown(key: Key) {
        feedback.play(soundFor(key))
        feedback.vibrate()
        when (key.role) {
            KeyRole.PAGE, KeyRole.EMOJI -> key.target?.let { switchToPage(it) }
            KeyRole.SHIFT -> shiftDown()
            KeyRole.EMOJI_CATEGORY -> key.category?.let { selectEmojiCategory(it) }
            else -> {}
        }
    }

    fun soundFor(key: Key): SoundKind = when (key.role) {
        KeyRole.CHAR -> if (key.appearance == app.metrokeys.keyboard.layout.Appearance.NORMAL) SoundKind.LETTER else SoundKind.FUNCTION
        KeyRole.SPACE -> SoundKind.SPACE
        KeyRole.BACKSPACE -> SoundKind.DELETE
        KeyRole.ENTER -> SoundKind.RETURN
        else -> SoundKind.FUNCTION
    }

    fun switchToPage(target: String) {
        val p = layout.pages[target] ?: return
        if (miniKeyboardOpen && p.isLetters) {
            closeMiniKeyboard()
            return
        }
        setPage(p, viaKey = true)
        view?.announce(pageName(p))
    }

    fun pageName(p: Page): String = when {
        p.kind == PageKind.EMOJI -> str(R.string.a11y_emoji)
        p.isSymbols && p.symbolsNumber == 2 -> str(R.string.a11y_symbols_page, 2)
        p.isSymbols -> str(R.string.a11y_symbols)
        else -> str(R.string.a11y_letters)
    }

    /** Release of a key: its character is typed / its function runs (03 §1.2). [x], [y] in key-area px. */
    fun keyCommit(key: Key, x: Float, y: Float) {
        when (key.role) {
            KeyRole.CHAR -> {
                val out = key.outputText(isShifted) ?: return
                typeOnPage(out, x, y)
            }
            KeyRole.SPACE -> typeOnPage(" ", x, y)
            KeyRole.BACKSPACE -> logic.backspace()
            KeyRole.ENTER -> enter()
            KeyRole.LANGUAGE -> nextLanguage()
            KeyRole.DOMAIN -> typeOnPage(domains().first(), Float.NaN, Float.NaN)
            else -> {}
        }
    }

    /** Text from a key or picker on the current page, with the automatic return of 03 §7.2. */
    fun typeOnPage(text: String, x: Float = Float.NaN, y: Float = Float.NaN) {
        val symbolPage = page.isSymbols && pageViaKey
        logic.onText(text, x, y)
        if (symbolPage) {
            val c = text.singleOrNull()
            when {
                c != null && c in "'\\@" -> backToLetters()
                c != null && c.isDigit() -> {}
                c == ' ' -> if (symbolUsed) backToLetters()
                else -> symbolUsed = true
            }
        }
    }

    private fun enter() {
        val symbolPage = page.isSymbols && pageViaKey && symbolUsed
        logic.enter()
        if (symbolPage) backToLetters()
    }

    private fun backToLetters() {
        val letters = layout.lettersOf(page) ?: return
        if (miniKeyboardOpen) {
            closeMiniKeyboard()
            return
        }
        setPage(letters, viaKey = false)
    }

    /** Held key repeat step (03 §5). */
    fun keyRepeat(key: Key) {
        when (key.role) {
            KeyRole.BACKSPACE -> logic.backspace(repeated = true)
            KeyRole.SPACE -> typeOnPage(" ")
            KeyRole.ENTER -> enter()
            else -> {}
        }
    }

    fun wordDelete() = logic.wordDelete()

    /** Hold output of a dual-character key (dial-pad 0 → "+", 03 §3.2). */
    fun holdOutput(key: Key) {
        key.holdOutput?.let { typeOnPage(it) }
    }

    /** The picker items of a long press (03 §3.2): null if there is no picker. */
    fun pickerItems(key: Key): List<String>? {
        val items = when (key.role) {
            KeyRole.DOMAIN -> domains()
            KeyRole.LANGUAGE -> languageOrder(language).let { l -> listOf(language) + l.filter { it != language } }
            KeyRole.CHAR -> {
                val shifted = isShifted
                val own = key.outputText(shifted) ?: return null
                listOf(own) + key.alternates.filter { !(shifted && it.hideWhenShifted) }.map { it.text(shifted) }
            }
            else -> return null
        }.distinct()
        return if (items.size > 1) items else null
    }

    fun pickerCommit(key: Key, text: String) {
        if (key.role == KeyRole.LANGUAGE) {
            switchLanguage(text)
            return
        }
        typeOnPage(text)
    }

    fun pickerOpened(items: List<String>) {
        feedback.play(SoundKind.PICKER)
        view?.announce(str(R.string.a11y_alternatives_opened, items.joinToString(", ")))
    }

    // ================================================================ languages (01 §11, 03 §7.3)

    fun languageLabel(tag: String): String = layoutFor(tag).languageInfo.label

    fun languageInfo(tag: String) = layoutFor(tag).languageInfo

    private fun layoutFor(tag: String): LayoutSet {
        val name = layoutFile(tag)
        return layoutCache.getOrPut(name) { LayoutSet.parse(context.assets.open(name).bufferedReader().use { it.readText() }) }
    }

    /** Enabled languages ordered by label text. */
    private fun languageOrder(@Suppress("UNUSED_PARAMETER") current: String): List<String> =
        settings.languages.sortedBy { languageLabel(it) }

    fun languageName(tag: String): String = str(if (tag.startsWith("de")) R.string.lang_de else R.string.lang_en)

    fun nextLanguage() {
        val order = languageOrder(language)
        if (order.size < 2) return
        switchLanguage(order[(order.indexOf(language) + 1) % order.size])
    }

    fun switchLanguage(tag: String) {
        if (tag !in settings.languages) return
        logic.endSwipeMark()
        language = tag
        Prefs.of(context).edit().putString(Prefs.ACTIVE_LANGUAGE, tag).apply()
        engine?.activeLanguage = tag
        loadLayout()
        engineGeometryKey = ""
        showStartPage()
        logic.refresh()
        view?.announce(str(R.string.a11y_language, languageName(tag)).substringBefore(","))
    }

    /** Domain endings by device region (01 §11.6; other regions: own ccTLD first, open point 2). */
    fun domains(): List<String> {
        val region = Locale.getDefault().country.uppercase(Locale.ROOT)
        return when (region) {
            "DE" -> listOf(".de", ".com", ".org", ".net")
            "US", "" -> listOf(".com", ".us", ".org", ".net")
            else -> listOf(".${region.lowercase(Locale.ROOT)}", ".com", ".org", ".net")
        }
    }

    // ================================================================ shape writing (06)

    /** Shape writing possible on the current page and field (06 §1.2)? */
    val shapeWritingActive: Boolean
        get() = settings.shapeWriting && page.isLetters && this.field.policy.shapeWriting && engine != null &&
            view?.touchExploration != true

    fun swipe(trace: ShapeTrace) {
        val e = engine ?: return
        val before = editor.textBefore(InputLogic.BEFORE).toString()
        val shiftState = when (shift) {
            Shift.OFF -> ShiftState.OFF
            Shift.ONE_SHOT -> ShiftState.ONE_SHOT
            Shift.CAPS_LOCK -> ShiftState.CAPS_LOCK
        }
        val f = field.info
        scheduler.background {
            val r = e.recognizeShape(trace, before, f)
            val texts = r.texts(shiftState)
            scheduler.main(0) { logic.applySwipe(r, texts) }
        }
    }

    /** Key choice at touch-down (03 §1.1a): may a near letter be chosen instead of the touched one? */
    fun keyChoiceAllowed(): Boolean = page.isLetters && logic.isTypingAllowedForKeyChoice && !logic.lastWasBackspace &&
        view?.touchExploration != true

    /** Frequency class of the best word starting with the current word plus [letter], −1 if impossible. */
    fun prefixClass(letter: String): Int {
        val e = engine ?: return 0
        val w = logic.currentWord()
        if (w.isEmpty()) return 0
        return e.prefixClass(w + letter)
    }

    fun currentWordNonEmpty(): Boolean = logic.currentWord().any { Character.isLetter(it) }

    // ================================================================ strip

    fun pick(content: StripContent, index: Int) {
        val item = content.items.getOrNull(index) ?: return
        feedback.play(if (item.addWord) SoundKind.DICTIONARY else SoundKind.CANDIDATE)
        logic.pick(content, index)
    }

    /** "Can paste" of 03 §6.6.2: also false while the recently-copied chip is shown. */
    val canPaste: Boolean
        get() = clipboard.hasPrimaryClip() && !this.field.isPassword && !appBarHasClipboard() && chip == null

    private fun appBarHasClipboard(): Boolean = settings.navMode == app.metrokeys.settings.NavMode.APP_BAR &&
        (settings.navTap.contains("clipboard") || settings.navHold.contains("clipboard"))

    fun paste() {
        feedback.play(SoundKind.PASTE)
        logic.paste { editor.performPaste() }
    }

    /** Clipboard-history button shown in the strip (03 §6.7.2). */
    val clipButtonVisible: Boolean
        get() = settings.clipboardHistory && strip.allowsClipboardButton && !this.field.isPassword &&
            clipboardHasText() && !appBarHasClipboard() && !clipHistory.isEmpty(System.currentTimeMillis())

    private fun clipboardHasText(): Boolean =
        clipboard.primaryClipDescription?.let { it.hasMimeType("text/*") } ?: false

    // ================================================================ recently-copied chip (03 §6.11)

    /** What the recently-copied chip shows: a text or an image entry of the history. */
    class Chip(val text: String?, val image: ClipEntry?)

    /** The chip of 03 §6.11 item 1, or null when it is not shown. */
    val chip: Chip?
        get() {
            val c = currentClip ?: return null
            if (this.field.isPassword || c.sensitive || !strip.allowsClipboardButton || c.stamp == chipPastedStamp) return null
            if (System.currentTimeMillis() - c.seenAt >= CHIP_LIFETIME_MS) return null
            if (c.image != null) {
                if (!settings.clipboardHistory) return null
                return clipHistory.imageEntry(c.image)?.let { Chip(null, it) }
            }
            val t = c.text?.takeIf { it.isNotBlank() } ?: return null
            return Chip(t, null)
        }

    /** Tap on the chip (03 §6.11 item 4): text like a paste from the panel, an image by commitContent (§10.4). */
    fun chipPaste(): Boolean {
        val c = chip ?: return false
        val cur = currentClip ?: return false
        val before = chipPastedStamp
        // set first, so the strip refresh caused by the insertion already sees the chip gone
        chipPastedStamp = cur.stamp
        val ok = if (c.image != null) insertImage(c.image) else {
            feedback.play(SoundKind.PASTE)
            logic.insertClip(c.text!!)
            true
        }
        if (!ok) chipPastedStamp = before
        return ok
    }

    private fun scheduleChipExpiry() {
        handler.removeCallbacks(chipExpiry)
        val c = currentClip ?: return
        val left = c.seenAt + CHIP_LIFETIME_MS - System.currentTimeMillis()
        if (left > 0) handler.postDelayed(chipExpiry, left + 20)
    }

    // ================================================================ clipboard history (03 §10)

    private fun clipFile() = File(service.noBackupFilesDir, "clipboard.bin")

    private fun saveClips() {
        clipImages.delete(clipHistory.takeDroppedImages())
        val data = clipHistory.serialize()
        scheduler.background { clipFile().writeBytes(data) }
    }

    private fun onClipChanged() {
        recordCurrentClip(fromListener = true)
        view?.onClipboardChanged()
    }

    /**
     * Reads the current system clip: remembers it for the chip and records it in the history (03 §10.1): texts at
     * once, images (a content URI without text, MIME type image/…) after copying them in the background.
     */
    private fun recordCurrentClip(fromListener: Boolean = false) {
        if (field.isPassword) return
        val clip = try {
            clipboard.primaryClip
        } catch (_: SecurityException) {
            null
        }
        if (clip == null || clip.itemCount == 0) {
            currentClip = null
            return
        }
        val desc = clip.description
        val extras: PersistableBundle? = desc.extras
        val sensitive = extras?.getBoolean(EXTRA_IS_SENSITIVE, false) == true
        val stamp = desc.timestamp
        val now = System.currentTimeMillis()
        val same = currentClip?.takeIf { it.stamp == stamp }
        // the copy time: when the listener saw the change, else the clip's own timestamp (wall clock)
        val seenAt = same?.seenAt ?: if (fromListener || stamp !in 1..now) now else stamp
        val item = clip.getItemAt(0)
        val uri = item.uri
        val mime = if (item.text == null && uri != null) {
            (0 until desc.mimeTypeCount).map { desc.getMimeType(it) }.firstOrNull { ClipDescription.compareMimeTypes(it, "image/*") }
                ?: try {
                    context.contentResolver.getType(uri)
                } catch (_: Exception) {
                    null
                }
        } else null
        if (uri != null && mime != null && ClipDescription.compareMimeTypes(mime, "image/*")) {
            currentClip = CurrentClip(stamp, seenAt, null, same?.image, sensitive)
            scheduleChipExpiry()
            if (sensitive || !settings.clipboardHistory || same?.image != null || !clipHistory.claim(stamp)) return
            clipImages.background {
                val name = clipImages.copy(uri, mime)
                scheduler.main(0) {
                    if (name == null) return@main
                    if (!settings.clipboardHistory) {
                        clipImages.delete(listOf(name))
                        return@main
                    }
                    clipHistory.recordImage(name, mime, System.currentTimeMillis())
                    saveClips()
                    currentClip?.takeIf { it.stamp == stamp }?.let { currentClip = it.copy(image = name) }
                    view?.onClipboardChanged()
                }
            }
            return
        }
        val text = if (sensitive) null else item.coerceToText(context)?.toString()
        currentClip = CurrentClip(stamp, seenAt, text, null, sensitive)
        scheduleChipExpiry()
        if (sensitive || !settings.clipboardHistory || text == null) return
        if (clipHistory.record(text, now, stamp)) saveClips()
    }

    /** Thumbnail of a stored image (03 §10.3 item 7), or null while it is being decoded. */
    fun clipThumbnail(name: String, height: Int): android.graphics.Bitmap? =
        clipImages.thumbnail(name, height) { view?.invalidate() }

    /**
     * Inserts a stored image with commitContent (03 §10.4) if the editor declares a matching MIME type; otherwise a
     * short toast. Returns true if the editor took it.
     */
    fun insertImage(e: ClipEntry): Boolean {
        val name = e.image ?: return false
        val mime = e.mime ?: ClipImages.mimeFor(name)
        val declared = service.currentInputEditorInfo?.contentMimeTypes ?: emptyArray()
        val ic = service.currentInputConnection
        if (ic == null || declared.none { ClipDescription.compareMimeTypes(mime, it) }) {
            showMessage(str(R.string.clip_image_unsupported), long = false)
            return false
        }
        val content = InputContentInfo(clipImages.uriFor(name), ClipDescription("image", arrayOf(mime)))
        feedback.play(SoundKind.PASTE)
        val ok = logic.insertContent { ic.commitContent(content, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null) }
        if (!ok) showMessage(str(R.string.clip_image_unsupported), long = false)
        return ok
    }

    fun toggleClipboardPanel() {
        if (field.isPassword) return
        clipboardPanelOpen = !clipboardPanelOpen
        appBarExpanded = false
        if (clipboardPanelOpen) {
            val n = clipHistory.list(System.currentTimeMillis()).size
            view?.announce(if (n == 0) str(R.string.a11y_clip_empty) else texts.resources.getQuantityString(R.plurals.a11y_clip_opened, n, n))
        }
        view?.onPanelChanged()
    }

    fun clipEntries(): List<ClipEntry> {
        val l = clipHistory.list(System.currentTimeMillis())
        clipImages.delete(clipHistory.takeDroppedImages())
        return l
    }

    fun clipInsert(e: ClipEntry) {
        if (e.isImage) {
            if (!insertImage(e)) return
        } else {
            logic.insertClip(e.text)
        }
        clipboardPanelOpen = false
        view?.onPanelChanged()
    }

    fun clipTogglePin(e: ClipEntry) {
        val pinned = clipHistory.togglePin(e, System.currentTimeMillis()) ?: return
        saveClips()
        view?.announce(str(if (pinned) R.string.a11y_pinned_now else R.string.a11y_unpinned_now))
        view?.invalidate()
    }

    fun clipDelete(e: ClipEntry) {
        clipHistory.delete(e)
        saveClips()
        view?.announce(str(R.string.a11y_deleted))
        view?.invalidate()
    }

    fun clipClearUnpinned() {
        clipHistory.clearUnpinned()
        saveClips()
        view?.announce(str(R.string.a11y_deleted))
        view?.invalidate()
    }

    fun closeClipboardPanel() {
        if (!clipboardPanelOpen) return
        clipboardPanelOpen = false
        view?.onPanelChanged()
    }

    // ================================================================ emoji (01 §7, 03 §8)

    fun selectEmojiCategory(id: String) {
        emojiCategory = id
        view?.onEmojiCategoryChanged()
    }

    fun emojiItems(id: String, limit: Int): List<String> =
        if (id == "recent") emojiHistory.top(limit) else emojiCategories.firstOrNull { it.id == id }?.items.orEmpty()

    fun emojiPicked(text: String) {
        logic.insertEmoji(text, fromEmojiPage = true)
        emojiHistory.record(text, System.currentTimeMillis())
        Prefs.of(context).edit().putString(Prefs.EMOJI_HISTORY, emojiHistory.serialize()).apply()
        if (settings.autoswitchAfterEmoji) {
            if (miniKeyboardOpen) closeMiniKeyboard() else layout.lettersOf(page)?.let { setPage(it, viaKey = false) }
                ?: layout.pages["text.letters"]?.let { setPage(it, viaKey = false) }
        }
    }

    // ================================================================ application bar (03 §11, §12)

    fun appBarButtons(): List<AppBarButton> = (0..2).map { i ->
        val tap = settings.navTap[i]
        val hold = settings.navHold[i]
        buttonFor(i, tap, hold)
    }

    private fun buttonFor(i: Int, tap: String, hold: String): AppBarButton {
        if (tap.startsWith("app:")) {
            val pkg = tap.removePrefix("app:")
            val pm = context.packageManager
            return try {
                val ai = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(ai).toString()
                AppBarButton(i, tap, hold, "app", label.lowercase(), true, pm.getApplicationIcon(ai), label)
            } catch (_: Exception) {
                AppBarButton(i, tap, hold, "app", str(R.string.bar_app_missing), false)
            }
        }
        val (icon, label) = when (tap) {
            "editor" -> fieldActionIconAndLabel()
            "hide" -> "hide" to str(R.string.bar_hide)
            "switch" -> "switch" to str(R.string.bar_switch)
            "home" -> "home" to str(R.string.bar_home)
            "search" -> "web" to str(R.string.bar_web)
            "emoji" -> "emoji" to str(R.string.bar_emoji)
            "clipboard" -> "clipboard" to str(R.string.bar_clipboard)
            "language" -> "language" to str(R.string.bar_language)
            "left" -> "left" to str(R.string.bar_left)
            "right" -> "right" to str(R.string.bar_right)
            "undo" -> "undo" to str(R.string.bar_undo)
            "voice" -> "voice" to str(R.string.bar_voice)
            "settings" -> "settings" to str(R.string.bar_settings)
            else -> "none" to ""
        }
        val enabled = when (tap) {
            "editor" -> fieldActionEnabled()
            "clipboard" -> !field.isPassword
            "language" -> settings.languages.size >= 2
            "emoji" -> true
            else -> true
        }
        return AppBarButton(i, tap, hold, icon, label, enabled)
    }

    private fun fieldActionIconAndLabel(): Pair<String, String> = when (field.appBarAction) {
        FieldState.ACTION_SEARCH -> "search" to str(R.string.bar_search)
        FieldState.ACTION_GO -> "go" to str(R.string.bar_go)
        FieldState.ACTION_NEXT -> "next" to str(R.string.bar_next)
        FieldState.ACTION_PREVIOUS -> "previous" to str(R.string.bar_previous)
        FieldState.ACTION_DONE -> "done" to str(R.string.bar_done)
        else -> "send" to str(R.string.bar_send)
    }

    /** 03 §12.5.2: an action exists, and for "send" the field is not empty. */
    private fun fieldActionEnabled(): Boolean {
        val a = field.appBarAction
        if (a == 0) return false
        if (a == FieldState.ACTION_SEND) return editor.textBefore(1).isNotEmpty() || editor.textAfter(1).isNotEmpty()
        return true
    }

    fun appBarTap(b: AppBarButton) {
        appBarExpanded = false
        runAction(b.tap)
        view?.onAppBarChanged()
    }

    fun appBarHold(b: AppBarButton) {
        appBarExpanded = false
        runAction(b.hold)
        view?.onAppBarChanged()
    }

    fun toggleAppBarExpanded() {
        appBarExpanded = !appBarExpanded
        view?.onAppBarChanged()
    }

    fun menuAction(index: Int) {
        appBarExpanded = false
        when (index) {
            0 -> runAction("switch")
            1 -> runAction("clipboard")
            2 -> runAction("settings")
        }
        view?.onAppBarChanged()
    }

    fun runAction(id: String) {
        when {
            id == "editor" -> {
                val a = field.appBarAction
                if (a != 0 && fieldActionEnabled()) {
                    // like Enter (04 §9): the app gets the swiped word as finished text
                    logic.endSwipeMark()
                    editor.performEditorAction(a)
                }
            }
            id == "hide" -> service.requestHideSelf(0)
            id == "switch" -> service.showInputMethodPicker()
            id == "home" -> {
                service.requestHideSelf(0)
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            }
            id == "search" -> webSearch()
            id == "emoji" -> {
                closeClipboardPanel()
                layout.pages["text.emoji"]?.let { setPage(it, viaKey = true) }
            }
            id == "clipboard" -> toggleClipboardPanel()
            id == "language" -> nextLanguage()
            id == "left" -> {
                logic.endSwipeMark()
                editor.sendKey(KeyEvent.KEYCODE_DPAD_LEFT)
            }
            id == "right" -> {
                logic.endSwipeMark()
                editor.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            }
            id == "undo" -> {
                logic.endSwipeMark()
                editor.sendKey(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
            }
            id == "voice" -> toggleDictation()
            id == "settings" -> {
                service.requestHideSelf(0)
                startActivity(Intent(context, SettingsActivity::class.java))
            }
            id.startsWith("app:") -> {
                val i = context.packageManager.getLaunchIntentForPackage(id.removePrefix("app:")) ?: return
                service.requestHideSelf(0)
                startActivity(i)
            }
        }
    }

    private fun startActivity(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(i)
        } catch (_: Exception) {
        }
    }

    /** Web search for the selection, else the word at the caret; empty query in password fields (03 §12.3). */
    private fun webSearch() {
        val q = if (field.isPassword) "" else editor.selectedText()?.toString()?.takeIf { it.isNotBlank() } ?: run {
            val b = editor.textBefore(TextModel.WORD_MAX_LEN).toString()
            val a = editor.textAfter(TextModel.WORD_MAX_LEN).toString()
            TextModel.currentWord(b) + a.takeWhile { TextModel.isWordChar(it) }
        }
        service.requestHideSelf(0)
        val i = Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(i)
        } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
        }
    }

    // ================================================================ dictation (03 §14)

    fun toggleDictation() {
        if (dictationState != null) {
            dictation.stop()
            return
        }
        if (!dictation.start(language)) {
            if (!switchToVoiceInputMethod()) {
                showMessage(str(R.string.dictation_unavailable), long = true)
            }
        }
    }

    private fun switchToVoiceInputMethod(): Boolean {
        val imm = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java) ?: return false
        for (imi in imm.enabledInputMethodList) {
            if (imi.packageName == context.packageName) continue
            for (i in 0 until imi.subtypeCount) {
                if (imi.getSubtypeAt(i).mode == "voice") {
                    if (Build.VERSION.SDK_INT >= 28) {
                        service.switchInputMethod(imi.id, imi.getSubtypeAt(i))
                        return true
                    }
                }
            }
        }
        return false
    }

    fun onDictationState(s: DictationState?) {
        dictationState = s
        view?.onDictationChanged()
    }

    fun onDictationResult(text: String) {
        val cap = settings.autoCaps && field.policy.autoCapitalisation && (field.capsSentences || field.capsWordsOrChars) &&
            TextRules.isSentenceStart(editor.textBefore(3))
        logic.insertDictation(text, cap)
    }

    // ================================================================ inline autofill (03 §16)

    /** Inline autofill chips cover the strip: own candidates and buttons are not drawn or tappable. */
    var autofillActive = false
        private set

    fun setAutofillActive(on: Boolean) {
        if (autofillActive == on) return
        autofillActive = on
        view?.invalidate()
    }

    fun onPickerChanged() = service.onPickerChanged()

    // ================================================================ hardware keyboard (03 §13)

    fun setHardwareMode(on: Boolean) {
        if (hardwareMode == on) return
        hardwareMode = on
        miniKeyboardOpen = false
        view?.onHardwareModeChanged()
    }

    fun toggleMiniKeyboard(emoji: Boolean) {
        if (miniKeyboardOpen) {
            closeMiniKeyboard()
            return
        }
        val target = if (emoji) layout.pages["text.emoji"] else layout.pages["${layout.pagesFor(field.inputKind).first().substringBefore('.')}.symbols-1"]
        target ?: return
        miniKeyboardOpen = true
        setPage(target, viaKey = true)
        view?.onMiniKeyboardChanged(true)
    }

    fun closeMiniKeyboard() {
        if (!miniKeyboardOpen) return
        miniKeyboardOpen = false
        showStartPage()
        view?.onMiniKeyboardChanged(false)
    }

    /**
     * A short message of the keyboard: a toast, or – when the system would suppress it because the app's
     * notifications are off (Android 13+ drops toasts of apps without a resumed activity then) – the same text in
     * the strip for the toast's duration.
     */
    fun showMessage(text: String, long: Boolean) {
        if (androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        } else {
            view?.showMessage(text, if (long) 3500L else 2000L)
        }
    }

    // ================================================================ forgetting learned words (03 §6.3.4, 05 §12.5)

    /** True if the candidate [word] is a learned word the dictionary does not know (hold-to-forget). */
    fun isForgettable(word: String): Boolean = engine?.isForgettable(word) == true

    /** Forget(word): removes it from the learned data, then refreshes candidates and spelling marks. */
    fun forgetWord(word: String) {
        val e = engine ?: return
        scheduler.background {
            e.forget(word)
            e.saveLearnedData(learnedStore)
            scheduler.main(0) {
                logic.refresh()
                logic.scheduleSpellRun()
            }
        }
    }

    /** Words forgotten on the settings' learned words page (07 §9.13 item 5), applied to the running engine. */
    private fun applyForgetRequests(s: KeyboardSettings) {
        val e = engine ?: return
        val new = s.forgetRequests.filter { it.first > forgetHandled }
        if (new.isEmpty()) return
        forgetHandled = new.maxOf { it.first }
        scheduler.background {
            for ((_, w) in new) e.forget(w)
            e.saveLearnedData(learnedStore)
            scheduler.main(0) {
                logic.refresh()
                logic.scheduleSpellRun()
            }
        }
    }

    private var forgetHandled = 0L

    companion object {
        /** Lifetime of the recently-copied chip (03 §6.11). */
        const val CHIP_LIFETIME_MS = 2 * 60 * 1000L

        /** ClipDescription.EXTRA_IS_SENSITIVE (API 33), also used by password managers on older versions. */
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}
