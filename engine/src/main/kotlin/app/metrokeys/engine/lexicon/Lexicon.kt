package app.metrokeys.engine.lexicon

import app.metrokeys.engine.predict.EditDistance
import app.metrokeys.engine.text.capitalizeFirst
import app.metrokeys.engine.text.toKey
import app.metrokeys.engine.text.upperPerChar
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.BitSet
import java.util.PriorityQueue
import java.util.zip.GZIPInputStream

/** A cased form of a dictionary key with its frequency class 0…255 (higher = more frequent). */
public data class WordForm(val text: String, val frequencyClass: Int)

/**
 * Read-only word list of one language (spec 05b §6): keys (lower-cased words) with their cased forms, organised
 * as a prefix tree whose nodes know the best frequency class below them. Safe for concurrent reads.
 *
 * The tree is laid out breadth first: the children of a node are contiguous and sorted by character, so node
 * indices order nodes by (depth, text).
 *
 * 0.7.1 (05b §1.1, §6): every form carries its derived data — offered bit (suggestion lexicon), spelling class
 * (05d §6) and do-not-suggest flag — in the upper bits of its kind byte; the header carries the cost anchors (05 §3.2)
 * and the guard class (05 §10.1 step 7a). Nodes also know the highest class of the *offered* forms below them (the
 * bound of the key-slip search, 05 §8.1) and which case kinds of forms lie below them, so that the cased forms can be
 * walked in code-unit order without a second tree ([CasedForms], 05d §7).
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
    /** Children of node i are nodeStart[i] until nodeStart[i + 1] (breadth-first layout: the children follow each other). */
    private val nodeStart: IntArray,
    private val nodeParent: IntArray,
    /** Terminal nodes (a key ends there) as a bit set with a rank directory, and the key of each terminal in node order. */
    private val terminalBits: LongArray,
    private val terminalRank: IntArray,
    private val terminalKey: IntArray,
    private val nodeMaxClass: ByteArray,
    private val keyNode: IntArray,
    private val formStart: IntArray,
    private val formClass: ByteArray,
    private val formKind: ByteArray,
    /** Form indices (ascending) whose text is stored explicitly, and those texts. */
    private val explicitIndex: IntArray,
    private val explicitText: Array<String>,
    /** Elision prefix table (05b §1.1, version-2 word lists): prefixes (lower case, `'`) in file order and their classes. */
    private val prefixTexts: Array<String> = emptyArray(),
    private val prefixClasses: ByteArray = ByteArray(0),
    /** Keys kept by the count rule without being a form of the language (05b §3.6 step 7; 05 §10.1 step 5, 0.6.9). */
    private val unattestedKeys: BitSet = BitSet(),
    /** Highest class of the offered forms below each node (0 if none; 05b §6 key trie bound). */
    private val nodeMaxOffered: ByteArray,
    /** Per node: bit k set if a form of kind k (0 key, 1 first upper, 2 all upper) ends at or below it. */
    private val nodeCaseBits: ByteArray,
    /** Cost anchors (05 §3.2, 05b §1.1): classes descending and their costs. */
    private val anchorClassArray: IntArray,
    private val anchorCostArray: IntArray,
    /** Class of the form of frequency rank `AC_SPLIT_RANK` (05b §1.1 "Guard class"). */
    public val guardClass: Int,
    /** True if the derived data came from the file (version 3), false if this lexicon derived it itself. */
    public val derivedFromFile: Boolean,
    /** Keys that are abbreviations in the build's sources (05b §1.1 abbreviation bit, 0.7.1). */
    private val abbreviationKeys: BitSet = BitSet(),
) {
    /** The cost anchors (class, cost) of 05 §3.2, highest class first. */
    public val costAnchors: List<Pair<Int, Int>> get() = anchorClassArray.indices.map { anchorClassArray[it] to anchorCostArray[it] }

    /** The explicitly stored forms (kind 3) as a small cased tree, for the spelling source (05d §3). */
    internal val explicitForms: CasedTrie by lazy {
        val texts = ArrayList<Pair<String, Int>>()
        for (x in explicitIndex.indices) texts.add(explicitText[x] to explicitIndex[x])
        CasedTrie.build(texts) { a, b ->
            val sa = spellingClassOfId(a)
            val sb = spellingClassOfId(b)
            sa != sb && CasedForms.better(sa, sb) == sa
        }
    }
    public val nodeCount: Int get() = nodeChar.size

    /** Number of unattested keys (05 §10.1 step 5). */
    public val unattestedCount: Int get() = unattestedKeys.cardinality()

    /** True if key [keyIndex] is unattested: kept by the count rule of its build without being a form (05b §3.6). */
    public fun isUnattested(keyIndex: Int): Boolean = keyIndex >= 0 && unattestedKeys.get(keyIndex)

    /** True if the key of [word] is in the word list and unattested. */
    public fun isUnattestedWord(word: String): Boolean = isUnattested(keyIndex(word))

    /** Number of keys with the abbreviation bit (05b §1.1). */
    public val abbreviationCount: Int get() = abbreviationKeys.cardinality()

    /**
     * True if key [keyIndex] carries the abbreviation bit (05b §1.1, 0.7.1): the key is an abbreviation in the build's
     * sources. It changes no class and no validity (05 §2.1 `typedIsAbbreviation`, 04 §3.2 item 9).
     */
    public fun isAbbreviation(keyIndex: Int): Boolean = keyIndex >= 0 && abbreviationKeys.get(keyIndex)

    /** True if the key of [word] is in the word list and carries the abbreviation bit. */
    public fun isAbbreviationWord(word: String): Boolean = isAbbreviation(keyIndex(word))

    // ---- elision prefix table (05b §1.1, §6 `prefixClass`) ----

    /** Number of elision prefixes with a frequency class (0 without a prefix table). */
    public val prefixCount: Int get() = prefixTexts.size

    /** True if the word list stores frequency classes for elision prefixes (05 §8.2.1). */
    public val hasPrefixTable: Boolean get() = prefixTexts.isNotEmpty()

    /** Elision prefix number [index] of the prefix table (lower case, ASCII apostrophe). */
    public fun prefixText(index: Int): String = prefixTexts[index]

    /** Index of the elision prefix [prefix] (matched case-insensitively, `’` = `'`), or −1. */
    public fun prefixIndex(prefix: String): Int {
        if (prefixTexts.isEmpty() || prefix.isEmpty()) return -1
        val p = normalizePrefix(prefix)
        for (i in prefixTexts.indices) if (prefixTexts[i] == p) return i
        return -1
    }

    /** Frequency class of an elision prefix ("l'"), or −1 (no table, or not a prefix of the language). */
    public fun prefixClass(prefix: String): Int {
        val i = prefixIndex(prefix)
        return if (i < 0) -1 else prefixClasses[i].toInt() and 0xFF
    }

    // ---- trie access (also used by shape writing) ----

    internal val root: Int get() = 0

    internal fun childCount(node: Int): Int = nodeStart[node + 1] - nodeStart[node]

    internal fun firstChild(node: Int): Int = nodeStart[node]

    internal fun charOf(node: Int): Char = nodeChar[node]

    internal fun maxClassOf(node: Int): Int = nodeMaxClass[node].toInt() and 0xFF

    /** Highest class of the offered forms at or below [node], 0 if there is none (05 §8.1 bound). */
    internal fun maxOfferedClassOf(node: Int): Int = nodeMaxOffered[node].toInt() and 0xFF

    /** Case-kind bits of the forms at or below [node] (bit 0 kind 0, bit 1 kind 1, bit 2 kind 2). */
    internal fun caseBitsOf(node: Int): Int = nodeCaseBits[node].toInt()

    /** Kind (0 key, 1 first upper, 2 all upper, 3 explicit) of form [formIndex] of key [keyIndex]. */
    internal fun formKindOf(keyIndex: Int, formIndex: Int): Int = formKind[formStart[keyIndex] + formIndex].toInt() and 3

    /** Global index of form [formIndex] of key [keyIndex]. */
    internal fun formId(keyIndex: Int, formIndex: Int): Int = formStart[keyIndex] + formIndex

    /** Spelling class 0…3 of the form with global index [formId] (05d §6). */
    internal fun spellingClassOfId(formId: Int): Int = (formKind[formId].toInt() shr 3) and 3

    // ---- derived per-form data (0.7.1, 05b §1.1) ----

    /** True if form [formIndex] of key [keyIndex] is offered: class ≥ 1 and frequency rank ≤ `SUGGEST_MAX_RANK`. */
    public fun isOffered(keyIndex: Int, formIndex: Int): Boolean = formKind[formStart[keyIndex] + formIndex].toInt() and OFFERED != 0

    /** Spelling class 0…3 of a form (05d §6). */
    public fun spellingClass(keyIndex: Int, formIndex: Int): Int = (formKind[formStart[keyIndex] + formIndex].toInt() shr 3) and 3

    /** True if a form carries the do-not-suggest flag (05b §2.16). */
    public fun isFlagged(keyIndex: Int, formIndex: Int): Boolean = formKind[formStart[keyIndex] + formIndex].toInt() and NOSUGGEST != 0

    /** Index of the form equal to [word] within its key, or −1. */
    public fun formIndexOf(keyIndex: Int, word: String): Int {
        if (keyIndex < 0) return -1
        val key = keyString(keyIndex)
        for (i in 0 until formCountOf(keyIndex)) if (formText(keyIndex, key, i) == word) return i
        return -1
    }

    /** True if [word] is exactly a stored form with the do-not-suggest flag. */
    public fun isFlaggedWord(word: String): Boolean {
        val k = keyIndex(word)
        val i = formIndexOf(k, word)
        return i >= 0 && isFlagged(k, i)
    }

    /** True if [word] is exactly a stored, offered form. */
    public fun isOfferedWord(word: String): Boolean {
        val k = keyIndex(word)
        val i = formIndexOf(k, word)
        return i >= 0 && isOffered(k, i)
    }

    /** Number of forms with the do-not-suggest flag. */
    public val flaggedCount: Int get() = (0 until formCount).count { formKind[it].toInt() and NOSUGGEST != 0 }

    /** Number of offered forms. */
    public val offeredCount: Int get() = (0 until formCount).count { formKind[it].toInt() and OFFERED != 0 }

    /** Key index of a terminal node, or −1. */
    internal fun keyAt(node: Int): Int {
        val w = node ushr 6
        val bit = 1L shl (node and 63)
        val word = terminalBits[w]
        if (word and bit == 0L) return -1
        return terminalKey[terminalRank[w] + java.lang.Long.bitCount(word and (bit - 1))]
    }

    internal fun child(node: Int, c: Char): Int {
        var lo = nodeStart[node]
        var hi = nodeStart[node + 1] - 1
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

    /**
     * Index of the key of [word] (matched case-insensitively), or −1. A word with a hyphen (a French hyphen compound,
     * 05b §11.5) is looked up under its alias key, its letters without hyphens and apostrophes ("est-ce" → "estce").
     */
    public fun keyIndex(word: String): Int {
        val key = toKey(word)
        var n = nodeOf(key)
        if (n < 0 && key.indexOf('-') >= 0) n = nodeOf(key.replace("-", "").replace("'", "").replace("’", ""))
        return if (n < 0) -1 else keyAt(n)
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
        return when (formKind[f].toInt() and KIND_MASK) {
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

    /**
     * Best forms of the keys starting with [prefix] (case-insensitive), highest class first; with [skipFlagged] a key
     * whose best form carries the do-not-suggest flag is left out (05 §11 item 4).
     */
    @JvmOverloads
    public fun completions(prefix: String, limit: Int, skipFlagged: Boolean = false): List<WordForm> {
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
                if (!(skipFlagged && isFlagged(keyAt(node), 0))) out.add(bestForm(keyAt(node)))
                continue
            }
            val k = keyAt(node)
            if (k >= 0) queue.add(entry(255 - formClass(k, 0), 0, node))
            for (c in nodeStart[node] until nodeStart[node + 1]) queue.add(entry(255 - maxClassOf(c), 1, c))
        }
        return out
    }

    private fun entry(inverseClass: Int, subtree: Int, node: Int): Long =
        ((inverseClass * 2 + subtree).toLong() shl 32) or node.toLong()

    /**
     * Keys that equal the lower-cased [word], where a position with [loose] set may also hold any letter whose base
     * letter (diacritic removed) is the typed letter (05 §7 `CAPS_UNACCENTED`: "Etat" reaches "état"); at most [limit].
     */
    internal fun looseKeys(word: String, loose: BooleanArray, limit: Int = 16): List<Int> {
        val w = toKey(word)
        val out = ArrayList<Int>()
        fun walk(node: Int, i: Int) {
            if (out.size >= limit) return
            if (i == w.length) {
                val k = keyAt(node)
                if (k >= 0) out.add(k)
                return
            }
            val c = w[i]
            if (!loose[i]) {
                val ch = child(node, c)
                if (ch >= 0) walk(ch, i + 1)
                return
            }
            for (ch in nodeStart[node] until nodeStart[node + 1]) {
                val x = nodeChar[ch]
                if (x == c || app.metrokeys.engine.predict.EditDistance.stripDiacritic(x) == c) walk(ch, i + 1)
            }
        }
        if (loose.size == w.length) walk(0, 0)
        return out
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

        /** Bit 7 of a key's form-count byte: the key is unattested (0.6.9). */
        private const val UNATTESTED_FLAG = 0x80

        /** Bit 6 of a key's form-count byte in version 3: the abbreviation bit (05b §1.1, 0.7.1). */
        private const val ABBREVIATION_FLAG = 0x40
        private const val FORM_COUNT_MASK = 0x7F

        /** Form count of a version-3 key (bits 0-5; bit 6 is the abbreviation bit). */
        private const val FORM_COUNT_MASK_V3 = 0x3F

        /** Bits of a form's kind byte (version 3, 0.7.1): bits 0-1 kind, 2 offered, 3-4 spelling class, 5 do-not-suggest. */
        private const val KIND_MASK = 0x03
        private const val OFFERED = 0x04
        private const val NOSUGGEST = 0x20

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
            if (version !in 1..3) throw IOException("unsupported word list version $version")
            val tag = r.ascii()
            val scale = r.f32()
            val maxCount = r.varint()
            val corpus = r.varint()
            val keyCount = r.varintInt()
            val formCount = r.varintInt()
            var anchors: Pair<IntArray, IntArray>? = null
            var guard = -1
            if (version == 3) {
                val na = r.u8()
                val ac = IntArray(na)
                val co = IntArray(na)
                for (i in 0 until na) {
                    ac[i] = r.u8()
                    co[i] = r.u8()
                }
                anchors = ac to co
                guard = r.u8()
            }
            val keys = KeyBuffer(keyCount)
            val formStart = IntArray(keyCount + 1)
            val formClass = ByteArray(formCount)
            val formKind = ByteArray(formCount)
            val explicitIndex = GrowableInts()
            val explicitText = ArrayList<String>()
            val unattested = BitSet()
            val abbreviation = BitSet()
            val countMask = if (version == 3) FORM_COUNT_MASK_V3 else FORM_COUNT_MASK
            var f = 0
            for (k in 0 until keyCount) {
                val shared = r.varintInt()
                keys.add(shared, r.utf8())
                formStart[k] = f
                // bit 7 of the form count marks an unattested key (0.6.9); files written before never set it
                val countByte = r.u8()
                if (countByte and UNATTESTED_FLAG != 0) unattested.set(k)
                // bit 6 (version 3, 0.7.1): the abbreviation bit; earlier versions use it for the form count
                if (version == 3 && countByte and ABBREVIATION_FLAG != 0) abbreviation.set(k)
                val nf = countByte and countMask
                if (f + nf > formCount) throw IOException("form count mismatch")
                repeat(nf) {
                    formClass[f] = r.u8().toByte()
                    val kindByte = r.u8()
                    // files before version 3 carry no derived bits (the kind byte is 0…3)
                    formKind[f] = (if (version == 3) kindByte else kindByte and KIND_MASK).toByte()
                    if (kindByte and KIND_MASK == 3) {
                        explicitIndex.add(f)
                        explicitText.add(r.utf8())
                    }
                    f++
                }
            }
            formStart[keyCount] = f
            if (f != formCount) throw IOException("form count mismatch")
            var prefixTexts = emptyArray<String>()
            var prefixClasses = ByteArray(0)
            if (version >= 2) {
                val n = r.varintInt()
                if (n > 255) throw IOException("too many elision prefixes")
                prefixTexts = Array(n) { "" }
                prefixClasses = ByteArray(n)
                for (i in 0 until n) {
                    prefixTexts[i] = r.utf8()
                    prefixClasses[i] = r.u8().toByte()
                }
            }
            r.drain()
            return build(tag, scale, maxCount, corpus, digest.digest(), keys, formStart, formClass, formKind,
                explicitIndex.toArray(), explicitText.toTypedArray(), prefixTexts, prefixClasses, unattested, anchors, guard,
                abbreviation)
        }

        /** An elision prefix as stored: lower case with the ASCII apostrophe (`L’` → `l'`). */
        @JvmStatic
        public fun normalizePrefix(prefix: String): String = toKey(prefix).replace('’', '\'')

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
            prefixes: List<Pair<String, Int>> = emptyList(),
            unattested: Set<String> = emptySet(),
            /** Forms with the do-not-suggest flag (05b §2.16); the other derived data are computed from the classes. */
            flagged: Set<String> = emptySet(),
            /** Keys with the abbreviation bit (05b §1.1). */
            abbreviations: Set<String> = emptySet(),
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
                    formKind[f] = (3 or if (form.text in flagged) NOSUGGEST else 0).toByte()
                    explicitText.add(form.text)
                    f++
                }
            }
            formStart[sorted.size] = f
            return build(tag, scale, maxCount, corpusTokens, ByteArray(32), keys, formStart, formClass, formKind,
                IntArray(total) { it }, explicitText.toTypedArray(),
                prefixes.map { it.first }.toTypedArray(), ByteArray(prefixes.size) { prefixes[it].second.toByte() },
                BitSet().also { b -> for ((k, key) in sorted.withIndex()) if (key in unattested) b.set(k) }, null, -1,
                BitSet().also { b -> for ((k, key) in sorted.withIndex()) if (key in abbreviations) b.set(k) })
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
            prefixTexts: Array<String>,
            prefixClasses: ByteArray,
            unattested: BitSet,
            anchors: Pair<IntArray, IntArray>?,
            guard: Int,
            abbreviation: BitSet,
        ): Lexicon {
            val keyCount = keys.size
            val n = keys.nodeCount
            val ch = CharArray(n)
            // start[i + 1] counts the children of node i while the tree is built; the prefix sum below makes it the
            // children range (breadth-first layout: the children of node i + 1 follow those of node i)
            val start = IntArray(n + 1)
            val parent = IntArray(n)
            // terminal nodes (a key ends there) as a bit set, and their keys in node order (nodes are created in order)
            val words = (n + 63) ushr 6
            val bits = LongArray(words)
            val termKey = IntArray(keyCount)
            var t = 0
            for (i in 0 until keyCount) if (keys.length(i) == 0) {
                bits[0] = bits[0] or 1L
                termKey[t++] = i
            }
            var active = IntArray(keyCount) { it }
            var activeSize = keyCount
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
                        start[parentNode + 1]++
                        if (keys.length(i) == d + 1) {
                            bits[node ushr 6] = bits[node ushr 6] or (1L shl (node and 63))
                            termKey[t++] = i
                        }
                    }
                    active[kept++] = i
                }
                activeSize = kept
                d++
                if (d > Short.MAX_VALUE) throw IOException("key too long")
            }
            @Suppress("UNUSED_VALUE")
            active = IntArray(0)
            keys.release()
            if (next != n || t != keyCount) throw IOException("bad key order")
            start[0] = 1
            for (i in 0 until n) start[i + 1] += start[i]
            if (start[n] != n) throw IOException("bad tree layout")
            val rank = IntArray(words + 1)
            var r = 0
            for (w in 0 until words) {
                rank[w] = r
                r += java.lang.Long.bitCount(bits[w])
            }
            rank[words] = r
            val keyNode = IntArray(keyCount)
            run {
                var x = 0
                for (w in 0 until words) {
                    var word = bits[w]
                    while (word != 0L) {
                        val b = java.lang.Long.numberOfTrailingZeros(word)
                        keyNode[termKey[x++]] = (w shl 6) + b
                        word = word and (word - 1)
                    }
                }
            }
            val maxClass = ByteArray(n)
            val maxOffered = ByteArray(n)
            val caseBits = ByteArray(n)
            val derived = DerivedData.of(tag, formStart, formClass, formKind, keyNode, ch, parent, explicitIndex,
                explicitText, anchors, guard)
            for (k in 0 until keyCount) {
                val node = keyNode[k]
                var best = 0
                var bestOffered = 0
                var cb = 0
                for (x in formStart[k] until formStart[k + 1]) {
                    val c = formClass[x].toInt() and 0xFF
                    best = maxOf(best, c)
                    val kb = formKind[x].toInt()
                    if (kb and OFFERED != 0) bestOffered = maxOf(bestOffered, c)
                    val kind = kb and KIND_MASK
                    if (kind < 3) cb = cb or (1 shl kind)
                }
                maxClass[node] = best.toByte()
                maxOffered[node] = bestOffered.toByte()
                caseBits[node] = cb.toByte()
            }
            for (node in n - 1 downTo 1) {
                val p = parent[node]
                if ((maxClass[node].toInt() and 0xFF) > (maxClass[p].toInt() and 0xFF)) maxClass[p] = maxClass[node]
                if ((maxOffered[node].toInt() and 0xFF) > (maxOffered[p].toInt() and 0xFF)) maxOffered[p] = maxOffered[node]
                caseBits[p] = (caseBits[p].toInt() or caseBits[node].toInt()).toByte()
            }
            return Lexicon(
                tag, scale, maxCount, corpus, keyCount, formStart[keyCount], digest,
                ch, start, parent, bits, rank, termKey, maxClass,
                keyNode, formStart, formClass, formKind, explicitIndex, explicitText, prefixTexts, prefixClasses, unattested,
                maxOffered, caseBits, derived.anchorClasses, derived.anchorCosts, derived.guardClass, anchors != null,
                abbreviation,
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
            val version = r.u8()
            if (version !in 1..3) throw IOException("unsupported word list version")
            r.ascii()
            r.f32()
            r.varint()
            r.varint()
            val keyCount = r.varintInt()
            r.varintInt()
            if (version == 3) {
                repeat(r.u8()) {
                    r.u8()
                    r.u8()
                }
                r.u8()
            }
            val found = HashSet<String>()
            val prev = StringBuilder()
            for (k in 0 until keyCount) {
                val shared = r.varintInt()
                if (shared > prev.length) throw IOException("bad shared prefix")
                prev.setLength(shared)
                prev.append(r.utf8())
                val key = prev.toString()
                if (key in keys) found.add(key)
                repeat(r.u8() and if (version == 3) FORM_COUNT_MASK_V3 else FORM_COUNT_MASK) {
                    r.u8()
                    if (r.u8() and KIND_MASK == 3) r.utf8()
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
        private var len = IntArray(capacity)
        private var lcps = IntArray(capacity)
        private var start = IntArray(capacity + 1)
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

        /** Frees the buffer once the tree is built (05b §6: no transient copy beyond what loading needs). */
        fun release() {
            len = IntArray(0)
            lcps = IntArray(0)
            start = IntArray(0)
            chars = CharArray(0)
            prev = CharArray(0)
        }

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
