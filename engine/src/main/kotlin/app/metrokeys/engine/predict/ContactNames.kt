package app.metrokeys.engine.predict

import app.metrokeys.engine.text.toKey

/** Source of contact name strings ("given middle family" name, or the display name, and the nickname). */
public fun interface ContactNameSource {
    public fun contactNames(): List<String>
}

/**
 * Contact names as a word source (spec 05 §13). Kept in memory only. Immutable; build a new instance on every
 * contacts change.
 */
public class ContactNames private constructor(
    private val unigrams: Map<String, Int>,
    private val total: Int,
    private val runs: Map<String, Map<String, Int>>,
) {
    public val words: Set<String> get() = unigrams.keys

    public val totalCount: Int get() = total

    public fun count(word: String): Int = unigrams[word] ?: 0

    public fun contains(word: String): Boolean = unigrams.containsKey(word)

    /** Followers of a run context (lower-cased words joined by a space). */
    public fun followers(context: String): Map<String, Int>? = runs[context]

    /** contactCost(w) of 05 §13, or [NO_COST] if [word] is not a contact word. */
    public fun cost(word: String, nameField: Boolean): Int {
        val n = unigrams[word] ?: return NO_COST
        return surcharge(nameField) + LmCost.cost(lambda(n) * n / total)
    }

    public companion object {
        public const val NO_COST: Int = Int.MAX_VALUE

        @JvmField
        public val EMPTY: ContactNames = ContactNames(emptyMap(), 0, emptyMap())

        @JvmStatic
        public fun surcharge(nameField: Boolean): Int = if (nameField) 0 else Params.NAME_SURCHARGE

        @JvmStatic
        public fun lambda(n: Int): Double = minOf(n * Params.USER_LAMBDA_STEP, Params.USER_LAMBDA_CAP)

        private fun tokenOk(t: String): Boolean = t.all {
            Character.isLetter(it) || it == '\'' || it == '’' || it == '-' ||
                Character.getType(it) == Character.NON_SPACING_MARK.toInt() ||
                Character.getType(it) == Character.COMBINING_SPACING_MARK.toInt()
        }

        /** Filters one contact string (05 §13): keeps letter/apostrophe/mark/hyphen tokens; null if nothing is left. */
        @JvmStatic
        public fun filter(name: String): String? {
            val kept = name.split(Regex("\\s+")).filter { it.isNotEmpty() && tokenOk(it) && it.any { c -> Character.isLetter(c) } }
            val joined = kept.joinToString(" ")
            return if (joined.isEmpty() || joined.length > Params.NAME_MAX_LEN) null else joined
        }

        /** Builds the word source from contact strings (at most [Params.NAME_MAX] strings are accepted). */
        @JvmStatic
        public fun build(names: List<String>): ContactNames {
            val uni = HashMap<String, Int>()
            val runs = HashMap<String, HashMap<String, Int>>()
            var total = 0
            var accepted = 0
            for (raw in names) {
                if (accepted >= Params.NAME_MAX) break
                val s = filter(raw) ?: continue
                accepted++
                val tokens = s.split(' ')
                for (t in tokens) {
                    uni[t] = (uni[t] ?: 0) + 1
                    total++
                    if ('-' in t) for (p in t.split('-')) if (p.isNotEmpty()) {
                        uni[p] = (uni[p] ?: 0) + 1
                        total++
                    }
                }
                for (start in tokens.indices) {
                    for (len in 2..Params.NAME_RUN_MAX) {
                        if (start + len > tokens.size) break
                        val ctxWords = tokens.subList(start, start + len - 1)
                        if (ctxWords.any { '-' in it }) continue
                        val ctx = ctxWords.joinToString(" ") { toKey(it) }
                        val last = tokens[start + len - 1]
                        val m = runs.getOrPut(ctx) { HashMap() }
                        m[last] = (m[last] ?: 0) + 1
                    }
                }
            }
            if (total == 0) return EMPTY
            return ContactNames(uni, total, runs)
        }
    }
}
