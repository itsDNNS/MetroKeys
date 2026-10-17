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
    fun derivedDataOfALexiconBuiltFromEntries() {
        // 05b §1.1: without a version-3 header the lexicon derives the data from its classes (the class standing in for
        // the form count): every form of class ≥ 1 is offered (fewer than SUGGEST_MAX_RANK forms), anchors at the ranks
        // of the language's row, the guard class of the last form (fewer than AC_SPLIT_RANK forms)
        assertFalse(small.derivedFromFile)
        for (k in 0 until small.keyCount) for (f in 0 until small.formCountOf(k)) assertTrue(small.isOffered(k, f))
        assertEquals(listOf(188 to 112, 183 to 118, 10 to 254), small.costAnchors)
        assertEquals(10, small.guardClass)
        val zero = Lexicon.fromEntries("de-DE", 16f, 1000, 100000,
            mapOf("heure" to listOf(WordForm("heure", 0)), "heute" to listOf(WordForm("heute", 200))), flagged = setOf("heute"))
        assertFalse(zero.isOfferedWord("heure"))
        assertTrue(zero.isOfferedWord("heute"))
        assertTrue(zero.isFlaggedWord("heute"))
        assertFalse(zero.isFlaggedWord("heure"))
    }

    @Test
    fun derivedDataOfTheBundledLists() {
        // 0.7.1 lists carry the derived data (version 3): SUGGEST_MAX_RANK offered forms, flags from the register labels
        for (d in listOf(TestDictionaries.de, TestDictionaries.en, TestDictionaries.es, TestDictionaries.pt, TestDictionaries.it,
            TestDictionaries.fr)) {
            val lex = d.lexicon
            assertTrue(lex.derivedFromFile, lex.languageTag)
            assertEquals(90000, lex.offeredCount, lex.languageTag)
            assertTrue(lex.flaggedCount > 0, lex.languageTag)
            // the bound of a node is the highest offered class below it
            assertTrue(lex.maxOfferedClassOf(lex.root) <= lex.maxClassOf(lex.root))
        }
        // German: a form beyond SUGGEST_MAX_RANK stays valid (05 §17.7a: "performant")
        val de = TestDictionaries.de.lexicon
        assertTrue(de.exact("performant"))
        assertFalse(de.isOfferedWord("performant"))
        assertTrue(de.isOfferedWord("Haus"))
    }

    @Test
    fun casedFormsInCodeUnitOrder() {
        // 05d §7: the cased forms walked as a tree in code-unit order, every form once
        val lex = Lexicon.fromEntries("de-DE", 16f, 1000, 100000, mapOf(
            "haus" to listOf(WordForm("Haus", 183), WordForm("HAUS", 20)),
            "hausen" to listOf(WordForm("hausen", 90)),
            "iphone" to listOf(WordForm("iPhone", 100)),
            "dont" to listOf(WordForm("don't", 120)),
            "ärger" to listOf(WordForm("Ärger", 150), WordForm("ärger", 20)),
            "a" to listOf(WordForm("a", 10), WordForm("A", 9)),
        ))
        val walked = ArrayList<String>()
        val forms = CasedForms(lex)
        fun walk(kn: Int, modes: Int, en: Int, depth: Int, prefix: String) {
            val lv = CasedForms.Level()
            forms.children(kn, modes, en, depth, lv)
            val n = lv.size
            val chars = lv.char.copyOf(n)
            val k = lv.keyNode.copyOf(n)
            val m = lv.modes.copyOf(n)
            val e = lv.expNode.copyOf(n)
            val sp = IntArray(n) { forms.spellingAt(lv, it) }
            val hc = lv.hasChildren.copyOf(n)
            for (i in 0 until n) {
                val text = prefix + chars[i]
                if (sp[i] >= 0) walked.add(text)
                if (hc[i]) walk(k[i], m[i], e[i], depth + 1, text)
            }
        }
        walk(0, forms.rootModes, 0, 0, "")
        val expected = lex.words().map { it.text }.toSortedSet().toList()
        assertEquals(expected, walked)
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
        assertTrue(lex.keyCount in 405_000..420_000) // 0.7.0 (05b §12); 0.6.6–0.6.9: 315 000–325 000
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
        assertTrue("FrequencyWords" in de && "Wiktionary" in de && "Tatoeba" in de)
        // 0.7.0 (05b §2): the rebuilt data files are GPL-3.0-only; the CC BY-SA 4.0 sources keep their attribution and
        // the spelling dictionaries their notices
        for (tag in listOf("en-US", "es-ES", "pt-BR", "it-IT", "fr-FR")) {
            val t = Dictionary.bundledLicenseText(tag)!!
            assertTrue("GNU General Public License, version 3 only" in t && "CC BY-SA 4.0 / GPLv3" in t && "wordfreq" in t, tag)
            if (tag != "en-US") assertTrue("--- Spelling dictionary" in t, tag)
        }
        val en = Dictionary.bundledLicenseText("en-US")!!
        val notice = Dictionary::class.java.getResourceAsStream("/app/metrokeys/engine/dict/SCOWL-Copyright.txt")!!
            .readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(en.endsWith(notice))
        assertTrue("SCOWL" in en)
    }
}
