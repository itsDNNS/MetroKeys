package app.metrokeys.ime

import app.metrokeys.keyboard.SoundKind

/** Which sound the keyboard's own events play (03 §1.4 "Other sounds"), free of Android. */
object SoundEvents {
    /** An autocorrection applied at a separator (04 §3.3 item 4): the pick sound, after the trigger key's own. */
    val AUTOCORRECTION = SoundKind.PICK

    /** The paste header pasting (03 §6.6 item 5) and the recently-copied chip inserting (03 §6.11 item 4). */
    val PASTE = SoundKind.PASTE

    /** A tap on strip item [index] of [content] (03 §6.3, §6.5): add-to-dictionary or pick; the live swipe word plays nothing. */
    fun pick(content: StripContent, index: Int): SoundKind? {
        val item = content.items.getOrNull(index) ?: return null
        if (content.kind == StripKind.SWIPE_LIVE) return null
        return if (item.addWord) SoundKind.DICTIONARY else SoundKind.PICK
    }

    /**
     * The end of a recognised gesture (06 §5.3, §5.5): nothing recognised ([texts] empty) or an unrecognised gesture
     * plays the add-to-dictionary sound as "not recognised"; an inserted word plays nothing.
     */
    fun swipeResult(texts: List<String>, recognized: Boolean): SoundKind? =
        if (texts.isEmpty() || !recognized) SoundKind.NOT_RECOGNISED else null
}
