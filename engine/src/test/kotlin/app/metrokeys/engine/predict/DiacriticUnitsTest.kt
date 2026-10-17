package app.metrokeys.engine.predict

import app.metrokeys.engine.Query
import app.metrokeys.engine.TestDictionaries
import app.metrokeys.engine.query
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * UNITS of 05 §5: from 0.7.1 a diacritic difference counts 1 unit in languages whose `ED_DIACRITIC` is not 0 (1:1),
 * 0 units in the others (0.6.4 – 0.7.0: always 0); ED and costs are unchanged.
 */
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
    fun accentUnits() {
        // 0.7.1: an accent counts one unit where ED_DIACRITIC is not 0 (de, pt, it, fr), an ACCENT_SWAP pair too
        assertEquals(3, EditDistance.units("uberpeufe", "überprüfe", diacriticUnits = true))
        assertEquals(2, EditDistance.units("uberprufe", "überprüfe", diacriticUnits = true))
        assertEquals(1, EditDistance.units("perchè", "perché", accentSwap = true, diacriticUnits = true))
        assertEquals(1, EditDistance.units("e", "è", diacriticUnits = true))
        // the unaccented capital (fr) stays 0
        assertEquals(0, EditDistance.units("Etat", "État", capsUnaccented = true, diacriticUnits = true))
        // en, es: 0
        assertEquals(0, EditDistance.units("cancion", "canción"))
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
