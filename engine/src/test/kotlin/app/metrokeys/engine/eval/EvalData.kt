package app.metrokeys.engine.eval

import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** One item of a generated test set (spec 05c §10: id, half, set, band, lengthBand, variant, sentenceId, before, expected, baseKey). */
internal data class EvalItem(
    val id: String,
    val half: String,
    val set: String,
    val band: String,
    val lengthBand: String,
    val variant: String,
    val sentenceId: String,
    val before: String,
    val expected: String,
    val baseKey: String,
)

/** A test-set file of one language and salt. */
internal class TestSet(val file: File, val language: String, val salt: String, val header: String, val items: List<EvalItem>) {
    val sha256: String by lazy { sha256(file) }

    /** The generation report written next to the test set (counts, real-error parsing, base words without context). */
    val generation: Map<String, Any?> by lazy {
        val f = File(file.parentFile, file.name.removeSuffix(".tsv") + ".generation.json")
        @Suppress("UNCHECKED_CAST")
        if (f.exists()) app.metrokeys.engine.touch.Json.parse(f.readText()) as Map<String, Any?> else emptyMap()
    }

    companion object {
        fun read(file: File): TestSet {
            val lines = file.readLines(Charsets.UTF_8)
            require(lines.size >= 2 && lines[0].startsWith("# EVAL_VERSION=")) { "not a test set: $file" }
            val header = lines[0]
            val fields = header.removePrefix("# ").split('\t').associate { it.substringBefore('=') to it.substringAfter('=') }
            val items = ArrayList<EvalItem>(lines.size)
            for (line in lines.drop(2)) {
                if (line.isEmpty()) continue
                val c = line.split('\t')
                require(c.size == 10) { "bad test-set line in $file: $line" }
                items.add(EvalItem(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9]))
            }
            return TestSet(file, fields.getValue("language"), fields.getValue("salt"), header, items)
        }
    }
}

/** The ranked trusted words, names and abbreviations of a language (evaluation/lexicon/<tag>.lexicon.tsv.gz). */
internal class EvalLexicon(val file: File) {
    class Entry(val key: String, val rank: Int, val pW: Double, val forms: List<String>)

    val trusted = LinkedHashMap<String, Entry>()
    val names = LinkedHashMap<String, Entry>()
    val abbreviations = LinkedHashMap<String, Entry>()
    val sha256: String by lazy { sha256(file) }

    init {
        GZIPInputStream(file.inputStream()).bufferedReader(Charsets.UTF_8).useLines { seq ->
            for (line in seq) {
                if (line.startsWith("#") || line.startsWith("kind\t")) continue
                val c = line.split('\t')
                val e = Entry(c[1], c[2].toInt(), c[3].toDouble(), c[4].split(','))
                when (c[0]) {
                    "T" -> trusted[e.key] = e
                    "N" -> names[e.key] = e
                    "A" -> abbreviations[e.key] = e
                }
            }
        }
    }

    val trustedByRank: List<Entry> by lazy { trusted.values.sortedBy { it.rank } }

    /** zipf = log10(pW) + 9 of a key (trusted word, name or abbreviation), 0 if unknown. */
    fun zipf(key: String): Double {
        val e = trusted[key] ?: names[key] ?: abbreviations[key] ?: return 0.0
        return if (e.pW > 0) kotlin.math.log10(e.pW) + 9 else 0.0
    }
}

/**
 * The build's source-form export (05b §2, build-data/export/<tag>.forms.tsv.gz): subtitle count, written-language
 * frequency and flags per key. Optional: absent when the dictionaries were not built on this machine.
 */
internal class Provenance(val file: File) {
    class Row(val subtitleCount: Long, val pW: Double, val flags: String)

    val rows = HashMap<String, Row>()
    val sha256: String by lazy { sha256(file) }

    init {
        GZIPInputStream(file.inputStream()).bufferedReader(Charsets.UTF_8).useLines { seq ->
            for (line in seq) {
                val c = line.split('\t')
                if (c.size < 7 || c[0] in rows) continue
                rows[c[0]] = Row(c[2].toLong(), c[3].toDouble(), c[6])
            }
        }
    }

    fun flags(key: String): String = rows[key]?.flags?.ifEmpty { "none" } ?: "-"

    /** A form of the language by its flags (05b §12.1): Wiktionary, abbreviation rule or spelling dictionary. */
    fun isForm(key: String): Boolean? {
        val f = rows[key]?.flags ?: return null
        return f.split(',').any { it == "wiktionary" || it == "abbreviation" || it == "spelling" || it == "lemma-only" }
    }
}

internal fun sha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

internal fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
