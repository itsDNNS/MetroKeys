package app.metrokeys.engine.predict

/**
 * Parameters of the spelling source of spec 05d §9 for one language: the rows S (suggest), B (word begin), E (word
 * end), P (penalty) and F (spelling class) by index, the letter pairs and letter-group rules of §9.5, the vowel
 * classes, the adjacent keys and the digraph transliteration of §9.4. de, en and fr are the reference's values, es,
 * it and pt the derived ones of §9.6. Only letter-level entries (both sides at most three letters, no space; §2, §8).
 */
public class SpellingParams internal constructor(
    public val languageTag: String,
    /** S0…S12 (S3, S12 unused, 0). */
    internal val s: IntArray,
    /** B0…B5. */
    internal val b: IntArray,
    /** E0…E5. */
    internal val e: IntArray,
    /** P0…P30 (P5, P30 unused, 0). */
    internal val p: IntArray,
    /** F0…F3. */
    internal val f: IntArray,
    pairs: String,
    rules: String,
    vowelClasses: List<String>,
    adjacentExtra: Map<Char, String> = emptyMap(),
    /** §5.5 digraphs (typed → intended), tried for a typed word that is not valid. */
    internal val digraphs: List<Pair<String, String>> = emptyList(),
) {
    /** A letter-group rule: where the candidate has [context] + [intended], the user may have typed [context] + [typed]. */
    internal class Rule(val typed: String, val intended: String, val context: String, val cost: Int) {
        /** Position in [rules] (the table order). */
        var index: Int = -1
    }

    /** Pair table per typed letter of the pair-table range `a`…`{` (index letter − 'a'): intended letters and costs. */
    internal val pairIntended: Array<CharArray>
    internal val pairCost: Array<IntArray>

    /** Rules sorted by (typed, intended) in code-unit order, ties in listing order; insertion rules first (§2). */
    internal val rules: List<Rule>

    /** Insertion rules (empty typed side) and, per first typed letter, the other rules, both in table order (§7). */
    internal val insertionRules: List<Rule>
    internal val rulesByFirst: Map<Char, List<Rule>>

    /** Deletion rules (empty intended side) by first typed letter (§4.8). */
    internal val deletionRulesByFirst: Map<Char, List<Rule>>

    internal val vowelClasses: List<Set<Char>> = vowelClasses.map { it.toSet() }

    /** Adjacent keys: intended letter → typed neighbours (§9.4). */
    private val adjacent: Map<Char, Set<Char>>

    init {
        val pi = Array(RANGE) { CharArray(0) }
        val pc = Array(RANGE) { IntArray(0) }
        val byTyped = HashMap<Char, MutableList<Pair<Char, Int>>>()
        for (tok in pairs.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            // "a→e 32"
            val arrow = tok.indexOf('→')
            val typed = tok.substring(0, arrow)
            val rest = tok.substring(arrow + 1)
            val sp = rest.lastIndexOf(' ')
            val intended = rest.substring(0, sp)
            require(typed.length == 1 && intended.length == 1) { "bad pair $tok" }
            byTyped.getOrPut(typed[0]) { ArrayList() }.add(intended[0] to rest.substring(sp + 1).toInt())
        }
        for ((t, list) in byTyped) {
            require(t in 'a'..'{') { "pair outside the range: $t" }
            val sorted = list.sortedBy { it.first }
            pi[t - 'a'] = CharArray(sorted.size) { sorted[it].first }
            pc[t - 'a'] = IntArray(sorted.size) { sorted[it].second }
        }
        pairIntended = pi
        pairCost = pc
        val parsed = ArrayList<Rule>()
        for (tok in splitRules(rules)) {
            var t = tok
            var context = ""
            val close = t.indexOf(']')
            if (t.startsWith("[") && close > 1 && close < t.indexOf('→')) {
                context = t.substring(1, close)
                t = t.substring(close + 1)
            }
            val arrow = t.indexOf('→')
            val typed = t.substring(0, arrow).let { if (it == "∅") "" else it }
            val rest = t.substring(arrow + 1)
            val sp = rest.lastIndexOf(' ')
            val intended = rest.substring(0, sp).let { if (it == "∅") "" else it }
            parsed.add(Rule(typed, intended, context, rest.substring(sp + 1).toInt()))
        }
        // stable sort by (typed, intended) in code-unit order: ties keep the listing order
        this.rules = parsed.sortedWith { x, y ->
            val c = x.typed.compareTo(y.typed)
            if (c != 0) c else x.intended.compareTo(y.intended)
        }
        for ((i, r) in this.rules.withIndex()) r.index = i
        insertionRules = this.rules.filter { it.typed.isEmpty() }
        rulesByFirst = this.rules.filter { it.typed.isNotEmpty() }.groupBy { it.typed[0] }
        deletionRulesByFirst = this.rules.filter { it.typed.isNotEmpty() && it.intended.isEmpty() }.groupBy { it.typed[0] }
        val adj = HashMap<Char, MutableSet<Char>>()
        for (tok in ADJACENT.split(' ')) adj.getOrPut(tok[0]) { HashSet() }.addAll(tok.substring(1).toList())
        for ((c, typed) in adjacentExtra) {
            adj.getOrPut(c) { HashSet() }.addAll(typed.toList())
            for (n in typed) adj.getOrPut(n) { HashSet() }.add(c)
        }
        adjacent = adj
    }

    /** Adjacency as a table over the Latin letters (intended × typed, 0x250 each), the map for the rest. */
    private val adjacentTable = BooleanArray(TABLE * TABLE).also { t ->
        for ((i, set) in adjacent) for (c in set) if (i.code < TABLE && c.code < TABLE) t[i.code * TABLE + c.code] = true
    }

    /** True if the typed letter [typed] is an adjacent key of the intended letter [intended]. */
    internal fun isAdjacent(typed: Char, intended: Char): Boolean =
        if (typed.code < TABLE && intended.code < TABLE) adjacentTable[intended.code * TABLE + typed.code]
        else adjacent[intended]?.contains(typed) == true

    /** Bit i set if the letter belongs to vowel class i (§9.4), for the letters below 0x250. */
    private val vowelClassBits = IntArray(TABLE).also { t ->
        for ((i, cls) in vowelClasses.withIndex()) for (c in cls) if (c.code < TABLE) t[c.code] = t[c.code] or (1 shl i)
    }

    /**
     * §4.5.3 rule 3: true if the first vowel class (in list order) that contains either letter contains both.
     */
    internal fun sameVowelClass(a: Char, b: Char): Boolean {
        val ma = if (a.code < TABLE) vowelClassBits[a.code] else this.vowelClasses.foldIndexed(0) { i, m, c -> if (a in c) m or (1 shl i) else m }
        val mb = if (b.code < TABLE) vowelClassBits[b.code] else this.vowelClasses.foldIndexed(0) { i, m, c -> if (b in c) m or (1 shl i) else m }
        val either = ma or mb
        if (either == 0) return false
        val first = either and -either
        return ma and first != 0 && mb and first != 0
    }

    /**
     * The rules tried at a typed letter (§4.6): the insertion rules, then those whose typed side starts with [low] or
     * [fold] (different only for an accented typed letter), in table order, split by the folded first letter of their
     * intended side (rules with an empty intended side in every list). Cached.
     */
    internal fun rulesFor(low: Char, fold: Char): RuleIndex {
        val key = (low.code shl 16) or fold.code
        return ruleIndexCache.getOrPut(key) {
            val la = rulesByFirst[low].orEmpty()
            val lb = if (fold != low) rulesByFirst[fold].orEmpty() else emptyList()
            RuleIndex((insertionRules + (la + lb).sortedBy { it.index }).distinct())
        }
    }

    internal val noTypedLetterRules: RuleIndex by lazy { RuleIndex(insertionRules) }

    private val ruleIndexCache = java.util.concurrent.ConcurrentHashMap<Int, RuleIndex>()

    /** Rules by the folded first letter of the intended side; see [rulesFor]. */
    internal class RuleIndex(all: List<Rule>) {
        private val empty: List<Rule> = all.filter { it.intended.isEmpty() }
        private val byLetter = arrayOfNulls<List<Rule>>(TABLE)
        private val other = HashMap<Char, List<Rule>>()

        init {
            val keys = all.filter { it.intended.isNotEmpty() }.map { SpellingSource.fold(it.intended[0]) }.toSet()
            for (x in keys) {
                val l = all.filter { it.intended.isEmpty() || SpellingSource.fold(it.intended[0]) == x }
                if (x.code < TABLE) byLetter[x.code] = l else other[x] = l
            }
        }

        fun at(x: Char): List<Rule> = (if (x.code < TABLE) byLetter[x.code] else other[x]) ?: empty
    }

    /** True if the accent check of §4.4 is on (P27, P28 or P29 non-zero). */
    internal val accentCheck: Boolean get() = p[27] != 0 || p[28] != 0 || p[29] != 0

    internal fun s(i: Int): Int = s[i]

    public companion object {
        /** Typed letters of the pair-table range: `a` … `z` and `{` (§2). */
        internal const val RANGE: Int = 27

        /** The letters with tables (Latin up to U+024F). */
        private const val TABLE: Int = 0x250

        /** Splits the rule listing at ", " separators (a rule's own text never contains ", "). */
        private fun splitRules(text: String): List<String> {
            val out = ArrayList<String>()
            var i = 0
            val t = text.trim()
            while (i < t.length) {
                val j = t.indexOf(", ", i)
                // a rule may itself start with "," (",→m 20"): a separator is ", " followed by a rule start
                val end = if (j < 0) t.length else j
                out.add(t.substring(i, end).trim())
                i = if (j < 0) t.length else j + 2
            }
            return out.filter { it.isNotEmpty() }
        }

        private const val ADJACENT = "aqwsxz bvfg cxdfgb dxswerfvc ewsdfr fertgbvcd gbvfrt hyujmn iujklo jyhnmkiu kloiujm " +
            "lpoik,.; mnhjk, nhjm oiklp pol[; qasw redfgt sazxcdewq trfg uyhjki vcdfgb wqasde xzasdc yhju zasx"

        // §9.1 (S0, S1, S2, S3 unused, S4 … S11, S12 unused)
        private fun sRow(s0: Int) = intArrayOf(s0, 27, 2, 0, 5, 4, 76, 6, 70, 65, 21, 0, 0)
        private val E_ROW = intArrayOf(2, 6, 1, 1, 4, 2)

        /** §9.3 P0 … P30 of one column; [values] lists P0–P4, P6–P29 (P5, P30 unused). */
        private fun pRow(vararg values: Int): IntArray {
            require(values.size == 29)
            val out = IntArray(31)
            var j = 0
            for (i in 0..29) {
                if (i == 5) continue
                out[i] = values[j++]
            }
            return out
        }

        @JvmField
        public val GERMAN: SpellingParams = SpellingParams(
            "de-DE", sRow(52), intArrayOf(10, 4, 12, 13, 4, 6), E_ROW,
            pRow(15, 37, 22, 27, 27, 22, 22, 28, 40, 43, 29, 33, 47, 150, 12, 5, 10, 43, 9, 6, 3, 38, 18, 32, 18, 32, 7, 7, 9),
            intArrayOf(0, -5, -2, 4),
            "a→e 32, a→o 32, b→p 41, c→k 37, c→z 65, d→t 36, e→i 47, e→o 32, e→r 41, f→v 29, g→k 41, i→e 36, i→o 30, " +
                "i→y 9, k→c 4, k→g 21, m→n 30, n→m 31, o→a 44, o→i 42, o→u 29, p→b 36, r→e 55, r→t 30, s→z 3, t→d 29, " +
                "t→r 39, u→i 23, u→o 23, u→ü 3, v→w 40, y→i 41, z→s 41, z→t 41",
            "[a]∅→a 32, [i]∅→e 16, [f]∅→f 45, [p]∅→f 41, [n]∅→g 14, [t]∅→h 26, [e]∅→h 26, [a]∅→h 69, [sc]∅→h 26, " +
                "[k]∅→k 0, [l]∅→l 26, [m]∅→m 27, [n]∅→n 26, [r]∅→r 12, [e]∅→r 19, [a]∅→r 54, [s]∅→s 26, [t]∅→t 15, " +
                "[q]∅→u 40, ch→h 51, ch→sch 31, ck→k 25, en→den 30, f→ph 18, [s]h→ch 24, k→ck 23, kl→l 44, [h]n→en 16, " +
                "sch→ch 32, ss→ß 0, tz→z 24, z→tz 22, ß→ss 0",
            listOf("aeiäöü", "ou"),
            digraphs = listOf("ae" to "ä", "Ae" to "Ä", "oe" to "ö", "Oe" to "Ö", "ue" to "ü", "Ue" to "Ü", "ss" to "ß"),
        )

        @JvmField
        public val ENGLISH: SpellingParams = SpellingParams(
            "en-US", sRow(47), intArrayOf(10, 12, 12, 13, 4, 6), E_ROW,
            pRow(15, 17, 15, 17, 16, 20, 22, 28, 40, 42, 29, 33, 47, 80, 14, 18, 25, 45, 21, 5, 3, 38, 35, 29, 35, 29, 0, 0, 0),
            intArrayOf(0, -5, -2, 4),
            "b→p 35, c→k 19, c→s 32, d→t 35, e→w 41, e→y 38, g→j 30, g→k 40, h→j 37, i→y 21, j→g 34, j→h 37, k→c 19, " +
                "k→g 50, n→m 22, s→c 30, s→z 19, t→d 30, t→s 37, u→w 41, w→e 41, y→e 38, y→i 21, z→s 19, z→x 40",
            "[m]∅→b 29, [m]∅→n 29, [p]∅→t 29, [g]∅→u 29, [o]∅→w 26, \"→' 30, ,→m 20, .→' 30, /→' 30, 0→o 37, 1→l 37, " +
                ":→' 30, ;→' 29, ;→l 20, [→p 20, \\→' 30, [s]a→ci 37, a→er 40, [e]a→he 47, [w]ai→ha 35, air→ear 39, " +
                "[o]ak→ke 42, al→le 42, [e]am→me 35, ar→re 37, at→te 40, ay→e 40, ay→et 42, c→qu 40, [s]ce→e 33, " +
                "ce→sce 29, ch→t 50, ch→tch 29, ch→ti 40, ck→ke 40, cks→x 44, ct→x 40, cw→qu 40, [e]d→ad 42, dd→tt 40, " +
                "[e]de→ad 22, [o]de→ad 42, [a]de→id 37, dg→g 40, [s]e→ce 29, e→ia 42, e→oe 29, [ri]e→s 37, ea→i 40, " +
                "ear→irr 42, ec→iss 37, ee→i 40, en→ion 42, er→our 40, er→re 18, eri→ri 29, es→s 19, ess→ace 47, " +
                "est→ist 25, ew→u 40, ew→ue 35, exc→acc 42, ey→i 42, ey→y 28, f→gh 42, f→ph 27, [f]f→t 40, f→ve 31, " +
                "[e]ff→af 32, ff→ph 37, ff→ugh 44, fi→phe 37, fi→phy 40, g→dg 29, g→qu 42, [e]gg→c 42, [e]gg→x 42, " +
                "ggs→x 40, gs→x 40, gz→x 40, [s]h→ci 40, [s]h→i 40, [s]h→si 40, h→wh 29, [c]he→e 29, ht→th 12, i→ae 42, " +
                "[k]i→ay 30, [s]i→ci 29, i→eo 42, i→yi 30, id→de 32, ie→y 28, [a]ig→ge 47, [a]ik→ke 40, il→le 42, " +
                "[a]im→me 40, in→ne 42, [a]ir→re 38, is→ous 37, [o]is→se 37, is→yz 40, [a]is→z 42, ist→est 37, " +
                "[a]it→te 40, it→ute 44, j→di 40, j→dj 29, ji→gy 40, k→cc 40, k→ch 30, k→ck 29, k→qu 35, ke→ck 40, " +
                "ki→coy 30, [c]ks→t 47, ks→x 32, kw→qu 40, [s]l→tl 29, le→al 32, [a]le→il 42, [o]le→wl 42, [i]m→'m 15, " +
                "[m]m→b 29, m→gm 29, m→nd 42, n→gn 29, n→kn 5, n→pn 29, ne→gn 42, ne→ign 47, ne→in 37, nf→mph 40, " +
                "o→au 40, oe→e 29, oi→ir 40, om→alm 40, ow→uo 37, pn→n 27, ps→s 31, [o]r→ar 24, [o]r→ur 29, r→wr 29, " +
                "[e]re→ar 42, re→er 18, [a]re→ir 37, [o]re→or 30, [o]re→ur 28, ri→eri 29, s→es 24, s→ps 28, sa→ci 40, " +
                "[c]se→e 29, sel→tle 37, sh→c 40, sh→ci 40, sh→t 40, sh→ti 40, si→ce 40, sic→cis 42, sl→tl 35, ss→c 40, " +
                "t→'t 17, t→ch 50, t→ght 37, t→pt 24, tch→cci 42, tch→ch 33, [e]te→at 34, [o]te→at 37, te→ght 37, " +
                "te→it 32, u→oo 27, u→ou 29, ue→ew 35, uer→our 32, ul→le 50, une→oon 42, [o]ur→r 33, ve→f 42, " +
                "[a]w→u 40, [o]w→u 40, who→ho 33, wr→r 33, x→cc 40, x→ck 47, x→cks 42, x→cs 40, x→ks 42, y→ee 25, " +
                "y→ey 24, [p]y→he 47, y→ie 28, y→igh 40, [z]y→wi 47, [a]ye→i 40, yew→ieu 44, yle→ial 37, [a]ys→c 42, " +
                "[a]ys→ce 40, [a]z→is 42, “→' 30, ”→' 30",
            listOf("aie", "ou"),
            digraphs = listOf("ﬀ" to "ff", "ﬁ" to "fi", "ﬂ" to "fl", "ﬃ" to "ffi", "ﬄ" to "ffl", "ﬅ" to "ft", "ﬆ" to "st"),
        )

        @JvmField
        public val FRENCH: SpellingParams = SpellingParams(
            "fr-FR", sRow(54), intArrayOf(10, 12, 12, 13, 4, 6), E_ROW,
            pRow(13, 18, 19, 18, 21, 28, 34, 40, 40, 47, 29, 31, 57, 80, 14, 16, 15, 45, 16, 2, 0, 60, 45, 27, 45, 27, 7, 7, 9),
            intArrayOf(1, -5, -2, 4),
            "y→z 100",
            "AE→Æ 15, ae→æ 15, c→qu 18, e→ai 29, f→ph 35, ls→ux 28, [u]m→n 33, nt→s 33, o→au 29, oe→Œ 15, oe→œ 15, " +
                "oue→œu 28, ph→f 35, qua→ca 35, [ei]s→z 33, sez→tes 30, sse→ce 35, ssi→ci 35, [o]ua→i 28, y→ie 35, " +
                "yy→zz 100",
            listOf("aeiâàêéèîï", "ouôùû"),
        )

        /** The derived columns (§9.6): the English base with S0 54, accent costs 7/7/9 and the F row of de/en. */
        private fun derivedP() =
            pRow(15, 17, 15, 17, 16, 20, 22, 28, 40, 42, 29, 33, 47, 80, 14, 18, 25, 45, 21, 5, 3, 38, 35, 29, 35, 29, 7, 7, 9)

        @JvmField
        public val SPANISH: SpellingParams = SpellingParams(
            "es-ES", sRow(54), intArrayOf(10, 12, 12, 13, 4, 6), E_ROW, derivedP(), intArrayOf(0, -5, -2, 4),
            "b→v 20, c→s 32, g→j 30, i→y 25, j→g 30, s→c 32, s→z 30, v→b 20, y→i 25, z→s 30",
            "cu→qu 30, k→qu 30, ll→y 30, y→ll 30",
            listOf("aeiáéí", "ouóúü"),
            adjacentExtra = mapOf('ñ' to "lpo"),
        )

        @JvmField
        public val ITALIAN: SpellingParams = SpellingParams(
            "it-IT", sRow(54), intArrayOf(10, 12, 12, 13, 4, 6), E_ROW, derivedP(), intArrayOf(0, -5, -2, 4),
            "k→c 25",
            "[c]∅→h 30, [g]∅→h 30, [gl]∅→i 30, [sc]∅→i 30, n→gn 30",
            listOf("aeiàèéìí", "ouòóùú"),
        )

        @JvmField
        public val PORTUGUESE: SpellingParams = SpellingParams(
            "pt-BR", sRow(54), intArrayOf(10, 12, 12, 13, 4, 6), E_ROW, derivedP(), intArrayOf(0, -5, -2, 4),
            "c→ç 3, s→x 32, s→z 30, x→s 32, z→s 30",
            "am→ão 25, ch→x 30, [l]i→h 30, [n]i→h 30, ss→ç 20, x→ch 30",
            listOf("aeiáâãàéêí", "ouóôõúü"),
            adjacentExtra = mapOf('ç' to "lp"),
        )

        /** The spelling parameters of a supported language. */
        @JvmStatic
        public fun forLanguage(tag: String): SpellingParams = when (tag.substringBefore('-')) {
            "de" -> GERMAN
            "en" -> ENGLISH
            "fr" -> FRENCH
            "es" -> SPANISH
            "it" -> ITALIAN
            "pt" -> PORTUGUESE
            else -> GERMAN
        }
    }
}
