package app.metrokeys.ime

import app.metrokeys.engine.touch.Json

/** One emoji category of spec/data/emoji.json with its items (01 §7). */
data class EmojiCategory(val id: String, val tabLabel: String, val dynamic: Boolean, val items: List<String>) {
    /** Text emoticons (`:)`) are drawn in the label colour and text style (02 §5.9.2). */
    val isText: Boolean get() = id == "emoticons"
}

object EmojiData {
    /**
     * Builds the categories from emoji.json (order, tab labels) and the generated item file (assets/emoji/emoji.txt,
     * tools/build_emoji.py). [supported] filters out emoji the device font cannot draw.
     */
    fun parse(specJson: String, items: String, supported: (String) -> Boolean = { true }): List<EmojiCategory> {
        val byId = HashMap<String, List<String>>()
        for (line in items.lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val tab = line.indexOf('\t')
            if (tab < 0) continue
            byId[line.substring(0, tab)] = line.substring(tab + 1).split(' ').filter { it.isNotEmpty() }
        }
        @Suppress("UNCHECKED_CAST")
        val root = Json.parse(specJson) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val cats = root["categories"] as List<Map<String, Any?>>
        return cats.map { c ->
            val id = c["id"] as String
            val dynamic = c["dynamic"] as? Boolean ?: false
            val list = byId[id].orEmpty().let { l -> if (id == "emoticons") l else l.filter(supported) }
            EmojiCategory(id, c["tabLabel"] as String, dynamic, list)
        }
    }
}

/**
 * Recently used emoji (spec 03 §8.2.5): use count descending, then most recent first; the 48 most recently used
 * entries are kept. Serialised into the `emoji_history` preference as "emoji<TAB>count<TAB>time" lines.
 */
class EmojiHistory(serialized: String?) {
    private class Entry(val emoji: String, var count: Int, var time: Long)

    private val entries = ArrayList<Entry>()

    init {
        serialized?.lineSequence()?.forEach { line ->
            val p = line.split('\t')
            if (p.size == 3) {
                val c = p[1].toIntOrNull()
                val t = p[2].toLongOrNull()
                if (c != null && t != null && p[0].isNotEmpty()) entries.add(Entry(p[0], c, t))
            }
        }
    }

    fun record(emoji: String, now: Long) {
        val e = entries.firstOrNull { it.emoji == emoji }
        if (e != null) {
            e.count++
            e.time = now
        } else {
            entries.add(Entry(emoji, 1, now))
        }
        if (entries.size > MAX) {
            entries.sortByDescending { it.time }
            while (entries.size > MAX) entries.removeAt(entries.size - 1)
        }
    }

    /** The first [limit] entries in display order. */
    fun top(limit: Int): List<String> =
        entries.sortedWith(compareByDescending<Entry> { it.count }.thenByDescending { it.time }).take(limit).map { it.emoji }

    val isEmpty: Boolean get() = entries.isEmpty()

    fun serialize(): String = entries.joinToString("\n") { "${it.emoji}\t${it.count}\t${it.time}" }

    companion object {
        const val MAX = 48
    }
}
