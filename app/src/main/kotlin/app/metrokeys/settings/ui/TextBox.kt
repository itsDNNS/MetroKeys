package app.metrokeys.settings.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The try-it text box (07 §6.11): a real platform [EditText] so the keyboard's spelling marks and the platform
 * suggestion popup look as in other apps. Idle: light grey (dark theme) or white with grey outline (light theme);
 * focused: white with accent outline; black text, 16.9 sp, at least 2 lines; cursor, handles and selection in the
 * accent. [typeface] is the regular face of the app.
 */
@Composable
fun TextBox(text: String, onText: (String) -> Unit, hint: String, typeface: Typeface, top: Dp = 4.dp) {
    val c = MetroTheme.colors
    val m = LocalPageMargins.current
    var focused by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(onText)
    val fill = when {
        focused -> Color.White
        c.dark -> Color(0xFFBFBFBF)
        else -> Color.White
    }
    val stroke = when {
        focused -> c.accent
        c.dark -> Color(0xFFBFBFBF)
        else -> Color(0xFF999999)
    }
    AndroidView(
        modifier = Modifier.fillMaxWidth().padding(top = top).margins(m),
        factory = { ctx ->
            EditText(ctx).apply {
                val d = ctx.resources.displayMetrics.density
                setText(text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.9f)
                setTextColor(android.graphics.Color.BLACK)
                setHintTextColor(0xFF666666.toInt())
                this.hint = hint
                minLines = 2
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setPadding((7.2f * d).toInt(), (6 * d).toInt(), (7.2f * d).toInt(), (6 * d).toInt())
                includeFontPadding = false
                setOnFocusChangeListener { _, has -> focused = has }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                    override fun afterTextChanged(s: Editable?) = latest(s?.toString().orEmpty())
                })
            }
        },
        update = { e ->
            val d = e.resources.displayMetrics.density
            e.typeface = typeface
            e.hint = hint
            e.background = GradientDrawable().apply {
                setColor(fill.toArgb())
                setStroke((1.9f * d).toInt().coerceAtLeast(1), stroke.toArgb())
            }
            val accent = c.accent.toArgb()
            e.highlightColor = c.accent.copy(alpha = 0.4f).toArgb()
            if (Build.VERSION.SDK_INT >= 29) {
                e.textCursorDrawable = GradientDrawable().apply {
                    setColor(accent)
                    setSize((2 * d).toInt(), (20 * d).toInt())
                }
                e.textSelectHandle?.mutate()?.let { it.setTint(accent); e.setTextSelectHandle(it) }
                e.textSelectHandleLeft?.mutate()?.let { it.setTint(accent); e.setTextSelectHandleLeft(it) }
                e.textSelectHandleRight?.mutate()?.let { it.setTint(accent); e.setTextSelectHandleRight(it) }
            }
        },
    )
}
