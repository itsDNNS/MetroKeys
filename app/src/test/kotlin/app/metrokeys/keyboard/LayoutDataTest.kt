package app.metrokeys.keyboard

import app.metrokeys.ime.ClipboardHistory
import app.metrokeys.ime.EmojiData
import app.metrokeys.ime.EmojiHistory
import app.metrokeys.ime.FieldState
import app.metrokeys.keyboard.layout.KeyRole
import app.metrokeys.keyboard.layout.LayoutSet
import app.metrokeys.keyboard.layout.hitTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Layout, emoji and history data (spec 01, 03 §1.1, §8, §10). */
class LayoutDataTest {
    private fun layout(name: String) = LayoutSet.parse(File("../spec/data/$name").readText())

    @Test
    fun allLayoutFilesParseAndEveryInputTypeHasItsPages() {
        for (n in listOf("layouts-de.json", "layouts-de-multi.json", "layouts-en.json", "layouts-en-multi.json")) {
            val l = layout(n)
            for ((type, pages) in l.inputTypes) for (p in pages) assertTrue("$n $type $p", p in l.pages)
            for (p in l.pages.values) for (k in p.keys) if (k.role == KeyRole.PAGE || k.role == KeyRole.EMOJI) {
                assertTrue("$n ${p.id} → ${k.target}", k.target in l.pages)
            }
        }
    }

    @Test
    fun optionalCommaKeyIsPutBackOnMultiLanguageLetterPages() {
        val multi = layout("layouts-de-multi.json")
        val plain = multi.page("text.letters")
        assertFalse(plain.keys.any { it.output == "," })
        val p = multi.withOptionalComma().page("text.letters")
        val comma = p.keys.first { it.output == "," }
        assertEquals(32, p.keys.indexOf(comma))
        assertEquals(0.65f, comma.rect.left)
        val space = p.keys.first { it.role == KeyRole.SPACE }
        assertEquals(0.35f, space.rect.left)
        assertEquals(0.65f, space.rect.right)
        assertNull(p.optionalComma)
        assertEquals(0.65f, multi.withOptionalComma().page("default.letters").keys.first { it.role == KeyRole.SPACE }.rect.right)
        // single-language layouts have nothing to put back
        val single = layout("layouts-de.json")
        assertTrue(single.withOptionalComma() === single)
    }

    @Test
    fun germanLetterPageAndAlternates() {
        val p = layout("layouts-de.json").page("text.letters")
        val s = p.keys.first { it.output == "s" }
        assertEquals(listOf("ß", "§"), s.alternates.map { it.output })
        assertTrue(s.alternates[0].hideWhenShifted)
        val dot = p.keys.first { it.output == "." && it.rect.top == 0.75f }
        assertEquals(200, dot.longPressMs)
        assertEquals(6, p.keys.count { it.rect.top == 0.75f })
    }

    @Test
    fun hitTestUsesTheNearestKeyOfTheRow() {
        val p = layout("layouts-de.json").page("text.letters")
        // left of "a" (half a key free) → "a"
        assertEquals("a", p.hitTest(10f, 300f, 1080f, 756f, 40f)?.output)
        assertEquals("q", p.hitTest(5f, 5f, 1080f, 756f, 40f)?.output)
        // below the key area within the tolerance → bottom row
        assertEquals(KeyRole.SPACE, p.hitTest(500f, 780f, 1080f, 756f, 40f)?.role)
        assertNull(p.hitTest(500f, 800f, 1080f, 756f, 40f))
    }

    @Test
    fun fieldMapping() {
        assertEquals("pin", FieldState.from(0x12, 0).inputKind)
        assertEquals("phone", FieldState.from(0x3, 0).inputKind)
        assertEquals("email", FieldState.from(0x21, 0).inputKind)
        assertEquals("search", FieldState.from(0x1, 3).inputKind)
        assertEquals("password", FieldState.from(0x81, 0).inputKind)
        assertTrue(FieldState.from(0x1, 4).enterShowsGo)
        assertFalse(FieldState.from(0x1, 5).enterShowsGo)
        // "no enter action": Enter inserts a line break, the app bar honours only send
        val noEnter = FieldState.from(0x1, 4 or FieldState.FLAG_NO_ENTER_ACTION)
        assertFalse(noEnter.enterPerformsAction)
        assertEquals(FieldState.ACTION_SEND, noEnter.appBarAction)
        assertEquals(0, FieldState.from(0x1, 6 or FieldState.FLAG_NO_ENTER_ACTION).appBarAction)
    }

    @Test
    fun emojiCategoriesFollowTheSpecOrder() {
        val cats = EmojiData.parse(File("../spec/data/emoji.json").readText(), File("src/main/assets/emoji/emoji.txt").readText())
        assertEquals(listOf("recent", "emoticons", "people", "activities", "food-objects", "travel", "nature-weather", "symbols"), cats.map { it.id })
        assertTrue(cats[0].dynamic && cats[0].items.isEmpty())
        // the fixed text-smiley list of emoji.json, in its order (01 §7.3)
        assertEquals(60, cats[1].items.size)
        assertEquals(listOf(";)", ":)", ":/", ":|", ":(", ":'(", ":D", ":P", "<3", ":S", ":-(", "):"), cats[1].items.take(12))
        assertEquals(":X", cats[1].items.last())
        assertEquals("😀", cats[2].items.first())
        // no skin-tone variants
        assertTrue(cats.flatMap { it.items }.none { s -> s.codePoints().anyMatch { it in 0x1F3FB..0x1F3FF } })
    }

    @Test
    fun emojiHistoryOrder() {
        val h = EmojiHistory(null)
        h.record("😀", 1)
        h.record("😃", 2)
        h.record("😃", 3)
        h.record("🎈", 4)
        assertEquals(listOf("😃", "🎈", "😀"), h.top(18))
        assertEquals(h.top(18), EmojiHistory(h.serialize()).top(18))
        repeat(60) { h.record("x$it", 10L + it) }
        assertEquals(EmojiHistory.MAX, EmojiHistory(h.serialize()).top(100).size)
    }

    @Test
    fun clipboardHistoryRules() {
        val h = ClipboardHistory()
        val hour = ClipboardHistory.EXPIRY_MS
        assertTrue(h.record("a", 0, 1))
        assertFalse("same clip twice", h.record("a", 1, 1))
        h.record("b", 10, 2)
        h.togglePin(h.list(20).first { it.text == "a" }, 20)
        assertEquals(listOf("a", "b"), h.list(30).map { it.text })
        // unpinned entries expire after an hour, pinned stay
        assertEquals(listOf("a"), h.list(10 + hour + 1).map { it.text })
        repeat(30) { h.record("t$it", 100L + it, 10L + it) }
        assertEquals(1 + ClipboardHistory.MAX_UNPINNED, h.list(200).size)
        val r = ClipboardHistory.read(h.serialize())
        assertEquals(h.list(200), r.list(200))
        assertTrue(ClipboardHistory.read(byteArrayOf(1, 2)).isEmpty(0))
    }

    @Test
    fun clipboardImageEntries() {
        val h = ClipboardHistory()
        val hour = ClipboardHistory.EXPIRY_MS
        h.record("text", 0, 1)
        assertTrue(h.claim(2))
        assertFalse("same clip twice", h.claim(2))
        h.recordImage("aa.png", "image/png", 10)
        assertEquals(listOf(true, false), h.list(20).map { it.isImage })
        assertEquals("image/png", h.imageEntry("aa.png")?.mime)
        // the same image again moves to the top, keeping its pin state
        h.togglePin(h.imageEntry("aa.png")!!, 20)
        h.record("other", 30, 3)
        h.recordImage("aa.png", "image/png", 40)
        assertEquals(1, h.list(50).count { it.isImage })
        assertTrue(h.imageEntry("aa.png")!!.pinned)
        assertTrue(h.takeDroppedImages().isEmpty())
        // files of removed entries are reported once: delete, clear, expiry
        h.recordImage("bb.png", "image/png", 60)
        h.delete(h.imageEntry("bb.png")!!)
        assertEquals(listOf("bb.png"), h.takeDroppedImages())
        assertTrue(h.takeDroppedImages().isEmpty())
        h.recordImage("cc.jpg", "image/jpeg", 70)
        h.list(70 + hour + 1)
        assertEquals(listOf("cc.jpg"), h.takeDroppedImages())
        assertEquals(setOf("aa.png"), h.imageFiles())
        // round trip, version 1 data still readable
        val r = ClipboardHistory.read(h.serialize())
        assertEquals(h.list(100), r.list(100))
        val v1 = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(v1).use { o ->
            o.writeInt(1); o.writeLong(5); o.writeInt(1)
            o.writeUTF("old"); o.writeLong(3); o.writeBoolean(true)
        }
        val old = ClipboardHistory.read(v1.toByteArray())
        assertEquals(listOf(ClipEntry_("old", 3, true)), old.list(4).map { ClipEntry_(it.text, it.time, it.pinned) })
        assertEquals(5L, old.lastClipStamp)
        h.clearAll()
        assertEquals(listOf("aa.png"), h.takeDroppedImages())
    }

    private data class ClipEntry_(val text: String, val time: Long, val pinned: Boolean)
}
