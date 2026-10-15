package app.metrokeys.engine.lexicon

import app.metrokeys.engine.TestDictionaries
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runtime data operations of spec 05b §6. */
class LexiconTest {
    private val small = Lexicon.fromEntries(
        "de-DE", 16f, 1000, 100000,
        mapOf(
            "leben" to listOf(WordForm("leben", 188), WordForm("Leben", 183)),
            "haus" to listOf(WordForm("Haus", 183)),
            "hausen" to listOf(WordForm("hausen", 90)),
            "haut" to listOf(WordForm("Haut", 150)),
            "hut" to listOf(WordForm("Hut", 145)),
            "berlin" to listOf(WordForm("Berlin", 170)),
            "a" to listOf(WordForm("a", 10)),
        ),
    )

    @Test
    fun formsAndExact() {
        assertEquals(listOf("leben", "Leben"), small.forms("LEBEN").map { it.text })
        assertTrue(small.exact("Leben"))
        assertFalse(small.exact("LEBEN"))
        assertEquals("Berlin", small.canonical("berlin"))
        assertEquals("leben", small.canonical("leben"))
        assertEquals("Leben", small.canonical("Leben"))
        assertNull(small.canonical("xyz"))
    }

    @Test
    fun frequency() {
        assertEquals(183, small.frequency("Leben"))
        assertEquals(188, small.frequency("leben"))
        assertEquals(188, small.frequency("LEBEN"))
        assertEquals(183, small.frequency("HAUS"))
        assertEquals(188, small.frequency("lEBEN"))
        assertEquals(-1, small.frequency("xyz"))
    }

    @Test
    fun completions() {
        assertEquals(listOf("Haus", "Haut", "Hut", "hausen"), small.completions("h", 10).map { it.text })
        assertEquals(listOf("Haus", "Haut"), small.completions("HA", 2).map { it.text })
        assertEquals(emptyList(), small.completions("x", 3))
        assertEquals(7, small.completions("", 100).size)
    }

    @Test
    fun fuzzy() {
        val r = small.fuzzy("hsus", 2, 10)
        assertEquals("Haus", r.first().form.text)
        assertEquals(1, r.first().distance)
        assertEquals(listOf("Haus" to 1, "Haut" to 2, "Hut" to 2), r.map { it.form.text to it.distance }.take(3))
        assertEquals(listOf("Haus" to 1), small.fuzzy("ahus", 1, 10).map { it.form.text to it.distance })
        assertTrue(small.fuzzy("qqqqq", 2, 10).isEmpty())
    }

    @Test
    fun fuzzyMatchesABruteForceScan() {
        val lex = TestDictionaries.de.lexicon
        val brute = lex.words().map { it.text.lowercase() }.distinct()
            .filter { app.metrokeys.engine.predict.EditDistance.units("dnan", it) <= 2 && unitOsa("dnan", it) <= 2 }.toSet()
        val viaTrie = lex.fuzzy("dnan", 2, 100000).map { it.form.text.lowercase() }.toSet()
        // every key within distance 2 is found (forms are best forms of the keys)
        val keys = (0 until lex.keyCount).map { lex.keyString(it) }.filter { unitOsa("dnan", it) <= 2 }.toSet()
        assertEquals(keys.map { lex.bestForm(lex.keyIndex(it)).text.lowercase() }.toSet(), viaTrie)
        assertTrue(brute.isNotEmpty())
    }

    private fun unitOsa(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }

    @Test
    fun pairs() {
        val p = PairTable.fromTriples(small, listOf(Triple("", "Haus", 200), Triple("leben", "Haus", 100), Triple("leben", "Berlin", 120)))
        assertEquals(200, p.bigram("", "haus"))
        assertEquals(100, p.bigram("LEBEN", "Haus"))
        assertEquals(-1, p.bigram("haus", "leben"))
        assertEquals(listOf("Berlin", "Haus"), p.followers("leben", 5).map { it.text })
    }

    @Test
    fun bundledWordsAndAllForms() {
        val lex = TestDictionaries.de.lexicon
        assertEquals(lex.formCount, lex.words().count())
        assertTrue(lex.keyCount in 230_000..245_000)
        assertTrue(lex.forms("haus").single().text == "Haus")
        val en = TestDictionaries.en.lexicon
        assertEquals(listOf("I'm"), en.forms("im").map { it.text }.take(1))
        assertEquals("a", en.canonical("a"))
        assertEquals("I", en.canonical("i"))
        assertTrue(en.exact("o'clock"))
        assertFalse(en.exact("colour"))
        assertNotNull(TestDictionaries.en.pairs)
    }

    @Test
    fun mismatchingPairTableIsRefused() {
        val cls = Dictionary::class.java
        fun res(name: String) = cls.getResourceAsStream("/app/metrokeys/engine/dict/$name")!!
        val en = Lexicon.read(res("en-US.words.gz"))
        assertFailsWith<PairTableMismatchException> { PairTable.read(res("de-DE.pairs.gz"), en) }
        val d = Dictionary.load(res("en-US.words.gz"), res("de-DE.pairs.gz"))
        assertNull(d.pairs)
        // a word list with one changed byte (same counts) is refused as well
        val payload = GZIPInputStream(res("en-US.words.gz")).readBytes()
        payload[payload.size - 2] = (payload[payload.size - 2] + 1).toByte()
        val bytes = ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write(payload) } }.toByteArray()
        val changed = runCatching { Lexicon.read(ByteArrayInputStream(bytes)) }.getOrNull()
        if (changed != null) assertFailsWith<PairTableMismatchException> { PairTable.read(res("en-US.pairs.gz"), changed) }
    }

    @Test
    fun loadTimeIsModest() {
        val cls = Dictionary::class.java
        val t0 = System.nanoTime()
        Dictionary.load(cls.getResourceAsStream("/app/metrokeys/engine/dict/de-DE.words.gz")!!,
            cls.getResourceAsStream("/app/metrokeys/engine/dict/de-DE.pairs.gz"))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 3000, "load took $ms ms")
    }
}

class LicenseTextTest {
    @Test
    fun attributionTextsAreBundled() {
        val de = Dictionary.bundledLicenseText("de-DE")!!
        assertTrue("FrequencyWords" in de && "Wiktionary" in de && "Tatoeba" in de && "CC BY-SA 4.0" in de)
        val en = Dictionary.bundledLicenseText("en-US")!!
        val notice = Dictionary::class.java.getResourceAsStream("/app/metrokeys/engine/dict/SCOWL-Copyright.txt")!!
            .readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(en.endsWith(notice))
        assertTrue("SCOWL" in en)
    }
}
