package app.metrokeys.engine.predict

import app.metrokeys.engine.text.toKey

/**
 * The learned words list of 05 §12.5 (settings, 07 §9.13 item 5), built from learned data alone plus a lookup of
 * which keys the dictionaries know, so a caller without loaded dictionaries can answer it with exact lookups
 * (05b §6 "Memory").
 */
public object LearnedWordList {
    /** Alphabetical, case-insensitive; equal letters ordered by text. */
    public val ORDER: Comparator<Pair<String, Int>> = compareBy({ it.first.lowercase() }, { it.first })

    /**
     * Every word stored in [models] (use count ≥ 1, no other threshold) whose key [knownKeys] does not report as a
     * dictionary key; a form stored in several languages is listed once with the sum of its use counts.
     * [knownKeys] gets the lower-cased keys of all stored words and returns those a dictionary knows.
     */
    @JvmStatic
    public fun of(models: List<UserModel>, knownKeys: (Set<String>) -> Set<String>): List<Pair<String, Int>> {
        val counts = LinkedHashMap<String, Int>()
        for (m in models) for ((w, c) in m.unknownLearnedWords()) counts[w] = (counts[w] ?: 0) + c
        if (counts.isEmpty()) return emptyList()
        val known = knownKeys(counts.keys.mapTo(HashSet()) { toKey(it) })
        return counts.entries.filter { toKey(it.key) !in known }.map { it.key to it.value }.sortedWith(ORDER)
    }
}
