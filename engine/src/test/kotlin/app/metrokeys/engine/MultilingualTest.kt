package app.metrokeys.engine

import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Multilingual typing, spec 05 §14. */
class MultilingualTest {
    private fun weights(e: PredictionEngine, text: String) =
        e.languageWeights(text.split(" ").filter { it.isNotEmpty() }.reversed())

    @Test
    fun languageWeightExamples() {
        val e = TestDictionaries.multilingual()
        assertTrue(weights(e, "Ich habe heute leider keine Zeit").getValue("de-DE") > 0.95)
        assertTrue(weights(e, "I think we should meet tomorrow").getValue("en-US") > 0.95)
        val empty = weights(e, "")
        assertEquals(0.65, empty.getValue("de-DE"), 1e-12)
        assertEquals(0.35, empty.getValue("en-US"), 1e-12)
        val vd = weights(e, "Vielen Dank")
        assertEquals(0.999865, vd.getValue("de-DE"), 1e-6)
        assertEquals(0.000135, vd.getValue("en-US"), 1e-6)
        // the selected language gets the prior
        e.activeLanguage = "en-US"
        assertEquals(0.65, weights(e, "").getValue("en-US"), 1e-12)
        // words with digits are skipped
        assertEquals(0.65, weights(e, "abc1 x2").getValue("en-US"), 1e-12)
    }

    @Test
    fun foreignWordBlockNumbers() {
        val e = TestDictionaries.multilingual()
        val de = e.language("de-DE")!!
        val en = e.language("en-US")!!
        val w = weights(e, "Vielen Dank")
        val german = de.languageLog10("für") + log10(w.getValue("de-DE")) - 2
        val english = en.languageLog10("fur") + log10(w.getValue("en-US"))
        assertEquals(-4.322, german, 0.001)
        assertEquals(-8.911, english, 0.002)
        assertEquals("für", e.query(Query("Vielen Dank fur")).autoCorrection)
    }

    @Test
    fun foreignWordsAreNotCorrected() {
        val e = TestDictionaries.multilingual()
        val de = TestDictionaries.single(TestDictionaries.german())
        for ((typed, germanFix) in listOf("have" to "habe", "think" to "trink", "where" to "wehre")) {
            assertEquals(germanFix, de.query(Query(typed)).autoCorrection, typed)
            assertNull(e.query(Query(typed)).autoCorrection, typed)
        }
        // a rare English word does not block
        assertEquals("gut", e.query(Query("hut")).autoCorrection)
    }

    @Test
    fun onlyTheTopLanguageCorrects() {
        val e = TestDictionaries.multilingual()
        assertEquals("Dann", e.query(Query("Dnan")).autoCorrection)
        assertEquals("de-DE", e.query(Query("Dnan")).autoCorrectionLanguage)
        e.activeLanguage = "en-US"
        assertEquals("Dan", e.query(Query("Dnan")).autoCorrection)
    }

    @Test
    fun mergedBar() {
        val e = TestDictionaries.multilingual()
        val p = e.query(Query("Ich habe heute ein meeting"))
        assertEquals("Meeting", p.suggestions.first().text)
        assertTrue(p.suggestions.first().isAutoCorrection)
        assertTrue(p.suggestions.size <= 11)
        assertEquals(p.suggestions.map { it.text }.distinct(), p.suggestions.map { it.text })
        assertTrue(p.typedIsValid) // valid in English
        val next = e.query(Query("I would like to thank "))
        assertTrue("you" in next.suggestions.take(3).map { it.text })
        val nextDe = e.query(Query("Vielen "))
        assertEquals("Dank", nextDe.suggestions.first().text)
    }

    @Test
    fun learningGoesToTheRightLanguage() {
        val e = TestDictionaries.multilingual()
        val de = e.language("de-DE")!!
        val en = e.language("en-US")!!
        e.learn("Haus", listOf("ein"))
        assertEquals(1, de.user.count("Haus"))
        e.learn("great", listOf("very"))
        assertEquals(1, en.user.count("great"))
        // unknown everywhere: the language of the previous words
        e.learn("Zwuckel", listOf("I", "think"))
        assertEquals(1, en.user.count("Zwuckel"))
        e.learn("Zwockel", listOf("Ich", "glaube"))
        assertEquals(1, de.user.count("Zwockel"))
        // rejection goes to all languages ("Hsus" is unknown: one undo suffices)
        e.rejectAutoCorrection("Hsus", "Haus")
        assertTrue(de.user.isRejected("Hsus") && en.user.isRejected("Hsus"))
        // forgetting removes the word from every language
        assertTrue(e.forget("Zwuckel"))
        assertEquals(0, en.user.count("Zwuckel"))
        assertTrue(e.forget("Hsus"))
        assertEquals(0, de.user.undoCount("Hsus") + en.user.undoCount("Hsus"))
        e.deleteLearnedData()
        assertEquals(0, de.user.wordCount + en.user.wordCount)
    }

    @Test
    fun proofingKnowsBothLanguages() {
        val e = TestDictionaries.multilingual()
        assertTrue(e.isKnown("Haus"))
        assertTrue(e.isKnown("receive"))
        e.settings = e.settings.copy(multilingual = false)
        assertTrue(!e.isKnown("receive"))
    }

    @Test
    fun threeLanguages() {
        // 05 §14.1 / §14.5: N = 3, es selected, empty context: 0.65 / 0.175 / 0.175
        val e = TestDictionaries.multilingual("es-ES", TestDictionaries.german(), TestDictionaries.english(), TestDictionaries.spanish())
        val empty = weights(e, "")
        assertEquals(0.65, empty.getValue("es-ES"), 1e-12)
        assertEquals(0.175, empty.getValue("de-DE"), 1e-12)
        assertEquals(0.175, empty.getValue("en-US"), 1e-12)
        assertTrue(weights(e, "Muchas gracias por todo").getValue("es-ES") > 0.95)
        e.activeLanguage = "de-DE"
        assertTrue(weights(e, "Muchas gracias por todo").getValue("es-ES") > 0.95)
        assertTrue(weights(e, "Ich habe heute leider keine Zeit").getValue("de-DE") > 0.95)
        assertTrue(weights(e, "I think we should meet tomorrow").getValue("en-US") > 0.95)
        // corrections follow the language of the sentence
        assertEquals("für", e.query(Query("Vielen Dank fur")).autoCorrection)
        assertEquals("mañana", e.query(Query("Nos vemos hasta manana")).autoCorrection)
        // suggestions come from all three languages
        val tags = e.query(Query("Dan")).suggestions.mapNotNull { it.languageTag }.toSet()
        assertEquals(setOf("de-DE", "en-US", "es-ES"), tags)
    }

    @Test
    fun geometryOfTheCurrentPageForEveryLanguage() {
        // 05 §14.4: on the Spanish page the German engine finds ü on the u key, on the German page ñ lives on n
        val e = TestDictionaries.multilingual("es-ES", TestDictionaries.german(), TestDictionaries.spanish())
        e.setGeometry(app.metrokeys.engine.touch.KeyboardGeometry.fallback("es-ES"))
        val de = e.language("de-DE")!!
        assertEquals(de.geometry.ownKey('u'), de.geometry.touchKey('ü'))
        assertEquals('ñ', de.geometry.touchKey('ñ')!!.letter)
        e.setGeometry(app.metrokeys.engine.touch.KeyboardGeometry.fallback("de-DE"))
        val es = e.language("es-ES")!!
        assertEquals(es.geometry.ownKey('n'), es.geometry.touchKey('ñ'))
        assertEquals("mañana", e.query(Query("Hasta manana")).autoCorrection)
    }
}
