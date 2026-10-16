package app.metrokeys.keyboard

import app.metrokeys.keyboard.layout.KeyCells
import app.metrokeys.keyboard.layout.LayoutSet
import app.metrokeys.keyboard.layout.PxRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Picker geometry, zones, slots and the opening animation (02 §5.6, §7.5; 03 §3.4). */
class PickerModelTest {
    private val layout = LayoutSet.parse(File("../spec/data/layouts-de.json").readText())
    private val letters = KeyCells(layout.page("text.letters"), 1080f, 756f)
    private val url = KeyCells(layout.page("url.letters"), 1080f, 756f)

    private fun place(cells: KeyCells, output: String, items: List<String>, cw: Float = 131f): PickerModel {
        val k = cells.keys.first { it.output == output }
        return PickerModel.place(k, cells.cell(k), items, cw, 203f, 9f, 1080f, false, 0L)
    }

    @Test
    fun theSExampleOf03Section34() {
        val p = place(letters, "s", listOf("s", "ß", "§"))
        assertEquals(192.78f, p.pickerBottom, 0.01f)
        assertEquals(221f, p.rowHitHeight)
        assertEquals(PxRect(141.5f, 189f, 290.5f, 378f), p.holdZone)
        // slots s 141.5–281.5, ß 281.5–421.5, § 421.5–561.5
        assertEquals(0, p.slotAt(141.5f, 300f))
        assertEquals(0, p.slotAt(281.4f, 300f))
        assertEquals(1, p.slotAt(281.5f, 300f))
        assertEquals(2, p.slotAt(561.4f, 300f))
        assertEquals(-1, p.slotAt(561.5f, 300f))
        assertEquals(-1, p.slotAt(141f, 300f))
        assertTrue(p.inBand(-28f))
        assertFalse(p.inBand(-28.3f))
        assertFalse(p.inBand(192.78f))
        assertTrue(p.inBand(100f))
    }

    @Test
    fun zonesAtTheEdgesAndForWideKeys() {
        // q (cell 0–108) → zone 0–149
        val q = place(letters, "q", listOf("q", "x"))
        assertEquals(PxRect(0f, 0f, 149f, 189f), q.holdZone)
        // o on the top row, mirrored: slots ö 703.5–843.5, o 843.5–983.5, zone 843.5–992.5 × 0–189
        val o = place(letters, "o", listOf("o", "ö"))
        assertEquals(PxRect(843.5f, 0f, 992.5f, 189f), o.holdZone)
        assertEquals(1, o.slotAt(703.5f, 100f))
        assertEquals(1, o.slotAt(843.4f, 100f))
        assertEquals(0, o.slotAt(843.5f, 100f))
        assertEquals(0, o.slotAt(983.4f, 100f))
        assertEquals(-1, o.slotAt(988f, 100f))
        // domain key of the URL page with large cells: zone 132.5–353.5
        val dk = url.keys.first { it.role == app.metrokeys.keyboard.layout.KeyRole.DOMAIN }
        val d = PickerModel.place(dk, url.cell(dk), listOf(".de", ".com", ".org", ".net"), 203f, 203f, 9f, 1080f, false, 0L)
        assertEquals(132.5f, d.holdZone.left)
        assertEquals(353.5f, d.holdZone.right)
        // the "." key of the URL page (162 px) with standard cells: its cell 756–918
        val dot = place(url, ".", listOf(".", ",", "/"))
        assertEquals(756f, dot.holdZone.left)
        assertEquals(918f, dot.holdZone.right)
    }

    @Test
    fun twoRowsChooseTheUpperRowAboveTheLowerFrame() {
        val items = (1..10).map { "x$it" }.map { it.take(2) }
        val p = place(letters, "g", items)
        assertEquals(2, p.rowCount)
        // the lower row takes ceil(n / 2) entries
        assertEquals(5, p.rows.count { it == 0 })
        val split = p.pickerBottom - p.rowHitHeight
        assertEquals(1, p.rowAt(split - 0.1f))
        assertEquals(0, p.rowAt(split))
        assertTrue(p.inBand(p.pickerBottom - 2 * p.rowHitHeight + 0.1f))
    }

    @Test
    fun cellsSlideOutAndAreDrawnRightToLeft() {
        val a = place(letters, "a", listOf("a", "ä", "à"))
        // stacked on the own cell for 100 ms, then linear to the final place at 300 ms
        assertEquals(a.cells[0].left, a.cellAt(2, 100).left)
        assertEquals((a.cells[0].left + a.cells[2].left) / 2, a.cellAt(2, 200).left, 0.01f)
        assertEquals(a.cells[2].left, a.cellAt(2, 300).left)
        // normal row: the own cell (leftmost) is drawn last, i.e. on top
        assertEquals(listOf(2, 1, 0), a.drawOrder())
        // mirrored row (o): the own cell is rightmost and drawn first, the alternates slide over it
        val o = place(letters, "o", listOf("o", "ö"))
        assertEquals(listOf(0, 1), o.drawOrder())
    }

    @Test
    fun theHoldPhaseIgnoresMovesInsideTheZone() {
        val p = place(letters, "s", listOf("s", "ß", "§"))
        assertFalse(p.move(150f, 200f))
        assertFalse(p.move(290.5f, 378f)) // edges inclusive
        assertFalse(p.drag)
        assertFalse(p.move(400f, 300f)) // leaves: drag phase, no highlight change for this position
        assertTrue(p.drag)
        assertEquals(0, p.highlighted)
        assertTrue(p.move(400f, 300f))
        assertEquals(1, p.highlighted)
        // coming back does not return to the hold phase
        assertTrue(p.move(200f, 300f))
        assertTrue(p.drag)
        assertEquals(0, p.highlighted)
    }
}
