package app.metrokeys.keyboard

/**
 * A text style: weight and em size in reference px (spec 02 §4.2, spec/data/metrics.json `textStyles`). Every key
 * label the spec gives as semibold uses [Weight.LABEL] (02 §4.2 "Key-label weight", 0.6.4).
 */
data class TextStyle(val weight: Weight, val size: Float)

data class StyleSet(
    val normal: TextStyle,
    val lowercase: TextStyle? = null,
    val popup: TextStyle? = null,
    val hint: TextStyle? = null,
)

object TextStyles {
    private fun t(w: Weight, size: Float) = TextStyle(w, size)

    private val LABEL_102 = t(Weight.LABEL, 102f)

    val styles: Map<String, StyleSet> = mapOf(
        "letter" to StyleSet(t(Weight.LABEL, 60f), lowercase = t(Weight.LABEL, 66f), popup = LABEL_102),
        "multichar" to StyleSet(t(Weight.LABEL, 48f), popup = t(Weight.LABEL, 69f)),
        "punctuation-large" to StyleSet(t(Weight.SEMILIGHT, 114f), popup = t(Weight.LABEL, 105f)),
        "icon" to StyleSet(t(Weight.REGULAR, 60f), popup = LABEL_102),
        "numeric" to StyleSet(t(Weight.REGULAR, 96f), hint = t(Weight.REGULAR, 42f)),
        "numeric-zero" to StyleSet(t(Weight.REGULAR, 96f), hint = t(Weight.REGULAR, 63f)),
        "language" to StyleSet(t(Weight.LABEL, 42f), popup = t(Weight.LABEL, 54f)),
        "emoji-category" to StyleSet(t(Weight.REGULAR, 78f)),
        "at" to StyleSet(t(Weight.LABEL, 66f), popup = t(Weight.LABEL, 96f)),
        "dialpad-punctuation" to StyleSet(t(Weight.REGULAR, 144f), popup = t(Weight.LABEL, 105f)),
        "dialpad-star" to StyleSet(t(Weight.SEMILIGHT, 198f)),
        "dialpad-hash" to StyleSet(t(Weight.REGULAR, 108f)),
        "space-medium" to StyleSet(t(Weight.LABEL, 51f)),
    )

    val languageSublabel = t(Weight.LABEL, 48f)
    val popupFallback = LABEL_102
    val pickerLong = t(Weight.LABEL, 69f)
    val candidate = t(Weight.REGULAR, 49.14f)
    val candidateBold = t(Weight.BOLD, 49.14f)
    val candidatePlus = t(Weight.BOLD, 70.2f)
    val candidateEmoji = t(Weight.REGULAR, 79.56f)
    val emojiItem = t(Weight.REGULAR, 108f)
    val emojiItemText = t(Weight.REGULAR, 66f)
    val emojiEmptyMessage = t(Weight.REGULAR, 54f)
    val functionText = t(Weight.REGULAR, 57f)
    val smallText = t(Weight.REGULAR, 39f)
    val appBarLabel = t(Weight.REGULAR, 26f)
    val appBarMenu = t(Weight.REGULAR, 42f)

    fun of(id: String): StyleSet = styles[id] ?: styles.getValue("letter")
}
