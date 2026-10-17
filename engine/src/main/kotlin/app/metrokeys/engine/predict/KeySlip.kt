package app.metrokeys.engine.predict

import app.metrokeys.engine.lexicon.Lexicon
import app.metrokeys.engine.touch.TouchModel
import java.util.concurrent.ConcurrentHashMap

/**
 * The slip of a key letter for a typed letter in the key-slip search of spec 05 §8.1: exact 0, neighbour key
 * floor(v · `SLIP_NEAR` / 1000) with the proximity v at the key centres, accent letter `SLIP_ACCENT`; −1 if the key
 * letter is not accepted (fillers are handled by the walk). Cached per typed letter; thread-safe.
 */
internal class SlipTable(private val touch: TouchModel, private val params: LanguageParams) {
    private val cache = ConcurrentHashMap<Char, IntArray>()

    fun slip(typed: Char, key: Char): Int {
        if (key.code >= TABLE) return compute(typed, key)
        val row = cache.getOrPut(typed) { IntArray(TABLE) { UNKNOWN } }
        var v = row[key.code]
        if (v == UNKNOWN) {
            v = compute(typed, key)
            row[key.code] = v
        }
        return v
    }

    private fun compute(typed: Char, key: Char): Int {
        if (typed == key) return 0
        val v = touch.proximity(typed, Float.NaN, Float.NaN, key)
        if (v >= 0) return v * params.slipNear / 1000
        val accents = params.slipAccents[typed] ?: params.slipAccents[EditDistance.stripDiacritic(typed)]
        if (accents != null && key in accents) return params.slipAccent
        return -1
    }

    companion object {
        private const val TABLE = 0x250
        private const val UNKNOWN = Int.MIN_VALUE

        fun isFiller(c: Char): Boolean = c == '\'' || c == '.' || c == '-'
    }
}

/** An entry of the key-slip lists (05 §8.1). [prefixKeyLength]: key letters matched by a completion. */
internal class SlipEntry(
    val text: String,
    val cls: Int,
    var total: Int,
    val completion: Boolean,
    var prefixKeyLength: Int,
    /** The key the walk reached the form through (−1 for a user word). */
    val keyIndex: Int,
)

/** A capped list of 05 §8.1, ordered by total, then class descending, then form in code-unit order. */
internal class SlipList(private val cap: Int) {
    val entries = ArrayList<SlipEntry>(cap + 1)

    /** The total an entry must not exceed to enter (the last kept total when full). */
    val threshold: Int get() = if (entries.size < cap) Int.MAX_VALUE else entries[entries.size - 1].total

    private fun before(a: SlipEntry, total: Int, cls: Int, text: String): Boolean {
        if (a.total != total) return a.total < total
        if (a.cls != cls) return a.cls > cls
        return a.text < text
    }

    fun offer(text: String, cls: Int, total: Int, completion: Boolean, prefixKeyLength: Int, keyIndex: Int = -1) {
        val old = entries.indexOfFirst { it.text == text }
        if (old >= 0) {
            if (entries[old].total <= total) return
            entries.removeAt(old)
        } else if (entries.size >= cap) {
            val last = entries[entries.size - 1]
            if (!(total < last.total || (total == last.total && (cls > last.cls || (cls == last.cls && text < last.text))))) return
        }
        var i = entries.size
        while (i > 0 && !before(entries[i - 1], total, cls, text)) i--
        entries.add(i, SlipEntry(text, cls, total, completion, prefixKeyLength, keyIndex))
        if (entries.size > cap) entries.removeAt(entries.size - 1)
    }
}

/** The two lists of one key-slip search and their hand-over (05 §8.1). */
internal class SlipResult(val same: SlipList, val comp: SlipList) {
    /** Same-length entries, then completions, the first `SLIP_HANDOVER`; a text that occurs twice is handed over once. */
    fun handOver(): List<SlipEntry> {
        val out = ArrayList<SlipEntry>()
        val seen = HashSet<String>()
        for (e in same.entries + comp.entries) {
            if (out.size >= Params.SLIP_HANDOVER) break
            if (seen.add(e.text)) out.add(e)
        }
        return out
    }
}

/**
 * The key-slip search of 05 §8.1 over a word list's key tree, best first with the per-node bound of the highest
 * offered class below a node. Forms whose cost in this query is below their static cost (pair followers of the
 * previous word, forms with learned counts, contact words) are given as [special] with their query cost and checked
 * directly, so the bound stays admissible.
 */
internal class KeySlipSearch(
    private val lex: Lexicon,
    private val slips: SlipTable,
    private val params: LanguageParams,
    private val classCost: (Int) -> Int,
) {
    /** A form with its lm in this query (05 §8.1, checked separately). */
    class Special(val keyIndex: Int, val formIndex: Int, val lm: Int)

    fun search(low: String, special: List<Special>, cancel: CancellationCheck?): SlipResult {
        val same = SlipList(Params.SLIP_SAME_KEEP)
        val comp = SlipList(Params.SLIP_COMP_KEEP)
        val len = low.length
        if (len == 0) return SlipResult(same, comp)
        // 1. all-exact keys (fillers allowed): every form, total 1
        exactKeys(low) { k ->
            for (f in 0 until lex.formCountOf(k)) same.offer(lex.formText(k, f), lex.formClass(k, f), 1, false, 0, k)
        }
        // 2. forms with a query cost below their static cost
        for (sp in special) {
            if (!lex.isOffered(sp.keyIndex, sp.formIndex)) continue
            val m = match(lex.keyString(sp.keyIndex), low) ?: continue
            val text = lex.formText(sp.keyIndex, sp.formIndex)
            val cls = lex.formClass(sp.keyIndex, sp.formIndex)
            if (m.sameSlip >= 0) same.offer(text, cls, sp.lm + m.sameSlip, false, 0, sp.keyIndex)
            if (m.compSlip >= 0) comp.offer(text, cls, sp.lm + m.compSlip, true, m.compPrefix, sp.keyIndex)
        }
        // 3. best-first walk
        val heap = Heap()
        val root = lex.root
        val rb = bound(root)
        if (rb != INF) heap.push(rb, root, 0, 0, KIND_PREFIX, true, 0, false)
        var steps = 0
        while (heap.size > 0) {
            val pr = heap.topPriority()
            if (pr > maxOf(same.threshold, comp.threshold)) break
            heap.pop()
            if (++steps % 1000 == 0 && cancel != null && cancel.isCancelled()) throw QueryCancelledException()
            val node = heap.node
            val i = heap.i
            val slip = heap.slip
            val kind = heap.kind
            val exact = heap.exact
            val first = lex.firstChild(node)
            val end = first + lex.childCount(node)
            when (kind) {
                KIND_PREFIX -> {
                    // for prefix items the prefix field is the node's depth
                    val depth = heap.prefix
                    if (i < len) {
                        val t = low[i]
                        for (c in first until end) {
                            val ch = lex.charOf(c)
                            if (SlipTable.isFiller(ch) && ch != t) {
                                pushChecked(heap, c, i, slip + params.slipFiller, KIND_PREFIX, exact, depth + 1, false,
                                    maxOf(same.threshold, comp.threshold))
                                continue
                            }
                            val s = slips.slip(t, ch)
                            if (s < 0) continue
                            pushChecked(heap, c, i + 1, slip + s, KIND_PREFIX, exact && s == 0 && ch == t, depth + 1, false,
                                maxOf(same.threshold, comp.threshold))
                        }
                    } else {
                        if (!exact) sameLength(node, slip, same)
                        for (c in first until end) {
                            val ch = lex.charOf(c)
                            if (SlipTable.isFiller(ch)) pushChecked(heap, c, len, slip + params.slipFiller, KIND_TRAIL, exact, 0, false, same.threshold)
                            pushChecked(heap, c, len, slip, KIND_COMP, false, depth, SlipTable.isFiller(ch), comp.threshold)
                        }
                    }
                }
                KIND_TRAIL -> {
                    if (!exact) sameLength(node, slip, same)
                    for (c in first until end) {
                        if (SlipTable.isFiller(lex.charOf(c))) pushChecked(heap, c, len, slip + params.slipFiller, KIND_TRAIL, exact, 0, false, same.threshold)
                    }
                }
                else -> {
                    val prefix = heap.prefix
                    val fillerOnly = heap.fillerOnly
                    val k = lex.keyAt(node)
                    if (k >= 0 && !fillerOnly) {
                        for (f in 0 until lex.formCountOf(k)) {
                            if (!lex.isOffered(k, f)) continue
                            comp.offer(lex.formText(k, f), lex.formClass(k, f), classCost(lex.formClass(k, f)) + slip, true, prefix, k)
                        }
                    }
                    for (c in first until end) {
                        pushChecked(heap, c, len, slip, KIND_COMP, false, prefix, fillerOnly && SlipTable.isFiller(lex.charOf(c)), comp.threshold)
                    }
                }
            }
        }
        return SlipResult(same, comp)
    }

    private fun sameLength(node: Int, slip: Int, same: SlipList) {
        val k = lex.keyAt(node)
        if (k < 0) return
        for (f in 0 until lex.formCountOf(k)) {
            if (!lex.isOffered(k, f)) continue
            same.offer(lex.formText(k, f), lex.formClass(k, f), classCost(lex.formClass(k, f)) + slip, false, 0, k)
        }
    }

    private fun bound(node: Int): Int {
        val m = lex.maxOfferedClassOf(node)
        return if (m <= 0) INF else classCost(m)
    }

    private fun pushChecked(h: Heap, node: Int, i: Int, slip: Int, kind: Int, exact: Boolean, prefix: Int, fillerOnly: Boolean, threshold: Int) {
        val b = bound(node)
        if (b == INF) return
        val pr = slip + b
        if (pr > threshold) return
        h.push(pr, node, i, slip, kind, exact, prefix, fillerOnly)
    }

    /** Keys reached with every typed letter matched exactly, fillers of the key skipped (also after the last letter). */
    private fun exactKeys(low: String, out: (Int) -> Unit) {
        fun walk(node: Int, i: Int) {
            if (i == low.length) {
                val k = lex.keyAt(node)
                if (k >= 0) out(k)
            }
            val first = lex.firstChild(node)
            for (c in first until first + lex.childCount(node)) {
                val ch = lex.charOf(c)
                if (i < low.length && ch == low[i]) walk(c, i + 1)
                else if (SlipTable.isFiller(ch) && (i == low.length || ch != low[i])) walk(c, i)
            }
        }
        walk(lex.root, 0)
    }

    /** Result of [match]: the slips as a same-length word and as a completion (−1 = not reached). */
    class Match(val sameSlip: Int, val compSlip: Int, val compPrefix: Int)

    /** The slips of a single key [key] for the typed letters [low] by the rules of 05 §8.1. */
    fun match(key: String, low: String): Match? = matchKey(key, low, slips, params.slipFiller)

    companion object {
        const val INF = Int.MAX_VALUE / 4
        private const val KIND_PREFIX = 0
        private const val KIND_TRAIL = 1
        private const val KIND_COMP = 2

        /** The slips of one key (05 §8.1 rules as a small dynamic programme): same-length and completion. */
        fun matchKey(key: String, low: String, slips: SlipTable, slipFiller: Int): Match? {
            val kl = key.length
            val n = low.length
            if (kl == 0 || n == 0) return null
            // first letter quick test
            val c0 = key[0]
            if (!SlipTable.isFiller(c0) && slips.slip(low[0], c0) < 0) return null
            val dp = Array(kl + 1) { IntArray(n + 1) { INF } }
            dp[0][0] = 0
            var compSlip = INF
            var compPrefix = 0
            for (j in 0 until kl) {
                val ch = key[j]
                for (i in 0..n) {
                    val v = dp[j][i]
                    if (v == INF) continue
                    if (i < n) {
                        if (SlipTable.isFiller(ch) && ch != low[i]) {
                            if (v + slipFiller < dp[j + 1][i]) dp[j + 1][i] = v + slipFiller
                        } else {
                            val s = slips.slip(low[i], ch)
                            if (s >= 0 && v + s < dp[j + 1][i + 1]) {
                                dp[j + 1][i + 1] = v + s
                                if (i + 1 == n && j + 1 < kl && v + s < compSlip) {
                                    compSlip = v + s
                                    compPrefix = j + 1
                                }
                            }
                        }
                    } else if (SlipTable.isFiller(ch)) {
                        if (v + slipFiller < dp[j + 1][n]) dp[j + 1][n] = v + slipFiller
                    }
                }
            }
            val sameSlip = dp[kl][n]
            if (sameSlip == INF && compSlip == INF) return null
            return Match(if (sameSlip == INF) -1 else sameSlip, if (compSlip == INF) -1 else compSlip, compPrefix)
        }

        /**
         * The beginning of [form] that holds the first [keyLength] letters of its key: a filler of the form that its key
         * lacks (an alias form such as "don't" for the key "dont") is taken along without being counted.
         */
        fun formPrefix(form: String, key: String, keyLength: Int): String {
            if (keyLength <= 0) return ""
            var j = 0
            for (idx in form.indices) {
                val f = Character.toLowerCase(form[idx])
                if (j < key.length && f == key[j]) {
                    j++
                    if (j == keyLength) return form.substring(0, idx + 1)
                } else if (!SlipTable.isFiller(form[idx])) {
                    j++
                    if (j == keyLength) return form.substring(0, idx + 1)
                }
            }
            return form
        }
    }

    /** A binary min-heap of walk items (priority, then insertion order). */
    private class Heap {
        private var keys = LongArray(256)
        private var nodes = IntArray(256)
        private var info = IntArray(256)
        private var slipsA = IntArray(256)
        private var prefixes = IntArray(256)
        private var heap = IntArray(256)
        private var items = 0
        var size = 0
            private set
        private var seq = 0L

        // fields of the last popped item
        var node = 0
        var i = 0
        var slip = 0
        var kind = 0
        var exact = false
        var prefix = 0
        var fillerOnly = false

        fun push(priority: Int, node: Int, i: Int, slip: Int, kind: Int, exact: Boolean, prefix: Int, fillerOnly: Boolean) {
            if (items == nodes.size) {
                val c = items * 2
                keys = keys.copyOf(c); nodes = nodes.copyOf(c); info = info.copyOf(c); slipsA = slipsA.copyOf(c)
                prefixes = prefixes.copyOf(c)
            }
            if (size == heap.size) heap = heap.copyOf(size * 2)
            val id = items++
            nodes[id] = node
            slipsA[id] = slip
            prefixes[id] = prefix
            info[id] = i or (kind shl 8) or ((if (exact) 1 else 0) shl 10) or ((if (fillerOnly) 1 else 0) shl 11)
            keys[id] = (priority.toLong() shl 40) or (seq++ and 0xFFFFFFFFFFL)
            var x = size++
            heap[x] = id
            while (x > 0) {
                val p = (x - 1) / 2
                if (keys[heap[p]] <= keys[heap[x]]) break
                val t = heap[p]; heap[p] = heap[x]; heap[x] = t
                x = p
            }
        }

        fun topPriority(): Int = (keys[heap[0]] ushr 40).toInt()

        fun pop() {
            val id = heap[0]
            node = nodes[id]
            slip = slipsA[id]
            prefix = prefixes[id]
            val inf = info[id]
            i = inf and 0xFF
            kind = (inf shr 8) and 3
            exact = (inf shr 10) and 1 == 1
            fillerOnly = (inf shr 11) and 1 == 1
            size--
            if (size > 0) {
                heap[0] = heap[size]
                var x = 0
                while (true) {
                    val l = 2 * x + 1
                    if (l >= size) break
                    val r = l + 1
                    val c = if (r < size && keys[heap[r]] < keys[heap[l]]) r else l
                    if (keys[heap[x]] <= keys[heap[c]]) break
                    val t = heap[c]; heap[c] = heap[x]; heap[x] = t
                    x = c
                }
            }
        }
    }
}
