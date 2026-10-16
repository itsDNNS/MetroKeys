package app.metrokeys.ime

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.util.Size
import android.view.ViewGroup
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import app.metrokeys.keyboard.KeyboardView
import app.metrokeys.keyboard.Metrics
import app.metrokeys.keyboard.Theme
import app.metrokeys.settings.KeyboardSettings

/** Inline autofill suggestions (03 §16): the request and the chips' style and size. */
object InlineAutofill {
    /**
     * Whether to ask for inline suggestions (03 §16.1, §16.5). The platform renders the chips for the input token of
     * the keyboard's window; before that window was shown for the first time (the first input after the keyboard
     * process started) it has none, the chips cannot be rendered and the strip would stay empty. Without a request
     * the autofill service shows the system's drop-down for that field instead.
     */
    fun shouldRequest(enabled: Boolean, windowAttached: Boolean): Boolean = enabled && windowAttached

    /**
     * The chips shown in the row (03 §16.2, §16.5). Every non-empty response gets a number and its own slots; each
     * chip is shown as soon as it is inflated. The platform may send the same suggestions twice in a row (e.g. after a
     * rotation) and then call back only once per chip, for either response, so a chip of the previous response fills
     * a slot of a newer one of the same size until the newer chip arrives. A clear (keyboard hidden, input view
     * started, empty response) drops every response so far, also chips whose inflation completes later.
     */
    class ChipSlots<T : Any> {
        private var last = 0
        private var validFrom = 1
        private var slots: MutableList<T?> = mutableListOf()
        private var owners = IntArray(0)

        /** A response with [count] chips arrived; returns its number. */
        fun response(count: Int): Int {
            last++
            if (count != slots.size) {
                slots = MutableList(count) { null }
                owners = IntArray(count)
            }
            return last
        }

        /** The chips were cleared. */
        fun clear() {
            validFrom = last + 1
            slots = mutableListOf()
            owners = IntArray(0)
        }

        /** Chip [index] of response [number] is inflated; returns the chips to show now, or null if nothing changes. */
        fun inflated(number: Int, index: Int, chip: T?): List<T>? {
            if (number < validFrom || chip == null || index !in slots.indices || number < owners[index]) return null
            slots[index] = chip
            owners[index] = number
            return slots.filterNotNull()
        }

        val shown: List<T> get() = slots.filterNotNull()
    }

    /** Size limits of the chips requested from the autofill service (03 §16.1), in px. */
    data class ChipSpec(val minWidth: Int, val maxWidth: Int, val height: Int)

    /** Chips 84 % of the strip height, at most the keyboard's width (03 §16.1). */
    fun chipSpec(m: Metrics): ChipSpec = ChipSpec(1, m.width, chipHeight(m))

    fun chipHeight(m: Metrics): Int = (m.stripHeight * 0.84f).toInt()

    /**
     * The metrics the request is built with: the laid-out view's, else the ones of the settings and the display
     * (the platform asks at input start, which on the first show after the process start or after the view was
     * recreated comes before the view is created or laid out).
     */
    fun requestMetrics(laidOut: Metrics?, fromSettings: () -> Metrics): Metrics = laidOut ?: fromSettings()

    /**
     * Layout size of an inflated chip in the chip row: the size the platform gave the chip view (the rendered chip's
     * measured size; 03 open point "accept the platform size"), else wrap content × [height]. The chip view does not
     * measure its rendered content itself, so replacing the platform's width by wrap content makes it 0 px wide.
     */
    fun chipLayoutSize(platformWidth: Int?, platformHeight: Int?, height: Int): Pair<Int, Int> {
        val w = platformWidth?.takeIf { it > 0 } ?: ViewGroup.LayoutParams.WRAP_CONTENT
        val h = platformHeight?.takeIf { it > 0 } ?: height
        return w to h
    }

    /** The request for the autofill service; needs no laid-out [view]. */
    @RequiresApi(30)
    fun request(context: Context, settings: KeyboardSettings, view: KeyboardView?): InlineSuggestionsRequest {
        val m = requestMetrics(view?.takeIf { it.isLaidOut && it.width > 0 }?.metrics) { KeyboardView.metricsFor(context, settings) }
        val theme = view?.theme ?: KeyboardView.themeFor(context, settings)
        val c = chipSpec(m)
        val spec = InlinePresentationSpec.Builder(Size(c.minWidth, c.height), Size(c.maxWidth, c.height))
            .setStyle(style(context, theme, m.stripHeight)).build()
        return InlineSuggestionsRequest.Builder(listOf(spec)).setMaxSuggestionCount(InlineSuggestionsRequest.SUGGESTION_COUNT_UNLIMITED).build()
    }

    /** Style of the chips (03 §16.4): flat rectangles in key colour, text ≈ 29 % of the strip height. */
    // The builder methods are public API of ViewStyle.Builder, declared in a library-internal base class, which
    // lint reports as a restricted call.
    @SuppressLint("RestrictedApi")
    @RequiresApi(30)
    private fun style(context: Context, t: Theme, stripH: Float): Bundle {
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
