package app.metrokeys.engine.lexicon

import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * A follower of a previous word in the pair table, in its stored running-text form. [keyIndex] is −1 − p for the
 * elision prefix p of the word list's prefix table ([text] is then the prefix).
 */
public data class PairFollower(val text: String, val keyIndex: Int, val pairClass: Int) {
    /** True if the follower is an elision prefix, not a word. */
    val isPrefix: Boolean get() = keyIndex < 0
}

/** Thrown when a pair table was built from a different word list than the one it is loaded with. */
public class PairTableMismatchException(message: String) : IOException(message)

/**
 * Word-pair table of one language (spec 05b §1.1, §6), bound to the exact word list it was built from.
 * Previous word "" is the sentence start. Version-2 tables (languages with an elision prefix table, 05b §10.6) also
 * hold the elision prefixes as previous words (up to `PREFIX_FOLLOWERS_MAX` followers) and as followers.
 *
 * Slots: 0 = sentence start, 1 … keyCount = key + 1, keyCount + 1 + p = elision prefix p. The followers of slot s are
 * the entries start[s] until start[s + 1], best class first. Follower entries ≥ keyCount are elision prefixes.
 */
public class PairTable private constructor(
    private val lexicon: Lexicon,
    private val start: IntArray,
    private val followerKey: IntArray,
    private val followerClass: ByteArray,
    private val followerForm: ByteArray,
) {
    public val pairCount: Int get() = followerKey.size

    public val previousWordCount: Int get() = (0 until start.size - 1).count { start[it + 1] > start[it] }

    private val keyCount: Int get() = lexicon.keyCount

    private fun slot(prev: String): Int {
        if (prev.isEmpty()) return 0
        val k = lexicon.keyIndex(prev)
        if (k >= 0) return k + 1
        val p = lexicon.prefixIndex(prev)
        return if (p < 0 || keyCount + 1 + p >= start.size - 1) -1 else keyCount + 1 + p
    }

    /** Follower entry number of [word]: its key index, keyCount + p for the elision prefix p, or −1. */
    private fun followerIndex(word: String): Int {
        val k = lexicon.keyIndex(word)
        if (k >= 0) return k
        val p = lexicon.prefixIndex(word)
        return if (p < 0) -1 else keyCount + p
    }

    /**
     * Pair class 0…255 of (prev, word), both matched case-insensitively; either may be an elision prefix ("l'") of a
     * version-2 table; −1 if unknown.
     */
    public fun bigram(prev: String, word: String): Int {
        val s = slot(prev)
        if (s < 0) return -1
        val w = followerIndex(word)
        return if (w < 0) -1 else bigram(s, w)
    }

    private fun bigram(s: Int, w: Int): Int {
        for (i in start[s] until start[s + 1]) if (followerKey[i] == w) return followerClass[i].toInt() and 0xFF
        return -1
    }

    /**
     * Best followers of [prev] ("" = sentence start, or an elision prefix), each in its stored running-text form; at
     * most [limit]. Elision prefixes among the followers are left out unless [withPrefixes].
     */
    @JvmOverloads
    public fun followers(prev: String, limit: Int, withPrefixes: Boolean = false): List<PairFollower> {
        val s = slot(prev)
        if (s < 0) return emptyList()
        val out = ArrayList<PairFollower>(minOf(limit, start[s + 1] - start[s]))
        for (i in start[s] until start[s + 1]) {
            if (out.size >= limit) break
            val k = followerKey[i]
            val cls = followerClass[i].toInt() and 0xFF
            if (k >= keyCount) {
                if (withPrefixes) out.add(PairFollower(lexicon.prefixText(k - keyCount), -1 - (k - keyCount), cls))
                continue
            }
            out.add(PairFollower(lexicon.formText(k, followerForm[i].toInt() and 0xFF), k, cls))
        }
        return out
    }

    /** Number of stored followers of [prev] (words and prefixes). */
    public fun followerCount(prev: String): Int {
        val s = slot(prev)
        return if (s < 0) 0 else start[s + 1] - start[s]
    }

    public companion object {
        /** Reads a gzip-compressed pair table; throws [PairTableMismatchException] if it is not bound to [lexicon]. */
        @JvmStatic
        public fun read(input: InputStream, lexicon: Lexicon): PairTable {
            // decoded as it streams in; followers go straight into primitive arrays (05b §6 "Memory")
            val r = ByteReader(GZIPInputStream(input, 1 shl 16))
            r.expectMagic("MKPT")
            val version = r.u8()
            if (version != 1 && version != 2) throw IOException("unsupported pair table version $version")
            val tag = r.ascii()
            val keyCount = r.varintInt()
            val formCount = r.varintInt()
            val hash = r.bytes(32)
            if (tag != lexicon.languageTag || keyCount != lexicon.keyCount || formCount != lexicon.formCount ||
                !hash.contentEquals(lexicon.payloadSha256) || (version == 2) != lexicon.hasPrefixTable
            ) {
                throw PairTableMismatchException("pair table $tag does not belong to the loaded word list")
            }
            val prefixes = if (version == 2) lexicon.prefixCount else 0
            val slots = keyCount + 1 + prefixes
            val followerLimit = keyCount + prefixes
            val prevCount = r.varintInt()
            val start = IntArray(slots + 1)
            var keys = IntArray(maxOf(1024, prevCount * 8))
            var classes = ByteArray(keys.size)
            var forms = ByteArray(keys.size)
            var size = 0
            var enc = 0
            var filled = 0 // start[0 .. filled] are set
            repeat(prevCount) {
                enc += r.varintInt()
                if (enc >= slots || (it > 0 && enc <= filled - 1)) throw IOException("bad previous word index")
                while (filled < enc) start[++filled] = size
                val n = if (version == 1) r.u8() else r.varintInt()
                if (size + n > keys.size) {
                    val cap = maxOf(size + n, keys.size + keys.size / 2)
                    keys = keys.copyOf(cap)
                    classes = classes.copyOf(cap)
                    forms = forms.copyOf(cap)
                }
                repeat(n) {
                    val k = r.varintInt()
                    if (k >= followerLimit) throw IOException("bad follower index")
                    keys[size] = k
                    classes[size] = r.u8().toByte()
                    val fi = r.u8()
                    if (k < keyCount && fi >= lexicon.formCountOf(k)) throw IOException("bad form index")
                    forms[size] = fi.toByte()
                    size++
                }
                start[++filled] = size
            }
            while (filled < slots) start[++filled] = size
            return PairTable(lexicon, start, keys.copyOf(size), classes.copyOf(size), forms.copyOf(size))
        }

        /**
         * Builds a table from (prev, follower form, class) triples, for tests; prev and follower may be elision
         * prefixes of [lexicon].
         */
        internal fun fromTriples(lexicon: Lexicon, triples: List<Triple<String, String, Int>>): PairTable {
            val kc = lexicon.keyCount
            fun slotOf(prev: String): Int = when {
                prev.isEmpty() -> 0
                lexicon.keyIndex(prev) >= 0 -> lexicon.keyIndex(prev) + 1
                lexicon.prefixIndex(prev) >= 0 -> kc + 1 + lexicon.prefixIndex(prev)
                else -> -1
            }
            val byPrev = triples.groupBy { slotOf(it.first) }
            val slots = kc + 1 + lexicon.prefixCount
            val start = IntArray(slots + 1)
            val keys = ArrayList<Int>()
            val classes = ArrayList<Byte>()
            val forms = ArrayList<Byte>()
            var filled = 0
            for ((slot, list) in byPrev.toSortedMap()) {
                require(slot >= 0) { "unknown previous word" }
                while (filled < slot) start[++filled] = keys.size
                for ((_, form, cls) in list.sortedByDescending { it.third }) {
                    val k = lexicon.keyIndex(form)
                    if (k < 0) {
                        val p = lexicon.prefixIndex(form)
                        require(p >= 0) { "unknown follower $form" }
                        keys.add(kc + p)
                        forms.add(0)
                    } else {
                        keys.add(k)
                        forms.add(lexicon.formsOf(k).indexOfFirst { it.text == form }.coerceAtLeast(0).toByte())
                    }
                    classes.add(cls.toByte())
                }
                start[++filled] = keys.size
            }
            while (filled < slots) start[++filled] = keys.size
            return PairTable(lexicon, start, keys.toIntArray(), classes.toByteArray(), forms.toByteArray())
        }
    }
}
