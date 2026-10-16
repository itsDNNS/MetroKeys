package app.metrokeys.engine.predict

import app.metrokeys.engine.Query
import app.metrokeys.engine.TestDictionaries
import app.metrokeys.engine.query
import kotlin.test.Test
import kotlin.test.assertEquals

/** 0.6.4: a diacritic difference counts 0 units (05 §5 UNITS, §8.1 fuzzy search); ED and costs are unchanged. */
class DiacriticUnitsTest {
    @Test
    fun units() {
        assertEquals(0, EditDistance.units("uberprufe", "überprüfe"))
        assertEquals(1, EditDistance.units("uberpeufe", "überprüfe"))
        // a typed accented letter for a plain one is still a substitution
        assertEquals(1, EditDistance.units("idéia", "ideia"))
        // ACCENT_SWAP pairs only where the language has the rule
        assertEquals(0, EditDistance.units("perchè", "perché", accentSwap = true))
        assertEquals(1, EditDistance.units("perchè", "perché"))
        // ß and ligature rules keep their unit
        assertEquals(1, EditDistance.units("strasse", "straße"))
        assertEquals(1, EditDistance.units("coeur", "cœur"))
    }

    @Test
    fun fuzzySearchFoldsAccents() {
        val m = TestDictionaries.de.lexicon.fuzzy("uberpeufe", 2, 48) { k -> EditDistance.units("uberpeufe", k) }
        assertEquals("überprüfe", m.first().form.text.lowercase())
        assertEquals(1, m.first().distance)
    }

    @Test
    fun uberpeufe() {
        val de = TestDictionaries.german()
        val r = de.query("Uberpeufe")
        println("Uberpeufe: valid=${r.validity} ac=${r.autoCorrection} corr=${r.corrections.take(4)}")
        val single = TestDictionaries.single(TestDictionaries.german())
        assertEquals("Überprüfe", single.query(Query("Uberpeufe")).autoCorrection)
        val multi = TestDictionaries.multilingual()
        assertEquals("Überprüfe", multi.query(Query("Uberpeufe")).autoCorrection)
        // with the umlaut typed it worked before as well
        assertEquals("Überprüfe", single.query(Query("Überpeufe")).autoCorrection)
    }
}
