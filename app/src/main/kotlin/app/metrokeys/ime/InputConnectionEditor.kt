package app.metrokeys.ime

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.SuggestionSpan
import android.view.KeyEvent
import android.view.inputmethod.InputConnection

/** [Editor] over the service's current InputConnection. */
class InputConnectionEditor(private val service: InputMethodService, private val context: Context) : Editor {
    private val ic: InputConnection? get() = service.currentInputConnection

    override fun textBefore(n: Int): CharSequence = ic?.getTextBeforeCursor(n, 0) ?: ""

    override fun textAfter(n: Int): CharSequence = ic?.getTextAfterCursor(n, 0) ?: ""

    override fun selectedText(): CharSequence? = ic?.getSelectedText(0)

    override fun commitText(text: CharSequence) {
        ic?.commitText(text, 1)
    }

    override fun deleteBefore(n: Int) {
        ic?.deleteSurroundingText(n, 0)
    }

    override fun setComposingText(text: CharSequence) {
        ic?.setComposingText(text, 1)
    }

    override fun finishComposing() {
        ic?.finishComposingText()
    }

    override fun setComposingRegion(start: Int, end: Int) {
        ic?.setComposingRegion(start, end)
    }

    override fun setSelection(start: Int, end: Int) {
        ic?.setSelection(start, end)
    }

    override fun sendKey(keyCode: Int, metaState: Int) {
        val ic = ic ?: return
        val t = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
        ic.sendKeyEvent(KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0, metaState))
    }

    override fun performEditorAction(action: Int) {
        ic?.performEditorAction(action)
    }

    override fun performPaste(): Boolean = ic?.performContextMenuAction(android.R.id.paste) ?: false

    override fun beginBatch() {
        ic?.beginBatchEdit()
    }

    override fun endBatch() {
        ic?.endBatchEdit()
    }

    override fun capsMode(reqModes: Int): Int = ic?.getCursorCapsMode(reqModes) ?: 0

    override fun supportsStyles(): Boolean {
        val ic = ic ?: return false
        val t = ic.getTextBeforeCursor(64, InputConnection.GET_TEXT_WITH_STYLES)
            ?: ic.getTextAfterCursor(64, InputConnection.GET_TEXT_WITH_STYLES)
        return t is Spanned
    }

    override fun restyleWord(start: Int, end: Int, word: String, suggestions: List<String>?, caret: Int) {
        val ic = ic ?: return
        val text: CharSequence = if (suggestions == null) word else SpannableString(word).apply {
            val span = SuggestionSpan(context, suggestions.toTypedArray(), SuggestionSpan.FLAG_MISSPELLED or SuggestionSpan.FLAG_EASY_CORRECT)
            setSpan(span, 0, word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        // commitText's cursor position is relative to the inserted text: keep the caret where it was
        val pos = if (caret >= end) caret - end + 1 else caret - start
        ic.setComposingRegion(start, end)
        ic.commitText(text, pos)
    }
}
