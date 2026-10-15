package app.metrokeys.keyboard

/**
 * Sizes of spec 02 §2 / 03 §17 for one view configuration. All values in device px.
 *
 * Portrait: s = width / 1080, key area 756 · s · h. Landscape: s = min(width / 1596, shortSide / 1080), key area
 * 576 · s · h stretched to the full width. The keyboard height factor h scales key heights and key labels only;
 * the strip shrinks to at most 85 %; popups, picker, candidates and the app bar keep their size.
 */
class Metrics(
    val width: Int,
    val landscape: Boolean,
    shortSide: Int,
    /** Keyboard height factor h (0.7 … 1). */
    val heightFactor: Float,
    val xdpi: Float,
) {
    val s: Float = if (landscape) minOf(width / 1596f, shortSide / 1080f) else width / 1080f

    /** Reference px → device px. */
    fun px(ref: Float): Float = ref * s

    val keyAreaHeight: Float = (if (landscape) 576f else 756f) * s * heightFactor
    val stripHeight: Float = 126f * s * maxOf(0.85f, heightFactor)
    val appBarHeight: Float = 126f * s
    val maxOverflow: Float = 330f * s

    /** Labels and glyphs on keys scale with s and h. */
    val keyLabelScale: Float = s * heightFactor

    val keyInset: Float = 7f * s
    val outsideTolerance: Float = 40f * s
    val moveTolerance: Float = 10f * s

    // key-press popup (02 §5.5)
    val popupContentW: Float = (if (landscape) 171f else 131f) * s
    val popupContentH: Float = (if (landscape) 207f else 203f) * s
    val popupLargeW: Float = (if (landscape) 234f else 203f) * s
    val popupLargeH: Float = 203f * s
    val popupLanguageW: Float = (if (landscape) 207f else 153f) * s
    val popupLanguageH: Float = (if (landscape) 207f else 203f) * s
    val frame: Float = 9f * s

    // candidate bar (02 §5.8)
    val pasteHeaderWidth: Float = 197f * s
    val clipSlotWidth: Float = 190f * s
    val itemPadding: Float = (4f + 42f) * s

    // emoji page (02 §5.9)
    val emojiCellW: Float = (if (landscape) 195f else 173f) * s
    val emojiCellH: Float = 155f * s
    val emojiColumns: Int = if (landscape) 8 else 6
    val emojiRecentRows: Int = if (landscape) 2 else 3

    /** Drag start of the lists: 2.077 mm (spec 02 §7.4.1). */
    val dragStart: Float = 2.077f * xdpi / 25.4f
}
