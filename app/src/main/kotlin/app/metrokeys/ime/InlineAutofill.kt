package app.metrokeys.ime

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import app.metrokeys.keyboard.KeyboardView

/** Style of the inline autofill chips (03 §16.4): flat rectangles in key colour, text ≈ 29 % of the strip height. */
object InlineAutofill {
    // The builder methods are public API of ViewStyle.Builder, declared in a library-internal base class, which
    // lint reports as a restricted call.
    @SuppressLint("RestrictedApi")
    @RequiresApi(30)
    fun style(context: Context, @Suppress("UNUSED_PARAMETER") controller: KeyboardController, v: KeyboardView): Bundle {
        val t = v.theme
        val stripH = v.metrics.stripHeight
        val density = context.resources.displayMetrics.density
        val fontScale = context.resources.configuration.fontScale
        val textSp = stripH * 0.29f / (density * fontScale)
        val pad = (stripH / 4).toInt()
        val style = InlineSuggestionUi.newStyleBuilder()
            .setChipStyle(ViewStyle.Builder().setBackgroundColor(t.key).setPadding(pad, 0, pad, 0).build())
            .setTitleStyle(TextViewStyle.Builder().setTextColor(t.keyLabel).setTextSize(textSp).build())
            .setSubtitleStyle(TextViewStyle.Builder().setTextColor(t.keyHint).setTextSize(textSp * 0.8f).build())
            .build()
        return UiVersions.newStylesBuilder().addStyle(style).build()
    }
}
