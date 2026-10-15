package app.metrokeys.ime

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.annotation.RequiresApi
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import app.metrokeys.keyboard.KeyboardView
import app.metrokeys.settings.KeyboardSettings
import app.metrokeys.settings.Prefs

/**
 * The MetroKeys input method service: Android lifecycle, the editor connection, window insets and touchable region
 * (03 §18), hardware keys (03 §13) and inline autofill suggestions (03 §16). The keyboard behaviour lives in
 * [KeyboardController], [InputLogic] and [KeyboardView].
 */
class MetroKeysService : InputMethodService(), SharedPreferences.OnSharedPreferenceChangeListener {
    lateinit var controller: KeyboardController
        private set
    private var root: FrameLayout? = null
    private var keyboardView: KeyboardView? = null
    private var chipScroll: HorizontalScrollView? = null
    private var chipRow: LinearLayout? = null

    override fun onCreate() {
        super.onCreate()
        controller = KeyboardController(this)
        controller.create()
        Prefs.of(this).registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        Prefs.of(this).unregisterOnSharedPreferenceChangeListener(this)
        controller.destroy()
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        if (key == Prefs.ACTIVE_LANGUAGE || key == Prefs.EMOJI_HISTORY) return
        controller.applySettings(KeyboardSettings.read(Prefs.of(this)))
    }

    // ================================================================ view

    @SuppressLint("InflateParams")
    override fun onCreateInputView(): View {
        val v = KeyboardView(this, controller)
        controller.view = v
        keyboardView = v
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(chips, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        chipRow = chips
        chipScroll = scroll
        val frame = object : FrameLayout(this) {
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                super.onLayout(changed, l, t, r, b)
                // the chip row lies exactly over the strip (03 §16.2)
                val kv = keyboardView ?: return
                if (!kv.isLaidOut) return
                val top = kv.top + kv.stripTop.toInt()
                val h = kv.metrics.stripHeight.toInt()
                scroll.measure(MeasureSpec.makeMeasureSpec(r - l, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
                scroll.layout(0, top, r - l, top + h)
            }
        }
        frame.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        frame.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        ViewCompat.setOnApplyWindowInsetsListener(frame) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.navInset = nav.bottom
            val tappable = insets.getInsets(WindowInsetsCompat.Type.tappableElement())
            v.gestureNavigation = nav.bottom > 0 && tappable.bottom == 0
            insets
        }
        root = frame
        return frame
    }

    override fun onWindowShown() {
        super.onWindowShown()
        window?.window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            @Suppress("DEPRECATION")
            w.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
        }
        root?.let { ViewCompat.requestApplyInsets(it) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateHardwareMode()
        keyboardView?.onConfigurationChanged()
    }

    // ================================================================ input lifecycle

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        controller.startInput(attribute, restarting)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        updateHardwareMode()
        controller.onShow()
        clearChips()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        controller.onHide()
        clearChips()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        controller.logic.finishInput()
        super.onFinishInput()
    }

    override fun onWindowHidden() {
        controller.onHide()
        super.onWindowHidden()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        controller.onSelectionUpdate(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
    }

    /** The view is always "shown"; with a hardware keyboard only the strip is drawn (03 §13.1). */
    override fun onEvaluateInputViewShown(): Boolean {
        updateHardwareMode(super.onEvaluateInputViewShown())
        return true
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    /** Strip only when a hardware keyboard is attached and the system would hide the on-screen keyboard. */
    private fun updateHardwareMode(systemShows: Boolean = super.onEvaluateInputViewShown()) {
        val c = resources.configuration
        val hard = c.keyboard != Configuration.KEYBOARD_NOKEYS && c.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
        controller.setHardwareMode(hard && !systemShows)
    }

    fun showInputMethodPicker() {
        getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    // ================================================================ insets (03 §18)

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val v = keyboardView ?: return
        val r = root ?: return
        if (!v.isLaidOut) return
        val top = v.top + v.visibleTop
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        if (v.picker != null) {
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
        } else {
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
            outInsets.touchableRegion.set(0, top, r.width, r.height)
        }
    }

    /** The picker needs the overflow strip to be touchable (03 §3.5). */
    fun onPickerChanged() {
        root?.requestLayout()
        window?.window?.decorView?.requestLayout()
    }

    // ================================================================ hardware keys (03 §13)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isInputViewShown || !controller.hardwareMode) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_F5 -> {
                controller.toggleMiniKeyboard(emoji = false)
                return true
            }
            KeyEvent.KEYCODE_PICTSYMBOLS -> {
                controller.toggleMiniKeyboard(emoji = true)
                return true
            }
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> if (controller.miniKeyboardOpen) {
                controller.closeMiniKeyboard()
                return true
            }
        }
        val f = controller.field
        val meta = event.metaState
        val modifiers = meta and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK) != 0
        if (!f.hardwareThroughLogic || modifiers) {
            controller.logic.endSwipeMark()
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                controller.logic.backspace()
                return true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                controller.logic.enter()
                return true
            }
        }
        val uc = event.getUnicodeChar(meta)
        if (uc == 0 || uc and KeyCharacterMap.COMBINING_ACCENT != 0 || Character.isISOControl(uc)) {
            controller.logic.endSwipeMark()
            return super.onKeyDown(keyCode, event)
        }
        controller.logic.onText(String(Character.toChars(uc)))
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (controller.hardwareMode && (keyCode == KeyEvent.KEYCODE_F5 || keyCode == KeyEvent.KEYCODE_PICTSYMBOLS)) return true
        return super.onKeyUp(keyCode, event)
    }

    // ================================================================ inline autofill (03 §16)

    @RequiresApi(30)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        if (!controller.settings.autofillInline) return null
        val v = keyboardView ?: return null
        if (!v.isLaidOut) return null
        val stripH = v.metrics.stripHeight
        val h = (stripH * 0.84f).toInt()
        val style = InlineAutofill.style(this, controller, v)
        val spec = android.widget.inline.InlinePresentationSpec.Builder(Size(1, h), Size(v.width, h)).setStyle(style).build()
        return InlineSuggestionsRequest.Builder(listOf(spec)).setMaxSuggestionCount(InlineSuggestionsRequest.SUGGESTION_COUNT_UNLIMITED).build()
    }

    @RequiresApi(30)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        val row = chipRow ?: return false
        val scroll = chipScroll ?: return false
        val v = keyboardView ?: return false
        val list: List<InlineSuggestion> = response.inlineSuggestions.sortedBy { if (it.info.isPinned) 1 else 0 }
        clearChips()
        if (list.isEmpty()) return true
        val h = (v.metrics.stripHeight * 0.84f).toInt()
        val size = Size(ViewGroup.LayoutParams.WRAP_CONTENT, h)
        var remaining = list.size
        val views = arrayOfNulls<View>(list.size)
        for ((i, s) in list.withIndex()) {
            s.inflate(this, size, mainExecutor) { view ->
                views[i] = view
                if (--remaining == 0) {
                    row.removeAllViews()
                    for (cv in views) if (cv != null) row.addView(cv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, h).apply {
                        marginEnd = (v.metrics.s * 9).toInt()
                    })
                    if (row.childCount > 0) {
                        scroll.visibility = View.VISIBLE
                        controller.setAutofillActive(true)
                    }
                }
            }
        }
        return true
    }

    private fun clearChips() {
        chipRow?.removeAllViews()
        chipScroll?.visibility = View.GONE
        if (this::controller.isInitialized) controller.setAutofillActive(false)
    }

}
