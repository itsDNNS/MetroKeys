package app.metrokeys.engine.predict

import app.metrokeys.engine.TestDictionaries
import app.metrokeys.engine.query
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contacts as a word source, spec 05 §13. */
class ContactNamesTest {
    @Test
    fun filter() {
        assertEquals("Anna Schmidt", ContactNames.filter("Dr. Anna R2D2 Schmidt"))
        assertEquals("Hans-Peter O'Neil", ContactNames.filter("  Hans-Peter   O'Neil "))
        assertNull(ContactNames.filter("123 @x"))
        assertNull(ContactNames.filter("A".repeat(128)))
    }

    @Test
    fun storage() {
        val c = ContactNames.build(listOf("Hans-Peter Müller", "Anna Schmidt", "Anna Lena Meier"))
        assertEquals(1, c.count("Hans-Peter"))
        assertEquals(1, c.count("Hans"))
        assertEquals(1, c.count("Peter"))
        assertEquals(2, c.count("Anna"))
        // tokens: 2 + 2 + 3 = 7, plus the two hyphen parts
        assertEquals(9, c.totalCount)
        assertNull(c.followers("hans-peter"))
        assertEquals(mapOf("Schmidt" to 1, "Lena" to 1), c.followers("anna"))
        assertEquals(mapOf("Meier" to 1), c.followers("anna lena"))
    }

    @Test
    fun costExample() {
        // 05 §13 (de, anchors of §3.2): n = 1, T = 600 → p = 1e-4 → cost 163 → 232 (163 in a name field)
        val names = listOf("Zora") + List(599) { "Name${'a' + it % 26}${'a' + it / 26}" }
        val c = ContactNames.build(names)
        val scale = TestDictionaries.german().scale
        assertEquals(600, c.totalCount)
        assertEquals(232, c.cost("Zora", nameField = false, scale))
        assertEquals(163, c.cost("Zora", nameField = true, scale))
        assertEquals(ContactNames.NO_COST, c.cost("Unbekannt", false, scale))
    }

    @Test
    fun usesInTheEngine() {
        val e = TestDictionaries.german()
        assertFalse(e.isKnown("Xaverine"))
        e.contacts = ContactNames.build(listOf("Xaverine Quast"))
        assertTrue(e.isKnown("Xaverine"))
        assertTrue(e.isKnown("Xaverine's"))
        assertTrue(e.isKnown("Xaverine'"))
        assertTrue(e.isKnown("Xaverine’s"))
        assertFalse(e.isKnown("xaverine"))
        assertTrue(e.query("Hallo Xaver").ranked.any { it.display == "Xaverine" })
    }
}
