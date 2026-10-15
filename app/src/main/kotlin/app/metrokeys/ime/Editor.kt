package app.metrokeys.ime

/**
 * The text field as seen by [InputLogic]: a thin layer over Android's InputConnection (see
 * [InputConnectionEditor]) so that the text rules can be unit tested with a fake editor.
 * Positions are absolute character offsets in the field.
 */
interface Editor {
    /** Up to [n] characters before the caret (selection start); empty when there is no connection. */
    fun textBefore(n: Int): CharSequence

    /** Up to [n] characters after the caret (selection end). */
    fun textAfter(n: Int): CharSequence

    fun selectedText(): CharSequence?

    /** Inserts [text] at the caret, replacing a selection or the composing text; the caret ends after it. */
    fun commitText(text: CharSequence)

    fun deleteBefore(n: Int)

    /** Replaces the composing text (or inserts at the caret) with [text]; the caret ends after it. */
    fun setComposingText(text: CharSequence)

    fun finishComposing()

    /** Marks [start, end) as composing text without changing it. */
    fun setComposingRegion(start: Int, end: Int)

    fun setSelection(start: Int, end: Int)

    fun sendKey(keyCode: Int, metaState: Int = 0)

    fun performEditorAction(action: Int)

    fun performPaste(): Boolean

    fun beginBatch()

    fun endBatch()

    /** The editor's caps mode at the caret for the given request flags (TextUtils.getCapsMode semantics). */
    fun capsMode(reqModes: Int): Int

    /** True if the editor keeps styled text (spans survive), needed for spelling marks (04 §7.5). */
    fun supportsStyles(): Boolean

    /**
     * Replaces [start, end) (whose current text is [word]) with [word] carrying a misspelled-suggestion span with
     * [suggestions], or plain [word] when [suggestions] is null, keeping the caret at [caret].
     */
    fun restyleWord(start: Int, end: Int, word: String, suggestions: List<String>?, caret: Int)
}
