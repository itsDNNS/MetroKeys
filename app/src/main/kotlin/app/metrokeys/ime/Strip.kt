package app.metrokeys.ime

/** Where the candidates of the strip came from; decides what a pick replaces (04 §4.1). */
enum class StripKind {
    /** Empty list (only the paste header). */
    NONE,

    /** Candidates for the word being typed. */
    TYPING,

    /** Next-word predictions after a separator: a pick is inserted at the caret. */
    PREDICTION,

    /** A list stored on a resumed word (03 §6.4) or the result of a correction search on resumption. */
    RESUMED,

    /** The recognised word and its alternatives after a swipe. */
    SWIPE,

    /** Alternatives of an unrecognised swipe: nothing was inserted (06 §5.5). */
    SWIPE_UNRECOGNISED,

    /** The best word of a running swipe (06 §5.3): shown only during the gesture, a tap on it does nothing. */
    SWIPE_LIVE,

    /** Candidates for exactly one selected word: a pick replaces the selection. */
    SELECTION,

    /**
     * Candidates for the word around a collapsed caret inside it (04 §10 item 5, first case, 0.6.4): a pick replaces
     * the whole word, the caret ends after it, no space is added (04 §4.1).
     */
    CARET_WORD,
}

/** One entry of the candidate strip. */
data class StripItem(
    val text: String,
    /** Drawn bold: the autocorrection or the inserted swiped word. */
    val bold: Boolean = false,
    val emoji: Boolean = false,
    /** The "+ word" add-to-dictionary item (03 §6.5). */
    val addWord: Boolean = false,
    val autocorrection: Boolean = false,
    /** An elision prefix ("l'", 05 §8.2.1 item 3): picked without a trailing space (04 §4.3). */
    val elisionPrefix: Boolean = false,
)

/** The candidate list of the strip with what it refers to. */
data class StripContent(
    val kind: StripKind,
    val items: List<StripItem>,
    /** The word the list belongs to (typed / resumed / selected / swiped word). */
    val word: String = "",
    /** Absolute start of [word] in the field, −1 if not applicable. */
    val wordStart: Int = -1,
    /** The stored list shown, if the list came from one (moved to the picked word, 04 §4.2.5). */
    val stored: StoredList? = null,
) {
    val isEmpty: Boolean get() = items.isEmpty()

    /** No word is being typed: empty or only predictions/emoji (clipboard button, 03 §6.7.2). */
    val allowsClipboardButton: Boolean get() = kind == StripKind.NONE || kind == StripKind.PREDICTION

    companion object {
        val EMPTY = StripContent(StripKind.NONE, emptyList())
    }
}

/** What kind of list a stored list is (03 §6.4.1). */
enum class StoredKind { AUTOCORRECTION, SWIPE, SEARCH, PICKED }

/**
 * A candidate list attached to a word of the text (03 §6.4): [items] in stored order. For an autocorrection the
 * typed word is first and [autoTyped]/[autoCorrection] are set (undo, 04 §3.4).
 */
data class StoredList(
    val start: Int,
    val word: String,
    val items: List<String>,
    val kind: StoredKind,
    val autoTyped: String? = null,
    val autoCorrection: String? = null,
) {
    val end: Int get() = start + word.length
}
