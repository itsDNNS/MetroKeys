package app.metrokeys.engine.lexicon

import java.io.IOException
import java.io.InputStream
import java.lang.ref.WeakReference

/** The static data of one language: word list and (optional) word-pair table. */
public class Dictionary(public val lexicon: Lexicon, public val pairs: PairTable?) {
    public val languageTag: String get() = lexicon.languageTag

    /** True if a pair table was asked for but could not be used (so it is not read again). */
    private var pairsMissing = false

    public companion object {
        private const val RESOURCE_DIR = "/app/metrokeys/engine/dict/"

        /**
         * Loads a word list and its pair table. A pair table that does not belong to the word list (or cannot be
         * read) is ignored; the dictionary then runs without pairs.
         */
        @JvmStatic
        public fun load(words: InputStream, pairs: InputStream?): Dictionary {
            val lexicon = words.use { Lexicon.read(it) }
            return Dictionary(lexicon, readPairs(pairs, lexicon))
        }

        /**
         * Attribution and licence text of a bundled dictionary (05b §2.5), for the licences screen. The English
         * text ends with the complete SCOWL notice.
         */
        @JvmStatic
        public fun bundledLicenseText(languageTag: String): String? =
            Dictionary::class.java.getResourceAsStream("${RESOURCE_DIR}LICENSE-$languageTag.txt")?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }

        private val shared = HashMap<String, WeakReference<Dictionary>>()

        /**
         * The dictionary bundled with the engine for "de-DE" or "en-US"; without [withPairs] only the word list is
         * needed. Bundled data is loaded at most once per process (05b §6): while an instance is in use, every
         * caller gets that instance (a word list loaded without pairs gets its pair table added on first demand,
         * sharing the word list).
         */
        @JvmStatic
        @JvmOverloads
        public fun loadBundled(languageTag: String, withPairs: Boolean = true): Dictionary = synchronized(shared) {
            val cached = shared[languageTag]?.get()
            if (cached != null && (cached.pairs != null || !withPairs || cached.pairsMissing)) return cached
            val cls = Dictionary::class.java
            val d = if (cached != null) {
                Dictionary(cached.lexicon, readPairs(cls.getResourceAsStream("$RESOURCE_DIR$languageTag.pairs.gz"), cached.lexicon))
                    .also { it.pairsMissing = it.pairs == null }
            } else {
                val words = cls.getResourceAsStream("$RESOURCE_DIR$languageTag.words.gz")
                    ?: throw IOException("no bundled word list for $languageTag")
                load(words, if (withPairs) cls.getResourceAsStream("$RESOURCE_DIR$languageTag.pairs.gz") else null)
                    .also { it.pairsMissing = withPairs && it.pairs == null }
            }
            shared[languageTag] = WeakReference(d)
            d
        }

        /** The bundled dictionary of [languageTag] if this process has it loaded right now, else null (nothing is loaded). */
        @JvmStatic
        public fun loadedBundled(languageTag: String): Dictionary? = synchronized(shared) { shared[languageTag]?.get() }

        /**
         * Which of [keys] (lower-cased dictionary keys) the bundled word list of [languageTag] contains. Uses the
         * loaded instance if there is one, else streams through the compressed file without loading it (05b §6).
         */
        @JvmStatic
        public fun bundledContains(languageTag: String, keys: Set<String>): Set<String> {
            loadedBundled(languageTag)?.let { d -> return keys.filterTo(HashSet()) { d.lexicon.keyIndex(it) >= 0 } }
            val words = Dictionary::class.java.getResourceAsStream("$RESOURCE_DIR$languageTag.words.gz")
                ?: throw IOException("no bundled word list for $languageTag")
            return words.use { Lexicon.containedKeys(it, keys) }
        }

        private fun readPairs(input: InputStream?, lexicon: Lexicon): PairTable? = input?.use {
            try {
                PairTable.read(it, lexicon)
            } catch (_: IOException) {
                null
            }
        }
    }
}
