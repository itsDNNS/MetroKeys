package app.metrokeys.engine.predict

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Language-model cost scale of spec 05 §3. */
public object LmCost {
    /** round(): nearest integer, halves away from zero. */
    @JvmStatic
    public fun roundHalfAway(x: Double): Int = (if (x < 0) -floor(abs(x) + 0.5) else floor(x + 0.5)).toInt()

    /** cost(p) = clamp(round(15 − 18 · log10 p), 0, 255); cost(0) = 255. */
    @JvmStatic
    public fun cost(p: Double): Int {
        if (p <= 0.0 || p.isNaN()) return 255
        return roundHalfAway(Params.LM_COST_AT_P1 - Params.LM_COST_PER_DECADE * log10(p)).coerceIn(0, 255)
    }

    /** log10 P(w | prev) of a pair class: (b − 255) · 5 / 255. */
    @JvmStatic
    public fun pairLog10(pairClass: Int): Double = (pairClass - 255) * 5.0 / 255.0

    @JvmStatic
    public fun pairProbability(pairClass: Int): Double = 10.0.pow(pairLog10(pairClass))

    /** A = round(−log10(corpusTokens), 2 decimals). */
    @JvmStatic
    public fun constantA(corpusTokens: Long): Double = roundDecimals(-log10(corpusTokens.toDouble()), 2)

    /** Bc = round(1 / (S · ln 10), 5 decimals). */
    @JvmStatic
    public fun constantBc(scale: Float): Double = roundDecimals(1.0 / (scale.toDouble() * kotlin.math.ln(10.0)), 5)

    private fun roundDecimals(x: Double, decimals: Int): Double {
        val f = 10.0.pow(decimals)
        return roundHalfAway(x * f) / f
    }
}
