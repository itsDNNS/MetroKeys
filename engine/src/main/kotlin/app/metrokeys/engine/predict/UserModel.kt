package app.metrokeys.engine.predict

import app.metrokeys.engine.lexicon.Lexicon
import app.metrokeys.engine.text.TextModel
import app.metrokeys.engine.text.decapitalize
import app.metrokeys.engine.text.isAllCapsWord
import app.metrokeys.engine.text.startsUpper
import app.metrokeys.engine.text.toKey
import app.metrokeys.engine.text.upperDisplay
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.text.Normalizer

/**
 * Learned data of one language (spec 05 §12): word counts, n-gram followers, the recent-words window, the table of
 * rejected autocorrections with how often each word was undone, and which words the user accepted (§12.5).
 * All methods are thread-safe.
 *
 * Words the dictionary does not know in any casing ("unknown words") count as known only when accepted, used
 * `LEARN_MIN_UNKNOWN` times or undone once; words the dictionary knows in some casing need
 * `LEARN_MIN_COUNT` uses (05 §7 rule 3, §12.2, §12.5).
 */
public class UserModel(private val lexicon: Lexicon?) {
    private class Entry(var count: Int, var lastUse: Long, var accepted: Boolean = false)

    private val words = LinkedHashMap<String, Entry>()
    private val contexts = LinkedHashMap<String, LinkedHashMap<String, Int>>()
    private val recent = ArrayDeque<String>()
    private val recentCounts = HashMap<String, Int>()

    /** Undo table: NFKC-normalised typed word → number of undos, most recent last. */
    private val undo = LinkedHashMap<String, Int>()
    private var total = 0L
    private var stamp = 0L

    @get:Synchronized
    public val totalCount: Long get() = total

    @get:Synchronized
    public val wordCount: Int get() = words.size

    @get:Synchronized
    public val contextCount: Int get() = contexts.size

    /** Learned count of exactly this form. */
    @Synchronized
    public fun count(form: String): Int = words[form]?.count ?: 0

    /** Occurrences of exactly this form among the last committed words. */
    @Synchronized
    public fun recentCount(form: String): Int = recentCounts[form] ?: 0

    /** Learned forms with a count of at least [minCount]. */
    @Synchronized
    public fun learnedWords(minCount: Int = Params.LEARN_MIN_COUNT): List<Pair<String, Int>> =
        words.entries.filter { it.value.count >= minCount }.map { it.key to it.value.count }

    /** True if the dictionary knows [word] in some casing (it has the word's key). */
    public fun dictionaryKnows(word: String): Boolean = lexicon != null && lexicon.keyIndex(word) >= 0

    /** True if the user accepted exactly this form (05 §12.5). */
    @Synchronized
    public fun isAccepted(form: String): Boolean = words[form]?.accepted == true

    /** How often the autocorrection of the typed word [word] was undone. */
    @Synchronized
    public fun undoCount(word: String): Int = undo[nfkc(word)] ?: 0

    /**
     * True if the learned form [form] counts as a known word (05 §7 rule 3, §12.5): a form whose key the dictionary
     * knows needs `LEARN_MIN_COUNT` uses; an unknown word must be accepted, used `LEARN_MIN_UNKNOWN` times or be in the
     * undo table (one undone autocorrection, 0.6.8).
     */
    @Synchronized
    public fun isKnownLearned(form: String): Boolean {
        val e = words[form]
        if (dictionaryKnows(form)) return e != null && e.count >= Params.LEARN_MIN_COUNT
        if (e != null && (e.accepted || e.count >= Params.LEARN_MIN_UNKNOWN)) return true
        return e != null && e.count > 0 && undoCount(form) > 0
    }

    /** Learned forms that count as known words (§12.5), with their counts: the correction pool and completions. */
    @Synchronized
    public fun knownLearnedWords(): List<Pair<String, Int>> =
        words.entries.filter { isKnownLearned(it.key) }.map { it.key to it.value.count }

    /**
     * The learned words list of 05 §12.5 for this language: every stored form whose key the dictionary does not know,
     * with its use count (no threshold: also words used once that do not count as known yet), sorted alphabetically
     * (case-insensitive, then by text).
     */
    @Synchronized
    public fun unknownLearnedWords(): List<Pair<String, Int>> =
        words.entries.filter { it.value.count > 0 && !dictionaryKnows(it.key) }
            .map { it.key to it.value.count }
            .sortedWith(LearnedWordList.ORDER)

    /** Followers of a learned context (lower-cased words joined by a space). */
    @Synchronized
    public fun followers(context: String): Map<String, Int>? = contexts[context]?.let { LinkedHashMap(it) }

    /**
     * True if [word] is a rejected autocorrection that is valid and never autocorrected again (05 §10.4): one undo is
     * enough for every word, whether the dictionary knows it or not (0.6.8, as in the reference).
     */
    @Synchronized
    public fun isRejected(word: String): Boolean = (undo[nfkc(word)] ?: 0) > 0

    /** All words of the undo table, oldest first. */
    @Synchronized
    public fun rejectedWords(): List<String> = undo.keys.toList()

    /** The form under which [word] is stored (05 §12.1 case folding). */
    @Synchronized
    public fun storedForm(word: String): String {
        val lex = lexicon
        if (lex != null && lex.exact(word)) return word
        val letters = word.count { Character.isLetter(it) }
        if (letters >= 2 && isAllCapsWord(word)) {
            val canon = lex?.canonical(word)
            return if (canon != null && upperDisplay(canon) == word) canon else word
        }
        if (startsUpper(word)) {
            val d = decapitalize(word)
            if ((lex != null && lex.exact(d)) || words.containsKey(d)) return d
        }
        return word
    }

    /**
     * Learns a committed word with up to two previous words (05 §12.1). With [accepted] the user picked the typed
     * word itself in the candidate bar (05 §12.5), which makes an unknown word known at once.
     *
     * [elision] gives the length of a word's elision prefix (05 §2.2, 0 for none): a part that starts with one is
     * learned as its remainder with the prefix (lower case, `'`) as the last context word, and a bare prefix is never
     * learned as a word (§12.1). With [expandContext] an elided previous word counts as its prefix and its remainder.
     */
    @Synchronized
    @JvmOverloads
    public fun learn(
        word: String,
        previousWords: List<String>,
        accepted: Boolean = false,
        elision: ((String) -> Int)? = null,
        expandContext: Boolean = false,
    ) {
        val ctx = ArrayList<String>()
        for (w in previousWords) for (part in w.split(' ')) {
            val t = TextModel.trimNonWord(part)
            if (t.isEmpty()) continue
            val n = if (expandContext && elision != null) elision(t) else 0
            if (n in 1 until t.length) {
                ctx.add(Lexicon.normalizePrefix(t.substring(0, n)))
                ctx.add(t.substring(n))
            } else {
                ctx.add(t)
            }
        }
        while (ctx.size > Params.LEARN_CONTEXT) ctx.removeAt(0)
        // a hyphen compound inserted by a pick or an autocorrection ("est-ce") is learned piece by piece (05 §12.1)
        for (raw in word.split(' ', '-')) {
            var part = TextModel.trimNonWord(raw)
            if (part.isEmpty()) continue
            val n = elision?.invoke(part) ?: 0
            if (n > 0) {
                ctx.add(Lexicon.normalizePrefix(part.substring(0, n)))
                while (ctx.size > Params.LEARN_CONTEXT) ctx.removeAt(0)
                if (n == part.length) continue
                part = part.substring(n)
            }
            if (part.length in Params.LEARN_MIN_LEN..Params.WORD_MAX_LEN && TextModel.isLearnable(part)) {
                learnOne(part, ctx, accepted)
            }
            ctx.add(part)
            while (ctx.size > Params.LEARN_CONTEXT) ctx.removeAt(0)
        }
    }

    private fun learnOne(part: String, ctx: List<String>, accepted: Boolean) {
        val stored = storedForm(part)
        bump(stored, 1)
        if (accepted) words[stored]?.accepted = true
        for (k in 1..minOf(ctx.size, Params.LEARN_CONTEXT)) {
            val context = ctx.takeLast(k).joinToString(" ") { toKey(it) }
            addFollower(context, stored)
        }
        if (stored.length >= 2) {
            recent.addLast(stored)
            recentCounts[stored] = (recentCounts[stored] ?: 0) + 1
            if (recent.size > Params.RECENT_WINDOW) {
                val old = recent.removeFirst()
                val c = (recentCounts[old] ?: 1) - 1
                if (c <= 0) recentCounts.remove(old) else recentCounts[old] = c
            }
        }
    }

    private fun bump(form: String, delta: Int): Entry {
        var e = words[form]
        if (e == null) {
            if (words.size >= Params.USER_MAX_WORDS) evictWord()
            e = Entry(0, 0)
            words[form] = e
        }
        e.count += delta
        total += delta
        e.lastUse = ++stamp
        return e
    }

    private fun evictWord() {
        var victim: String? = null
        var best: Entry? = null
        for ((w, e) in words) {
            val b = best
            if (b == null || e.count < b.count || (e.count == b.count && e.lastUse < b.lastUse)) {
                victim = w
                best = e
            }
        }
        if (victim != null) {
            total -= best!!.count
            words.remove(victim)
        }
    }

    private fun addFollower(context: String, follower: String) {
        var map = contexts[context]
        if (map == null) {
            if (contexts.size >= Params.USER_MAX_CONTEXTS) {
                var victim: String? = null
                var min = Long.MAX_VALUE
                for ((c, f) in contexts) {
                    val sum = f.values.sumOf { it.toLong() }
                    if (sum < min) {
                        min = sum
                        victim = c
                    }
                }
                contexts.remove(victim)
            }
            map = LinkedHashMap()
            contexts[context] = map
        }
        if (!map.containsKey(follower) && map.size >= Params.USER_MAX_NEXT) {
            var victim: String? = null
            var min = Int.MAX_VALUE
            for ((f, c) in map) if (c < min) {
                min = c
                victim = f
            }
            map.remove(victim)
        }
        map[follower] = (map[follower] ?: 0) + 1
    }

    /**
     * "Add to dictionary" (05 §12.4): count + 3 without context; the word counts as accepted (§12.5). Returns false
     * if the word is not learnable.
     */
    @Synchronized
    public fun addWord(word: String): Boolean {
        val w = TextModel.trimNonWord(nfkc(word))
        if (w.length !in Params.LEARN_MIN_LEN..Params.WORD_MAX_LEN || !TextModel.isLearnable(w)) return false
        bump(storedForm(w), Params.ADD_WORD_COUNT).accepted = true
        return true
    }

    /**
     * Rejects an autocorrection (05 §10.4): the typed word enters the undo table (its undo count + 1, moved to the
     * end); with [learn] a dictionary word's count is raised to at least `LEARN_MIN_COUNT`, an unknown word gets one
     * use, and each part of the correction loses one count. [accepted]: the undo was a pick of the typed word in the
     * candidate bar (§12.5), so the word is accepted (only with [learn]).
     */
    @Synchronized
    @JvmOverloads
    public fun reject(typed: String, correction: String, learn: Boolean, accepted: Boolean = false) {
        val t = nfkc(typed)
        val n = (undo.remove(t) ?: 0) + 1
        undo[t] = n
        while (undo.size > Params.UNDO_MAX) undo.remove(undo.keys.first())
        if (!learn) return
        if (t.length in Params.LEARN_MIN_LEN..Params.WORD_MAX_LEN && TextModel.isLearnable(t)) {
            val s = storedForm(t)
            val e = if (dictionaryKnows(t)) {
                val c = count(s)
                if (c < Params.LEARN_MIN_COUNT) bump(s, Params.LEARN_MIN_COUNT - c) else words.getValue(s)
            } else {
                bump(s, 1)
            }
            if (accepted) e.accepted = true
        }
        for (raw in correction.split(' ')) {
            val part = TextModel.trimNonWord(raw)
            if (part.isEmpty()) continue
            val s = storedForm(part)
            val e = words[s] ?: continue
            e.count -= 1
            total -= 1
            if (e.count <= 0) words.remove(s)
        }
    }

    /**
     * Forget(word) of 05 §12.5: removes every learned form with the key of [word] from the counts, the n-gram
     * contexts (as follower and as context word), the recent window, the accepted marks and the undo table. Words
     * whose key the dictionary knows cannot be forgotten. Returns true if anything was removed.
     */
    @Synchronized
    public fun forget(word: String): Boolean {
        val w = TextModel.trimNonWord(nfkc(word))
        if (w.isEmpty() || dictionaryKnows(w)) return false
        val key = toKey(w)
        var changed = false
        val forms = words.keys.filter { toKey(it) == key }
        for (f in forms) {
            total -= words.remove(f)!!.count
            changed = true
        }
        val ctxIt = contexts.entries.iterator()
        while (ctxIt.hasNext()) {
            val (c, followers) = ctxIt.next()
            if (c.split(' ').any { it == key }) {
                ctxIt.remove()
                changed = true
                continue
            }
            if (followers.keys.removeAll { toKey(it) == key }) changed = true
            if (followers.isEmpty()) ctxIt.remove()
        }
        if (recent.any { toKey(it) == key }) {
            val kept = recent.filter { toKey(it) != key }
            recent.clear()
            recentCounts.clear()
            for (r in kept) {
                recent.addLast(r)
                recentCounts[r] = (recentCounts[r] ?: 0) + 1
            }
            changed = true
        }
        if (undo.keys.removeAll { toKey(it) == key }) changed = true
        return changed
    }

    /** Removes all learned data ("delete learned words"). */
    @Synchronized
    public fun clear() {
        words.clear()
        contexts.clear()
        recent.clear()
        recentCounts.clear()
        undo.clear()
        total = 0
        stamp = 0
    }

    /** Replaces the content with serialised [data]; unreadable or wrong-version data leaves the model empty. */
    @Synchronized
    public fun restore(data: ByteArray?): Boolean {
        clear()
        if (data == null) return false
        return try {
            load(data)
            true
        } catch (_: Exception) {
            clear()
            false
        }
    }

    /** Serialises the model in the versioned format read by [read]. */
    @Synchronized
    public fun serialize(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeLong(stamp)
            out.writeInt(words.size)
            for ((w, e) in words) {
                out.writeUTF(w)
                out.writeInt(e.count)
                out.writeLong(e.lastUse)
                out.writeByte(if (e.accepted) FLAG_ACCEPTED else 0)
            }
            out.writeInt(contexts.size)
            for ((c, f) in contexts) {
                out.writeUTF(c)
                out.writeInt(f.size)
                for ((w, n) in f) {
                    out.writeUTF(w)
                    out.writeInt(n)
                }
            }
            out.writeInt(recent.size)
            for (w in recent) out.writeUTF(w)
            out.writeInt(undo.size)
            for ((w, n) in undo) {
                out.writeUTF(w)
                out.writeInt(n)
            }
        }
        return bytes.toByteArray()
    }

    @Synchronized
    private fun load(data: ByteArray) {
        DataInputStream(ByteArrayInputStream(data)).use { inp ->
            if (inp.readInt() != MAGIC) throw IOException("bad magic")
            val version = inp.readInt()
            if (version != VERSION && version != VERSION_1) throw IOException("unsupported version")
            stamp = inp.readLong()
            val nw = inp.readInt()
            if (nw !in 0..Params.USER_MAX_WORDS) throw IOException("bad word count")
            repeat(nw) {
                val w = inp.readUTF()
                val c = inp.readInt()
                val l = inp.readLong()
                val flags = if (version >= VERSION) inp.readUnsignedByte() else 0
                if (c <= 0) throw IOException("bad count")
                words[w] = Entry(c, l, flags and FLAG_ACCEPTED != 0)
                total += c
            }
            val nc = inp.readInt()
            if (nc !in 0..Params.USER_MAX_CONTEXTS) throw IOException("bad context count")
            repeat(nc) {
                val c = inp.readUTF()
                val nf = inp.readInt()
                if (nf !in 0..Params.USER_MAX_NEXT) throw IOException("bad follower count")
                val map = LinkedHashMap<String, Int>()
                repeat(nf) { map[inp.readUTF()] = inp.readInt() }
                contexts[c] = map
            }
            val nr = inp.readInt()
            if (nr !in 0..Params.RECENT_WINDOW) throw IOException("bad recent count")
            repeat(nr) {
                val w = inp.readUTF()
                recent.addLast(w)
                recentCounts[w] = (recentCounts[w] ?: 0) + 1
            }
            val nu = inp.readInt()
            if (nu !in 0..Params.UNDO_MAX) throw IOException("bad undo count")
            repeat(nu) {
                val w = inp.readUTF()
                // version 1 had no undo counts; every entry was rejected
                val n = if (version >= VERSION) inp.readInt() else 1
                if (n <= 0) throw IOException("bad undo count")
                undo[w] = n
            }
            if (inp.read() != -1) throw IOException("trailing data")
        }
    }

    public companion object {
        private const val MAGIC = 0x4D4B554D // "MKUM"

        /** Version 2 adds the accepted flag per word and the undo count per undo-table entry. */
        private const val VERSION = 2
        private const val VERSION_1 = 1
        private const val FLAG_ACCEPTED = 1

        /** Reads serialised data; unreadable or wrong-version data yields an empty model. */
        @JvmStatic
        public fun read(data: ByteArray?, lexicon: Lexicon?): UserModel {
            val model = UserModel(lexicon)
            model.restore(data)
            return model
        }

        internal fun nfkc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC)
    }
}
