package app.metrokeys.keyboard.layout

import app.metrokeys.engine.touch.Json

/** Key roles of spec 01 §8. */
enum class KeyRole {
    CHAR, SHIFT, BACKSPACE, ENTER, SPACE, PAGE, EMOJI, EMOJI_CATEGORY, EMOJI_GRID, LANGUAGE, DOMAIN, BLANK;

    companion object {
        fun of(s: String?): KeyRole = when (s) {
            "char" -> CHAR
            "shift" -> SHIFT
            "backspace" -> BACKSPACE
            "enter" -> ENTER
            "space" -> SPACE
            "page" -> PAGE
            "emoji" -> EMOJI
            "emoji-category" -> EMOJI_CATEGORY
            "emoji-grid" -> EMOJI_GRID
            "language" -> LANGUAGE
            "domain" -> DOMAIN
            else -> BLANK
        }
    }
}

enum class PageKind { LETTERS, SYMBOLS, EMOJI, NUMPAD, DIALPAD }

enum class Appearance { NORMAL, FUNCTION, EMPHASIZED }

/** A long-press alternate (spec 01 §12.1). */
data class Alternate(val output: String, val outputShifted: String? = null, val hideWhenShifted: Boolean = false) {
    fun text(shifted: Boolean): String = if (shifted) outputShifted ?: output else output
}

/** A rectangle in fractions of the key area: left/top inclusive. */
data class FRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** One key of a page (spec 01 §12.1). */
data class Key(
    val role: KeyRole,
    val rect: FRect,
    val label: String? = null,
    val labelShifted: String? = null,
    val icon: String? = null,
    val subLabel: String? = null,
    val subIcon: String? = null,
    val output: String? = null,
    val outputShifted: String? = null,
    val caseFollowsShift: Boolean = false,
    val target: String? = null,
    val category: String? = null,
    val hint: String? = null,
    val holdOutput: String? = null,
    val alternates: List<Alternate> = emptyList(),
    val longPressMs: Int = 450,
    val repeat: Boolean = false,
    val popup: Boolean = false,
    val enabled: Boolean = true,
    val appearance: Appearance = Appearance.NORMAL,
    val textStyle: String = "letter",
    /** Label inset [l, t, r, b] in reference px. */
    val labelInset: FloatArray = ZERO_INSET,
) {
    fun labelText(shifted: Boolean): String = if (shifted) labelShifted ?: label ?: "" else label ?: ""

    fun outputText(shifted: Boolean): String? = if (shifted) outputShifted ?: output else output

    /** A letter key: a character key whose output is a single letter. */
    val isLetter: Boolean get() = role == KeyRole.CHAR && output?.length == 1 && Character.isLetter(output[0])

    /** Keys that take part in hit testing (spec 03 §1.1). */
    val hittable: Boolean get() = enabled && role != KeyRole.BLANK && role != KeyRole.EMOJI_GRID

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    companion object {
        val ZERO_INSET = FloatArray(4)
    }
}

data class OptionalComma(val insertAt: Int, val key: Key, val spaceRectWithComma: FRect)

/** One keyboard page. */
data class Page(
    val id: String,
    val kind: PageKind,
    val rows: Int,
    val keys: List<Key>,
    val optionalComma: OptionalComma? = null,
) {
    val family: String get() = id.substringBefore('.')

    /** The page with its optional comma key inserted (01 §5.2); unchanged if it has none. */
    fun withOptionalComma(): Page {
        val oc = optionalComma ?: return this
        val k = keys.map { if (it.role == KeyRole.SPACE) it.copy(rect = oc.spaceRectWithComma) else it }.toMutableList()
        k.add(oc.insertAt.coerceIn(0, k.size), oc.key)
        return copy(keys = k, optionalComma = null)
    }
    val isLetters: Boolean get() = kind == PageKind.LETTERS
    val isSymbols: Boolean get() = kind == PageKind.SYMBOLS

    /** Index of this page in its family list (letters = 0, symbols-1 = 1, symbols-2 = 2), used for announcements. */
    val symbolsNumber: Int get() = when {
        id.endsWith("symbols-1") -> 1
        id.endsWith("symbols-2") -> 2
        else -> 0
    }
}

data class LanguageInfo(val label: String, val subLabel: String?, val subIcon: String?)

/** The content of one `layouts-<lang>[-multi].json` file (spec 01 §12.1). */
class LayoutSet(
    val language: String,
    val variant: String,
    val languageInfo: LanguageInfo,
    val domainRegion: String,
    val domains: List<String>,
    val inputTypes: Map<String, List<String>>,
    val pages: Map<String, Page>,
) {
    fun page(id: String): Page = pages[id] ?: error("no page $id in $language/$variant")

    /** Page list of an input type (spec 01 §10.1); unknown types fall back to "text". */
    fun pagesFor(inputType: String): List<String> = inputTypes[inputType] ?: inputTypes.getValue("text")

    /**
     * This layout with the optional comma key of the multi-language letter pages put back (01 §5.2 extension,
     * setting `show_comma_key`): the comma key is inserted at its index and the space bar gets the narrower rect.
     */
    fun withOptionalComma(): LayoutSet {
        if (pages.values.none { it.optionalComma != null }) return this
        val changed = pages.mapValues { (_, p) -> p.withOptionalComma() }
        return LayoutSet(language, variant, languageInfo, domainRegion, domains, inputTypes, changed)
    }

    /** The letter page of the family of [page] (`abcd` target and automatic return). */
    fun lettersOf(page: Page): Page? = pages["${page.family}.letters"]

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun parse(json: String): LayoutSet {
            val root = Json.parse(json) as Map<String, Any?>
            val defaults = root["defaults"] as? Map<String, Any?>
            val defaultLongPress = (defaults?.get("longPressMs") as? Double)?.toInt() ?: 450
            val li = root["languageInfo"] as Map<String, Any?>
            val domains = root["domains"] as? Map<String, Any?>
            val pages = LinkedHashMap<String, Page>()
            for ((id, pv) in root["pages"] as Map<String, Any?>) {
                val p = pv as Map<String, Any?>
                val keys = (p["keys"] as List<Any?>).map { parseKey(it as Map<String, Any?>, defaultLongPress) }
                val oc = (p["optionalComma"] as? Map<String, Any?>)?.let {
                    OptionalComma(
                        (it["insertAt"] as Double).toInt(),
                        parseKey(it["key"] as Map<String, Any?>, defaultLongPress),
                        rect(it["spaceRectWithComma"]),
                    )
                }
                pages[id] = Page(
                    id = id,
                    kind = when (p["kind"]) {
                        "letters" -> PageKind.LETTERS
                        "symbols" -> PageKind.SYMBOLS
                        "emoji" -> PageKind.EMOJI
                        "dialpad" -> PageKind.DIALPAD
                        else -> PageKind.NUMPAD
                    },
                    rows = (p["rows"] as? Double)?.toInt() ?: 4,
                    keys = keys,
                    optionalComma = oc,
                )
            }
            return LayoutSet(
                language = root["language"] as String,
                variant = root["variant"] as? String ?: "single",
                languageInfo = LanguageInfo(li["label"] as String, li["subLabel"] as? String, li["subIcon"] as? String),
                domainRegion = domains?.get("region") as? String ?: "US",
                domains = (domains?.get("list") as? List<Any?>)?.map { it as String } ?: listOf(".com"),
                inputTypes = (root["inputTypes"] as Map<String, Any?>).mapValues { (_, v) -> (v as List<Any?>).map { it as String } },
                pages = pages,
            )
        }

        private fun rect(v: Any?): FRect {
            @Suppress("UNCHECKED_CAST")
            val r = (v as List<Double>).map { it.toFloat() }
            return FRect(r[0], r[1], r[2], r[3])
        }

        @Suppress("UNCHECKED_CAST")
        private fun parseKey(k: Map<String, Any?>, defaultLongPress: Int): Key = Key(
            role = KeyRole.of(k["role"] as? String),
            rect = rect(k["rect"]),
            label = k["label"] as? String,
            labelShifted = k["labelShifted"] as? String,
            icon = k["icon"] as? String,
            subLabel = k["subLabel"] as? String,
            subIcon = k["subIcon"] as? String,
            output = k["output"] as? String,
            outputShifted = k["outputShifted"] as? String,
            caseFollowsShift = k["caseFollowsShift"] as? Boolean ?: false,
            target = k["target"] as? String,
            category = k["category"] as? String,
            hint = k["hint"] as? String,
            holdOutput = k["holdOutput"] as? String,
            alternates = (k["alternates"] as? List<Any?>)?.map {
                val a = it as Map<String, Any?>
                Alternate(a["output"] as String, a["outputShifted"] as? String, a["hideWhenShifted"] as? Boolean ?: false)
            } ?: emptyList(),
            longPressMs = (k["longPressMs"] as? Double)?.toInt() ?: defaultLongPress,
            repeat = k["repeat"] as? Boolean ?: false,
            popup = k["popup"] as? Boolean ?: false,
            enabled = k["enabled"] as? Boolean ?: true,
            appearance = when (k["appearance"]) {
                "function" -> Appearance.FUNCTION
                "emphasized" -> Appearance.EMPHASIZED
                else -> Appearance.NORMAL
            },
            textStyle = k["textStyle"] as? String ?: "letter",
            labelInset = (k["labelInset"] as? List<Double>)?.map { it.toFloat() }?.toFloatArray() ?: Key.ZERO_INSET,
        )
    }
}

/**
 * Hit testing of spec 03 §1.1 on a page, in key-area pixel coordinates ([width] × [height]). Returns null for
 * touches further than [outside] px outside the left, right or bottom edge.
 */
fun Page.hitTest(x: Float, y: Float, width: Float, height: Float, outside: Float, hidden: (Key) -> Boolean = { false }): Key? {
    if (x < -outside || x > width + outside || y > height + outside) return null
    val cy = y.coerceIn(0f, height - 0.001f)
    val cx = x.coerceIn(0f, width)
    var best: Key? = null
    var bestDist = Float.MAX_VALUE
    for (k in keys) {
        if (!k.hittable || hidden(k)) continue
        val top = k.rect.top * height
        val bottom = k.rect.bottom * height
        if (cy < top || cy >= bottom) continue
        val l = k.rect.left * width
        val r = k.rect.right * width
        if (cx >= l && cx < r) return k
        val d = if (cx < l) l - cx else cx - r
        if (d < bestDist) {
            bestDist = d
            best = k
        }
    }
    return best
}
