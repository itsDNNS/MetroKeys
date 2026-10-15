package app.metrokeys.engine

import app.metrokeys.engine.predict.Params

import app.metrokeys.engine.text.FieldInfo
import app.metrokeys.engine.text.FieldPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Candidate bar of spec 05 §9 and policies of §15. */
class CandidateBarTest {
    private fun de() = TestDictionaries.single(TestDictionaries.german())

    @Test
    fun autocorrectionFirstTypedWordNeverShown() {
        val p = de().query(Query("Das ist das haus"))
        assertEquals("Haus", p.autoCorrection)
        assertEquals("Haus", p.suggestions.first().text)
        assertTrue(p.suggestions.first().isAutoCorrection)
        assertEquals(1, p.suggestions.count { it.isAutoCorrection })
        assertTrue(p.suggestions.none { it.text == "haus" })
        assertTrue(p.suggestions.size <= 11)
        assertEquals(p.suggestions.map { it.text }.distinct(), p.suggestions.map { it.text })
        assertFalse(p.typedIsValid)
        // scores descend after the autocorrection
        val rest = p.suggestions.drop(1).filter { it.kind != SuggestionKind.EMOJI }
        assertEquals(rest.sortedByDescending { it.score }, rest)
    }

    @Test
    fun emojiSuggestion() {
        val e = de()
        val p = e.query(Query("Ich trinke Kaffee"))
        assertEquals("☕", p.suggestions.last().text)
        assertEquals(SuggestionKind.EMOJI, p.suggestions.last().kind)
        assertEquals(1, p.suggestions.count { it.kind == SuggestionKind.EMOJI })
        val ac = e.query(Query("Das ist das Hsus"))
        assertEquals("Haus", ac.autoCorrection)
        assertEquals("🏠", ac.suggestions.last().text)
        assertEquals(ac.suggestions[ac.suggestions.size - 2].score, ac.suggestions.last().score)
        val next = e.query(Query("Ich trinke Kaffee "))
        assertEquals("☕", next.suggestions.last().text)
        e.settings = e.settings.copy(emojiSuggestions = false)
        assertTrue(e.query(Query("Ich trinke Kaffee")).suggestions.none { it.kind == SuggestionKind.EMOJI })
        val url = e.query(Query("Kaffee", field = FieldInfo(FieldPolicy.URL)))
        assertTrue(url.suggestions.none { it.kind == SuggestionKind.EMOJI })
    }

    @Test
    fun settingsAndLimits() {
        val e = de()
        e.settings = EngineSettings(suggestions = false, autocorrect = true)
        val p = e.query(Query("Das ist das haus"))
        assertTrue(p.suggestions.isEmpty())
        assertEquals("Haus", p.autoCorrection)
        e.settings = EngineSettings(suggestions = false, autocorrect = false)
        val q = e.query(Query("Das ist das haus"))
        assertTrue(q.suggestions.isEmpty())
        assertNull(q.autoCorrection)
        e.settings = EngineSettings(suggestions = true, autocorrect = false)
        val r = e.query(Query("Das ist das haus"))
        assertNull(r.autoCorrection)
        assertEquals("Haus", r.suggestions.first().text)
        assertFalse(r.suggestions.first().isAutoCorrection)

        e.settings = EngineSettings()
        val long = e.query(Query("Donaudampfschifffahrtsgesellschaftskapitän"))
        assertTrue(long.suggestions.isEmpty())
        assertNull(long.autoCorrection)
        assertTrue(e.query(Query("hal", after = "lo")).suggestions.isEmpty())
    }

    @Test
    fun policies() {
        val e = de()
        for (p in listOf(FieldPolicy.PASSWORD, FieldPolicy.NUMBER, FieldPolicy.PHONE)) {
            val r = e.query(Query("Das ist das haus", field = FieldInfo(p)))
            assertTrue(r.suggestions.isEmpty(), "$p")
            assertNull(r.autoCorrection, "$p")
            assertTrue(e.query(Query("Vielen ", field = FieldInfo(p))).suggestions.isEmpty())
        }
        for (p in listOf(FieldPolicy.SEARCH, FieldPolicy.URL, FieldPolicy.EMAIL)) {
            val r = e.query(Query("Das ist das haus", field = FieldInfo(p)))
            assertNull(r.autoCorrection, "$p")
            assertTrue(r.suggestions.isNotEmpty(), "$p")
        }
        // no learning in URL fields
        e.learn("Zwuckel", emptyList(), FieldInfo(FieldPolicy.URL))
        assertEquals(0, e.active.user.count("Zwuckel"))
        e.settings = EngineSettings(learning = false)
        e.learn("Zwuckel", emptyList())
        assertEquals(0, e.active.user.count("Zwuckel"))
        assertFalse(e.addWord("Zwuckel"))
    }

    @Test
    fun addToDictionary() {
        val e = de()
        assertFalse(e.isKnown("Zwuckel"))
        assertTrue(e.addWord("Zwuckel"))
        assertTrue(e.isKnown("Zwuckel"))
        assertNull(e.query(Query("Das Zwuckel")).autoCorrection)
        assertTrue(e.query(Query("Das Zwuc")).suggestions.any { it.text == "Zwuckel" })
    }

    @Test
    fun resumeCorrections() {
        val e = de()
        val list = e.resumeCorrections("Das ist das hsus")
        assertEquals("Haus", list.first().text)
        assertTrue(list.all { it.kind == SuggestionKind.CORRECTION })
        assertEquals(listOf("Haus"), e.spellingSuggestions("Hsus", 5).take(1))
    }

    @Test
    fun learnedDataPersistence() {
        val store = object : LearnedDataStore {
            val data = HashMap<String, ByteArray>()
            override fun load(languageTag: String) = data[languageTag]
            override fun save(languageTag: String, data: ByteArray) {
                this.data[languageTag] = data
            }
        }
        val e = de()
        repeat(Params.LEARN_MIN_UNKNOWN) { e.learn("Zwuckel", emptyList()) }
        e.saveLearnedData(store)
        val f = de()
        assertFalse(f.isKnown("Zwuckel"))
        f.loadLearnedData(store)
        assertTrue(f.isKnown("Zwuckel"))
        store.data["de-DE"] = byteArrayOf(9, 9, 9)
        f.loadLearnedData(store)
        assertFalse(f.isKnown("Zwuckel"))
    }

    @Test
    fun allCapsDisplay() {
        val e = de()
        val p = e.query(Query("DAS IST DAS HSUS"))
        assertEquals("HAUS", p.autoCorrection)
        assertTrue(e.query(Query("HAUS")).typedIsValid)
        val next = e.query(Query("ICH HABE "))
        assertTrue(next.suggestions.filter { it.kind == SuggestionKind.PREDICTION }.all { it.text == it.text.uppercase() })
        val start = e.query(Query("Hallo. "))
        assertTrue(start.suggestions.first().text[0].isUpperCase())
    }
}
