package app.metrokeys.engine

import app.metrokeys.engine.touch.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Prefix lookup for the key choice at touch-down (03 §1.1a) and the public JSON reader. */
class PrefixClassTest {
    private val engine by lazy { TestDictionaries.single(TestDictionaries.german()) }

    @Test
    fun exampleOfTheSpec() {
        // after "hau" a touch between y and s: "hauy…" begins no word, "haus…" does
        assertEquals(-1, engine.prefixClass("hauy"))
        assertTrue(engine.prefixClass("haus") >= 0)
        assertTrue(engine.prefixClass("Haus") >= 0, "case-insensitive")
    }

    @Test
    fun jsonReader() {
        @Suppress("UNCHECKED_CAST")
        val m = Json.parse("""{"a": [1, "xé"], "b": null, "c": true}""") as Map<String, Any?>
        assertEquals(listOf(1.0, "xé"), m["a"])
        assertEquals(null, m["b"])
        assertEquals(true, m["c"])
    }
}
