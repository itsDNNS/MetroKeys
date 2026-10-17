package app.metrokeys.engine.lexicon

/**
 * A small tree of cased texts (the explicitly stored forms of a word list), children contiguous and sorted by code
 * unit; a terminal node holds the global form index (the form with the best spelling class if a text occurs twice).
 */
internal class CasedTrie private constructor(
    private val chars: CharArray,
    private val firstChild: IntArray,
    private val childCount: IntArray,
    private val terminal: IntArray,
) {
    fun childStart(node: Int): Int = firstChild[node]

    fun childEnd(node: Int): Int = firstChild[node] + childCount[node]

    fun charOf(node: Int): Char = chars[node]

    fun hasChildren(node: Int): Boolean = childCount[node] > 0

    /** Global form index of the text ending at [node], or −1. */
    fun formAt(node: Int): Int = terminal[node]

    companion object {
        /** Builds the tree of (text, global form index); [better] decides which form a duplicate text keeps. */
        fun build(texts: List<Pair<String, Int>>, better: (Int, Int) -> Boolean = { _, _ -> false }): CasedTrie {
            val sorted = texts.sortedBy { it.first }
            val chars = ArrayList<Char>()
            val first = ArrayList<Int>()
            val count = ArrayList<Int>()
            val term = ArrayList<Int>()
            fun newNode(c: Char): Int {
                chars.add(c)
                first.add(0)
                count.add(0)
                term.add(-1)
                return chars.size - 1
            }
            newNode('\u0000')
            // children of [node] for the texts [from, to) that share the first [depth] characters
            fun fill(node: Int, from: Int, to: Int, depth: Int) {
                var i = from
                val groups = ArrayList<IntArray>()
                while (i < to) {
                    val t = sorted[i].first
                    if (t.length == depth) {
                        val f = sorted[i].second
                        if (term[node] < 0 || better(f, term[node])) term[node] = f
                        i++
                        continue
                    }
                    var j = i + 1
                    while (j < to && sorted[j].first.length > depth && sorted[j].first[depth] == t[depth]) j++
                    groups.add(intArrayOf(i, j))
                    i = j
                }
                if (groups.isEmpty()) return
                val start = chars.size
                for (g in groups) newNode(sorted[g[0]].first[depth])
                first[node] = start
                count[node] = groups.size
                for ((x, g) in groups.withIndex()) fill(start + x, g[0], g[1], depth + 1)
            }
            fill(0, 0, sorted.size, 0)
            return CasedTrie(chars.toCharArray(), first.toIntArray(), count.toIntArray(), term.toIntArray())
        }
    }
}

/**
 * The cased forms of a word list as a tree walked in code-unit order (spec 05d §3.1, §7), without a second copy of
 * the forms: a node is a key-trie node together with the case modes of its forms below (L: the key itself, F: first
 * letter upper, A: every letter upper; 05b §6 kinds 0–2) plus a node of the small tree of the explicitly stored forms
 * (kind 3). Children are produced in code-unit order of the cased characters; equal characters are one child.
 *
 * Node handles are passed as (keyNode, modes, explicitNode) with −1 / 0 for an absent part.
 */
internal class CasedForms(private val lex: Lexicon) {
    private val explicit = lex.explicitForms

    /** One level of children, filled by [children]. */
    class Level {
        var size = 0

        // the node the level was last filled for (the walk often fills the same level for the same node again)
        var forKey = Int.MIN_VALUE
        var forModes = 0
        var forExp = 0
        var forDepth = -1
        var char = CharArray(64)
        var keyNode = IntArray(64)
        var modes = IntArray(64)
        var expNode = IntArray(64)

        /** Spelling class of the form ending at the child, filled by [spellingAt]. */
        var spelling = IntArray(64)
        var hasChildren = BooleanArray(64)

        // the two runs of capitals and small letters before the merge
        var uChar = CharArray(64)
        var uKey = IntArray(64)
        var uModes = IntArray(64)
        var lChar = CharArray(64)
        var lKey = IntArray(64)
        var lModes = IntArray(64)

        fun ensure(n: Int) {
            if (n <= char.size) return
            val c = maxOf(n, char.size * 2)
            char = char.copyOf(c)
            keyNode = keyNode.copyOf(c)
            modes = modes.copyOf(c)
            expNode = expNode.copyOf(c)
            uChar = uChar.copyOf(c)
            uKey = uKey.copyOf(c)
            uModes = uModes.copyOf(c)
            lChar = lChar.copyOf(c)
            lKey = lKey.copyOf(c)
            lModes = lModes.copyOf(c)
            spelling = spelling.copyOf(c)
            hasChildren = hasChildren.copyOf(c)
        }
    }

    /** The root: every key-trie case mode and the root of the explicit tree. */
    val rootModes: Int = MODE_L or MODE_F or MODE_A

    /**
     * Children of the node (keyNode, modes, expNode) at [depth] (the number of characters of the node's text), in
     * code-unit order, into [out].
     */
    fun children(keyNode: Int, modes: Int, expNode: Int, depth: Int, out: Level) {
        if (out.forKey == keyNode && out.forModes == modes && out.forExp == expNode && out.forDepth == depth) return
        out.forKey = keyNode
        out.forModes = modes
        out.forExp = expNode
        out.forDepth = depth
        // two runs from the key node: the capitals (F and A at depth 0, A below) and the small letters (L at depth 0, L and
        // F below), each with the modes it serves; then a merge with the explicit forms' children by code unit
        var nu = 0
        var nl = 0
        val first = if (keyNode >= 0) lex.firstChild(keyNode) else 0
        val end = if (keyNode >= 0 && modes != 0) first + lex.childCount(keyNode) else first
        out.ensure(2 * (end - first) + 8)
        val upperModes = modes and (if (depth == 0) MODE_F or MODE_A else MODE_A)
        val lowerModes = modes and (if (depth == 0) MODE_L else MODE_L or MODE_F)
        for (c in first until end) {
            val bits = lex.caseBitsOf(c)
            val ch = lex.charOf(c)
            val um = bits and upperModes
            if (um != 0) {
                out.uChar[nu] = Character.toUpperCase(ch); out.uKey[nu] = c; out.uModes[nu] = um; nu++
            }
            val lm = bits and lowerModes
            if (lm != 0) {
                out.lChar[nl] = ch; out.lKey[nl] = c; out.lModes[nl] = lm; nl++
            }
        }
        // the capitals are sorted except for a few letters whose capital lies elsewhere (à → À, ÿ → Ÿ): insertion sort
        for (a in 1 until nu) {
            val c = out.uChar[a]
            if (c >= out.uChar[a - 1]) continue
            val kn = out.uKey[a]
            val md = out.uModes[a]
            var b = a - 1
            while (b >= 0 && out.uChar[b] > c) {
                out.uChar[b + 1] = out.uChar[b]; out.uKey[b + 1] = out.uKey[b]; out.uModes[b + 1] = out.uModes[b]
                b--
            }
            out.uChar[b + 1] = c; out.uKey[b + 1] = kn; out.uModes[b + 1] = md
        }
        val es = if (expNode >= 0) explicit.childStart(expNode) else 0
        val ee = if (expNode >= 0) explicit.childEnd(expNode) else 0
        out.ensure(nu + nl + (ee - es) + 8)
        var iu = 0
        var il = 0
        var ie = es
        var m = 0
        while (iu < nu || il < nl || ie < ee) {
            var c = Char.MAX_VALUE
            if (iu < nu && out.uChar[iu] < c) c = out.uChar[iu]
            if (il < nl && out.lChar[il] < c) c = out.lChar[il]
            if (ie < ee && explicit.charOf(ie) < c) c = explicit.charOf(ie)
            var kn = -1
            var md = 0
            var en = -1
            if (iu < nu && out.uChar[iu] == c) {
                kn = out.uKey[iu]; md = out.uModes[iu]; iu++
            }
            if (il < nl && out.lChar[il] == c) {
                // an uncased character: the same key node serves both runs
                if (kn < 0 || kn == out.lKey[il]) {
                    kn = out.lKey[il]; md = md or out.lModes[il]
                }
                il++
            }
            if (ie < ee && explicit.charOf(ie) == c) {
                en = ie; ie++
            }
            out.char[m] = c; out.keyNode[m] = kn; out.modes[m] = md; out.expNode[m] = en
            m++
        }
        for (i in 0 until m) {
            out.spelling[i] = UNKNOWN
            out.hasChildren[i] = hasChildren(out.keyNode[i], out.modes[i], out.expNode[i])
        }
        out.size = m
    }

    /** Spelling class of the form ending at child [i] of [out] (best of several), −1 if none; computed on demand. */
    fun spellingAt(out: Level, i: Int): Int {
        var s = out.spelling[i]
        if (s == UNKNOWN) {
            s = terminalSpelling(out.keyNode[i], out.modes[i], out.expNode[i])
            out.spelling[i] = s
        }
        return s
    }

    private fun terminalSpelling(keyNode: Int, modes: Int, expNode: Int): Int {
        var best = -1
        if (keyNode >= 0) {
            val k = lex.keyAt(keyNode)
            if (k >= 0) {
                for (i in 0 until lex.formCountOf(k)) {
                    val kind = lex.formKindOf(k, i)
                    if (kind < 3 && modes and (1 shl kind) != 0) best = better(best, lex.spellingClass(k, i))
                }
            }
        }
        if (expNode >= 0) {
            val f = explicit.formAt(expNode)
            if (f >= 0) best = better(best, lex.spellingClassOfId(f))
        }
        return best
    }

    /** True if the node may have children (a key node with children whose subtree has forms of [modes], or explicit ones). */
    private fun hasChildren(keyNode: Int, modes: Int, expNode: Int): Boolean {
        if (expNode >= 0 && explicit.hasChildren(expNode)) return true
        return keyNode >= 0 && modes != 0 && lex.childCount(keyNode) > 0
    }

    companion object {
        private const val UNKNOWN = -2
        const val MODE_L = 1
        const val MODE_F = 2
        const val MODE_A = 4

        /** Order of preference of spelling classes: 1, 2, 0, 3 (the spelling-class cost F rises in that order, 05d §9.3). */
        private val PREFERENCE = intArrayOf(2, 0, 1, 3)

        fun better(a: Int, b: Int): Int = if (a < 0) b else if (b < 0) a else if (PREFERENCE[b] < PREFERENCE[a]) b else a
    }
}
