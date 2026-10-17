package app.metrokeys.settings

import java.nio.charset.Charset

/**
 * The file rules of spec 07 §9.9 (sounds) and §9.11 (fonts), free of Android so they can be unit tested.
 */
object ImportRules {
    const val MAX_SOUND_BYTES = 4L * 1024 * 1024
    const val MAX_FONT_BYTES = 40L * 1024 * 1024

    /** Sound slots in the order of 07 §9.9. */
    val SOUND_SLOTS = listOf("letter", "space", "function", "picker", "candidate", "dictionary", "paste")

    /** Font slots in the order of 07 §9.11. */
    val FONT_SLOTS = listOf("light", "semilight", "regular", "semibold", "bold", "emoji")

    /**
     * The slot of a sound file by its name (07 §9.9): case-insensitive, extension removed, non-letters ignored, the
     * first matching rule wins; null if no rule matches.
     */
    fun soundSlotForName(fileName: String): String? {
        val base = fileName.substringAfterLast('/').let { n -> if ('.' in n) n.substringBeforeLast('.') else n }
        val s = base.lowercase().filter { it.isLetter() }
        return when {
            "dictionary" in s -> "dictionary"
            "candidate" in s || "suggestion" in s -> "candidate"
            "paste" in s || "clipboard" in s -> "paste"
            "picker" in s || "accent" in s || "alternate" in s -> "picker"
            "space" in s -> "space"
            "function" in s -> "function"
            "letter" in s || "tap" in s || "key" in s -> "letter"
            else -> null
        }
    }

    /** A WAV file: RIFF header of type WAVE, longer than 44 bytes, at most 4 MiB. */
    fun isWav(bytes: ByteArray): Boolean =
        bytes.size > 44 && bytes.size <= MAX_SOUND_BYTES &&
            ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WAVE"

    private fun ascii(b: ByteArray, at: Int, n: Int): String = String(b, at, n, Charsets.ISO_8859_1)

    /**
     * Information read from a TrueType/OpenType file: OS/2 weight class and italic bit, family name and style name
     * (typographic subfamily, name ID 17, else subfamily, name ID 2).
     */
    data class FontInfo(val weightClass: Int, val italic: Boolean, val family: String?, val style: String? = null)

    /** Parses the sfnt header, OS/2 and name tables; null when the file is not a TrueType/OpenType font. */
    fun parseFont(b: ByteArray): FontInfo? {
        if (b.size < 12) return null
        val version = u32(b, 0)
        if (version != 0x00010000L && version != 0x74727565L && version != 0x4F54544FL) return null
        val numTables = u16(b, 4)
        if (12 + numTables * 16 > b.size) return null
        var os2 = -1
        var os2Len = 0
        var name = -1
        var nameLen = 0
        for (i in 0 until numTables) {
            val r = 12 + i * 16
            val tag = String(b, r, 4, Charsets.ISO_8859_1)
            val off = u32(b, r + 8)
            val len = u32(b, r + 12)
            if (off < 0 || off + len > b.size) continue
            when (tag) {
                "OS/2" -> {
                    os2 = off.toInt()
                    os2Len = len.toInt()
                }
                "name" -> {
                    name = off.toInt()
                    nameLen = len.toInt()
                }
            }
        }
        if (os2 < 0 || os2Len < 64) return null
        val weight = u16(b, os2 + 4)
        val italic = u16(b, os2 + 62) and 1 != 0
        return FontInfo(
            weight, italic,
            if (name >= 0) nameString(b, name, nameLen, preferred = 16, fallback = 1) else null,
            if (name >= 0) nameString(b, name, nameLen, preferred = 17, fallback = 2) else null,
        )
    }

    /**
     * A name-table string: name ID [preferred] before [fallback] (16/1 for the family, 17/2 for the style);
     * Windows/Unicode or Mac Roman encodings, US English Windows records first.
     */
    private fun nameString(b: ByteArray, table: Int, len: Int, preferred: Int, fallback: Int): String? {
        if (len < 6) return null
        val count = u16(b, table + 2)
        val strings = table + u16(b, table + 4)
        var best: String? = null
        var bestRank = Int.MAX_VALUE
        for (i in 0 until count) {
            val r = table + 6 + i * 12
            if (r + 12 > table + len) break
            val platform = u16(b, r)
            val encoding = u16(b, r + 2)
            val language = u16(b, r + 4)
            val id = u16(b, r + 6)
            val n = u16(b, r + 8)
            val at = strings + u16(b, r + 10)
            if (id != preferred && id != fallback) continue
            if (at + n > b.size) continue
            val text = when {
                platform == 3 || platform == 0 -> String(b, at, n, Charsets.UTF_16BE)
                platform == 1 && encoding == 0 -> String(b, at, n, macRoman)
                else -> continue
            }.trim()
            if (text.isEmpty()) continue
            val rank = (if (id == preferred) 0 else 10) + (if (platform == 3 && language == 0x409) 0 else 1)
            if (rank < bestRank) {
                bestRank = rank
                best = text
            }
        }
        return best
    }

    private val macRoman: Charset = try {
        Charset.forName("x-MacRoman")
    } catch (_: Exception) {
        Charsets.ISO_8859_1
    }

    /**
     * The slot named by a style name (07 §9.11), or null: "Semilight", "Semibold", "Light", "Bold" or "Regular",
     * case-insensitive and ignoring spaces, hyphens and underscores ("Semi Light"); the semi weights are tested
     * before the words they contain.
     */
    fun styleSlot(style: String?): String? {
        val s = style?.lowercase()?.filter { it != ' ' && it != '-' && it != '_' } ?: return null
        return when {
            "semilight" in s -> "semilight"
            "semibold" in s -> "semibold"
            "light" in s -> "light"
            "bold" in s -> "bold"
            "regular" in s -> "regular"
            else -> null
        }
    }

    /**
     * The slot of a font (07 §9.11): a family containing "emoji" → emoji; italic → null (rejected); a style name
     * with a known weight word decides; else the OS/2 weight class, where weights outside 250–799 are rejected.
     */
    fun fontSlot(info: FontInfo): String? = when {
        info.family?.contains("emoji", ignoreCase = true) == true -> "emoji"
        info.italic -> null
        styleSlot(info.style) != null -> styleSlot(info.style)
        info.weightClass in 250..324 -> "light"
        info.weightClass in 325..374 -> "semilight"
        info.weightClass in 375..499 -> "regular"
        info.weightClass in 500..649 -> "semibold"
        info.weightClass in 650..799 -> "bold"
        else -> null
    }

    private fun u16(b: ByteArray, at: Int): Int =
        if (at < 0 || at + 2 > b.size) 0 else (b[at].toInt() and 0xFF shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, at: Int): Long =
        if (at < 0 || at + 4 > b.size) -1 else (u16(b, at).toLong() shl 16) or u16(b, at + 2).toLong()
}
