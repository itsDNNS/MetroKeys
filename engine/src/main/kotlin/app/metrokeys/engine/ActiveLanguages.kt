package app.metrokeys.engine

import app.metrokeys.engine.predict.Params

/**
 * The active set of multilingual typing (spec 05 §14.5): with up to [Params.LANG_ACTIVE_MAX] enabled languages all of
 * them; with more, the selected language plus the other enabled languages selected most recently. Languages never
 * selected rank behind, in the fixed language order. Only the active set needs to be loaded (00 "Quality
 * requirements").
 */
public object ActiveLanguages {
    /**
     * The active set for the [selected] language. [enabled] is in the fixed language order (07 §9.5), [recency] the
     * selection order, most recent first (07 §9.1 `language_recency`; unknown or disabled tags are ignored). The
     * result is in the fixed language order.
     */
    @JvmStatic
    @JvmOverloads
    public fun select(enabled: List<String>, selected: String, recency: List<String>, max: Int = Params.LANG_ACTIVE_MAX): List<String> {
        if (enabled.size <= max) return enabled
        val ranked = LinkedHashSet<String>()
        if (selected in enabled) ranked.add(selected)
        for (t in recency) if (t in enabled) ranked.add(t)
        for (t in enabled) ranked.add(t)
        val chosen = ranked.take(max).toSet()
        return enabled.filter { it in chosen }
    }

    /** The recency order after [selected] was chosen with the language key or the picker: moved to the front. */
    @JvmStatic
    public fun selected(recency: List<String>, selected: String, enabled: List<String>): List<String> =
        (listOf(selected) + recency.filter { it != selected }).filter { it in enabled }

    /** Parses the stored `language_recency` value ("es-ES,de-DE,…"). */
    @JvmStatic
    public fun parse(value: String?): List<String> =
        value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** The stored form of a recency order. */
    @JvmStatic
    public fun format(recency: List<String>): String = recency.joinToString(",")
}
