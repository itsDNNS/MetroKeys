package app.metrokeys.engine.eval

import app.metrokeys.engine.lexicon.Lexicon
import app.metrokeys.engine.predict.EditDistance
import app.metrokeys.engine.predict.LanguageParams
import app.metrokeys.engine.text.toKey
import app.metrokeys.engine.touch.KeyboardGeometry
import app.metrokeys.engine.touch.TouchModel
import java.text.Normalizer

/**
 * Dictionary lint of 05c §4 (data only, no engine): diacritic shadows (L1), typo shadows (L2), coverage (L3), rank
 * inversions (L4) and strong unattested keys (L5) of the evaluated word list against the trusted words.
 */
internal class Lint(
    private val tag: String,
    private val list: Lexicon,
    private val lex: EvalLexicon,
    private val provenance: Provenance?,
    geometry: KeyboardGeometry,
) {
    class Result(val code: String, val count: Int, val counts: Map<String, Int>, val shares: Map<String, Double>, val entries: List<String>)

    private val params = LanguageParams.forLanguage(tag)
    private val touch = TouchModel(geometry, params)
    private val letters = geometry.letters

    /** A form of the language (05b §12.1): from the provenance flags when present, else "not flagged unattested". */
    private fun isForm(key: String, k: Int): Boolean = provenance?.isForm(key) ?: !list.isUnattested(k)

    private fun bestClass(k: Int): Int = if (k < 0) -1 else (0 until list.formCountOf(k)).maxOf { list.formClass(k, it) }

    fun run(): List<Result> = listOf(l1(), l2(), l3(), l4(), l5())

    /** L1: trusted words of ranks 1…LINT_TOP with diacritics whose stripped spelling is a key but neither trusted nor a form. */
    fun l1(): Result {
        val out = ArrayList<String>()
        for (e in lex.trustedByRank) {
            if (e.rank > LINT_TOP) break
            val stripped = strip(e.key)
            if (stripped == e.key) continue
            val k = list.keyIndex(stripped)
            if (k < 0 || stripped in lex.trusted || isForm(stripped, k)) continue
            out.add("${e.rank} ${e.key} ← $stripped (class ${bestClass(k)}, ${flags(stripped)})")
        }
        return Result("L1", out.size, emptyMap(), emptyMap(), out)
    }

    /** L2: single E1–E4 variants of the trusted words of ranks 1…LINT_TOP_SHORT that are unattested keys. */
    fun l2(): Result {
        val out = ArrayList<String>()
        val byBand = sortedMapOf("L1" to 0, "L2" to 0, "L3" to 0)
        for (e in lex.trustedByRank) {
            if (e.rank > LINT_TOP_SHORT) break
            val hits = sortedSetOf<String>()
            for (v in variants(e.key)) {
                val k = list.keyIndex(v)
                if (k >= 0 && list.isUnattested(k)) hits.add(v)
            }
            if (hits.isEmpty()) continue
            val band = lengthBand(e.key)
            byBand[band] = byBand.getValue(band) + hits.size
            for (v in hits) out.add("${e.rank} ${e.key} → $v ($band, class ${bestClass(list.keyIndex(v))}, ${flags(v)})")
        }
        return Result("L2", out.size, byBand, emptyMap(), out)
    }

    /** L3: coverage of the trusted words of ranks 1…20 000 and 1…100 000. */
    fun l3(): Result {
        val missing = ArrayList<String>()
        var in20 = 0
        var n20 = 0
        var in100 = 0
        var n100 = 0
        for (e in lex.trustedByRank) {
            val present = list.keyIndex(e.key) >= 0
            if (e.rank <= 20000) {
                n20++
                if (present) in20++
            }
            if (e.rank <= 100000) {
                n100++
                if (present) in100++
            }
            if (!present && missing.size < 200) missing.add("${e.rank} ${e.forms.first()}")
        }
        return Result("L3", n100 - in100, mapOf("top20000" to n20, "top100000" to n100, "missing20000" to n20 - in20),
            mapOf("top20000" to pct(in20, n20), "top100000" to pct(in100, n100)), missing)
    }

    /** L4: pairs (u, t), u an unattested key or a name, t a trusted word of rank ≤ 20 000, unit distance 1, class(u) > class(t). */
    fun l4(): Result {
        data class Inv(val diff: Int, val text: String)
        val rows = ArrayList<Inv>()
        for (e in lex.trustedByRank) {
            if (e.rank > 20000) break
            val kt = list.keyIndex(e.key)
            if (kt < 0) continue
            val ct = bestClass(kt)
            val units = { cand: String -> EditDistance.units(e.key, cand, accentSwap = params.accentSwap) }
            for (m in list.fuzzy(e.key, 1, 400, units)) {
                if (m.distance != 1) continue
                val u = toKey(m.form.text)
                if (u == e.key) continue
                val ku = list.keyIndex(u)
                if (ku < 0 || !(list.isUnattested(ku) || u in lex.names)) continue
                val cu = bestClass(ku)
                if (cu > ct) rows.add(Inv(cu - ct, "${m.form.text} $cu > ${e.forms.first()} $ct (rank ${e.rank}, ${flags(u)})"))
            }
        }
        rows.sortWith(compareBy<Inv>({ -it.diff }, { it.text }))
        return Result("L4", rows.size, emptyMap(), emptyMap(), rows.map { "+${it.diff} ${it.text}" })
    }

    /** L5: unattested keys with class ≥ LINT_CLASS_MIN, with pS and pW. */
    fun l5(): Result {
        data class Row(val cls: Int, val key: String, val text: String)
        val rows = ArrayList<Row>()
        for (k in 0 until list.keyCount) {
            if (!list.isUnattested(k)) continue
            val c = bestClass(k)
            if (c < LINT_CLASS_MIN) continue
            val key = list.keyString(k)
            val p = provenance?.rows?.get(key)
            val ps = p?.let { fmt(it.subtitleCount.toDouble() / list.corpusTokens) } ?: "-"
            val pw = p?.let { fmt(it.pW) } ?: "-"
            rows.add(Row(c, key, "${list.formText(k, 0)} class $c pS $ps pW $pw"))
        }
        rows.sortWith(compareBy<Row>({ -it.cls }, { it.key }))
        return Result("L5", rows.size, emptyMap(), emptyMap(), rows.take(200).map { it.text })
    }

    private fun flags(key: String): String = provenance?.flags(key) ?: (if (list.isUnattestedWord(key)) "unattested" else "attested")

    /** The single E1, E2, E3, E4 variants of [key] (05c §3.5) on the language's letter page. */
    fun variants(key: String): Set<String> {
        val out = HashSet<String>()
        val n = key.length
        for (i in 0 until n) {
            val c = key[i]
            val nb = neighbours(c)
            for (x in nb) out.add(key.substring(0, i) + x + key.substring(i + 1))                       // E1
            if (n >= 4 && !(i > 0 && key[i - 1] == c)) out.add(key.removeRange(i, i + 1))              // E2
            if (n >= 4 && i > 0 && key[i - 1] == c) out.add(key.removeRange(i, i + 1))                 // E2a
            for (x in nb) {
                out.add(key.substring(0, i) + x + key.substring(i))                                    // E3
                out.add(key.substring(0, i + 1) + x + key.substring(i + 1))
            }
            if (i + 1 < n && key[i + 1] != c) out.add(key.substring(0, i) + key[i + 1] + c + key.substring(i + 2)) // E4
        }
        if (n < 3) return emptySet()
        out.remove(key)
        return out
    }

    private val neighbourCache = HashMap<Char, List<Char>>()

    /** Letters on other keys whose proximity at the key centres is ≤ NEAR_MAX (05c §3.4). */
    fun neighbours(c: Char): List<Char> = neighbourCache.getOrPut(c) {
        val own = touchLetter(c)
        letters.filter { it != own && touch.proximity(own, Float.NaN, Float.NaN, it) >= 0 }.sorted()
    }

    private fun touchLetter(c: Char): Char {
        if (c in letters) return c
        if (c == 'ß') return 's'
        if (c == 'œ') return 'o'
        if (c == 'æ') return 'a'
        return KeyboardGeometry.baseLetter(c) ?: c
    }

    companion object {
        const val LINT_TOP = 5000
        const val LINT_TOP_SHORT = 1000
        const val LINT_CLASS_MIN = 60

        /** 05c E5: diacritics removed (canonical decomposition, combining marks dropped), ß → ss, œ → oe, æ → ae. */
        fun strip(text: String): String {
            val sb = StringBuilder()
            for (ch in text) {
                when (ch) {
                    'ß' -> sb.append("ss")
                    'œ' -> sb.append("oe")
                    'Œ' -> sb.append("Oe")
                    'æ' -> sb.append("ae")
                    'Æ' -> sb.append("Ae")
                    else -> {
                        val d = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
                        for (x in d) if (Character.getType(x) != Character.NON_SPACING_MARK.toInt()) sb.append(x)
                    }
                }
            }
            return Normalizer.normalize(sb.toString(), Normalizer.Form.NFC)
        }

        fun lengthBand(key: String): String {
            val n = key.count { Character.isLetter(it) }
            return if (n <= 3) "L1" else if (n <= 6) "L2" else "L3"
        }

        fun pct(a: Int, b: Int): Double = if (b == 0) 100.0 else 100.0 * a / b

        fun fmt(x: Double): String = if (x == 0.0) "0" else "%.3g".format(java.util.Locale.ROOT, x)
    }
}
