package app.metrokeys.engine.text

/** Previous-word context for word pairs (spec 05 §2.2). */
public sealed class PrevContext {
    /** Text start, after a sentence delimiter or a new-sentence character. */
    public object SentenceStart : PrevContext() {
        override fun toString(): String = "SentenceStart"
    }

    /** A previous word (without digits). */
    public data class Word(val word: String) : PrevContext()

    /** Context cleared by another non-word character. */
    public object None : PrevContext() {
        override fun toString(): String = "None"
    }

    /** The word used for pair lookups ("" = sentence start), or null for no context. */
    public val pairKey: String?
        get() = when (this) {
            SentenceStart -> ""
            is Word -> word
            None -> null
        }
}

/** Character classes and word boundaries (spec 05 §2.2, 04 §11). */
public object TextModel {
    public const val WORD_MAX_LEN: Int = 27

    private const val SENTENCE_DELIMITERS = ".!?:।"
    private const val NEW_SENTENCE = "\r\n￹￻\u0007¿¡"
    private const val OPENERS = "„“\"«»‚‘›‹(["

    /** Word characters: letters, digits, apostrophes, combining marks and the listed script marks. */
    @JvmStatic
    public fun isWordChar(c: Char): Boolean {
        val cp = c.code
        if (c == '\'' || c == '’') return true
        if (cp in 0x0300..0x036F) return true
        if (cp in 0x0591..0x05C7) return true
        if (cp in 0x0610..0x0615 || cp in 0x064B..0x065E || cp == 0x0670 || cp in 0x06D6..0x06E4 ||
            cp in 0x06E7..0x06ED || cp == 0x0640
        ) return true
        if (cp in 0x0900..0x0963 || cp in 0x0966..0x097F) return true
        if (Character.isSurrogate(c)) return false
        if (cp in 0x2300..0x33FF) return false
        return Character.isLetterOrDigit(c)
    }

    @JvmStatic
    public fun isWordCodePoint(cp: Int): Boolean =
        if (cp > 0xFFFF) Character.isLetterOrDigit(cp) && cp < 0x1F000 else isWordChar(cp.toChar())

    @JvmStatic
    public fun isSentenceDelimiter(c: Char): Boolean = SENTENCE_DELIMITERS.indexOf(c) >= 0

    @JvmStatic
    public fun isNewSentenceChar(c: Char): Boolean = NEW_SENTENCE.indexOf(c) >= 0

    @JvmStatic
    public fun isOpener(c: Char): Boolean = OPENERS.indexOf(c) >= 0

    /** Start index of the current word (the maximal run of word characters directly before the end of [before]). */
    @JvmStatic
    public fun currentWordStart(before: CharSequence): Int {
        var i = before.length
        while (i > 0 && isWordChar(before[i - 1])) i--
        return i
    }

    @JvmStatic
    public fun currentWord(before: CharSequence): String = before.subSequence(currentWordStart(before), before.length).toString()

    /** The character directly before the current word, or null at the text start. */
    @JvmStatic
    public fun charBeforeCurrentWord(before: CharSequence): Char? {
        val s = currentWordStart(before)
        return if (s > 0) before[s - 1] else null
    }

    /**
     * Up to [n] words before position [end] (normally the start of the current word), oldest first; collected
     * backwards and stopping at a sentence delimiter or a new-sentence character.
     */
    @JvmStatic
    public fun precedingWords(before: CharSequence, end: Int, n: Int): List<String> {
        val out = ArrayList<String>()
        var i = end
        while (i > 0 && out.size < n) {
            val c = before[i - 1]
            if (isSentenceDelimiter(c) || isNewSentenceChar(c)) break
            if (isWordChar(c)) {
                val e = i
                while (i > 0 && isWordChar(before[i - 1])) i--
                out.add(before.subSequence(i, e).toString())
            } else {
                i--
            }
        }
        out.reverse()
        return out
    }

    /** Previous word for word pairs (strict static context, spec 05 §2.2) of the word starting at [wordStart]. */
    @JvmStatic
    public fun previousWordForPairs(before: CharSequence, wordStart: Int): PrevContext {
        var i = wordStart
        while (i > 0 && before[i - 1].isWhitespace() && !isNewSentenceChar(before[i - 1])) i--
        if (i == 0) return PrevContext.SentenceStart
        val c = before[i - 1]
        if (isSentenceDelimiter(c) || isNewSentenceChar(c)) return PrevContext.SentenceStart
        if (isOpener(c)) {
            while (i > 0 && isOpener(before[i - 1])) i--
            while (i > 0 && before[i - 1].isWhitespace() && !isNewSentenceChar(before[i - 1])) i--
            if (i == 0) return PrevContext.SentenceStart
            val d = before[i - 1]
            return if (isSentenceDelimiter(d) || isNewSentenceChar(d)) PrevContext.SentenceStart else PrevContext.None
        }
        if (isWordChar(c)) {
            val e = i
            while (i > 0 && isWordChar(before[i - 1])) i--
            val w = before.subSequence(i, e).toString()
            return if (w.any { it.isDigit() }) PrevContext.None else PrevContext.Word(w)
        }
        return PrevContext.None
    }

    /** The ordinal indicators º ª: letters in Unicode, but never part of a dictionary word (05 §2.2, 04 §11.1). */
    @JvmStatic
    public fun isOrdinalIndicator(c: Char): Boolean = c == 'º' || c == 'ª'

    /**
     * True if [s] contains only letters, apostrophes and combining marks (spelling-check filter); a word with an ordinal
     * indicator is never checked (05 §16).
     */
    @JvmStatic
    public fun isCheckable(s: String): Boolean {
        if (s.length !in 2..127) return false
        for (c in s) {
            if (isOrdinalIndicator(c)) return false
            val ok = Character.isLetter(c) || c == '\'' || c == '’' || Character.getType(c) == Character.NON_SPACING_MARK.toInt() ||
                Character.getType(c) == Character.COMBINING_SPACING_MARK.toInt() || Character.getType(c) == Character.ENCLOSING_MARK.toInt()
            if (!ok) return false
        }
        return true
    }

    /** True if [s] contains a letter and consists of word characters only. */
    @JvmStatic
    public fun isLearnable(s: String): Boolean = s.any { Character.isLetter(it) } && s.all { isWordChar(it) }

    /** Trims leading and trailing non-word characters. */
    @JvmStatic
    public fun trimNonWord(s: String): String {
        var a = 0
        var b = s.length
        while (a < b && !isWordChar(s[a])) a++
        while (b > a && !isWordChar(s[b - 1])) b--
        return s.substring(a, b)
    }

    /**
     * Symbol characters handled like emoji (04 §11): U+2300–U+33FF, ™, ℹ, arrows U+2194–U+2199, ↩ ↪, ‼ ⁉, © ®.
     */
    @JvmStatic
    public fun isSymbolChar(cp: Int): Boolean =
        cp in 0x2300..0x33FF || cp == 0x2122 || cp == 0x2139 || cp in 0x2194..0x2199 || cp == 0x21A9 || cp == 0x21AA ||
            cp == 0x203C || cp == 0x2049 || cp == 0x00A9 || cp == 0x00AE

    @JvmStatic
    public fun isEmojiCodePoint(cp: Int): Boolean = cp >= 0x1F000
}
