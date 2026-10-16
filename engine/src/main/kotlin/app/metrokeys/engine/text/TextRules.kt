package app.metrokeys.engine.text

/** How the space before the caret came into the text (for the double-space period, spec 04 §2). */
public enum class SpaceOrigin {
    /** A normal press of the space key: 400 ms window. */
    KEY,

    /** Inserted together with an autocorrection, or the trailing space after a picked candidate: 1000 ms window. */
    AUTOCORRECTION_OR_PICK,

    /** The trailing space after an emoji: never. */
    EMOJI,
}

/** What to do after a pick or an emoji when looking at the text right of the caret (spec 04 §4.3). */
public enum class TrailingSpaceAction {
    /** Insert a space at the caret. */
    INSERT_SPACE,

    /** Move the caret behind the existing space right of it. */
    SKIP_EXISTING_SPACE,

    /** Do nothing. */
    NOTHING,
}

/** The text-editing rules of spec 04 that do not need Android types. */
public object TextRules {
    /** Autocorrection trigger characters (05 §1.2 `AC_TRIGGERS`, 04 §3.1). */
    public const val AC_TRIGGERS: String = " .,!?;:)\"؟،؛।॥"

    /** No autocorrection of a word directly after one of these (05 `AC_BLOCK_PREV`). */
    public const val AC_BLOCK_PREV: String = "./@\\"

    private const val CAP_DELIMITERS = ".!?:।"
    private const val NO_DOUBLE_SPACE_BEFORE = " .,!?;:-।\r\n"

    @JvmStatic
    public fun isAutocorrectTrigger(c: Char): Boolean = AC_TRIGGERS.indexOf(c) >= 0

    @JvmStatic
    public fun blocksAutocorrect(charBeforeWord: Char?): Boolean = charBeforeWord != null && AC_BLOCK_PREV.indexOf(charBeforeWord) >= 0

    /**
     * Sentence-start rule (04 §1.2): looks only at the last three characters before the caret.
     * Embedded-object markers (U+FFFC) count as a line start.
     */
    @JvmStatic
    public fun isSentenceStart(before: CharSequence): Boolean {
        val n = before.length
        if (n == 0) return true
        val last = before[n - 1]
        if (last == '\n' || last == '\r' || last == '¿' || last == '¡' || last == '￼') return true
        if (last != ' ') return false
        if (n >= 2 && CAP_DELIMITERS.indexOf(before[n - 2]) >= 0) return true
        if (n >= 3 && before[n - 2] == ' ' && CAP_DELIMITERS.indexOf(before[n - 3]) >= 0) return true
        if (n >= 3 && (before[n - 2] == '"' || before[n - 2] == '\'') && CAP_DELIMITERS.indexOf(before[n - 3]) >= 0) return true
        return false
    }

    /**
     * Automatic shift (04 §1.1–1.3). [fieldRequestsSentenceCaps]/[fieldRequestsWordOrCharCaps] are the field's
     * capitalisation flags, [editorCapsMode] the editor's own caps mode at the caret (used for word/character caps).
     */
    @JvmStatic
    public fun autoShift(
        before: CharSequence,
        settingOn: Boolean,
        letterPage: Boolean,
        policy: FieldPolicy,
        fieldRequestsSentenceCaps: Boolean,
        fieldRequestsWordOrCharCaps: Boolean,
        editorCapsMode: Boolean,
    ): Boolean {
        if (!settingOn || !letterPage || !policy.autoCapitalisation) return false
        if (!fieldRequestsSentenceCaps && !fieldRequestsWordOrCharCaps) return false
        val sentence = isSentenceStart(before)
        return if (fieldRequestsWordOrCharCaps) sentence || editorCapsMode else sentence
    }

    /** Double-space window in ms for the origin of the first space, or null for "never". */
    @JvmStatic
    public fun periodWindowMs(origin: SpaceOrigin): Long? = when (origin) {
        SpaceOrigin.KEY -> 400L
        SpaceOrigin.AUTOCORRECTION_OR_PICK -> 1000L
        SpaceOrigin.EMOJI -> null
    }

    /**
     * Double-space period (04 §2): true if typing a space now should replace the space before the caret with
     * ". ". [elapsedMs] is the time since the first space was inserted.
     */
    @JvmStatic
    public fun shouldInsertPeriod(
        before: CharSequence,
        origin: SpaceOrigin,
        elapsedMs: Long,
        settingOn: Boolean,
        policy: FieldPolicy,
    ): Boolean {
        if (!settingOn || !policy.doubleSpacePeriod) return false
        val window = periodWindowMs(origin) ?: return false
        if (elapsedMs < 0 || elapsedMs >= window) return false
        val n = before.length
        if (n < 2 || before[n - 1] != ' ') return false
        return NO_DOUBLE_SPACE_BEFORE.indexOf(before[n - 2]) < 0
    }

    /**
     * Text replacing the typed word when a trigger is typed (05 §10.3): the correction, with its final "." dropped if
     * the trigger is "." as well. The trigger itself is inserted by the caller.
     */
    @JvmStatic
    public fun correctionText(correction: String, trigger: Char): String =
        if (trigger == '.' && correction.endsWith('.')) correction.dropLast(1) else correction

    /** Trailing space after a pick or an emoji (04 §4.3), from the (up to two) characters right of the caret. */
    @JvmStatic
    public fun trailingSpace(after: CharSequence): TrailingSpaceAction {
        if (after.isEmpty()) return TrailingSpaceAction.INSERT_SPACE
        val c = after[0]
        if (c == ' ') {
            if (after.length == 1) return TrailingSpaceAction.SKIP_EXISTING_SPACE
            val d = after[1]
            return if (d == ' ' || d == '\n' || d == '\r') TrailingSpaceAction.SKIP_EXISTING_SPACE else TrailingSpaceAction.NOTHING
        }
        if (c == '\n' || c == '\r') return TrailingSpaceAction.INSERT_SPACE
        return if (isAutocorrectTrigger(c)) TrailingSpaceAction.NOTHING else TrailingSpaceAction.INSERT_SPACE
    }

    /**
     * The Spanish opening marks `¿` `¡` (04 §1.6): no triggers, never an automatic space before or after them, auto-shift
     * after them; after a swiped word a space is inserted first.
     */
    @JvmStatic
    public fun isOpeningMark(text: CharSequence): Boolean = text.length == 1 && (text[0] == '¿' || text[0] == '¡')

    /**
     * Space before a swiped word (06 §5.1 item 1): true if the text before the caret ends with a character that is not
     * whitespace, except directly after an opening mark `¿ ¡` (04 §1.6) and directly after a hyphen that follows a
     * letter (`deixe-` + `me` → `deixe-me`, `E-` + `Mail` → `E-Mail`; a hyphen after a space keeps the space).
     */
    @JvmStatic
    public fun spaceBeforeSwipe(before: CharSequence): Boolean {
        val n = before.length
        if (n == 0) return false
        val last = before[n - 1]
        if (last.isWhitespace() || last == '¿' || last == '¡') return false
        if (last == '-' && n >= 2 && Character.isLetter(before[n - 2])) return false
        return true
    }

    /** Smart space (04 §4.3): an auto space is deleted before this character (a trigger other than space). */
    @JvmStatic
    public fun smartSpaceDeletesBefore(c: Char): Boolean = c != ' ' && isAutocorrectTrigger(c)

    /**
     * Number of characters one step of the held-backspace word delete removes (04 §6.3): all non-word characters
     * directly before the caret, then the word before them. Embedded-object markers stop the deletion.
     */
    @JvmStatic
    public fun wordDeleteLength(before: CharSequence): Int {
        var i = before.length
        while (i > 0 && !TextModel.isWordChar(before[i - 1]) && before[i - 1] != '￼') i--
        while (i > 0 && TextModel.isWordChar(before[i - 1])) i--
        return before.length - i
    }

    /**
     * True if the selection [start, end) of [text] is exactly one whole word (04 §10): word characters only,
     * at most 27 characters, not preceded or followed by a word character.
     */
    @JvmStatic
    public fun isSingleWordSelection(text: CharSequence, start: Int, end: Int): Boolean {
        if (start < 0 || end > text.length || end <= start || end - start > TextModel.WORD_MAX_LEN) return false
        for (i in start until end) if (!TextModel.isWordChar(text[i])) return false
        if (start > 0 && TextModel.isWordChar(text[start - 1])) return false
        if (end < text.length && TextModel.isWordChar(text[end])) return false
        return true
    }

    /** True if the caret is inside a word: a word character directly right of the caret (05 §2.3). */
    @JvmStatic
    public fun caretInsideWord(after: CharSequence): Boolean = after.isNotEmpty() && TextModel.isWordChar(after[0])

    /**
     * The keyboard-side conditions of 04 §3.2 under which an autocorrection reported by the engine may be applied
     * when [trigger] is typed. [enterInMultiLine] is true when the trigger is a line break the keyboard writes itself
     * in a multi-line field.
     */
    @JvmStatic
    public fun mayApplyAutocorrection(
        settingOn: Boolean,
        policy: FieldPolicy,
        typedSinceMove: Boolean,
        caretInsideWord: Boolean,
        charBeforeWord: Char?,
        word: String,
        rejected: Boolean,
        autoSpaceJustRemoved: Boolean,
        swiped: Boolean,
        fromClipboard: Boolean,
        trigger: Char,
        enterInMultiLine: Boolean = false,
    ): Boolean {
        if (!settingOn || !policy.autocorrection) return false
        if (!typedSinceMove || caretInsideWord || swiped || fromClipboard || rejected || autoSpaceJustRemoved) return false
        if (blocksAutocorrect(charBeforeWord)) return false
        if (word.isEmpty() || !word.all { Character.isLetter(it) || it == '\'' || it == '’' }) return false
        if (trigger == '\n') return enterInMultiLine
        return isAutocorrectTrigger(trigger)
    }

    /** Query scheduling (05 §2.3): delay before running a query for a key that came [gapMs] after the previous one. */
    @JvmStatic
    public fun queryDelayMs(gapMs: Long): Long = if (gapMs in 0 until 700) 350 else 0
}
