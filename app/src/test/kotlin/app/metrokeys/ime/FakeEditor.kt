package app.metrokeys.ime

/** An in-memory text field with selection and composing region, like an EditText behind an InputConnection. */
class FakeEditor : Editor {
    val text = StringBuilder()
    var selStart = 0
    var selEnd = 0
    var compStart = -1
    var compEnd = -1
    var styles = true
    val sentKeys = ArrayList<Int>()
    val actions = ArrayList<Int>()
    val marks = HashMap<IntRange, List<String>>()
    private var batch = 0

    /** False: selection updates report no composing region (some editors do not). */
    var reportComposing = true

    /** Receives selection updates (like InputMethodService.onUpdateSelection). */
    var onUpdate: ((Int, Int, Int, Int) -> Unit)? = null
    private var dirty = false

    override fun textBefore(n: Int): CharSequence = text.substring(maxOf(0, selStart - n), selStart)

    override fun textAfter(n: Int): CharSequence = text.substring(selEnd, minOf(text.length, selEnd + n))

    override fun selectedText(): CharSequence? = if (selStart == selEnd) null else text.substring(selStart, selEnd)

    private fun replace(a: Int, b: Int, s: CharSequence) {
        text.replace(a, b, s.toString())
        selStart = a + s.length
        selEnd = selStart
        changed()
    }

    override fun commitText(text: CharSequence) {
        if (compStart >= 0) {
            replace(compStart, compEnd, text)
            compStart = -1
            compEnd = -1
        } else {
            replace(selStart, selEnd, text)
        }
    }

    /**
     * Like Android's BaseInputConnection.deleteSurroundingText: the composing text is skipped (the deletion starts
     * before the composing region if it begins before the caret) and the composing region stays.
     */
    override fun deleteBefore(n: Int) {
        val from = if (compStart >= 0) minOf(selStart, compStart) else selStart
        val a = maxOf(0, from - n)
        val removed = from - a
        text.delete(a, from)
        if (compStart >= 0) {
            compStart -= removed
            compEnd -= removed
        }
        selStart -= removed
        selEnd -= removed
        changed()
    }

    override fun setComposingText(text: CharSequence) {
        val a = if (compStart >= 0) compStart else selStart
        val b = if (compStart >= 0) compEnd else selEnd
        replace(a, b, text)
        compStart = a
        compEnd = a + text.length
        if (text.isEmpty()) {
            compStart = -1
            compEnd = -1
        }
    }

    override fun finishComposing() {
        compStart = -1
        compEnd = -1
        changed()
    }

    override fun setComposingRegion(start: Int, end: Int) {
        compStart = start
        compEnd = end
    }

    override fun setSelection(start: Int, end: Int) {
        selStart = start
        selEnd = end
        changed()
    }

    override fun sendKey(keyCode: Int, metaState: Int) {
        sentKeys.add(keyCode)
    }

    override fun performEditorAction(action: Int) {
        actions.add(action)
    }

    /** Text the editor's paste action inserts; null: nothing to paste. */
    var clipboard: String? = null

    /**
     * Like TextView's paste: replaces the selection with [clipboard]; a composing region before or after the
     * selection is kept (moved with the text), as the composing span survives a paste.
     */
    override fun performPaste(): Boolean {
        val clip = clipboard ?: return false
        val a = selStart
        val b = selEnd
        val delta = clip.length - (b - a)
        val cs = compStart
        val ce = compEnd
        text.replace(a, b, clip)
        selStart = a + clip.length
        selEnd = selStart
        when {
            cs < 0 -> {}
            ce <= a -> {}
            cs >= b -> {
                compStart = cs + delta
                compEnd = ce + delta
            }
            else -> {
                compStart = -1
                compEnd = -1
            }
        }
        changed()
        return true
    }

    /** The current composing text, null when there is none. */
    val composing: String? get() = if (compStart >= 0) text.substring(compStart, compEnd) else null

    override fun beginBatch() {
        batch++
    }

    override fun endBatch() {
        batch--
        if (batch == 0) flush()
    }

    private fun changed() {
        dirty = true
        if (batch == 0) flush()
    }

    private fun flush() {
        if (!dirty) return
        dirty = false
        update()
    }

    override fun capsMode(reqModes: Int): Int = 0

    override fun supportsStyles(): Boolean = styles

    override fun restyleWord(start: Int, end: Int, word: String, suggestions: List<String>?, caret: Int) {
        check(text.substring(start, end) == word) { "restyle of a changed word" }
        // like InputConnectionEditor: the word is made the composing region and committed again, which ends any
        // composing region
        compStart = -1
        compEnd = -1
        if (suggestions == null) marks.remove(start until end) else marks[start until end] = suggestions
    }

    /** A caret move by the user. */
    fun userMove(start: Int, end: Int = start) {
        selStart = start
        selEnd = end
        update()
    }

    private fun update() {
        if (reportComposing) onUpdate?.invoke(selStart, selEnd, compStart, compEnd) else onUpdate?.invoke(selStart, selEnd, -1, -1)
    }

    override fun toString(): String = text.substring(0, selStart) + "|" + text.substring(selStart)
}

/**
 * Runs background work at once (or, with [deferBackground], when [runBackground] is called) and main-thread work when
 * [run] is called, with a controllable clock. [inBackground] tells whether background work is running.
 */
class FakeScheduler : Scheduler {
    var time = 10_000L
    private val queue = ArrayList<Pair<Long, () -> Unit>>()
    var inBackground = false
        private set
    var deferBackground = false
    private val pending = ArrayList<() -> Unit>()

    override fun now(): Long = time

    override fun background(task: () -> Unit) {
        if (deferBackground) {
            pending.add(task)
            return
        }
        val was = inBackground
        inBackground = true
        try {
            task()
        } finally {
            inBackground = was
        }
    }

    /** Runs the deferred background tasks in order (also ones they post). */
    fun runBackground() {
        while (pending.isNotEmpty()) {
            val t = pending.removeAt(0)
            inBackground = true
            try {
                t()
            } finally {
                inBackground = false
            }
        }
    }

    override fun main(delayMs: Long, task: () -> Unit): () -> Unit {
        val e = (time + delayMs) to task
        queue.add(e)
        return { queue.remove(e) }
    }

    /** Advances the clock by [ms] and runs every task that became due (also ones posted meanwhile). */
    fun run(ms: Long = 0) {
        time += ms
        while (true) {
            val next = queue.filter { it.first <= time }.minByOrNull { it.first } ?: break
            queue.remove(next)
            next.second()
        }
    }
}
