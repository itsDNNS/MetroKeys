package app.metrokeys.engine.predict

import app.metrokeys.engine.touch.TapSequence
import app.metrokeys.engine.touch.TouchModel
import java.text.Normalizer

/** Weighted Damerau (optimal string alignment) distance and unit distance of spec 05 §5. */
public class EditDistance(private val params: LanguageParams, private val touch: TouchModel) {

    /**
     * ED(typed, cand, taps); [taps] are aligned to [typed]. With [ac] the autocorrection edit weights of 05 §10.1
     * (`AC_INSERT`, `AC_DELETE`, `AC_SPACE`, `AC_SWAP`) replace the insert, delete, space and swap weights.
     */
    @JvmOverloads
    public fun weighted(typed: String, cand: String, taps: TapSequence?, ac: Boolean = false): Int {
        val n = typed.length
        val m = cand.length
        if (n > Params.ED_MAX_LEN || m > Params.ED_MAX_LEN) return Params.ED_OVERFLOW
        val p = params
        val wInsert = if (ac) p.acInsert else p.edInsert
        val wDelete = if (ac) p.acDelete else p.edDelete
        val wSpace = if (ac) p.acSpace else p.edSpace
        val wSwap = if (ac) p.acSwap else p.edSwap
        val t = CharArray(n) { Character.toLowerCase(typed[it]) }
        val c = CharArray(m) { Character.toLowerCase(cand[it]) }
        val d = Array(n + 1) { IntArray(m + 1) }
        for (j in 0..m) d[0][j] = j * wInsert
        for (i in 0..n) d[i][0] = i * wDelete
        for (i in 1..n) {
            val ti = t[i - 1]
            val tu = Character.isUpperCase(typed[i - 1])
            val tx = taps?.x(i - 1) ?: Float.NaN
            val ty = taps?.y(i - 1) ?: Float.NaN
            for (j in 1..m) {
                val cj = c[j - 1]
                val ins = d[i][j - 1] + when {
                    isFiller(cj) -> p.edFiller
                    cj == ' ' -> wSpace
                    else -> wInsert
                }
                val del = d[i - 1][j] + wDelete
                val cu = Character.isUpperCase(cand[j - 1])
                val s = if (ti == cj) {
                    if (typed[i - 1] != cand[j - 1]) p.edCase else 0
                } else if (p.capsUnaccented && tu && cu && isUnaccentedCapital(ti, cj)) {
                    // CAPS_UNACCENTED (fr): "E" typed for "É" costs nothing
                    0
                } else {
                    val base = when {
                        isDiacriticOf(ti, cj) -> p.edDiacritic
                        p.accentSwap && isAccentSwap(ti, cj) -> p.edDiacritic
                        cj == ' ' -> wSpace
                        else -> touch.substitutionCost(ti, tx, ty, cj)
                    }
                    if (tu != cu) base + p.edCase else base
                }
                var v = minOf(ins, del, d[i - 1][j - 1] + s)
                if (i > 1 && j > 1 && ti == c[j - 2] && t[i - 2] == cj && ti != cj) {
                    v = minOf(v, d[i - 2][j - 2] + wSwap)
                }
                if (i > 1 && cj == 'ß' && ti == 's' && t[i - 2] == 's') {
                    v = minOf(v, d[i - 2][j - 1] + p.edDiacritic)
                }
                // ligature rule: typed "oe"/"ae" for "œ"/"æ"
                if (i > 1 && ti == 'e' && ((cj == 'œ' && t[i - 2] == 'o') || (cj == 'æ' && t[i - 2] == 'a'))) {
                    val caseCost = if (Character.isUpperCase(typed[i - 2]) != cu) p.edCase else 0
                    v = minOf(v, d[i - 2][j - 1] + p.edDiacritic + caseCost)
                }
                // umlaut rule (UMLAUT_TRANSLIT, de): typed "ae"/"oe"/"ue" for "ä"/"ö"/"ü"
                if (p.umlautTranslit && i > 1 && ti == 'e' && isUmlautOf(t[i - 2], cj)) {
                    val caseCost = if (Character.isUpperCase(typed[i - 2]) != cu) p.edCase else 0
                    v = minOf(v, d[i - 2][j - 1] + p.edDiacritic + caseCost)
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

        /** The letter with its diacritic removed, a ligature as its first letter (œ → o, æ → a): the fuzzy search's fold. */
        @JvmStatic
        public fun fold(c: Char): Char = when (c) {
            'œ' -> 'o'
            'æ' -> 'a'
            else -> stripDiacritic(c)
        }

        /** c with its diacritic removed (U+00C0–U+024F, canonical decomposition, first character); ß unchanged. */
        @JvmStatic
        public fun stripDiacritic(c: Char): Char = if (c.code in 0xC0 until 0x250) STRIPPED[c.code - 0xC0] else c

        /**
         * True if [cand] is [typed] with a diacritic (05 §5); a ligature counts as its first letter with a diacritic
         * (œ for o, æ for a).
         */
        internal fun isDiacriticOf(typed: Char, cand: Char): Boolean {
            if (cand == 'œ') return typed == 'o'
            if (cand == 'æ') return typed == 'a'
            val s = stripDiacritic(cand)
            return s != cand && s == typed
        }

        /** True if [umlaut] (lower case) is ä, ö or ü and [vowel] (lower case) its base letter a, o or u. */
        internal fun isUmlautOf(vowel: Char, umlaut: Char): Boolean =
            (umlaut == 'ä' && vowel == 'a') || (umlaut == 'ö' && vowel == 'o') || (umlaut == 'ü' && vowel == 'u')

        /** `CAPS_UNACCENTED` pair (lower-cased letters): [typed] has no diacritic and is [cand] without its diacritic. */
        internal fun isUnaccentedCapital(typed: Char, cand: Char): Boolean {
            if (stripDiacritic(typed) != typed) return false
            val s = stripDiacritic(cand)
            return s != cand && s == typed
        }

        /** `ACCENT_SWAP` pair (05 §5): both letters carry a diacritic and have the same base letter ("è" for "é"). */
        internal fun isAccentSwap(typed: Char, cand: Char): Boolean {
            if (typed == cand) return false
            val a = stripDiacritic(typed)
            val b = stripDiacritic(cand)
            return a != typed && b != cand && a == b
        }

        /**
         * UNITS(typed, cand): the same recursion with unit costs, case 0 and filler 0 (05 §5); the ß rule and the ligature
         * rule cost 1, an unaccented capital ([capsUnaccented], fr) 0. A diacritic difference (typed base letter for an
         * accented letter of the candidate, or an `ACCENT_SWAP` pair when [accentSwap]) counts 1 unit with
         * [diacriticUnits] (languages whose `ED_DIACRITIC` is not 0, 0.7.1, 1:1), else 0. With [umlautTranslit] (de,
         * 0.7.0) the umlaut rule (typed "ae"/"oe"/"ue" for "ä"/"ö"/"ü") costs 1 as well.
         */
        @JvmStatic
        @JvmOverloads
        public fun units(
            typed: String,
            cand: String,
            capsUnaccented: Boolean = false,
            accentSwap: Boolean = false,
            umlautTranslit: Boolean = false,
            diacriticUnits: Boolean = false,
        ): Int {
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
                    val same = ti == cj || (!diacriticUnits && (isDiacriticOf(ti, cj) || (accentSwap && isAccentSwap(ti, cj)))) ||
                        (capsUnaccented && Character.isUpperCase(typed[i - 1]) && Character.isUpperCase(cand[j - 1]) &&
                            isUnaccentedCapital(ti, cj))
                    val sub = d[i - 1][j - 1] + if (same) 0 else 1
                    var v = minOf(ins, del, sub)
                    if (i > 1 && j > 1 && ti == c[j - 2] && t[i - 2] == cj && ti != cj) v = minOf(v, d[i - 2][j - 2] + 1)
                    if (i > 1 && cj == 'ß' && ti == 's' && t[i - 2] == 's') v = minOf(v, d[i - 2][j - 1] + 1)
                    if (i > 1 && ti == 'e' && ((cj == 'œ' && t[i - 2] == 'o') || (cj == 'æ' && t[i - 2] == 'a'))) {
                        v = minOf(v, d[i - 2][j - 1] + 1)
                    }
                    if (umlautTranslit && i > 1 && ti == 'e' && isUmlautOf(t[i - 2], cj)) v = minOf(v, d[i - 2][j - 1] + 1)
                    d[i][j] = v
                }
            }
            return d[n][m]
        }
    }
}
