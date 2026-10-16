package app.metrokeys.keyboard

import app.metrokeys.keyboard.layout.Hit
import app.metrokeys.keyboard.layout.HitTester
import app.metrokeys.keyboard.layout.Hysteresis
import app.metrokeys.keyboard.layout.Key
import app.metrokeys.keyboard.layout.KeyCells
import app.metrokeys.keyboard.layout.KeyRole
import app.metrokeys.keyboard.layout.LayoutSet
import app.metrokeys.keyboard.layout.LetterCorrection
import app.metrokeys.keyboard.layout.Page
import app.metrokeys.keyboard.layout.PxRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Hit testing (03 §1.1), letter correction (§1.1a) and hysteresis areas (§1.2) with the worked examples of the spec. */
class HitTestTest {
    private fun page(file: String, id: String = "text.letters"): Page = LayoutSet.parse(File("../spec/data/$file").readText()).page(id)

    private val de = KeyCells(page("layouts-de.json"), 1080f, 756f)
    private val en = KeyCells(page("layouts-en.json"), 1080f, 756f)
    private val fr = KeyCells(page("layouts-fr.json"), 1080f, 756f)

    private fun name(k: Key?): String? = when (k?.role) {
        null -> null
        KeyRole.CHAR -> k.output
        else -> k.role.name
    }

    private fun down(c: KeyCells, x: Float, y: Float, tester: HitTester = HitTester(), t: Long = 0, fast: Boolean = false): Hit? =
        tester.hit(c, x, y, t, touchDown = true, outside = 40f, fastTyping = fast)

    private fun moving(c: KeyCells, x: Float, y: Float): Hit? = HitTester().hit(c, x, y, 0, touchDown = false, outside = 0f, fastTyping = false)

    @Test
    fun cellsAreWholePixels() {
        // columns of 108 px, rows of 189 px, the row-2 indent 54 px (03 §1.1 rule 1)
        val a = de.cell(de.keys.first { it.output == "a" })
        assertEquals(PxRect(54f, 189f, 162f, 378f), a)
        val m = de.cell(de.keys.first { it.output == "m" })
        assertEquals(PxRect(810f, 378f, 918f, 567f), m)
        val d = de.cell(de.keys.first { it.output == "d" })
        assertEquals(PxRect(270f, 189f, 378f, 378f), d)
        val space = de.cell(de.keys.first { it.role == KeyRole.SPACE })
        assertEquals(PxRect(378f, 567f, 810f, 756f), space)
    }

    @Test
    fun workedExamplesOfTheGermanLetterPage() {
        val cases = listOf(
            Triple(590f, 280f, "g"), Triple(594f, 280f, "g"), Triple(597f, 280f, "h"),
            Triple(500f, 186f, "t"), Triple(500f, 189f, "t"), Triple(500f, 192f, "g"),
            Triple(20f, 280f, "a"), Triple(20f, 200f, "q"), Triple(0f, 216f, "q"), Triple(0f, 217f, "a"),
            Triple(30f, 368f, "SHIFT"), Triple(1060f, 280f, "l"), Triple(1070f, 205f, "p"), Triple(1050f, 370f, "BACKSPACE"),
            Triple(500f, 780f, "SPACE"), Triple(850f, 790f, "."),
        )
        for ((x, y, want) in cases) assertEquals("($x, $y)", want, name(down(de, x, y)?.key))
        assertNull(down(de, 500f, 800f))
        // distances of the table
        assertEquals(34f, down(de, 20f, 280f)!!.distance)
        assertEquals(22f, down(de, 20f, 200f)!!.distance)
        assertEquals(20f, down(de, 30f, 368f)!!.distance)
        assertEquals(24f, down(de, 500f, 780f)!!.distance)
        assertEquals(34f, down(de, 850f, 790f)!!.distance)
        assertTrue(down(de, 594f, 280f)!!.inCell)
    }

    @Test
    fun azertyHasNoCellFreeArea() {
        assertEquals("q", name(down(fr, 5f, 280f)?.key))
        assertEquals("m", name(down(fr, 1075f, 300f)?.key))
        assertTrue(down(fr, 5f, 280f)!!.inCell)
    }

    @Test
    fun theIndentRuleOfRule9() {
        // left indent: q when 2·(y − 189) ≤ 54 − x, Shift when 2·(378 − y) < 54 − x, else a
        for (y in 190..377) for (x in 0..53) {
            val xf = x.toFloat()
            val yf = y.toFloat()
            val want = when {
                2 * (y - 189) <= 54 - x -> "q"
                2 * (378 - y) < 54 - x -> "SHIFT"
                else -> "a"
            }
            assertEquals("($x, $y)", want, name(down(de, xf, yf)?.key))
            val wantRight = when {
                2 * (y - 189) <= (1080 - x) - 1026 -> "p"
                2 * (378 - y) < (1080 - x) - 1026 -> "BACKSPACE"
                else -> "l"
            }
            assertEquals("(${1080 - x}, $y)", wantRight, name(down(de, 1080f - xf, yf)?.key))
        }
    }

    @Test
    fun aMovingFingerOutsideTheKeyAreaHitsNoKey() {
        assertNull(moving(de, 500f, -1f))
        assertNull(moving(de, 500f, 757f))
        assertNull(moving(de, -1f, 300f))
        assertEquals("t", name(moving(de, 500f, 0f)?.key))
        // the doubled vertical distance applies to a moving finger too
        assertEquals("q", name(moving(de, 20f, 200f)?.key))
    }

    @Test
    fun disabledKeysTakePartAndAreInert() {
        val pin = KeyCells(page("layouts-de.json", "pin.numpad"), 1080f, 756f)
        val h = down(pin, 100f, 700f)!!
        assertEquals(KeyRole.BLANK, h.key.role)
        assertTrue(h.inert)
        val emoji = KeyCells(page("layouts-de.json", "text.emoji"), 1080f, 756f)
        assertTrue(moving(emoji, 300f, 300f)!!.inert)
    }

    @Test
    fun fastTypingLeavesBackspaceOut() {
        // "d", "a", then backspace at (1000, 470) 300 ms after "d": m (82) beats l (184) and Enter (194)
        val t = HitTester()
        assertEquals("d", name(down(de, 300f, 280f, t, 0, fast = true)?.key))
        assertEquals("a", name(down(de, 100f, 280f, t, 150, fast = true)?.key))
        val h = down(de, 1000f, 470f, t, 300, fast = true)!!
        assertEquals("m", name(h.key))
        assertEquals(82f, h.distance)
        // the same tap 400 ms after the "d" deletes
        val t2 = HitTester()
        down(de, 300f, 280f, t2, 0, fast = true)
        down(de, 100f, 280f, t2, 150, fast = true)
        assertEquals("BACKSPACE", name(down(de, 1000f, 470f, t2, 400, fast = true)?.key))
        // (1050, 370) while fast → l
        val t3 = HitTester()
        down(de, 300f, 280f, t3, 0, fast = true)
        down(de, 100f, 280f, t3, 100, fast = true)
        assertEquals("l", name(down(de, 1050f, 370f, t3, 200, fast = true)?.key))
        // not in other fields
        val t4 = HitTester()
        down(de, 300f, 280f, t4, 0)
        down(de, 100f, 280f, t4, 100)
        assertEquals("BACKSPACE", name(down(de, 1000f, 470f, t4, 200)?.key))
    }

    @Test
    fun fastTypingHistoryIsClearedByFunctionKeysAndOutsideHits() {
        // a shift tap clears the history: the backspace after it is not excluded
        val t = HitTester()
        down(de, 300f, 280f, t, 0, fast = true)
        down(de, 50f, 500f, t, 100, fast = true) // Shift
        down(de, 100f, 280f, t, 150, fast = true)
        assertEquals("BACKSPACE", name(down(de, 1000f, 470f, t, 250, fast = true)?.key))
        // a hit with d > 0 (the indent) clears it too
        val t2 = HitTester()
        down(de, 300f, 280f, t2, 0, fast = true)
        down(de, 20f, 280f, t2, 100, fast = true) // a, d = 34
        down(de, 100f, 280f, t2, 150, fast = true)
        assertEquals("BACKSPACE", name(down(de, 1000f, 470f, t2, 250, fast = true)?.key))
        // space and enter keep it
        val t3 = HitTester()
        down(de, 300f, 280f, t3, 0, fast = true)
        down(de, 500f, 650f, t3, 100, fast = true) // space
        assertEquals("m", name(down(de, 1000f, 470f, t3, 200, fast = true)?.key))
    }

    @Test
    fun letterCorrectionExamples() {
        // US-English letter page, current word "e": "eq…" and "ea…" both begin words
        val possible = { k: Key -> if (k.output == "q" || k.output == "a") 100 else -1 }
        fun pick(x: Float, y: Float): String? = name(LetterCorrection.choose(en, down(en, x, y)!!, x, y, 13f, 7f, possible))
        assertEquals("a", name(down(en, 45f, 195f)?.key))
        assertEquals("q", pick(45f, 195f))
        assertEquals("a", name(down(en, 40f, 200f)?.key))
        assertEquals("q", pick(40f, 200f))
        assertEquals("a", pick(20f, 280f))
        // after "hau": a touch on "y" 5 px below "s" → "s" (only "haus…" begins a word)
        val haus = { k: Key -> if (k.output == "s") 120 else -1 }
        val h = down(de, 216f, 383f)!!
        assertEquals("y", name(h.key))
        val chosen = LetterCorrection.choose(de, h, 216f, 383f, 13f, 7f, haus)
        assertEquals("s", chosen.output)
        // its hysteresis area: x 162–270, y 189–393
        assertEquals(PxRect(162f, 189f, 270f, 393f), Hysteresis.ofCorrected(de.cell(chosen), 216f, 383f, 10f))
        // a possible touched key inside its cell is kept
        assertEquals("y", LetterCorrection.choose(de, h, 216f, 383f, 13f, 7f) { 50 }.output)
    }

    @Test
    fun hysteresisAreas() {
        val d = de.cell(de.keys.first { it.output == "d" })
        assertEquals(PxRect(260f, 179f, 388f, 388f), Hysteresis.of(d, 300f, 280f, 10f, 20f))
        val a = de.cell(de.keys.first { it.output == "a" })
        assertEquals(PxRect(0f, 179f, 172f, 388f), Hysteresis.of(a, 20f, 280f, 10f, 20f))
        // the slide from a (100, 280) to (30, 280): leaves 44–172, gets a back with the area 10–172 × 179–388
        val area = Hysteresis.of(a, 100f, 280f, 10f, 20f)
        assertEquals(44f, area.left)
        assertTrue(!area.contains(30f, 280f))
        assertEquals("a", name(moving(de, 30f, 280f)?.key))
        assertEquals(PxRect(10f, 179f, 172f, 388f), Hysteresis.of(a, 30f, 280f, 10f, 20f))
        // the boundary is inside
        assertTrue(Hysteresis.of(d, 300f, 280f, 10f, 20f).contains(388f, 388f))
    }
}
