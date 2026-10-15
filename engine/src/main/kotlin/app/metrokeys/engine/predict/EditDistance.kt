package app.metrokeys.engine.predict

import app.metrokeys.engine.touch.TapSequence
import app.metrokeys.engine.touch.TouchModel
import java.text.Normalizer

/** Weighted Damerau (optimal string alignment) distance and unit distance of spec 05 §5. */
public class EditDistance(private val params: LanguageParams, private val touch: TouchModel) {

    /** ED(typed, cand, taps); [taps] are aligned to [typed]. */
    public fun weighted(typed: String, cand: String, taps: TapSequence?): Int {
        val n = typed.length
        val m = cand.length
        if (n > Params.ED_MAX_LEN || m > Params.ED_MAX_LEN) return Params.ED_OVERFLOW
        val p = params
        val t = CharArray(n) { Character.toLowerCase(typed[it]) }
        val c = CharArray(m) { Character.toLowerCase(cand[it]) }
        val d = Array(n + 1) { IntArray(m + 1) }
        for (j in 0..m) d[0][j] = j * p.edInsert
        for (i in 0..n) d[i][0] = i * p.edDelete
        for (i in 1..n) {
            val ti = t[i - 1]
            val tu = Character.isUpperCase(typed[i - 1])
            val tx = taps?.x(i - 1) ?: Float.NaN
            val ty = taps?.y(i - 1) ?: Float.NaN
            for (j in 1..m) {
                val cj = c[j - 1]
                val ins = d[i][j - 1] + when {
                    isFiller(cj) -> p.edFiller
                    cj == ' ' -> p.edSpace
                    else -> p.edInsert
                }
                val del = d[i - 1][j] + p.edDelete
                val s = if (ti == cj) {
                    if (typed[i - 1] != cand[j - 1]) p.edCase else 0
                } else {
                    val base = when {
                        isDiacriticOf(ti, cj) -> p.edDiacritic
                        cj == ' ' -> p.edSpace
                        else -> touch.substitutionCost(ti, tx, ty, cj)
                    }
                    if (tu != Character.isUpperCase(cand[j - 1])) base + p.edCase else base
                }
                var v = minOf(ins, del, d[i - 1][j - 1] + s)
                if (i > 1 && j > 1 && ti == c[j - 2] && t[i - 2] == cj && ti != cj) {
                    v = minOf(v, d[i - 2][j - 2] + p.edSwap)
                }
                if (i > 1 && cj == 'ß' && ti == 's' && t[i - 2] == 's') {
                    v = minOf(v, d[i - 2][j - 1] + p.edDiacritic)
                }
                d[i][j] = v
            }
        }
        return d[n][m]
    }

    public companion object {
        private val STRIPPED = CharArray(0x250 - 0xC0).also { arr ->
            for (cp in 0xC0 until 0x250) {
                val ch = cp.toChar()
                val dec = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
                arr[cp - 0xC0] = if (ch != 'ß' && dec.isNotEmpty() && dec[0] != ch) dec[0] else ch
            }
        }

        private fun isFiller(c: Char): Boolean = c == '\'' || c == '.' || c == '-'

        /** c with its diacritic removed (U+00C0–U+024F, canonical decomposition, first character); ß unchanged. */
        @JvmStatic
        public fun stripDiacritic(c: Char): Char = if (c.code in 0xC0 until 0x250) STRIPPED[c.code - 0xC0] else c

        internal fun isDiacriticOf(typed: Char, cand: Char): Boolean {
            val s = stripDiacritic(cand)
            return s != cand && s == typed
        }

        /** UNITS(typed, cand): the same recursion with unit costs, case 0 and filler 0 (05 §5). */
        @JvmStatic
        public fun units(typed: String, cand: String): Int {
            val n = typed.length
            val m = cand.length
            if (n > Params.ED_MAX_LEN || m > Params.ED_MAX_LEN) return Params.ED_OVERFLOW
            val t = CharArray(n) { Character.toLowerCase(typed[it]) }
            val c = CharArray(m) { Character.toLowerCase(cand[it]) }
            val d = Array(n + 1) { IntArray(m + 1) }
            for (j in 0..m) d[0][j] = j
            for (i in 0..n) d[i][0] = i
            for (i in 1..n) {
                val ti = t[i - 1]
                for (j in 1..m) {
                    val cj = c[j - 1]
                    val ins = d[i][j - 1] + if (isFiller(cj)) 0 else 1
                    val del = d[i - 1][j] + 1
                    val sub = d[i - 1][j - 1] + if (ti == cj) 0 else 1
                    var v = minOf(ins, del, sub)
                    if (i > 1 && j > 1 && ti == c[j - 2] && t[i - 2] == cj && ti != cj) v = minOf(v, d[i - 2][j - 2] + 1)
                    if (i > 1 && cj == 'ß' && ti == 's' && t[i - 2] == 's') v = minOf(v, d[i - 2][j - 1] + 1)
                    d[i][j] = v
                }
            }
            return d[n][m]
        }
    }
}
