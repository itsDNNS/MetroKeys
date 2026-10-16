package app.metrokeys.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/** The active set of multilingual typing with more than three languages, spec 05 §14.5. */
class ActiveLanguagesTest {
    private val five = listOf("de-DE", "en-US", "es-ES", "fr-FR", "it-IT")

    @Test
    fun allLanguagesUpToThree() {
        assertEquals(listOf("de-DE", "en-US", "es-ES"), ActiveLanguages.select(listOf("de-DE", "en-US", "es-ES"), "es-ES", emptyList()))
        assertEquals(listOf("de-DE", "es-ES"), ActiveLanguages.select(listOf("de-DE", "es-ES"), "de-DE", listOf("fr-FR")))
    }

    @Test
    fun specExampleWithFiveLanguages() {
        // recency es > de > fr > en > it, es selected: {es, de, fr}
        var recency = listOf("es-ES", "de-DE", "fr-FR", "en-US", "it-IT")
        assertEquals(listOf("de-DE", "es-ES", "fr-FR"), ActiveLanguages.select(five, "es-ES", recency))
        // Italian picked in the language picker: {it, es, de}, fr leaves
        recency = ActiveLanguages.selected(recency, "it-IT", five)
        assertEquals(listOf("it-IT", "es-ES", "de-DE", "fr-FR", "en-US"), recency)
        assertEquals(listOf("de-DE", "es-ES", "it-IT"), ActiveLanguages.select(five, "it-IT", recency))
        // a tap on the language key goes from ITA to DEU (label order, wrapping); de is in the set already
        recency = ActiveLanguages.selected(recency, "de-DE", five)
        assertEquals(listOf("de-DE", "es-ES", "it-IT"), ActiveLanguages.select(five, "de-DE", recency))
    }

    @Test
    fun neverSelectedLanguagesRankBehindInTheFixedOrder() {
        assertEquals(listOf("de-DE", "en-US", "fr-FR"), ActiveLanguages.select(five, "fr-FR", emptyList()))
        assertEquals(listOf("de-DE", "es-ES", "it-IT"), ActiveLanguages.select(five, "it-IT", listOf("es-ES")))
    }

    @Test
    fun disablingRemovesALanguageFromTheSetAndTheRecency() {
        val recency = listOf("es-ES", "de-DE", "fr-FR", "en-US", "it-IT")
        val enabled = five - "de-DE"
        // the next language by recency moves up
        assertEquals(listOf("en-US", "es-ES", "fr-FR"), ActiveLanguages.select(enabled, "es-ES", recency))
        assertEquals(listOf("es-ES", "fr-FR", "en-US", "it-IT"), ActiveLanguages.selected(recency, "es-ES", enabled))
    }

    @Test
    fun storedForm() {
        assertEquals(listOf("es-ES", "de-DE"), ActiveLanguages.parse(" es-ES,de-DE,,es-ES"))
        assertEquals(emptyList(), ActiveLanguages.parse(null))
        assertEquals("es-ES,de-DE", ActiveLanguages.format(listOf("es-ES", "de-DE")))
    }

    @Test
    fun theFiveSupportedLanguages() {
        // 0.6.2: de, en, es, it and pt enabled; the language key goes DEU → ENG → ESP → ITA → POR → DEU (01 §11.2)
        val all = app.metrokeys.engine.predict.LanguageParams.ALL.map { it.languageTag }
        assertEquals(listOf("de-DE", "en-US", "es-ES", "it-IT", "pt-BR"), all)
        var recency = emptyList<String>()
        val sets = ArrayList<List<String>>()
        for (tag in all + all.first()) {
            recency = ActiveLanguages.selected(recency, tag, all)
            sets.add(ActiveLanguages.select(all, tag, recency))
        }
        assertEquals(listOf("de-DE", "en-US", "es-ES"), sets[0])
        assertEquals(listOf("de-DE", "en-US", "es-ES"), sets[2])
        // Italian enters, German (least recently selected) leaves
        assertEquals(listOf("en-US", "es-ES", "it-IT"), sets[3])
        assertEquals(listOf("es-ES", "it-IT", "pt-BR"), sets[4])
        assertEquals(listOf("de-DE", "it-IT", "pt-BR"), sets[5])
        assertEquals(listOf("de-DE", "pt-BR", "it-IT", "es-ES", "en-US"), recency)
    }
}
