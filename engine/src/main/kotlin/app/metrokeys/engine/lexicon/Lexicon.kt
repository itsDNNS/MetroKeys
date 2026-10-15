package app.metrokeys.engine.lexicon

import app.metrokeys.engine.text.capitalizeFirst
import app.metrokeys.engine.text.toKey
import app.metrokeys.engine.text.upperPerChar
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.PriorityQueue
import java.util.zip.GZIPInputStream

/** A cased form of a dictionary key with its frequency class 0…255 (higher = more frequent). */
public data class WordForm(val text: String, val frequencyClass: Int)

/** Result of [Lexicon.fuzzy]: the best form of a key and its unit edit distance to the query. */
public data class FuzzyMatch(val form: WordForm, val distance: Int)

/**
 * Read-only word list of one language (spec 05b §6): keys (lower-cased words) with their cased forms, organised
 * as a prefix tree whose nodes know the best frequency class below them. Safe for concurrent reads.
 *
 * The tree is laid out breadth first: the children of a node are contiguous and sorted by character, so node
 * indices order nodes by (depth, text).
 */
public class Lexicon private constructor(
    public val languageTag: String,
    /** `S = 255 / ln(maxCount)`. */
    public val scale: Float,
    public val maxCount: Long,
    public val corpusTokens: Long,
    public val keyCount: Int,
    public val formCount: Int,
    internal val payloadSha256: ByteArray,
    private val nodeChar: CharArray,
    private val nodeFirstChild: IntArray,
    private val nodeChildCount: ShortArray,
    private val nodeParent: IntArray,
    private val nodeKey: IntArray,
    private val nodeMaxClass: ByteArray,
    private val keyNode: IntArray,
    private val formStart: IntArray,
    private val formClass: ByteArray,
    private val formKind: ByteArray,
    /** Form indices (ascending) whose text is stored explicitly, and those texts. */
    private val explicitIndex: IntArray,
    private val explicitText: Array<String>,
) {
    public val nodeCount: Int get() = nodeChar.size

    // ---- trie access (also used by shape writing) ----

    internal val root: Int get() = 0

    internal fun childCount(node: Int): Int = nodeChildCount[node].toInt()

    internal fun firstChild(node: Int): Int = nodeFirstChild[node]

    internal fun charOf(node: Int): Char = nodeChar[node]

    internal fun maxClassOf(node: Int): Int = nodeMaxClass[node].toInt() and 0xFF

    /** Key index of a terminal node, or −1. */
    internal fun keyAt(node: Int): Int = nodeKey[node]

    internal fun child(node: Int, c: Char): Int {
        var lo = nodeFirstChild[node]
        var hi = lo + nodeChildCount[node] - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val mc = nodeChar[mid]
            when {
                mc < c -> lo = mid + 1
                mc > c -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /** Node of the given lower-cased key or prefix, or −1. */
    internal fun nodeOf(key: String): Int {
        var n = 0
        for (c in key) {
            n = child(n, c)
            if (n < 0) return -1
        }
        return n
    }

    // ---- keys and forms ----

    /** Index of the key of [word] (matched case-insensitively), or −1. */
    public fun keyIndex(word: String): Int {
        val n = nodeOf(toKey(word))
        return if (n < 0) -1 else nodeKey[n]
    }

    public fun keyString(keyIndex: Int): String {
        var n = keyNode[keyIndex]
        val sb = StringBuilder()
        while (n != 0) {
            sb.append(nodeChar[n])
            n = nodeParent[n]
        }
        return sb.reverse().toString()
    }

    public fun formCountOf(keyIndex: Int): Int = formStart[keyIndex + 1] - formStart[keyIndex]

    public fun formText(keyIndex: Int, formIndex: Int): String = formText(keyIndex, keyString(keyIndex), formIndex)

    private fun formText(keyIndex: Int, key: String, formIndex: Int): String {
        val f = formStart[keyIndex] + formIndex
        return when (formKind[f].toInt()) {
            0 -> key
            1 -> capitalizeFirst(key)
            2 -> upperPerChar(key)
            else -> explicitText[java.util.Arrays.binarySearch(explicitIndex, f)]
        }
    }

    public fun formClass(keyIndex: Int, formIndex: Int): Int = formClass[formStart[keyIndex] + formIndex].toInt() and 0xFF

    public fun formsOf(keyIndex: Int): List<WordForm> {
        val key = keyString(keyIndex)
        return List(formCountOf(keyIndex)) { WordForm(formText(keyIndex, key, it), formClass(keyIndex, it)) }
    }

    public fun bestForm(keyIndex: Int): WordForm = WordForm(formText(keyIndex, 0), formClass(keyIndex, 0))

    /** All cased forms of the key of [word], best first; empty if the key is unknown. */
    public fun forms(word: String): List<WordForm> {
        val k = keyIndex(word)
        return if (k < 0) emptyList() else formsOf(k)
    }

    /** Class of the form equal to [word], or −1 if [word] is not exactly a stored form. */
    public fun exactClass(word: String): Int {
        val k = keyIndex(word)
        if (k < 0) return -1
        val key = keyString(k)
        for (i in 0 until formCountOf(k)) if (formText(k, key, i) == word) return formClass(k, i)
        return -1
    }

    /** True if [word] equals one of the stored forms. */
    public fun exact(word: String): Boolean = exactClass(word) >= 0

    /** [word] if it is a stored form, else the best form of its key; null if the key is unknown. */
    public fun canonical(word: String): String? {
        val k = keyIndex(word)
        if (k < 0) return null
        val key = keyString(k)
        for (i in 0 until formCountOf(k)) if (formText(k, key, i) == word) return word
        return formText(k, key, 0)
    }

    /**
     * Class of the form that equals [word]; else of a form that [word] equals with its first letter capitalised or
     * fully upper-cased; else of the best form; −1 if the key is unknown.
     */
    public fun frequency(word: String): Int {
        val k = keyIndex(word)
        if (k < 0) return -1
        val key = keyString(k)
        val n = formCountOf(k)
        for (i in 0 until n) if (formText(k, key, i) == word) return formClass(k, i)
        for (i in 0 until n) {
            val f = formText(k, key, i)
            if (capitalizeFirst(f) == word || upperPerChar(f) == word || f.uppercase() == word) return formClass(k, i)
        }
        return formClass(k, 0)
    }

    /** Best forms of the keys starting with [prefix] (case-insensitive), highest class first. */
    public fun completions(prefix: String, limit: Int): List<WordForm> {
        if (limit <= 0) return emptyList()
        val start = nodeOf(toKey(prefix))
        if (start < 0) return emptyList()
        val out = ArrayList<WordForm>(limit)
        // priority: (255 - class) * 2 + (0 for a result, 1 for a subtree); then node index (shorter, alphabetical)
        val queue = PriorityQueue<Long>()
        queue.add(entry(255 - maxClassOf(start), 1, start))
        while (queue.isNotEmpty() && out.size < limit) {
            val e = queue.poll()
            val node = (e and 0xFFFFFFFFL).toInt()
            if ((e ushr 32) and 1L == 0L) {
                out.add(bestForm(nodeKey[node]))
                continue
            }
            val k = nodeKey[node]
            if (k >= 0) queue.add(entry(255 - formClass(k, 0), 0, node))
            val first = nodeFirstChild[node]
            for (c in first until first + nodeChildCount[node]) queue.add(entry(255 - maxClassOf(c), 1, c))
        }
        return out
    }

    private fun entry(inverseClass: Int, subtree: Int, node: Int): Long =
        ((inverseClass * 2 + subtree).toLong() shl 32) or node.toLong()

    /**
     * All keys within [maxEdits] optimal-string-alignment unit edits of the lower-cased [word], ordered by distance,
     * then class (descending); their best forms, at most [limit]. Prunes the tree walk by the row minimum.
     */
    public fun fuzzy(word: String, maxEdits: Int, limit: Int): List<FuzzyMatch> {
        val w = toKey(word)
        val n = w.length
        val hits = ArrayList<Long>()
        val rows = Array(MAX_DEPTH + 2) { IntArray(n + 1) }
        for (j in 0..n) rows[0][j] = j
        fuzzyWalk(0, 0, w, maxEdits, rows, hits)
        // sort by distance, then class descending, then key index
        val sorted = hits.sortedWith(compareBy<Long>({ it ushr 40 }, { 255 - formClass(((it and 0xFFFFFFFFFFL)).toInt(), 0) },
            { it and 0xFFFFFFFFFFL }))
        val out = ArrayList<FuzzyMatch>(minOf(limit, sorted.size))
        for (h in sorted) {
            if (out.size >= limit) break
            out.add(FuzzyMatch(bestForm((h and 0xFFFFFFFFFFL).toInt()), (h ushr 40).toInt()))
        }
        return out
    }

    private fun fuzzyWalk(node: Int, depth: Int, w: String, maxEdits: Int, rows: Array<IntArray>, hits: MutableList<Long>) {
        if (depth >= MAX_DEPTH) return
        val n = w.length
        val prev = rows[depth]
        val prevPrev = if (depth > 0) rows[depth - 1] else null
        val prevChar = if (depth > 0) nodeChar[node] else '\u0000'
        val row = rows[depth + 1]
        val first = nodeFirstChild[node]
        for (child in first until first + nodeChildCount[node]) {
            val ch = nodeChar[child]
            row[0] = prev[0] + 1
            var rowMin = row[0]
            for (j in 1..n) {
                val tj = w[j - 1]
                var v = minOf(prev[j] + 1, row[j - 1] + 1, prev[j - 1] + if (tj == ch) 0 else 1)
                if (prevPrev != null && j > 1 && ch == w[j - 2] && prevChar == tj && ch != tj) {
                    v = minOf(v, prevPrev[j - 2] + 1)
                }
                row[j] = v
                if (v < rowMin) rowMin = v
            }
            if (row[n] <= maxEdits && nodeKey[child] >= 0) {
                hits.add((row[n].toLong() shl 40) or nodeKey[child].toLong())
            }
            var prevMin = Int.MAX_VALUE
            for (v in prev) if (v < prevMin) prevMin = v
            if (rowMin <= maxEdits || prevMin + 1 <= maxEdits) {
                fuzzyWalk(child, depth + 1, w, maxEdits, rows, hits)
            }
        }
    }

    /** Iterates over all forms of all keys. */
    public fun words(): Sequence<WordForm> = sequence {
        for (k in 0 until keyCount) {
            val key = keyString(k)
            for (i in 0 until formCountOf(k)) yield(WordForm(formText(k, key, i), formClass(k, i)))
        }
    }

    public companion object {
        private const val MAX_DEPTH = 64

        /**
         * Reads a gzip-compressed word list (format: tools/dictbuild/common.py). The data is decoded as it streams
         * in and the prefix tree is built from the front-coded keys without materialising one string per key, so
         * loading needs little more memory than the final structure (05b §6 "Memory").
         */
        @JvmStatic
        public fun read(input: InputStream): Lexicon {
            val digest = MessageDigest.getInstance("SHA-256")
            val r = ByteReader(DigestInputStream(GZIPInputStream(input, 1 shl 16), digest))
            r.expectMagic("MKWL")
            val version = r.u8()
            if (version != 1) throw IOException("unsupported word list version $version")
            val tag = r.ascii()
            val scale = r.f32()
            val maxCount = r.varint()
            val corpus = r.varint()
            val keyCount = r.varintInt()
            val formCount = r.varintInt()
            val keys = KeyBuffer(keyCount)
            val formStart = IntArray(keyCount + 1)
            val formClass = ByteArray(formCount)
            val formKind = ByteArray(formCount)
            val explicitIndex = GrowableInts()
            val explicitText = ArrayList<String>()
            var f = 0
            for (k in 0 until keyCount) {
                val shared = r.varintInt()
                keys.add(shared, r.utf8())
                formStart[k] = f
                val nf = r.u8()
                if (f + nf > formCount) throw IOException("form count mismatch")
                repeat(nf) {
                    formClass[f] = r.u8().toByte()
                    val kind = r.u8()
                    formKind[f] = kind.toByte()
                    if (kind == 3) {
                        explicitIndex.add(f)
                        explicitText.add(r.utf8())
                    }
                    f++
                }
            }
            formStart[keyCount] = f
            if (f != formCount) throw IOException("form count mismatch")
            r.drain()
            return build(tag, scale, maxCount, corpus, digest.digest(), keys, formStart, formClass, formKind,
                explicitIndex.toArray(), explicitText.toTypedArray())
        }

        /**
         * Builds a lexicon from entries (key → forms with classes, best first); used by tests and tools.
         * Keys are derived from the forms' lower-cased text unless given explicitly.
         */
        internal fun fromEntries(
            tag: String,
            scale: Float,
            maxCount: Long,
            corpusTokens: Long,
            entries: Map<String, List<WordForm>>,
        ): Lexicon {
            val sorted = entries.keys.sorted()
            val keys = KeyBuffer(sorted.size)
            var prev = ""
            for (key in sorted) {
                var shared = 0
                while (shared < minOf(prev.length, key.length) && prev[shared] == key[shared]) shared++
                keys.add(shared, key.substring(shared))
                prev = key
            }
            val formStart = IntArray(sorted.size + 1)
            val total = entries.values.sumOf { it.size }
            val formClass = ByteArray(total)
            val formKind = ByteArray(total)
            val explicitText = ArrayList<String>(total)
            var f = 0
            for ((k, key) in sorted.withIndex()) {
                formStart[k] = f
                for (form in entries.getValue(key)) {
                    formClass[f] = form.frequencyClass.toByte()
                    formKind[f] = 3
                    explicitText.add(form.text)
                    f++
                }
            }
            formStart[sorted.size] = f
            return build(tag, scale, maxCount, corpusTokens, ByteArray(32), keys, formStart, formClass, formKind,
                IntArray(total) { it }, explicitText.toTypedArray())
        }

        /**
         * Builds the breadth-first prefix tree level by level from the sorted, front-coded keys. A key's node at
         * depth d is new exactly when its common prefix with the previous key is shorter than d, so every level is
         * one pass over the keys that reach it; node arrays are allocated with their exact size.
         */
        private fun build(
            tag: String,
            scale: Float,
            maxCount: Long,
            corpus: Long,
            digest: ByteArray,
            keys: KeyBuffer,
            formStart: IntArray,
            formClass: ByteArray,
            formKind: ByteArray,
            explicitIndex: IntArray,
            explicitText: Array<String>,
        ): Lexicon {
            val keyCount = keys.size
            val n = keys.nodeCount
            val ch = CharArray(n)
            val firstChild = IntArray(n)
            val childCount = ShortArray(n)
            val parent = IntArray(n)
            val nodeKey = IntArray(n) { -1 }
            var active = IntArray(keyCount) { it }
            var activeSize = keyCount
            for (i in 0 until keyCount) if (keys.length(i) == 0) nodeKey[0] = i
            var levelStart = 0
            var next = 1
            var d = 0
            while (activeSize > 0) {
                var parentNode = levelStart - 1
                levelStart = next
                var kept = 0
                for (a in 0 until activeSize) {
                    val i = active[a]
                    val lcp = keys.lcp(i)
                    if (lcp < d) parentNode++
                    if (keys.length(i) < d + 1) continue
                    if (lcp < d + 1) {
                        val node = next++
                        ch[node] = keys.charAt(i, d)
                        parent[node] = parentNode
                        if (childCount[parentNode].toInt() == 0) firstChild[parentNode] = node
                        val c = childCount[parentNode] + 1
                        if (c > Short.MAX_VALUE) throw IOException("too many children")
                        childCount[parentNode] = c.toShort()
                        if (keys.length(i) == d + 1) nodeKey[node] = i
                    }
                    active[kept++] = i
                }
                activeSize = kept
                d++
                if (d > Short.MAX_VALUE) throw IOException("key too long")
            }
            @Suppress("UNUSED_VALUE")
            active = IntArray(0)
            if (next != n) throw IOException("bad key order")
            val maxClass = ByteArray(n)
            val keyNode = IntArray(keyCount)
            for (node in 0 until n) {
                val k = nodeKey[node]
                if (k >= 0) {
                    keyNode[k] = node
                    var best = 0
                    for (x in formStart[k] until formStart[k + 1]) best = maxOf(best, formClass[x].toInt() and 0xFF)
                    maxClass[node] = best.toByte()
                }
            }
            for (node in n - 1 downTo 1) {
                val p = parent[node]
                if ((maxClass[node].toInt() and 0xFF) > (maxClass[p].toInt() and 0xFF)) maxClass[p] = maxClass[node]
            }
            return Lexicon(
                tag, scale, maxCount, corpus, keyCount, formStart[keyCount], digest,
                ch, firstChild, childCount, parent, nodeKey, maxClass,
                keyNode, formStart, formClass, formKind, explicitIndex, explicitText,
            )
        }

        /**
         * Streams through a gzip-compressed word list and returns those of [keys] (lower-cased dictionary keys)
         * that it contains, without building the word list in memory (05b §6: exact lookups for a second consumer
         * such as the settings' learned-words page).
         */
        @JvmStatic
        public fun containedKeys(input: InputStream, keys: Set<String>): Set<String> {
            if (keys.isEmpty()) return emptySet()
            val r = ByteReader(GZIPInputStream(input, 1 shl 16))
            r.expectMagic("MKWL")
            if (r.u8() != 1) throw IOException("unsupported word list version")
            r.ascii()
            r.f32()
            r.varint()
            r.varint()
            val keyCount = r.varintInt()
            r.varintInt()
            val found = HashSet<String>()
            val prev = StringBuilder()
            for (k in 0 until keyCount) {
                val shared = r.varintInt()
                if (shared > prev.length) throw IOException("bad shared prefix")
                prev.setLength(shared)
                prev.append(r.utf8())
                val key = prev.toString()
                if (key in keys) found.add(key)
                repeat(r.u8()) {
                    r.u8()
                    if (r.u8() == 3) r.utf8()
                }
            }
            return found
        }
    }

    /**
     * The sorted keys during loading: per key its length, its common prefix with the previous key and only the
     * characters after that prefix (the rest equals the previous key), so the buffer holds one character per node.
     */
    private class KeyBuffer(capacity: Int) {
        private val len = IntArray(capacity)
        private val lcps = IntArray(capacity)
        private val start = IntArray(capacity + 1)
        private var chars = CharArray(maxOf(16, capacity * 4))
        private var prev = CharArray(MAX_DEPTH * 4)
        private var prevLen = 0
        var size = 0
            private set

        /** Number of tree nodes: the root plus one per stored character. */
        val nodeCount: Int get() = start[size] + 1

        fun add(shared: Int, suffix: String) {
            if (shared > prevLen) throw IOException("bad shared prefix")
            // the true common prefix may be longer than the stored one
            var l = shared
            while (l < prevLen && l - shared < suffix.length && prev[l] == suffix[l - shared]) l++
            val total = shared + suffix.length
            if (size > 0) {
                val cmp = when {
                    l < prevLen && l < total -> suffix[l - shared].compareTo(prev[l])
                    else -> total.compareTo(prevLen)
                }
                if (cmp <= 0) throw IOException("keys not sorted")
            }
            val i = size++
            len[i] = total
            lcps[i] = if (i == 0) -1 else l
            val own = total - maxOf(l, 0)
            val s = start[i]
            if (s + own > chars.size) chars = chars.copyOf(maxOf(s + own, chars.size + chars.size / 2))
            for (j in maxOf(l, 0) until total) chars[s + j - maxOf(l, 0)] = suffix[j - shared]
            start[i + 1] = s + own
            if (total > prev.size) prev = prev.copyOf(total * 2)
            for (j in shared until total) prev[j] = suffix[j - shared]
            prevLen = total
        }

        fun length(i: Int): Int = len[i]

        /** Common prefix length with the previous key; −1 for the first key. */
        fun lcp(i: Int): Int = lcps[i]

        /** Character [d] of key [i]; valid for d ≥ [lcp]. */
        fun charAt(i: Int, d: Int): Char = chars[start[i] + d - maxOf(lcps[i], 0)]
    }

    private class GrowableInts {
        private var a = IntArray(64)
        private var n = 0

        fun add(v: Int) {
            if (n == a.size) a = a.copyOf(n * 2)
            a[n++] = v
        }

        fun toArray(): IntArray = a.copyOf(n)
    }
}
