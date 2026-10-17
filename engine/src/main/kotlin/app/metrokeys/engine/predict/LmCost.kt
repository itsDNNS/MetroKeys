package app.metrokeys.engine.predict

import app.metrokeys.engine.lexicon.Lexicon
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Shared helpers of the language-model cost of spec 05 §3. */
public object LmCost {
    /** round(): nearest integer, halves away from zero. */
    @JvmStatic
    public fun roundHalfAway(x: Double): Int = (if (x < 0) -floor(abs(x) + 0.5) else floor(x + 0.5)).toInt()

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

/**
 * The anchored cost scale of 05 §3.2 (0.7.1) of one word list: anchor points (x_i = log10 p_uni(k_i), c_i) from the
 * list's header (05b §1.1). Above the first anchor the cost falls by `LM_HEAD_SLOPE` per decade; between two anchors it
 * is linear in log10 p; below the last anchor it is the last anchor's cost. cost(0) = 255.
 */
public class LmScale(
    /** log10 p of the anchors, strictly decreasing. */
    private val x: DoubleArray,
    /** Costs of the anchors, increasing. */
    private val c: IntArray,
    /** Constants A and Bc of the list (05 §3.1), for the cost per class. */
    private val a: Double,
    private val bc: Double,
) {
    init {
        require(x.size == c.size && x.isNotEmpty())
    }

    /** cost(p) of 05 §3.2: clamp(round(map(log10 p)), 0, 255); 255 for p ≤ 0. */
    public fun cost(p: Double): Int {
        if (p <= 0.0 || p.isNaN()) return 255
        return costLog10(log10(p))
    }

    /** The cost at log10 p = [lx]. */
    public fun costLog10(lx: Double): Int = LmCost.roundHalfAway(raw(lx)).coerceIn(0, 255)

    /** The map before rounding. */
    internal fun raw(lx: Double): Double {
        if (lx >= x[0]) return c[0] - Params.LM_HEAD_SLOPE * (lx - x[0])
        for (i in 0 until x.size - 1) {
            if (lx >= x[i + 1]) return c[i] + (c[i + 1] - c[i]) * (x[i] - lx) / (x[i] - x[i + 1])
        }
        return c[c.size - 1].toDouble()
    }

    private val perClass = IntArray(256) { k -> costLog10(a + k * bc) }

    /** The static cost of frequency class [k] (05 §3.2: SHOULD be precomputed). */
    public fun classCost(k: Int): Int = perClass[k.coerceIn(0, 255)]

    /**
     * The inverse map (06 §4.1, 0.7.1): log10 p whose cost is [cost] — linear between anchors, the head slope above
     * the first anchor; null for a cost at or above the last anchor's cost.
     */
    public fun inverseLog10(cost: Double): Double? {
        if (cost >= c[c.size - 1]) return null
        if (cost <= c[0]) return x[0] - (cost - c[0]) / Params.LM_HEAD_SLOPE
        for (i in 0 until c.size - 1) {
            if (cost <= c[i + 1] && c[i + 1] > c[i]) return x[i] - (cost - c[i]) * (x[i] - x[i + 1]) / (c[i + 1] - c[i])
        }
        return null
    }

    public companion object {
        /** The scale of a word list from its header anchors (05b §1.1) and its constants A and Bc. */
        @JvmStatic
        public fun of(lexicon: Lexicon, a: Double, bc: Double): LmScale {
            val anchors = lexicon.costAnchors
            return LmScale(DoubleArray(anchors.size) { a + anchors[it].first * bc }, IntArray(anchors.size) { anchors[it].second }, a, bc)
        }
    }
}
