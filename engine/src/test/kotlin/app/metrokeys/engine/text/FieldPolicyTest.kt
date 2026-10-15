package app.metrokeys.engine.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Android editor → policy mapping of spec 05 §15. */
class FieldPolicyTest {
    private fun map(type: Int, ime: Int = 0) = FieldPolicy.fromAndroid(type, ime)

    @Test
    fun mapping() {
        assertEquals(FieldPolicy.NUMBER, map(0x2).policy)
        assertEquals(FieldPolicy.PASSWORD, map(0x12).policy)
        assertEquals(FieldPolicy.PHONE, map(0x3).policy)
        assertEquals(FieldPolicy.NUMBER, map(0x4).policy)
        assertEquals(FieldPolicy.URL, map(0x11).policy)
        assertEquals(FieldPolicy.EMAIL, map(0x21).policy)
        assertEquals(FieldPolicy.EMAIL, map(0xd1).policy)
        assertEquals(FieldPolicy.PASSWORD, map(0x81).policy)
        assertEquals(FieldPolicy.PASSWORD, map(0x91).policy)
        assertEquals(FieldPolicy.PASSWORD, map(0xe1).policy)
        val name = map(0x61)
        assertEquals(FieldPolicy.DEFAULT, name.policy)
        assertTrue(name.nameField)
        assertEquals(FieldPolicy.DEFAULT, map(0x71).policy)
        assertEquals(FieldPolicy.DEFAULT, map(0xc1).policy)
        assertEquals(FieldPolicy.SEARCH, map(0xb1).policy)
        assertEquals(FieldPolicy.SEARCH, map(0x1, 3).policy)
        assertEquals(FieldPolicy.SEARCH, map(0x1, 2).policy)
        assertEquals(FieldPolicy.TEXT, map(0x1, 6).policy)
        assertEquals(FieldPolicy.TEXT, FieldPolicy.fromAndroid(0, 0, hasEditorInfo = false).policy)
    }

    @Test
    fun spellingMarks() {
        assertTrue(map(0x1).spellingMarks)
        assertFalse(map(0x41).spellingMarks) // short message
        assertFalse(map(0x1 or 0x80000).spellingMarks) // no suggestions
        assertFalse(map(0x61).spellingMarks) // default field
        assertTrue(map(0xb1).spellingMarks)
    }

    @Test
    fun policyTable() {
        assertTrue(FieldPolicy.TEXT.autocorrection)
        assertFalse(FieldPolicy.SEARCH.autocorrection)
        assertTrue(FieldPolicy.SEARCH.learning)
        assertFalse(FieldPolicy.URL.learning)
        assertTrue(FieldPolicy.URL.prediction)
        assertFalse(FieldPolicy.PASSWORD.prediction)
        assertFalse(FieldPolicy.NUMBER.prediction)
        assertFalse(FieldPolicy.DEFAULT.spellingMarks)
        assertFalse(FieldPolicy.PASSWORD.shapeWriting)
    }
}
