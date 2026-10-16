package app.metrokeys.engine.touch

import app.metrokeys.engine.predict.LanguageParams
import app.metrokeys.engine.predict.Params
import kotlin.math.floor

/** One tap of the current word: the character it produced and its position in keyboard pixels (NaN = unknown). */
public data class Tap(val char: Char, val x: Float, val y: Float) {
    val known: Boolean get() = !x.isNaN() && !y.isNaN()

    public companion object {
        @JvmStatic
        public fun unknown(char: Char): Tap = Tap(char, Float.NaN, Float.NaN)
    }
}

/** Tap positions aligned to the characters of a typed word (NaN = unknown). */
public class TapSequence internal constructor(internal val xs: FloatArray, internal val ys: FloatArray) {
    public val size: Int get() = xs.size

    public fun x(i: Int): Float = if (i in xs.indices) xs[i] else Float.NaN

    public fun y(i: Int): Float = if (i in ys.indices) ys[i] else Float.NaN

    /** The taps without the first [n] characters (an elided prefix, 05 §2.2). */
    internal fun drop(n: Int): TapSequence = TapSequence(xs.copyOfRange(minOf(n, xs.size), xs.size), ys.copyOfRange(minOf(n, ys.size), ys.size))

    public companion object {
        /**
         * Aligns [taps] to [word] from the end (05 §2.1): the last tap belongs to the last character; a tap whose
         * character differs case-insensitively from the aligned character is unknown.
         */
        @JvmStatic
        public fun align(word: String, taps: List<Tap>?): TapSequence {
            val xs = FloatArray(word.length) { Float.NaN }
            val ys = FloatArray(word.length) { Float.NaN }
            if (taps != null) {
                var t = taps.size - 1
                var i = word.length - 1
                while (i >= 0 && t >= 0) {
                    val tap = taps[t]
                    if (tap.known && Character.toLowerCase(tap.char) == Character.toLowerCase(word[i])) {
                        xs[i] = tap.x
                        ys[i] = tap.y
                    }
                    i--
                    t--
                }
            }
            return TapSequence(xs, ys)
        }

        @JvmStatic
        public fun none(length: Int): TapSequence =
            TapSequence(FloatArray(length) { Float.NaN }, FloatArray(length) { Float.NaN })
    }
}

/** Touch model of spec 05 §4. */
public class TouchModel(public val geometry: KeyboardGeometry, private val params: LanguageParams) {
    /** g(x, y, K) in key-normalised units. */
    public fun touchCost(x: Float, y: Float, key: LetterKey): Double {
        val dx = (x - key.centerX) / geometry.keyWidth.toDouble()
        val dy = (y - key.centerY) / geometry.keyHeight.toDouble()
        return dx * dx * Params.TOUCH_KX + dy * dy * Params.TOUCH_KY
    }

    /**
     * Proximity "letter [typed] was typed (with the tap at [x], [y], NaN = unknown) but [intended] was meant";
     * −1 if the keys are not neighbours.
     */
    public fun proximity(typed: Char, x: Float, y: Float, intended: Char): Int {
        val ki = geometry.touchKey(intended) ?: return -1
        val px: Float
        val py: Float
        if (!x.isNaN() && !y.isNaN()) {
            px = x
            py = y
        } else {
            val kt = geometry.touchKey(typed) ?: return -1
            if (kt === ki) return -1
            px = kt.centerX
            py = kt.centerY
        }
        val v = floor(touchCost(px, py, ki) / Params.TOUCH_UNIT + 0.5).toInt()
        return if (v > Params.NEAR_MAX) -1 else v
    }

    /** Substitution cost for typed [typed] (tap [x], [y]) when [intended] was meant (05 §4). */
    public fun substitutionCost(typed: Char, x: Float, y: Float, intended: Char): Int {
        val v = proximity(typed, x, y, intended)
        if (v < 0) return params.edSubstitute
        return minOf(params.edSubstitute, v / params.edNearDiv + params.edNearBase)
    }
}
