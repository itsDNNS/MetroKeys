package app.metrokeys.ime

import app.metrokeys.engine.PredictionEngine
import app.metrokeys.engine.Prediction
import app.metrokeys.engine.Proofreader
import app.metrokeys.engine.Query
import app.metrokeys.engine.SuggestionKind
import app.metrokeys.engine.TextRange
import app.metrokeys.engine.swipe.ShapeResult
import app.metrokeys.engine.text.FieldPolicy
import app.metrokeys.engine.text.SpaceOrigin
import app.metrokeys.engine.text.TextModel
import app.metrokeys.engine.text.TextRules
import app.metrokeys.engine.text.TrailingSpaceAction
import app.metrokeys.engine.touch.Tap
import app.metrokeys.settings.KeyboardSettings
import java.text.BreakIterator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Timers and threads for [InputLogic]; replaced by a synchronous one in tests. */
interface Scheduler {
    fun now(): Long

    fun background(task: () -> Unit)

    /**
     * Runs a typing query off the main thread: queries of the current word, which should not wait behind learning,
     * proofreading or saving (production: an own thread; tests: [background]).
     */
    fun query(task: () -> Unit) = background(task)

    /** Runs [task] on the main thread after [delayMs]; returns a cancel function. */
    fun main(delayMs: Long, task: () -> Unit): () -> Unit
}

/**
 * The text-editing rules of spec 04 and the candidate-strip behaviour of 03 §6.2–§6.5: typing, separators,
 * autocorrection and its undo, picks, trailing/smart space, double-space period, backspace and word delete,
 * resumption with stored lists, swiped words, emoji insertion, Enter, caret moves and spelling marks.
 *
 * All calls happen on the main thread; engine queries run through [Scheduler.background] and [Scheduler.query]. The
 * typing path asks the engine nothing on the main thread: every change of the current word starts its query at once in
 * the background ([AheadQuery]), the strip shows it after the delay of 03 §6.2, and an autocorrection trigger takes it
 * (waiting for it if it is still running) instead of computing it.
 */
class InputLogic(
    private val editor: Editor,
    private val scheduler: Scheduler,
    private val listener: Listener,
) {
    interface Listener {
        /** A new strip list (shown at once unless an autocorrection flash is running). */
        fun onStrip(content: StripContent)

        /** An autocorrection was applied at a separator (flash, announcement, sound; 04 §3.3). */
        fun onAutoCorrected(correction: String)

        /** The text changed through the keyboard or the caret moved: re-evaluate shift, app bar state … */
        fun onTextChanged()

        /** Release a one-shot shift (after characters, picks, swipes; 03 §4.4). */
        fun onReleaseShift()
    }

    var engine: PredictionEngine? = null
    var settings: KeyboardSettings? = null
    var field: FieldState = FieldState.EMPTY
        private set

    // ---------------------------------------------------------------- selection tracking (04 §10)

    var selStart = 0
        private set
    var selEnd = 0
        private set
    private var compStart = -1
    private var compEnd = -1
    private val expected = ArrayDeque<Triple<Int, Int, Long>>()

    // ---------------------------------------------------------------- per-word state

    private val taps = ArrayList<Tap>()
    private var typedSinceMove = false
    private var autoSpace = false
    private var lastSpaceTime = 0L
    private var lastSpaceOrigin = SpaceOrigin.KEY
    private var swiped: StoredList? = null
    private var resumed: StoredList? = null
    private var lastKeyTime = 0L

    /** The last user move put the collapsed caret inside a word and no key was used since (04 §10 item 5). */
    private var caretWord = false

    /** True after a backspace until the next other key (key choice at touch-down, 03 §1.1a). */
    var lastWasBackspace = false
        private set

    /** The strip list currently shown (the one a pick refers to). */
    var strip: StripContent = StripContent.EMPTY
        private set

    private val stored = ArrayList<StoredList>()
    private val autocorrected = ArrayList<TextRange>()
    private val marked = ArrayList<TextRange>()
    private var stylesSupported: Boolean? = null

    private var generation = 0
    private var cancelQuery: (() -> Unit)? = null
    private var cancelSpell: (() -> Unit)? = null
    private var spellGeneration = 0

    private val s: KeyboardSettings? get() = settings

    // ================================================================ lifecycle

    /**
     * Start or restart of input in a field (03 §20): all per-word state and stored lists are cleared; the strip is then
     * set from the editor's current selection (04 §10 "Input start, restart and re-shown keyboard", 0.6.4).
     */
    fun startInput(field: FieldState, selStart: Int, selEnd: Int) {
        this.field = field
        editor.invalidateCache()
        ahead = null
        this.selStart = maxOf(0, selStart)
        this.selEnd = maxOf(0, selEnd)
        compStart = -1
        compEnd = -1
        expected.clear()
        resetWordState()
        stored.clear()
        autocorrected.clear()
        marked.clear()
        stylesSupported = null
        lastSpaceTime = 0
        lastWasBackspace = false
        cancelQuery?.invoke()
        generation++
        stripFromSelection()
        scheduleSpellRun()
    }

    /**
     * The keyboard view is shown again (04 §10 "Input start, restart and re-shown keyboard", 0.6.4): items 1–4 of a user
     * move apply, then the strip is set from the editor's current selection (items 5 and 6), so a caret inside a word
     * shows that word's candidates again.
     */
    fun reshown() {
        editor.invalidateCache()
        ahead = null
        finishComposing()
        resetWordState()
        lastWasBackspace = false
        cancelQuery?.invoke()
        generation++
        scheduleSpellRun()
        stripFromSelection()
    }

    fun finishInput() {
        cancelQuery?.invoke()
        cancelSpell?.invoke()
        if (swiped != null) {
            editor.finishComposing()
            swiped = null
        }
    }

    private fun resetWordState() {
        taps.clear()
        typedSinceMove = false
        autoSpace = false
        swiped = null
        resumed = null
        caretWord = false
    }

    // ================================================================ own edits

    private fun pushExpected() {
        val now = scheduler.now()
        while (expected.isNotEmpty() && now - expected.first().third > 3000) expected.removeFirst()
        expected.addLast(Triple(selStart, selEnd, now))
    }

    private fun commit(text: CharSequence) {
        val start = if (compStart >= 0) compStart else selStart
        val removedEnd = if (compStart >= 0) compEnd else selEnd
        editor.commitText(text)
        shiftRanges(start, removedEnd, text.length)
        selStart = start + text.length
        selEnd = selStart
        compStart = -1
        compEnd = -1
        pushExpected()
    }

    private fun deleteBefore(n: Int) {
        if (n <= 0) return
        val a = maxOf(0, selStart - n)
        editor.deleteBefore(n)
        shiftRanges(a, selStart, 0)
        selStart = a
        selEnd = a
        pushExpected()
    }

    private fun setComposing(text: String) {
        val start = if (compStart >= 0) compStart else selStart
        val end = if (compStart >= 0) compEnd else selEnd
        editor.setComposingText(text)
        shiftRanges(start, end, text.length)
        compStart = start
        compEnd = start + text.length
        selStart = compEnd
        selEnd = compEnd
        pushExpected()
    }

    /**
     * Finishes the composing text (the swiped word, 04 §12.2 item 4). Also when the editor reported no composing
     * region while a swiped word is marked: some editors leave it out of their selection updates, and an insertion
     * or deletion next to a composing region the editor still keeps would replace or skip it.
     */
    private fun finishComposing() {
        if (compStart >= 0 || swiped != null) {
            editor.finishComposing()
            compStart = -1
            compEnd = -1
        }
    }

    /** Moves stored lists and marks after an own edit that replaced [a, b) with [len] characters (03 §6.4.2). */
    private fun shiftRanges(a: Int, b: Int, len: Int) {
        val delta = len - (b - a)
        // entirely before (also typing at the very end) → unchanged; entirely after (also typing at the very
        // start) → shifted; overlapping → discarded
        fun adjust(start: Int, end: Int): Int? = when {
            end <= a -> 0
            start >= b -> delta
            else -> null
        }
        val it = stored.listIterator()
        while (it.hasNext()) {
            val l = it.next()
            when (val d = adjust(l.start, l.end)) {
                null -> it.remove()
                0 -> {}
                else -> it.set(l.copy(start = l.start + d))
            }
        }
        for (list in listOf(autocorrected, marked)) {
            val ri = list.listIterator()
            while (ri.hasNext()) {
                val r = ri.next()
                when (val d = adjust(r.start, r.end)) {
                    null -> ri.remove()
                    0 -> {}
                    else -> ri.set(TextRange(r.start + d, r.end + d))
                }
            }
        }
        swiped = swiped?.let { sw -> adjust(sw.start, sw.end)?.let { d -> sw.copy(start = sw.start + d) } }
    }

    /**
     * Selection update from the editor. Returns true if it was a user move (04 §10), false for the keyboard's own
     * edits and composing-only changes.
     */
    fun onSelectionUpdate(newStart: Int, newEnd: Int, candStart: Int, candEnd: Int): Boolean {
        // a composing region the keyboard did not set up (it sets one only for a swiped word): its copy may be wrong
        if (candStart >= 0 && swiped == null) editor.invalidateCache()
        val idx = expected.indexOfFirst { it.first == newStart && it.second == newEnd }
        if (idx >= 0) {
            repeat(idx + 1) { expected.removeFirst() }
            selStart = newStart
            selEnd = newEnd
            compStart = candStart
            compEnd = candEnd
            return false
        }
        if (newStart == selStart && newEnd == selEnd) {
            compStart = candStart
            compEnd = candEnd
            return false
        }
        expected.clear()
        // the editor changed the text or the caret itself: read the text again
        editor.invalidateCache()
        // selecting exactly the word still being typed is not a resumption (03 §6.4 item 5)
        val stillTyped = typedSinceMove && swiped == null && selStart == selEnd && newEnd == selStart && newStart < newEnd
        val typingList = strip.takeIf { it.kind == StripKind.TYPING }
        selStart = newStart
        selEnd = newEnd
        compStart = candStart
        compEnd = candEnd
        userMove(if (stillTyped) typingList ?: StripContent.EMPTY else null)
        return true
    }

    /** [typingList]: the strip of the word being typed if the move selected exactly that word, else null. */
    private fun userMove(typingList: StripContent? = null) {
        // the keyboard keeps no composing text apart from a swiped word (04 §12.1); a move ends it (§12.2 item 2)
        finishComposing()
        resetWordState()
        lastWasBackspace = false
        cancelQuery?.invoke()
        generation++
        scheduleSpellRun()
        if (selStart != selEnd && typingList != null && typedWordSelection(typingList)) {
            listener.onTextChanged()
            return
        }
        stripFromSelection()
        listener.onTextChanged()
    }

    /** The strip for the editor's current selection (04 §10 items 5 and 6, without the still-typed word of item 6). */
    private fun stripFromSelection() {
        if (selStart != selEnd) {
            selectionCandidates()
            return
        }
        val before = editor.textBefore(BEFORE).toString()
        val after = editor.textAfter(AFTER).toString()
        val atWordEnd = before.isNotEmpty() && TextModel.isWordChar(before.last())
        when {
            TextRules.caretInsideWord(after) -> if (atWordEnd) {
                caretWord = true
                caretWordCandidates()
            } else {
                showStrip(StripContent.EMPTY)
            }
            atWordEnd -> resume(before)
            before.isNotEmpty() -> requestPredictions()
            else -> showStrip(StripContent.EMPTY)
        }
    }

    /**
     * The selection covers exactly the word that was being typed (03 §6.4 item 5, 04 §10 item 6): the strip keeps the
     * typing list of that word (completions and corrections, nothing bold, no add-to-dictionary item); a pick replaces
     * the selection. Returns false if the selection is not that word.
     */
    private fun typedWordSelection(typingList: StripContent): Boolean {
        val sel = editor.selectedText()?.toString() ?: return false
        val before = editor.textBefore(BEFORE).toString()
        val after = editor.textAfter(AFTER).toString()
        if (!TextRules.isSingleWordSelection(before + sel + after, before.length, before.length + sel.length)) return false
        if (!policyAllowsSuggestions()) {
            showStrip(StripContent.EMPTY)
            return true
        }
        fun plain(items: List<StripItem>) = items.filter { !it.addWord }.map { it.copy(bold = false, autocorrection = false) }
        if (typingList.word == sel && typingList.wordStart == selStart && typingList.items.isNotEmpty()) {
            showStrip(StripContent(StripKind.SELECTION, plain(typingList.items), sel, selStart))
            return true
        }
        val engine = engine ?: return true
        val gen = ++generation
        val start = selStart
        val q = Query((before + sel).takeLast(BEFORE), after, null, field.info)
        scheduler.background {
            val p = engine.query(q)
            scheduler.main(0) {
                if (gen != generation) return@main
                showStrip(StripContent(StripKind.SELECTION, plain(toItems(p)), sel, start))
            }
        }
        return true
    }

    // ================================================================ typing

    /** Text of a character key, an alternate or a domain key, with the tap position for the touch model. */
    fun onText(text: String, x: Float = Float.NaN, y: Float = Float.NaN) {
        if (text.isEmpty()) return
        val cp = text.codePointAt(0)
        val single = text.length == Character.charCount(cp)
        when {
            single && cp == ' '.code -> separator(' ')
            single && cp < 0x10000 && TextModel.isWordChar(cp.toChar()) -> wordChar(cp.toChar(), x, y)
            isEmojiOrSymbol(text) -> insertEmoji(text, fromEmojiPage = false)
            single && cp < 0x10000 && TextRules.isAutocorrectTrigger(cp.toChar()) -> separator(cp.toChar())
            else -> otherText(text)
        }
    }

    private fun noteKey() {
        val now = scheduler.now()
        // the first key after a pause reads the field again (the app may have changed it without moving the caret)
        if (now - lastKeyTime >= CACHE_REFRESH_PAUSE_MS) editor.invalidateCache()
        lastKeyTime = now
        lastWasBackspace = false
        caretWord = false
    }

    private fun wordChar(c: Char, x: Float, y: Float) {
        val gap = scheduler.now() - lastKeyTime
        noteKey()
        editor.beginBatch()
        if (swiped != null) {
            finishComposing()
            swiped = null
            if (c != '\'' && c != '’') commit(" ")
        }
        val r = resumed
        if (r?.autoTyped != null && selStart == r.end) reject(r)
        resumed = null
        autoSpace = false
        val before = editor.textBefore(1)
        if (before.isEmpty() || !TextModel.isWordChar(before.last())) taps.clear()
        unmarkTouching()
        commit(c.toString())
        editor.endBatch()
        taps.add(Tap(c, x, y))
        typedSinceMove = true
        listener.onReleaseShift()
        requestTyping(gap)
        listener.onTextChanged()
    }

    /** Space, punctuation triggers and the line break of a multi-line Enter (04 §5.2). */
    private fun separator(c: Char) {
        noteKey()
        val now = scheduler.now()
        editor.beginBatch()
        val wasSwiped = swiped != null
        if (wasSwiped) {
            finishComposing()
            swiped = null
        }
        var autoSpaceRemoved = false
        if (autoSpace && (TextRules.smartSpaceDeletesBefore(c) || c == '\n')) {
            if (editor.textBefore(1).toString() == " ") {
                deleteBefore(1)
                autoSpaceRemoved = true
            }
        }
        autoSpace = false
        val before = editor.textBefore(BEFORE).toString()
        val after = editor.textAfter(AFTER).toString()
        val word = TextModel.currentWord(before)
        var correction: String? = null
        val st = s
        if (word.isNotEmpty() && st != null) {
            val may = TextRules.mayApplyAutocorrection(
                settingOn = st.effectiveAutocorrect,
                policy = field.policy,
                typedSinceMove = typedSinceMove,
                caretInsideWord = TextRules.caretInsideWord(after),
                charBeforeWord = TextModel.charBeforeCurrentWord(before),
                word = word,
                rejected = false,
                autoSpaceJustRemoved = autoSpaceRemoved,
                swiped = wasSwiped,
                fromClipboard = false,
                trigger = c,
                enterInMultiLine = c == '\n' && field.multiLine,
            )
            if (may) {
                val p = predictionNow(before, after)
                val ac = p?.autoCorrection
                if (ac != null && ac != word) {
                    correction = TextRules.correctionText(ac, c)
                    val start = selStart - word.length
                    val others = p.suggestions.filter { !it.isAutoCorrection && it.kind != SuggestionKind.EMOJI }.map { it.text }
                    val shownForWord = strip.kind == StripKind.TYPING && strip.word == word && strip.items.isNotEmpty()
                    val list = (listOf(word, correction) + if (shownForWord) emptyList() else others).distinct()
                    deleteBefore(word.length)
                    commit(correction)
                    stored.removeAll { it.start == start }
                    stored.add(StoredList(start, correction, list, StoredKind.AUTOCORRECTION, word, correction))
                    autocorrected.add(TextRange(start, start + correction.length))
                }
            }
        }
        val preceding = TextModel.precedingWords(before, before.length - word.length, 2)
        if (correction != null) {
            learn(correction, preceding)
        } else if (word.isNotEmpty() && typedSinceMove && !wasSwiped) {
            learn(word, preceding)
        }
        if (c == ' ' && correction == null && st != null) {
            val b = editor.textBefore(3)
            if (TextRules.shouldInsertPeriod(b, lastSpaceOrigin, now - lastSpaceTime, st.doubleSpacePeriod, field.policy) && lastSpaceTime > 0) {
                deleteBefore(1)
                commit(". ")
                editor.endBatch()
                lastSpaceTime = 0
                taps.clear()
                resumed = null
                showStrip(StripContent.EMPTY)
                scheduleSpellRun()
                listener.onTextChanged()
                return
            }
        }
        commit(c.toString())
        editor.endBatch()
        if (c == ' ') {
            lastSpaceTime = now
            lastSpaceOrigin = if (correction != null) SpaceOrigin.AUTOCORRECTION_OR_PICK else SpaceOrigin.KEY
        } else {
            lastSpaceTime = 0
        }
        taps.clear()
        resumed = null
        if (c != '\n') listener.onReleaseShift()
        if (correction != null) listener.onAutoCorrected(correction)
        if (c == '\n') showStrip(StripContent.EMPTY) else requestPredictions()
        scheduleSpellRun()
        listener.onTextChanged()
    }

    /** Other non-word characters (`- / ( @ #` …) and multi-character texts such as domain endings (04 §5.3). */
    private fun otherText(text: String) {
        noteKey()
        editor.beginBatch()
        val wasSwiped = swiped != null
        if (wasSwiped) {
            finishComposing()
            swiped = null
        }
        autoSpace = false
        val before = editor.textBefore(BEFORE).toString()
        val word = TextModel.currentWord(before)
        if (word.isNotEmpty() && typedSinceMove && !wasSwiped && !TextRules.caretInsideWord(editor.textAfter(1))) {
            learn(word, TextModel.precedingWords(before, before.length - word.length, 2))
        }
        // an opening mark ¿ ¡ starts a new phrase: after a swiped word a space comes first (04 §1.6 item 4, 06 §5.6)
        if (wasSwiped && TextRules.isOpeningMark(text)) commit(" ")
        commit(text)
        editor.endBatch()
        lastSpaceTime = 0
        taps.clear()
        resumed = null
        listener.onReleaseShift()
        showStrip(StripContent.EMPTY)
        scheduleSpellRun()
        listener.onTextChanged()
    }

    /** Emoji from the emoji page or symbol characters from keys and pickers (04 §5.4). */
    fun insertEmoji(text: String, fromEmojiPage: Boolean) {
        noteKey()
        editor.beginBatch()
        val wasSwiped = swiped != null
        if (wasSwiped) {
            finishComposing()
            swiped = null
        }
        unmarkTouching()
        val before = editor.textBefore(BEFORE).toString()
        val after = editor.textAfter(AFTER).toString()
        val word = TextModel.currentWord(before)
        if (word.isNotEmpty() && typedSinceMove && !wasSwiped && !TextRules.caretInsideWord(after)) {
            learn(word, TextModel.precedingWords(before, before.length - word.length, 2))
        }
        if (wasSwiped) {
            commit(" ")
        } else if (before.length >= 2 && before.last() == ' ' && endsWithEmoji(before.dropLast(1))) {
            deleteBefore(1)
        }
        autoSpace = false
        commit(text)
        if (s?.effectiveSpaceAfterSuggestion == true) trailingSpace(SpaceOrigin.EMOJI)
        editor.endBatch()
        taps.clear()
        resumed = null
        if (!fromEmojiPage) listener.onReleaseShift()
        showStrip(StripContent.EMPTY)
        scheduleSpellRun()
        listener.onTextChanged()
    }

    // ================================================================ trailing space (04 §4.3)

    /** Returns true if a space was inserted or skipped (an auto space). */
    private fun trailingSpace(origin: SpaceOrigin): Boolean {
        val action = TextRules.trailingSpace(editor.textAfter(2))
        when (action) {
            TrailingSpaceAction.INSERT_SPACE -> commit(" ")
            TrailingSpaceAction.SKIP_EXISTING_SPACE -> {
                val p = selStart + 1
                editor.setSelection(p, p)
                selStart = p
                selEnd = p
                pushExpected()
            }
            TrailingSpaceAction.NOTHING -> {
                autoSpace = false
                return false
            }
        }
        autoSpace = true
        lastSpaceTime = scheduler.now()
        lastSpaceOrigin = origin
        return true
    }

    // ================================================================ backspace (04 §6)

    /**
     * One backspace (04 §6). [repeated]: a step of a held backspace (03 §5), which never rejects an autocorrection
     * (05 §10.4 extension): deleting through corrected words is no undo.
     */
    fun backspace(repeated: Boolean = false) {
        noteKey()
        lastWasBackspace = true
        autoSpace = false
        lastSpaceTime = 0
        if (selStart != selEnd) {
            editor.beginBatch()
            commit("")
            editor.endBatch()
            resetWordState()
            lastWasBackspace = true
            afterDeletion(removedOnlySeparators = false)
            return
        }
        if (swiped != null && compStart >= 0) {
            editor.beginBatch()
            setComposing("")
            finishComposing()
            editor.endBatch()
            swiped = null
            showStrip(StripContent.EMPTY)
            listener.onTextChanged()
            return
        }
        if (swiped != null) {
            finishComposing()
            swiped = null
        }
        val before = editor.textBefore(64).toString()
        if (before.isEmpty()) {
            editor.sendKey(KEYCODE_DEL)
            listener.onTextChanged()
            return
        }
        val n = lastGraphemeLength(before)
        val removed = before.substring(before.length - n)
        val r = resumed
        if (r?.autoTyped != null && selStart == r.end && !repeated) reject(r)
        resumed = null
        editor.beginBatch()
        deleteBefore(n)
        unmarkTouching()
        editor.endBatch()
        if (taps.isNotEmpty() && removed.any { TextModel.isWordChar(it) }) taps.removeAt(taps.size - 1)
        afterDeletion(removedOnlySeparators = removed.none { TextModel.isWordChar(it) })
    }

    /** One step of the held-backspace word delete (04 §6.3). */
    fun wordDelete() {
        noteKey()
        lastWasBackspace = true
        autoSpace = false
        if (swiped != null) {
            finishComposing()
            swiped = null
        }
        if (selStart != selEnd) {
            backspace()
            return
        }
        val before = editor.textBefore(BEFORE).toString()
        val n = TextRules.wordDeleteLength(before)
        if (n == 0) return
        editor.beginBatch()
        deleteBefore(n)
        editor.endBatch()
        taps.clear()
        resumed = null
        afterDeletion(removedOnlySeparators = false)
    }

    private fun afterDeletion(removedOnlySeparators: Boolean) {
        val before = editor.textBefore(BEFORE).toString()
        val after = editor.textAfter(AFTER).toString()
        val atWordEnd = before.isNotEmpty() && TextModel.isWordChar(before.last())
        when {
            TextRules.caretInsideWord(after) -> showStrip(StripContent.EMPTY)
            removedOnlySeparators && atWordEnd -> {
                typedSinceMove = false
                taps.clear()
                resume(before)
            }
            atWordEnd -> {
                typedSinceMove = true
                requestTyping(0)
            }
            else -> {
                typedSinceMove = true
                if (before.isEmpty()) showStrip(StripContent.EMPTY) else requestPredictions()
            }
        }
        scheduleSpellRun()
        listener.onTextChanged()
    }

    // ================================================================ Enter (04 §9)

    fun enter() {
        noteKey()
        if (autoSpace && editor.textBefore(1).toString() == " ") deleteBefore(1)
        autoSpace = false
        if (swiped != null) {
            finishComposing()
            swiped = null
        }
        if (field.enterPerformsAction) {
            val before = editor.textBefore(BEFORE).toString()
            val word = TextModel.currentWord(before)
            if (word.isNotEmpty() && typedSinceMove) learn(word, TextModel.precedingWords(before, before.length - word.length, 2))
            taps.clear()
            showStrip(StripContent.EMPTY)
            editor.performEditorAction(field.action)
            listener.onTextChanged()
            return
        }
        if (field.rawInput) {
            editor.sendKey(KEYCODE_ENTER)
            showStrip(StripContent.EMPTY)
            listener.onTextChanged()
            return
        }
        if (field.multiLine) {
            separator('\n')
            return
        }
        // single-line field without action: let the app handle the key
        val before = editor.textBefore(BEFORE).toString()
        val word = TextModel.currentWord(before)
        if (word.isNotEmpty() && typedSinceMove) learn(word, TextModel.precedingWords(before, before.length - word.length, 2))
        editor.sendKey(KEYCODE_ENTER)
        taps.clear()
        showStrip(StripContent.EMPTY)
        scheduleSpellRun()
        listener.onTextChanged()
    }

    // ================================================================ picks (04 §4)

    /** Tap on strip entry [index] of [content]. */
    fun pick(content: StripContent, index: Int) {
        val item = content.items.getOrNull(index) ?: return
        noteKey()
        if (item.addWord) {
            val e = engine
            val w = content.word
            val f = field.info
            if (e != null) scheduler.background { e.addWord(w, f) }
            marked.removeAll { r -> r.start == content.wordStart && r.end == content.wordStart + content.word.length }
            showStrip(StripContent.EMPTY)
            scheduleSpellRun()
            return
        }
        val text = item.text
        val r = resumed
        editor.beginBatch()
        var newStart: Int
        when (content.kind) {
            StripKind.SELECTION -> {
                newStart = selStart
                commit(text)
            }
            StripKind.CARET_WORD -> {
                // the whole word around the caret (04 §4.1); the caret ends after the inserted word
                finishComposing()
                val start = content.wordStart
                val end = start + content.word.length
                editor.setSelection(start, end)
                selStart = start
                selEnd = end
                pushExpected()
                newStart = start
                commit(text)
            }
            StripKind.SWIPE -> {
                if (compStart >= 0) {
                    newStart = compStart
                    commit(text)
                } else {
                    newStart = selStart
                    commit(text)
                }
                swiped = null
            }
            StripKind.SWIPE_UNRECOGNISED -> {
                finishComposing()
                val b = editor.textBefore(1)
                if (b.isNotEmpty() && !b.last().isWhitespace()) commit(" ")
                newStart = selStart
                commit(text)
            }
            StripKind.PREDICTION, StripKind.NONE -> {
                finishComposing()
                if (item.emoji) {
                    val b = editor.textBefore(BEFORE).toString()
                    if (b.length >= 2 && b.last() == ' ' && endsWithEmoji(b.dropLast(1))) deleteBefore(1)
                }
                newStart = selStart
                commit(text)
            }
            StripKind.TYPING, StripKind.RESUMED -> {
                finishComposing()
                val b = editor.textBefore(BEFORE).toString()
                val w = TextModel.currentWord(b)
                deleteBefore(w.length)
                newStart = selStart
                commit(text)
            }
        }
        swiped = null
        // a picked elision prefix ("l'") gets no trailing space and no auto space (04 §4.3); the strip then offers the
        // elided words (05 §2.2)
        val prefixPick = item.elisionPrefix && content.kind in setOf(StripKind.TYPING, StripKind.RESUMED)
        // a word around the caret is replaced in place: no space is added (04 §10 item 5)
        val noSpace = prefixPick || content.kind == StripKind.CARET_WORD
        val spaced = if (!noSpace && s?.effectiveSpaceAfterSuggestion == true) trailingSpace(SpaceOrigin.AUTOCORRECTION_OR_PICK) else false
        if (noSpace) autoSpace = false
        editor.endBatch()
        // learning (not for emoji); the typed word of a resumed autocorrection rejects it. Picking the typed word
        // itself accepts it (05 §12.5).
        if (r?.autoTyped != null && text == r.autoTyped) {
            reject(r, accepted = true)
        } else if (!item.emoji && !prefixPick) {
            val b = editor.textBefore(BEFORE).toString()
            val typedWord = content.kind in setOf(StripKind.TYPING, StripKind.RESUMED, StripKind.SELECTION, StripKind.CARET_WORD) &&
                text == content.word
            learn(text, TextModel.precedingWords(b, maxOf(0, b.length - text.length - (if (spaced) 1 else 0)), 2), typedWord)
        }
        val list = content.stored
        if (list != null && !item.emoji) {
            stored.removeAll { it.start == newStart }
            stored.add(list.copy(start = newStart, word = text, kind = StoredKind.PICKED))
        } else if (content.kind == StripKind.SWIPE) {
            stored.removeAll { it.start == newStart }
            stored.add(StoredList(newStart, text, content.items.map { it.text }, StoredKind.SWIPE))
        }
        typedSinceMove = false
        taps.clear()
        resumed = null
        listener.onReleaseShift()
        showStrip(StripContent.EMPTY)
        if (prefixPick) requestTyping(0) else if (spaced && !item.emoji) requestPredictions()
        scheduleSpellRun()
        listener.onTextChanged()
    }

    // ================================================================ swipes (06 §5)

    /** Applies a shape-writing result; [texts] are the candidate texts with the shift case applied. */
    fun applySwipe(result: ShapeResult, texts: List<String>) {
        if (texts.isEmpty()) return
        noteKey()
        if (!result.recognized) {
            showStrip(StripContent(StripKind.SWIPE_UNRECOGNISED, texts.map { StripItem(it) }))
            return
        }
        editor.beginBatch()
        finishComposing()
        swiped = null
        autoSpace = false
        // 06 §5.1 item 1; never a space directly after an opening mark ¿ ¡ (04 §1.6 item 4), after "<letter>-" or
        // after an elision prefix of a loaded language ("l'" + "acqua")
        val elided = engine?.isElisionPrefix(TextModel.currentWord(editor.textBefore(BEFORE))) == true
        if (!elided && TextRules.spaceBeforeSwipe(editor.textBefore(2))) commit(" ")
        val start = selStart
        setComposing(texts[0])
        editor.endBatch()
        val list = StoredList(start, texts[0], texts, StoredKind.SWIPE)
        swiped = list
        stored.removeAll { it.start == start }
        stored.add(list)
        typedSinceMove = false
        taps.clear()
        resumed = null
        lastSpaceTime = 0
        listener.onReleaseShift()
        showStrip(StripContent(StripKind.SWIPE, texts.mapIndexed { i, t -> StripItem(t, bold = i == 0) }, texts[0], start))
        listener.onTextChanged()
    }

    /** Text from the clipboard history panel or the recently-copied chip: treated like a paste (03 §10.3, 04 §4.4). */
    fun insertClip(text: String) {
        noteKey()
        editor.beginBatch()
        finishComposing()
        swiped = null
        commit(text)
        editor.endBatch()
        afterPaste()
    }

    /**
     * The paste button (03 §6.6 item 5): [perform] runs the editor's own paste action, which inserts at the caret
     * and leaves a composing region alone. The swiped word is therefore finished first (04 §12.2 items 2, 4);
     * otherwise it would stay underlined and the next backspace would delete the text before it.
     */
    fun paste(perform: () -> Boolean) {
        insertContent { perform(); true }
    }

    /**
     * Inserts content that is not committed by the keyboard as text (an image by commitContent, 03 §10.4; the
     * editor's paste): the composing text is finished first, [commit] performs the insertion; on success the paste
     * effects apply. Returns the result of [commit].
     */
    fun insertContent(commit: () -> Boolean): Boolean {
        noteKey()
        editor.beginBatch()
        finishComposing()
        swiped = null
        editor.endBatch()
        val ok = commit()
        if (ok) afterPaste()
        return ok
    }

    /** Effects of a paste on the text state (03 §6.6.6). */
    private fun afterPaste() {
        autoSpace = false
        swiped = null
        resumed = null
        typedSinceMove = false
        taps.clear()
        showStrip(StripContent.EMPTY)
        listener.onTextChanged()
    }

    /** Dictation result (03 §14.2): a space before it if needed, capitalised at a sentence start. */
    fun insertDictation(text: String, capitalize: Boolean) {
        if (text.isBlank()) return
        noteKey()
        editor.beginBatch()
        finishComposing()
        swiped = null
        val b = editor.textBefore(1)
        if (b.isNotEmpty() && !b.last().isWhitespace()) commit(" ")
        commit(if (capitalize) text.replaceFirstChar { it.uppercaseChar() } else text)
        editor.endBatch()
        autoSpace = false
        typedSinceMove = false
        taps.clear()
        requestPredictions()
        scheduleSpellRun()
        listener.onTextChanged()
    }

    /** Ends the swiped-word mark without other changes (03 §13.2 hardware keys going to the app, language change). */
    fun endSwipeMark() {
        if (swiped != null) {
            finishComposing()
            swiped = null
        }
    }

    // ================================================================ resumption and selection (03 §6.4, §6.5)

    private fun resume(before: String) {
        val word = TextModel.currentWord(before)
        if (word.isEmpty() || word.length > TextModel.WORD_MAX_LEN) {
            showStrip(StripContent.EMPTY)
            return
        }
        val start = selStart - word.length
        val list = stored.firstOrNull { it.start == start }
        if (list != null && list.word == word) {
            resumed = list
            val items = list.items.filter { it != word }.map { StripItem(it) }.toMutableList()
            addWordItem(items, word, list.items.firstOrNull() == word && list.autoTyped == null)
            showStrip(StripContent(StripKind.RESUMED, items, word, start, list))
            return
        }
        if (list != null) stored.remove(list)
        val engine = engine ?: return showStrip(StripContent.EMPTY)
        if (!policyAllowsSuggestions()) return showStrip(StripContent.EMPTY)
        val gen = ++generation
        val f = field.info
        scheduler.background {
            val res = engine.resumeCorrections(before, f).map { it.text }.filter { it != word }
            val known = engine.isKnown(word)
            scheduler.main(0) {
                if (gen != generation) return@main
                val l = StoredList(start, word, listOf(word) + res, StoredKind.SEARCH)
                stored.removeAll { it.start == start }
                stored.add(l)
                resumed = l
                val items = res.map { StripItem(it) }.toMutableList()
                addWordItem(items, word, !known)
                showStrip(StripContent(StripKind.RESUMED, items, word, start, l))
            }
        }
    }

    private fun selectionCandidates() {
        val sel = editor.selectedText()?.toString() ?: return showStrip(StripContent.EMPTY)
        val before = editor.textBefore(BEFORE).toString()
        val after = editor.textAfter(AFTER).toString()
        val whole = before + sel + after
        if (!TextRules.isSingleWordSelection(whole, before.length, before.length + sel.length) || !policyAllowsSuggestions()) {
            return showStrip(StripContent.EMPTY)
        }
        val list = stored.firstOrNull { it.start == selStart && it.word == sel }
        if (list != null) {
            val items = list.items.filter { it != sel }.map { StripItem(it) }.toMutableList()
            addWordItem(items, sel, list.items.firstOrNull() == sel && list.autoTyped == null)
            return showStrip(StripContent(StripKind.SELECTION, items, sel, selStart, list))
        }
        val engine = engine ?: return
        val gen = ++generation
        val f = field.info
        val start = selStart
        scheduler.background {
            val res = engine.resumeCorrections(before + sel, f).map { it.text }.filter { it != sel }
            val known = engine.isKnown(sel)
            scheduler.main(0) {
                if (gen != generation) return@main
                val items = res.map { StripItem(it) }.toMutableList()
                addWordItem(items, sel, !known)
                showStrip(StripContent(StripKind.SELECTION, items, sel, start))
            }
        }
    }

    /**
     * Collapsed caret inside a word (04 §10 item 5, first case, 0.6.4): candidates for the whole word around the caret,
     * as for a selection of that one word — its stored list if it has one, else the correction search with the word as
     * typed and no taps; the same length and character limits; the add-to-dictionary item. A pick replaces the word.
     */
    private fun caretWordCandidates() {
        val before = editor.textBefore(BEFORE).toString()
        val after = editor.textAfter(TextModel.WORD_MAX_LEN + 1).toString()
        val leftStart = TextModel.currentWordStart(before)
        var rightLen = 0
        while (rightLen < after.length && TextModel.isWordChar(after[rightLen])) rightLen++
        val word = before.substring(leftStart) + after.substring(0, rightLen)
        val whole = before + after
        if (!TextRules.isSingleWordSelection(whole, leftStart, leftStart + word.length) || !policyAllowsSuggestions()) {
            return showStrip(StripContent.EMPTY)
        }
        val start = selStart - (before.length - leftStart)
        val list = stored.firstOrNull { it.start == start && it.word == word }
        if (list != null) {
            val items = list.items.filter { it != word }.map { StripItem(it) }.toMutableList()
            addWordItem(items, word, list.items.firstOrNull() == word && list.autoTyped == null)
            return showStrip(StripContent(StripKind.CARET_WORD, items, word, start, list))
        }
        val engine = engine ?: return showStrip(StripContent.EMPTY)
        val gen = ++generation
        val f = field.info
        val context = before.substring(0, leftStart) + word
        scheduler.background {
            val res = engine.resumeCorrections(context, f).map { it.text }.filter { it != word }
            val known = engine.isKnown(word)
            scheduler.main(0) {
                if (gen != generation) return@main
                val items = res.map { StripItem(it) }.toMutableList()
                addWordItem(items, word, !known)
                showStrip(StripContent(StripKind.CARET_WORD, items, word, start))
            }
        }
    }

    /** The add-to-dictionary item after the best suggestion (03 §6.5). */
    private fun addWordItem(items: MutableList<StripItem>, word: String, unknown: Boolean) {
        val st = s ?: return
        if (!unknown || !st.suggestions || !st.effectiveSpellingUnderline || !field.info.spellingMarks) return
        if (!TextModel.isCheckable(word)) return
        if (engine?.isKnown(word) == true) return
        items.add(minOf(1, items.size), StripItem(word, addWord = true))
    }

    private fun policyAllowsSuggestions(): Boolean = field.policy.prediction && s?.suggestions == true

    // ================================================================ queries (03 §6.2, 05 §2.3)

    private fun requestTyping(gapMs: Long) {
        val engine = engine
        if (engine == null || !policyAllowsSuggestions()) {
            showStrip(StripContent.EMPTY)
            return
        }
        cancelQuery?.invoke()
        val gen = ++generation
        startAhead(engine)
        val delay = TextRules.queryDelayMs(gapMs)
        cancelQuery = scheduler.main(delay) { runTypingQuery(engine, gen) }
    }

    /**
     * The query of the current word as it is now, or null when the strip shows nothing for it (no word, a word longer
     * than 27 characters, the caret inside a word).
     */
    private fun currentTypingQuery(): Query? {
        val before = editor.textBefore(BEFORE).toString()
        val after = editor.textAfter(AFTER).toString()
        val word = TextModel.currentWord(before)
        if (word.isEmpty() || word.length > TextModel.WORD_MAX_LEN || TextRules.caretInsideWord(after)) return null
        return Query(before, after, ArrayList(taps), field.info)
    }

    /**
     * A background query of the current word, started at once at every change of the word and independent of the
     * strip's delayed update (03 §6.2): the strip update and an autocorrection trigger use its result. [state] decides
     * who computes it: the query thread when it starts it first, else the main thread that [claim]ed it.
     */
    private class AheadQuery(val query: Query) {
        val state = AtomicInteger(PENDING)
        @Volatile
        var result: Prediction? = null
        val done = CountDownLatch(1)

        /** The main thread takes over a query that has not started yet; true if it did. */
        fun claim(): Boolean = state.compareAndSet(PENDING, CLAIMED)

        companion object {
            const val PENDING = 0
            const val RUNNING = 1
            const val DONE = 2
            const val CLAIMED = 3
        }
    }

    @Volatile
    private var ahead: AheadQuery? = null

    private fun startAhead(engine: PredictionEngine) {
        val q = currentTypingQuery()
        if (q == null) {
            ahead = null
            return
        }
        val a = AheadQuery(q)
        ahead = a
        scheduler.query {
            // replaced by a newer word state, or claimed by the main thread
            if (ahead !== a || !a.state.compareAndSet(AheadQuery.PENDING, AheadQuery.RUNNING)) return@query
            try {
                a.result = Perf.section("mk:aheadQuery") { engine.query(q) }
            } finally {
                a.state.set(AheadQuery.DONE)
                a.done.countDown()
            }
        }
    }

    private fun runTypingQuery(engine: PredictionEngine, gen: Int) = Perf.section("mk:typingQuery") {
        val q = currentTypingQuery()
        if (q == null) {
            showStrip(StripContent.EMPTY)
            return@section
        }
        val word = TextModel.currentWord(q.before)
        val start = selStart - word.length
        val a = ahead?.takeIf { it.query == q }
        // after the ahead query on the same thread: its result is ready unless it was skipped or claimed
        scheduler.query {
            val p = a?.takeIf { it.state.get() == AheadQuery.DONE }?.result ?: engine.query(q)
            scheduler.main(0) {
                if (gen != generation) return@main
                lastPrediction = p
                lastPredictionBefore = q.before
                showStrip(StripContent(StripKind.TYPING, toItems(p), word, start))
            }
        }
    }

    private var lastPrediction: Prediction? = null
    private var lastPredictionBefore: String? = null

    /**
     * The prediction for the word before the caret at an autocorrection trigger: the last strip result for the same
     * text, else the ahead query of this text (waiting for it while it runs), else – only when that query has not
     * started yet or the text differs – computed now.
     */
    private fun predictionNow(before: String, after: String): Prediction? {
        val engine = engine ?: return null
        val b = before.takeLast(BEFORE)
        val last = lastPrediction
        if (last != null && lastPredictionBefore == b) return last
        val q = Query(b, after, ArrayList(taps), field.info)
        val a = ahead
        if (a != null && a.query == q && !a.claim()) {
            val ready = Perf.section("mk:awaitAhead") { a.done.await(AHEAD_WAIT_MS, TimeUnit.MILLISECONDS) }
            if (ready) a.result?.let { return it }
        }
        return Perf.section("mk:syncQuery") { engine.query(q) }
    }

    private fun requestPredictions() {
        val engine = engine
        if (engine == null || !policyAllowsSuggestions()) {
            showStrip(StripContent.EMPTY)
            return
        }
        cancelQuery?.invoke()
        val gen = ++generation
        val before = editor.textBefore(BEFORE).toString()
        if (before.isEmpty()) {
            showStrip(StripContent.EMPTY)
            return
        }
        val q = Query(before, editor.textAfter(AFTER).toString(), null, field.info)
        scheduler.background {
            val p = engine.query(q)
            scheduler.main(0) {
                if (gen != generation) return@main
                showStrip(StripContent(StripKind.PREDICTION, toItems(p)))
            }
        }
    }

    private fun toItems(p: Prediction): List<StripItem> = p.suggestions.map {
        StripItem(it.text, bold = it.isAutoCorrection, emoji = it.kind == SuggestionKind.EMOJI, autocorrection = it.isAutoCorrection,
            elisionPrefix = it.isElisionPrefix)
    }

    private fun showStrip(c: StripContent) {
        strip = c
        listener.onStrip(c)
    }

    /** Re-runs the strip evaluation for the current caret (after the engine finished loading, settings changes). */
    fun refresh() {
        if (selStart != selEnd) {
            selectionCandidates()
            return
        }
        val before = editor.textBefore(BEFORE).toString()
        val after = editor.textAfter(AFTER).toString()
        when {
            swiped != null -> {}
            TextRules.caretInsideWord(after) ->
                if (caretWord && before.isNotEmpty() && TextModel.isWordChar(before.last())) caretWordCandidates()
                else showStrip(StripContent.EMPTY)
            before.isNotEmpty() && TextModel.isWordChar(before.last()) ->
                if (typedSinceMove) requestTyping(0) else resume(before)
            before.isNotEmpty() && strip.kind == StripKind.PREDICTION -> requestPredictions()
            else -> {}
        }
    }

    // ================================================================ learning (04 §13)

    private fun learn(word: String, preceding: List<String>, accepted: Boolean = false) {
        val engine = engine ?: return
        if (!field.policy.learning) return
        val w = TextModel.trimNonWord(word)
        if (w.isEmpty() || !TextModel.isLearnable(w.replace(" ", ""))) return
        val f = field.info
        scheduler.background { engine.learn(w, preceding, f, accepted) }
    }

    /** Rejects the autocorrection stored in [r] (05 §10.4); [accepted]: by picking the typed word (05 §12.5). */
    private fun reject(r: StoredList, accepted: Boolean = false) {
        val typed = r.autoTyped ?: return
        val corr = r.autoCorrection ?: return
        val engine = engine ?: return
        val f = field.info
        scheduler.background { engine.rejectAutoCorrection(typed, corr, f, accepted) }
        stored.remove(r)
        autocorrected.removeAll { it.start == r.start }
        resumed = null
    }

    // ================================================================ spelling marks (04 §7, 05 §16)

    fun scheduleSpellRun() {
        cancelSpell?.invoke()
        val st = s ?: return
        if (!st.effectiveSpellingUnderline || !field.info.spellingMarks) return
        val engine = engine ?: return
        cancelSpell = scheduler.main(Proofreader.RUN_DELAY_MS) { spellRun(engine) }
    }

    private fun spellRun(engine: PredictionEngine) = Perf.section("mk:spellRun") { spellRunNow(engine) }

    private fun spellRunNow(engine: PredictionEngine) {
        if (selStart != selEnd) return
        if (stylesSupported == null) {
            if (editor.textBefore(1).isEmpty() && editor.textAfter(1).isEmpty()) return
            stylesSupported = editor.supportsStyles()
        }
        if (stylesSupported != true) return
        // the check may use the keyboard's copy of the text; changing marks rewrites words, so the result is applied only
        // if the field's own text (read again below) is still the checked one
        val before = editor.textBefore(Proofreader.RANGE).toString()
        val after = editor.textAfter(Proofreader.RANGE).toString()
        val base = selStart - before.length
        val text = before + after
        val caret = before.length
        val m = marked.map { TextRange(it.start - base, it.end - base) }
        val ac = autocorrected.map { TextRange(it.start - base, it.end - base) }
        val gen = ++spellGeneration
        val sel = selStart
        scheduler.background {
            val res = Proofreader(engine).run(text, caret, caret, m, ac)
            scheduler.main(0) {
                if (gen != spellGeneration || sel != selStart || selStart != selEnd) return@main
                if (res.add.isEmpty() && res.remove.isEmpty()) return@main
                // the text must be unchanged: compared with the field itself, not with the copy
                editor.invalidateCache()
                val nb = editor.textBefore(Proofreader.RANGE).toString()
                val na = editor.textAfter(Proofreader.RANGE).toString()
                if (nb + na != text) return@main
                editor.beginBatch()
                for (r in res.remove) {
                    editor.restyleWord(r.start + base, r.end + base, text.substring(r.start, r.end), null, selStart)
                    marked.removeAll { it.start == r.start + base && it.end == r.end + base }
                }
                for (a in res.add) {
                    editor.restyleWord(a.start + base, a.end + base, a.word, a.suggestions, selStart)
                    marked.add(TextRange(a.start + base, a.end + base))
                }
                restoreComposing()
                editor.endBatch()
            }
        }
    }

    /** Any edit that touches a marked word removes its mark (04 §7.4). */
    private fun unmarkTouching() {
        val c = selStart
        val touching = marked.filter { it.start <= c && c <= it.end }
        if (touching.isEmpty()) return
        editor.invalidateCache()
        val before = editor.textBefore(BEFORE).toString()
        val after = editor.textAfter(AFTER).toString()
        val base = selStart - before.length
        val text = before + after
        for (r in touching) {
            marked.remove(r)
            val a = r.start - base
            val b = r.end - base
            if (a >= 0 && b <= text.length) editor.restyleWord(r.start, r.end, text.substring(a, b), null, selStart)
        }
        restoreComposing()
    }

    private fun restoreComposing() {
        val sw = swiped ?: return
        editor.setComposingRegion(sw.start, sw.end)
    }

    // ================================================================ helpers

    /** The 1.1a key choice needs the current word before the caret. */
    fun currentWord(): String = TextModel.currentWord(editor.textBefore(TextModel.WORD_MAX_LEN + 1))

    val isTypingAllowedForKeyChoice: Boolean
        get() = this.field.policy == FieldPolicy.TEXT && s?.effectiveAutocorrect == true

    companion object {
        const val BEFORE = 100
        const val AFTER = 20
        const val KEYCODE_DEL = 67
        const val KEYCODE_ENTER = 66

        /** The first key after this pause reads the field's text again instead of the keyboard's copy. */
        const val CACHE_REFRESH_PAUSE_MS = 1000L

        /** Longest wait of an autocorrection trigger for the running ahead query before it computes the query itself. */
        const val AHEAD_WAIT_MS = 1000L

        private val breaker: BreakIterator = BreakIterator.getCharacterInstance()

        /** Length of the last grapheme cluster of [s] (04 §6.1.4). */
        fun lastGraphemeLength(s: String): Int {
            if (s.isEmpty()) return 0
            synchronized(breaker) {
                breaker.setText(s)
                val end = breaker.last()
                val start = breaker.previous()
                return if (start == BreakIterator.DONE) s.length else end - start
            }
        }

        /** Emoji (code points ≥ U+1F000 and sequences) and the symbol class of 04 §11. */
        fun isEmojiOrSymbol(text: String): Boolean {
            val cp = text.codePointAt(0)
            if (TextModel.isEmojiCodePoint(cp) || TextModel.isSymbolChar(cp)) return true
            if (text.length > Character.charCount(cp)) {
                // sequences with a variation selector, keycap or joiner (❤️, 1️⃣, …)
                return text.any { it == '️' || it == '⃣' || it == '‍' }
            }
            return cp in 0x2600..0x27BF
        }

        /** True if [s] ends with an emoji or a symbol character. */
        fun endsWithEmoji(s: String): Boolean {
            if (s.isEmpty()) return false
            val cp = s.codePointBefore(s.length)
            return TextModel.isEmojiCodePoint(cp) || TextModel.isSymbolChar(cp) || cp == 0xFE0F || cp == 0x20E3 ||
                cp in 0x2600..0x27BF || cp in 0xE0020..0xE007F
        }
    }
}
