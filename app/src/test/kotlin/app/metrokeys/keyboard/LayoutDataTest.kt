package app.metrokeys.keyboard

import app.metrokeys.ime.ClipboardHistory
import app.metrokeys.ime.EmojiData
import app.metrokeys.ime.EmojiHistory
import app.metrokeys.ime.FieldState
import app.metrokeys.keyboard.layout.KeyRole
import app.metrokeys.keyboard.layout.LayoutSet
import app.metrokeys.keyboard.layout.Page
import app.metrokeys.settings.Prefs
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
        for (n in listOf("layouts-de.json", "layouts-de-multi.json", "layouts-en.json", "layouts-en-multi.json",
            "layouts-es.json", "layouts-es-multi.json", "layouts-pt.json", "layouts-pt-multi.json")) {
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
    fun spanishPages() {
        val es = layout("layouts-es.json")
        assertEquals("es-ES", es.language)
        assertEquals("ESP", es.languageInfo.label)
        assertEquals("ES", es.languageInfo.subLabel)
        assertEquals("ES", es.domainRegion)
        assertEquals(listOf(".com", ".es", ".org", ".net"), es.domains)
        val p = es.page("text.letters")
        // row 2: ten keys without indent, ñ last (01 §3.4)
        val row2 = p.keys.filter { it.rect.top == 0.25f && it.isLetter }.sortedBy { it.rect.left }
        assertEquals("asdfghjklñ", row2.joinToString("") { it.output!! })
        assertEquals(0f, row2.first().rect.left)
        val enye = row2.last()
        assertEquals("Ñ", enye.outputText(true))
        assertTrue(enye.alternates.isEmpty())
        assertEquals(listOf("é", "ê", "è", "ë"), p.keys.first { it.output == "e" }.alternates.map { it.output })
        assertEquals(listOf("ñ"), p.keys.first { it.output == "n" }.alternates.map { it.output })
        assertEquals(listOf("§"), p.keys.first { it.output == "s" }.alternates.map { it.output })
        // the . key offers the inverted marks (01 §5.1): ¿ third and ¡ sixth cell of ". , ¿ ? : ¡ ! -"
        val dot = p.keys.first { it.output == "." && it.rect.top == 0.75f }
        assertEquals(listOf(",", "¿", "?", ":", "¡", "!", "-"), dot.alternates.map { it.output })
        assertEquals("espacio", p.keys.first { it.role == KeyRole.SPACE }.label)
        // number pad: decimal comma (alternates . -)
        val decimal = es.page("number.numpad").keys.first { it.rect.top == 0.75f && it.rect.left == 0f }
        assertEquals(",", decimal.output)
        assertEquals(listOf(".", "-"), decimal.alternates.map { it.output })
        // symbols-1: € with $ £ ¥ ¢ ¤
        val euro = es.page("text.symbols-1").keys.first { it.output == "€" }
        assertEquals(listOf("$", "£", "¥", "¢", "¤"), euro.alternates.map { it.output })
        // the multi-language file has the language key with the label ESP
        val multi = layout("layouts-es-multi.json").page("text.letters")
        val lang = multi.keys.first { it.role == KeyRole.LANGUAGE }
        assertEquals(0.15f, lang.rect.left)
        assertEquals("espacio", multi.keys.first { it.role == KeyRole.SPACE }.label)
    }

    @Test
    fun portuguesePages() {
        val pt = layout("layouts-pt.json")
        assertEquals("pt-BR", pt.language)
        assertEquals("POR", pt.languageInfo.label)
        assertEquals("BR", pt.languageInfo.subLabel)
        assertEquals("BR", pt.domainRegion)
        assertEquals(listOf(".com", ".br", ".com.br", ".gov.br", ".org", ".net"), pt.domains)
        val p = pt.page("text.letters")
        // row 2: ten keys without indent, ç last (01 §3.5)
        val row2 = p.keys.filter { it.rect.top == 0.25f && it.isLetter }.sortedBy { it.rect.left }
        assertEquals("asdfghjklç", row2.joinToString("") { it.output!! })
        assertEquals(0f, row2.first().rect.left)
        val cedilla = row2.last()
        assertEquals("Ç", cedilla.outputText(true))
        assertTrue(cedilla.alternates.isEmpty())
        assertEquals(listOf("ã", "á", "â", "à", "ä", "æ", "å"), p.keys.first { it.output == "a" }.alternates.map { it.output })
        assertEquals(listOf("õ", "ó", "ô", "ò", "ö", "œ", "ø"), p.keys.first { it.output == "o" }.alternates.map { it.output })
        assertEquals(listOf("ç", "©"), p.keys.first { it.output == "c" }.alternates.map { it.output })
        val dot = p.keys.first { it.output == "." && it.rect.top == 0.75f }
        assertEquals(listOf(",", "?", "!", "-"), dot.alternates.map { it.output })
        assertEquals("espaço", p.keys.first { it.role == KeyRole.SPACE }.label)
        // url letters: the . key has no comma (01 §5.1)
        val urlDot = pt.page("url.letters").keys.first { it.output == "." && it.rect.top == 0.75f }
        assertEquals(listOf("/", ":", "\"", "&", "+", "-"), urlDot.alternates.map { it.output })
        // number pad: decimal comma (alternates . -)
        val decimal = pt.page("number.numpad").keys.first { it.rect.top == 0.75f && it.rect.left == 0f }
        assertEquals(",", decimal.output)
        assertEquals(listOf(".", "-"), decimal.alternates.map { it.output })
        // symbols-1: $ with € £ ¥ ¢ ¤
        val dollar = pt.page("text.symbols-1").keys.first { it.output == "$" }
        assertEquals(listOf("€", "£", "¥", "¢", "¤"), dollar.alternates.map { it.output })
        val multi = layout("layouts-pt-multi.json").page("text.letters")
        assertEquals(0.15f, multi.keys.first { it.role == KeyRole.LANGUAGE }.rect.left)
        assertEquals("espaço", multi.keys.first { it.role == KeyRole.SPACE }.label)
    }

    @Test
    fun italianPages() {
        val it = layout("layouts-it.json")
        assertEquals("it-IT", it.language)
        assertEquals("ITA", it.languageInfo.label)
        assertEquals(null, it.languageInfo.subLabel)
        assertEquals("keyboard", it.languageInfo.subIcon)
        assertEquals("IT", it.domainRegion)
        assertEquals(listOf(".com", ".it", ".org", ".net"), it.domains)
        val en = layout("layouts-en.json").page("text.letters")
        val p = it.page("text.letters")
        // the English letter rows and alternates, key for key (01 §3.6)
        fun letters(page: Page) = page.keys.filter { it.isLetter }.map { Triple(it.output, it.rect, it.alternates.map { a -> a.output }) }
        assertEquals(letters(en), letters(p))
        val row2 = p.keys.filter { it.rect.top == 0.25f && it.isLetter }.sortedBy { it.rect.left }
        assertEquals("asdfghjkl", row2.joinToString("") { it.output!! })
        assertEquals(0.05f, row2.first().rect.left)
        // the Italian accents by long press, in the common order: à 4th, é 2nd / è 4th, ì 4th, ó 2nd / ò 4th, ù 4th
        assertEquals("à", p.keys.first { it.output == "a" }.alternates[2].output)
        assertEquals(listOf("é", "ê", "è", "ë"), p.keys.first { it.output == "e" }.alternates.map { it.output })
        assertEquals("ì", p.keys.first { it.output == "i" }.alternates[2].output)
        assertEquals("ò", p.keys.first { it.output == "o" }.alternates[2].output)
        assertEquals("ù", p.keys.first { it.output == "u" }.alternates[2].output)
        // no apostrophe on the letter page, neither as key nor as alternate (01 §3.6)
        assertTrue(p.keys.none { k -> k.output == "'" || k.alternates.any { it.output == "'" || it.output == "’" } })
        val dot = p.keys.first { it.output == "." && it.rect.top == 0.75f }
        assertEquals(listOf(",", "?", "!", "-"), dot.alternates.map { it.output })
        assertEquals("spazio", p.keys.first { it.role == KeyRole.SPACE }.label)
        val urlDot = it.page("url.letters").keys.first { it.output == "." && it.rect.top == 0.75f }
        assertEquals(listOf(",", "/", ":", "\"", "&", "+", "-"), urlDot.alternates.map { it.output })
        // symbols-1: the apostrophe key (with ` ‘ ’) and € with $ £ ¥ ¢ ¤ (01 §4.1, §4.3)
        val sym = it.page("text.symbols-1")
        val apostrophe = sym.keys.first { it.output == "'" }
        assertEquals(listOf("`", "‘", "’"), apostrophe.alternates.map { it.output })
        assertEquals(listOf("$", "£", "¥", "¢", "¤"), sym.keys.first { it.output == "€" }.alternates.map { it.output })
        // number pad: decimal comma (alternates . -)
        val decimal = it.page("number.numpad").keys.first { it.rect.top == 0.75f && it.rect.left == 0f }
        assertEquals(",", decimal.output)
        assertEquals(listOf(".", "-"), decimal.alternates.map { it.output })
        val multi = layout("layouts-it-multi.json").page("text.letters")
        assertEquals(0.15f, multi.keys.first { it.role == KeyRole.LANGUAGE }.rect.left)
        assertEquals("spazio", multi.keys.first { it.role == KeyRole.SPACE }.label)
    }

    @Test
    fun languageKeyOrderByLabel() {
        val labels = listOf("layouts-pt.json", "layouts-it.json", "layouts-fr.json", "layouts-es.json", "layouts-de.json",
            "layouts-en.json").associate { n -> layout(n).let { it.language to it.languageInfo.label } }
        // 01 §11.2: DEU < ENG < ESP < FRA < ITA < POR, the same order as the tags (07 §9.5)
        assertEquals(listOf("de-DE", "en-US", "es-ES", "fr-FR", "it-IT", "pt-BR"), labels.keys.sortedWith(compareBy({ labels.getValue(it) }, { it })))
        assertEquals(listOf("DEU", "ENG", "ESP", "FRA", "ITA", "POR"), labels.values.sorted())
        assertEquals(Prefs.SUPPORTED_LANGUAGES, labels.keys.sorted())
    }

    @Test
    fun frenchPages() {
        val fr = layout("layouts-fr.json")
        assertEquals("fr-FR", fr.language)
        assertEquals("FRA", fr.languageInfo.label)
        assertEquals("FR", fr.languageInfo.subLabel)
        assertEquals("FR", fr.domainRegion)
        assertEquals(listOf(".com", ".fr", ".org", ".net"), fr.domains)
        val p = fr.page("text.letters")
        // AZERTY (01 §3.7): ten keys in rows 1 and 2 without indent, six letters in row 3 between 0.2-wide side keys
        fun row(top: Float) = p.keys.filter { it.rect.top == top && it.isLetter }.sortedBy { it.rect.left }
        assertEquals("azertyuiop", row(0f).joinToString("") { it.output!! })
        assertEquals("qsdfghjklm", row(0.25f).joinToString("") { it.output!! })
        assertEquals(0f, row(0.25f).first().rect.left)
        assertEquals("wxcvbn", row(0.5f).joinToString("") { it.output!! })
        assertEquals(0.2f, row(0.5f).first().rect.left, 1e-4f)
        val shift = p.keys.first { it.role == KeyRole.SHIFT }
        assertEquals(0.2f, shift.rect.right - shift.rect.left, 1e-4f)
        val back = p.keys.first { it.role == KeyRole.BACKSPACE }
        assertEquals(0.8f, back.rect.left, 1e-4f)
        // the alternates of the Spanish lists: à is the 4th cell of a, œ the 6th of o, ç the 2nd of c (01 §3.7)
        assertEquals(listOf("á", "â", "à", "ä", "æ", "å"), p.keys.first { it.output == "a" }.alternates.map { it.output })
        assertEquals(listOf("ó", "ô", "ò", "ö", "œ", "ø"), p.keys.first { it.output == "o" }.alternates.map { it.output })
        assertEquals(listOf("ç", "©"), p.keys.first { it.output == "c" }.alternates.map { it.output })
        assertEquals(listOf("ý", "ÿ"), p.keys.first { it.output == "y" }.alternates.map { it.output })
        assertEquals(listOf("§"), p.keys.first { it.output == "s" }.alternates.map { it.output })
        assertTrue(p.keys.first { it.output == "t" }.alternates.isEmpty())
        assertTrue(p.keys.first { it.output == "d" }.alternates.isEmpty())
        // no apostrophe on the letter page (01 §3.7)
        assertTrue(p.keys.none { k -> k.output == "'" || k.alternates.any { it.output == "'" || it.output == "’" } })
        val dot = p.keys.first { it.output == "." && it.rect.top == 0.75f }
        assertEquals(listOf(",", "?", "!", "-"), dot.alternates.map { it.output })
        assertEquals("espace", p.keys.first { it.role == KeyRole.SPACE }.label)
        val urlDot = fr.page("url.letters").keys.first { it.output == "." && it.rect.top == 0.75f }
        assertEquals(listOf("/", ":", "\"", "&", "+", "-"), urlDot.alternates.map { it.output })
        // symbols-1: the apostrophe (` ‘ ’), the guillemets as 4th and 5th cells of ", € with $ £ ¥ ¢ ¤ (01 §3.7, §4)
        val sym = fr.page("text.symbols-1")
        assertEquals(listOf("`", "‘", "’"), sym.keys.first { it.output == "'" }.alternates.map { it.output })
        assertEquals(listOf("”", "“", "»", "«"), sym.keys.first { it.output == "\"" }.alternates.map { it.output })
        assertEquals(listOf("$", "£", "¥", "¢", "¤"), sym.keys.first { it.output == "€" }.alternates.map { it.output })
        // the symbol pages keep the common side keys (next-page key 0–0.15, backspace 0.85–1.0)
        assertEquals(0.85f, sym.keys.first { it.role == KeyRole.BACKSPACE }.rect.left, 1e-4f)
        val decimal = fr.page("number.numpad").keys.first { it.rect.top == 0.75f && it.rect.left == 0f }
        assertEquals(",", decimal.output)
        assertEquals(listOf(".", "-"), decimal.alternates.map { it.output })
        val multi = layout("layouts-fr-multi.json").page("text.letters")
        assertEquals("FRA", layout("layouts-fr-multi.json").languageInfo.label)
        assertEquals("espace", multi.keys.first { it.role == KeyRole.SPACE }.label)
        assertTrue(multi.keys.any { it.role == KeyRole.LANGUAGE })
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
