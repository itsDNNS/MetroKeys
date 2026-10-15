package app.metrokeys.engine.lexicon

import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** A follower of a previous word in the pair table, in its stored running-text form. */
public data class PairFollower(val text: String, val keyIndex: Int, val pairClass: Int)

/** Thrown when a pair table was built from a different word list than the one it is loaded with. */
public class PairTableMismatchException(message: String) : IOException(message)

/**
 * Word-pair table of one language (spec 05b §1.1, §6), bound to the exact word list it was built from.
 * Previous word "" is the sentence start.
 */
public class PairTable private constructor(
    private val lexicon: Lexicon,
    private val start: IntArray,
    private val count: ByteArray,
    private val followerKey: IntArray,
    private val followerClass: ByteArray,
    private val followerForm: ByteArray,
) {
    public val pairCount: Int get() = followerKey.size

    public val previousWordCount: Int get() = count.count { it.toInt() != 0 }

    private fun slot(prev: String): Int {
        if (prev.isEmpty()) return 0
        val k = lexicon.keyIndex(prev)
        return if (k < 0) -1 else k + 1
    }

    /** Pair class 0…255 of (prev, word), both matched case-insensitively; −1 if unknown. */
    public fun bigram(prev: String, word: String): Int {
        val s = slot(prev)
        if (s < 0) return -1
        val w = lexicon.keyIndex(word)
        return if (w < 0) -1 else bigram(s, w)
    }

    internal fun bigramBySlot(prevSlot: Int, wordKey: Int): Int = bigram(prevSlot, wordKey)

    private fun bigram(s: Int, w: Int): Int {
        val n = count[s].toInt() and 0xFF
        val base = start[s]
        for (i in base until base + n) if (followerKey[i] == w) return followerClass[i].toInt() and 0xFF
        return -1
    }

    /** Best followers of [prev] ("" = sentence start), each in its stored running-text form. */
    public fun followers(prev: String, limit: Int): List<PairFollower> {
        val s = slot(prev)
        if (s < 0) return emptyList()
        val n = minOf(count[s].toInt() and 0xFF, limit)
        val base = start[s]
        return List(n) {
            val k = followerKey[base + it]
            PairFollower(lexicon.formText(k, followerForm[base + it].toInt() and 0xFF), k, followerClass[base + it].toInt() and 0xFF)
        }
    }

    internal fun slotOf(prev: String): Int = slot(prev)

    public companion object {
        /** Reads a gzip-compressed pair table; throws [PairTableMismatchException] if it is not bound to [lexicon]. */
        @JvmStatic
        public fun read(input: InputStream, lexicon: Lexicon): PairTable {
            // decoded as it streams in; followers go straight into primitive arrays (05b §6 "Memory")
            val r = ByteReader(GZIPInputStream(input, 1 shl 16))
            r.expectMagic("MKPT")
            val version = r.u8()
            if (version != 1) throw IOException("unsupported pair table version $version")
            val tag = r.ascii()
            val keyCount = r.varintInt()
            val formCount = r.varintInt()
            val hash = r.bytes(32)
            if (tag != lexicon.languageTag || keyCount != lexicon.keyCount || formCount != lexicon.formCount ||
                !hash.contentEquals(lexicon.payloadSha256)
            ) {
                throw PairTableMismatchException("pair table $tag does not belong to the loaded word list")
            }
            val prevCount = r.varintInt()
            val start = IntArray(keyCount + 1)
            val count = ByteArray(keyCount + 1)
            var keys = IntArray(maxOf(1024, prevCount * 8))
            var classes = ByteArray(keys.size)
            var forms = ByteArray(keys.size)
            var size = 0
            var enc = 0
            repeat(prevCount) {
                enc += r.varintInt()
                if (enc > keyCount) throw IOException("bad previous word index")
                val n = r.u8()
                start[enc] = size
                count[enc] = n.toByte()
                if (size + n > keys.size) {
                    val cap = maxOf(size + n, keys.size + keys.size / 2)
                    keys = keys.copyOf(cap)
                    classes = classes.copyOf(cap)
                    forms = forms.copyOf(cap)
                }
                repeat(n) {
                    val k = r.varintInt()
                    if (k >= keyCount) throw IOException("bad follower index")
                    keys[size] = k
                    classes[size] = r.u8().toByte()
                    val fi = r.u8()
                    if (fi >= lexicon.formCountOf(k)) throw IOException("bad form index")
                    forms[size] = fi.toByte()
                    size++
                }
            }
            return PairTable(lexicon, start, count, keys.copyOf(size), classes.copyOf(size), forms.copyOf(size))
        }

        /** Builds a table from (prev, follower form, class) triples, for tests. */
        internal fun fromTriples(lexicon: Lexicon, triples: List<Triple<String, String, Int>>): PairTable {
            val byPrev = triples.groupBy { if (it.first.isEmpty()) 0 else lexicon.keyIndex(it.first) + 1 }
            val start = IntArray(lexicon.keyCount + 1)
            val count = ByteArray(lexicon.keyCount + 1)
            val keys = ArrayList<Int>()
            val classes = ArrayList<Byte>()
            val forms = ArrayList<Byte>()
            for ((slot, list) in byPrev.toSortedMap()) {
                require(slot >= 0) { "unknown previous word" }
                start[slot] = keys.size
                val sorted = list.sortedByDescending { it.third }
                count[slot] = sorted.size.toByte()
                for ((_, form, cls) in sorted) {
                    val k = lexicon.keyIndex(form)
                    require(k >= 0) { "unknown follower $form" }
                    keys.add(k)
                    classes.add(cls.toByte())
                    val fi = lexicon.formsOf(k).indexOfFirst { it.text == form }.coerceAtLeast(0)
                    forms.add(fi.toByte())
                }
            }
            return PairTable(lexicon, start, count, keys.toIntArray(), classes.toByteArray(), forms.toByteArray())
        }
    }
}
