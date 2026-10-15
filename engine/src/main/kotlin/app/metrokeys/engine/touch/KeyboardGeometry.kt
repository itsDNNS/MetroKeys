package app.metrokeys.engine.touch

import java.text.Normalizer

/** A letter key of the current letter page, in keyboard (key-area) pixels. */
public data class LetterKey(val letter: Char, val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** Hit test with inclusive edges. */
    public fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom

    /** Hit test against the rectangle shrunk by [fx] of the width left/right and [fy] of the height top/bottom. */
    public fun containsShrunk(x: Float, y: Float, fx: Float, fy: Float): Boolean {
        val mx = width * fx
        val my = height * fy
        return x >= left + mx && x <= right - mx && y >= top + my && y <= bottom - my
    }
}

/**
 * Geometry of the letter keys of the current letter page (spec 05 §4, 06 §3): one rectangle per single-letter key,
 * plus the nominal letter-key width and height (the most common ones).
 */
public class KeyboardGeometry(
    /** Width of the key area (four key rows, without the candidate bar). */
    public val width: Float,
    /** Height of the key area. */
    public val height: Float,
    public val keys: List<LetterKey>,
    public val keyWidth: Float = mostCommon(keys.map { it.width }),
    public val keyHeight: Float = mostCommon(keys.map { it.height }),
) {
    private val byLetter: Map<Char, LetterKey> = buildMap {
        for (k in this@KeyboardGeometry.keys) putIfAbsent(Character.toLowerCase(k.letter), k)
    }

    /** The key that outputs [letter] (lower-cased), or null. */
    public fun ownKey(letter: Char): LetterKey? = byLetter[Character.toLowerCase(letter)]

    /**
     * Key of [letter] for the touch model (05 §4): its own key, else the base key of the umlaut/accent alternates
     * listed in the spec (ä à á â → a, ö ò ó ô → o, ü ù ú û → u, é è ê ë → e, ß § → s).
     */
    public fun touchKey(letter: Char): LetterKey? {
        val low = Character.toLowerCase(letter)
        byLetter[low]?.let { return it }
        val base = TOUCH_BASE[low] ?: return null
        return byLetter[base]
    }

    /** Key of [letter] for shape writing (06 §4.2): own key, else the key of its base letter (diacritics removed, ß → s). */
    public fun shapeKey(letter: Char): LetterKey? {
        val low = Character.toLowerCase(letter)
        byLetter[low]?.let { return it }
        val base = baseLetter(low) ?: return null
        return byLetter[base]
    }

    /** True if [letter] is reached only through its base letter's key (accent cost in shape writing). */
    public fun isViaBaseLetter(letter: Char): Boolean = byLetter[Character.toLowerCase(letter)] == null

    /** Letters that have a key of their own. */
    public val letters: List<Char> get() = byLetter.keys.sorted()

    public companion object {
        private val TOUCH_BASE: Map<Char, Char> = buildMap {
            for (c in "äàáâ") put(c, 'a')
            for (c in "öòóô") put(c, 'o')
            for (c in "üùúû") put(c, 'u')
            for (c in "éèêë") put(c, 'e')
            put('ß', 's')
            put('§', 's')
        }

        /** Lower-case base letter with diacritics removed (ß → s), or null if there is none. */
        @JvmStatic
        public fun baseLetter(c: Char): Char? {
            val low = Character.toLowerCase(c)
            if (low == 'ß') return 's'
            val d = Normalizer.normalize(low.toString(), Normalizer.Form.NFD)
            val b = d[0]
            return if (b != low && Character.isLetter(b)) b else null
        }

        private fun mostCommon(values: List<Float>): Float {
            if (values.isEmpty()) return 1f
            return values.groupingBy { Math.round(it * 1000f) }.eachCount().entries
                .sortedWith(compareBy({ -it.value }, { it.key })).first().key / 1000f
        }

        /** Letter rows of the fallback grid (05 §4). */
        @JvmStatic
        public fun fallbackRows(languageTag: String): List<String> =
            if (languageTag.startsWith("de")) listOf("qwertzuiop", "asdfghjkl", "yxcvbnm")
            else listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

        /**
         * The fallback grid of 05 §4 on a [width] × [height] letter area: keys 0.1 · width × 0.25 · height, row 1 from
         * x = 0, row 2 from 0.05 · width, row 3 from 0.15 · width.
         */
        @JvmStatic
        @JvmOverloads
        public fun fallback(languageTag: String, width: Float = 1080f, height: Float = 756f): KeyboardGeometry {
            val kw = width * 0.1f
            val kh = height * 0.25f
            val offsets = floatArrayOf(0f, 0.05f * width, 0.15f * width)
            val keys = ArrayList<LetterKey>()
            for ((r, row) in fallbackRows(languageTag).withIndex()) {
                for ((i, c) in row.withIndex()) {
                    val l = offsets[r] + i * kw
                    keys.add(LetterKey(c, l, r * kh, l + kw, (r + 1) * kh))
                }
            }
            return KeyboardGeometry(width, height, keys, kw, kh)
        }

        /**
         * Builds the geometry of a letter page of a layout data file (spec/data/layouts-*.json): every key with role
         * `char` whose output is a single letter, scaled to [width] × [height] pixels.
         */
        @JvmStatic
        @JvmOverloads
        public fun fromLayoutJson(json: String, pageId: String = "text.letters", width: Float = 1080f, height: Float = 756f): KeyboardGeometry {
            @Suppress("UNCHECKED_CAST")
            val root = Json.parse(json) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val pages = root["pages"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val page = pages[pageId] as? Map<String, Any?> ?: throw IllegalArgumentException("no page $pageId")
            @Suppress("UNCHECKED_CAST")
            val keyList = page["keys"] as List<Map<String, Any?>>
            val keys = ArrayList<LetterKey>()
            for (k in keyList) {
                if (k["role"] != "char") continue
                val out = k["output"] as? String ?: continue
                if (out.length != 1 || !Character.isLetter(out[0])) continue
                @Suppress("UNCHECKED_CAST")
                val r = (k["rect"] as List<Double>).map { it.toFloat() }
                keys.add(LetterKey(out[0], r[0] * width, r[1] * height, r[2] * width, r[3] * height))
            }
            return KeyboardGeometry(width, height, keys)
        }
    }
}
