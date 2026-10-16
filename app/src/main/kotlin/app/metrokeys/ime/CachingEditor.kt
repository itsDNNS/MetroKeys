package app.metrokeys.ime

/**
 * An [Editor] that keeps a copy of the text around the caret, so the typing path does not wait for the app: every
 * read of an InputConnection is a synchronous call that the app answers on its UI thread, after it has processed the
 * keyboard's earlier edits (and often a layout pass). The copy is updated by the keyboard's own commits, deletions and
 * composing text (a swiped word directly before the caret), and dropped by every other change: [invalidateCache]
 * (input start, caret moves, unexpected selection updates, a typing pause), selection changes, keys and actions sent to
 * the app, the paste action, and edits next to a composing region at an unknown place.
 */
class CachingEditor(private val inner: Editor) : Editor {
    /** Text before the caret (the selection start) as last read or edited; null: unknown. */
    private var before: String? = null
    /** [before] reaches the start of the field. */
    private var beforeComplete = false
    private var after: String? = null
    private var afterComplete = false
    /** Length of the composing text set through this editor; it ends at the caret. */
    private var composingLen = 0
    /** A composing region may exist somewhere else: commits and deletions drop the copy. */
    private var composingElsewhere = false

    /** Number of reads that went to the editor (for tests and measurements). */
    var reads = 0
        private set

    override fun invalidateCache() {
        before = null
        after = null
        // the caret may have left a composing region set before: its place is no longer known
        if (composingLen > 0) composingElsewhere = true
        composingLen = 0
    }

    override fun textBefore(n: Int): CharSequence {
        if (n <= 0) return ""
        val b = before
        if (b != null && (b.length >= n || beforeComplete)) return if (b.length <= n) b else b.substring(b.length - n)
        reads++
        val want = maxOf(n, FETCH)
        val t = inner.textBefore(want).toString()
        before = t
        beforeComplete = t.length < want
        return if (t.length <= n) t else t.substring(t.length - n)
    }

    override fun textAfter(n: Int): CharSequence {
        if (n <= 0) return ""
        val a = after
        if (a != null && (a.length >= n || afterComplete)) return if (a.length <= n) a else a.substring(0, n)
        reads++
        val want = maxOf(n, FETCH)
        val t = inner.textAfter(want).toString()
        after = t
        afterComplete = t.length < want
        return if (t.length <= n) t else t.substring(0, n)
    }

    override fun selectedText(): CharSequence? = inner.selectedText()

    /** [before] with its last [drop] characters replaced by [text]; null if not enough of it is known. */
    private fun replaceEnd(drop: Int, text: CharSequence): String? {
        val b = before ?: return null
        if (drop > b.length || (drop == b.length && !beforeComplete && drop > 0)) return null
        return trimmed(b.substring(0, b.length - drop) + text)
    }

    override fun commitText(text: CharSequence) {
        inner.commitText(text)
        if (composingElsewhere) {
            composingElsewhere = false
            invalidateCache()
            return
        }
        // replaces the composing text, else the selection (the text after the selection end stays after the caret)
        before = replaceEnd(composingLen, text)
        composingLen = 0
    }

    override fun deleteBefore(n: Int) {
        inner.deleteBefore(n)
        val b = before
        if (composingElsewhere || b == null) {
            invalidateCache()
            return
        }
        // like deleteSurroundingText: the composing text before the caret is skipped and stays
        val head = b.length - composingLen
        if (n > head || (n == head && !beforeComplete && n > 0)) {
            before = null
            return
        }
        before = b.substring(0, head - n) + b.substring(head)
    }

    override fun setComposingText(text: CharSequence) {
        inner.setComposingText(text)
        if (composingElsewhere) {
            composingElsewhere = false
            invalidateCache()
        } else {
            before = replaceEnd(composingLen, text)
        }
        composingLen = text.length
    }

    override fun finishComposing() {
        inner.finishComposing()
        // the text and the caret stay
        composingLen = 0
        composingElsewhere = false
    }

    override fun setComposingRegion(start: Int, end: Int) {
        inner.setComposingRegion(start, end)
        // the text stays; the region's place relative to the caret is not known here
        composingLen = 0
        composingElsewhere = true
    }

    override fun setSelection(start: Int, end: Int) {
        inner.setSelection(start, end)
        invalidateCache()
    }

    override fun sendKey(keyCode: Int, metaState: Int) {
        inner.sendKey(keyCode, metaState)
        invalidateCache()
    }

    override fun performEditorAction(action: Int) {
        inner.performEditorAction(action)
        invalidateCache()
    }

    override fun performPaste(): Boolean {
        val r = inner.performPaste()
        invalidateCache()
        return r
    }

    override fun beginBatch() = inner.beginBatch()

    override fun endBatch() = inner.endBatch()

    override fun capsMode(reqModes: Int): Int = inner.capsMode(reqModes)

    override fun supportsStyles(): Boolean = inner.supportsStyles()

    override fun restyleWord(start: Int, end: Int, word: String, suggestions: List<String>?, caret: Int) {
        inner.restyleWord(start, end, word, suggestions, caret)
        // the same word again with or without a mark: text and caret stay; the composing region is gone
        composingLen = 0
        composingElsewhere = false
    }

    /** Keeps the cached text before the caret bounded; a longer read goes to the editor again. */
    private fun trimmed(s: String): String {
        if (s.length <= KEEP) return s
        beforeComplete = false
        return s.substring(s.length - FETCH)
    }

    companion object {
        /** Characters read at once (more than any typing-path read, [InputLogic.BEFORE] = 100). */
        const val FETCH = 256
        private const val KEEP = 2 * FETCH
    }
}
