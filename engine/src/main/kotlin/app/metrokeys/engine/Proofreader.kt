package app.metrokeys.engine

import app.metrokeys.engine.text.TextModel

/** A range [start, end) of the field text. */
public data class TextRange(val start: Int, val end: Int)

/** A spelling mark to apply, with up to 5 correction suggestions. */
public data class SpellMark(val start: Int, val end: Int, val word: String, val suggestions: List<String>)

/** Actions of one proofing run: marks to add and marked ranges to clear. */
public data class ProofreadResult(val add: List<SpellMark>, val remove: List<TextRange>) {
    public companion object {
        @JvmField
        public val NOTHING: ProofreadResult = ProofreadResult(emptyList(), emptyList())
    }
}

/**
 * Engine side of the spelling marks (spec 05 §16, 04 §7): which words around the caret are marked or unmarked.
 * Applying the spans, the run delay and the field checks are up to the caller.
 */
public class Proofreader(private val engine: PredictionEngine) {
    /**
     * One run over [text] with the selection [selStart, selEnd), the currently [marked] ranges and the ranges of
     * autocorrected words ([autocorrected], never marked).
     */
    @JvmOverloads
    public fun run(
        text: CharSequence,
        selStart: Int,
        selEnd: Int,
        marked: Collection<TextRange>,
        autocorrected: Collection<TextRange> = emptyList(),
    ): ProofreadResult {
        if (selStart != selEnd) return ProofreadResult.NOTHING
        val caret = selStart
        val from = maxOf(0, caret - RANGE)
        val to = minOf(text.length, caret + RANGE)
        val markedSet = marked.toSet()
        val noMark = autocorrected.toSet()
        val known = HashMap<String, Boolean>()
        val suggestions = HashMap<String, List<String>>()
        val add = ArrayList<SpellMark>()
        val remove = ArrayList<TextRange>()
        var i = from
        // do not start in the middle of a word
        while (i > 0 && i < text.length && TextModel.isWordChar(text[i - 1]) && TextModel.isWordChar(text[i])) i--
        while (i < to) {
            if (!TextModel.isWordChar(text[i])) {
                i++
                continue
            }
            val s = i
            while (i < text.length && TextModel.isWordChar(text[i])) i++
            val range = TextRange(s, i)
            val word = text.subSequence(s, i).toString()
            if (!TextModel.isCheckable(word)) continue
            val isKnown = known.getOrPut(word) { engine.isKnown(word) }
            val isMarked = range in markedSet
            if (isKnown && isMarked) {
                remove.add(range)
            } else if (!isKnown && !isMarked && range !in noMark && !(caret >= s && caret <= i)) {
                val sug = suggestions[word] ?: if (suggestions.size < MAX_SUGGESTED_WORDS) {
                    engine.spellingSuggestions(word, MAX_SUGGESTIONS).also { suggestions[word] = it }
                } else emptyList()
                add.add(SpellMark(s, i, word, sug))
            }
        }
        return ProofreadResult(add, remove)
    }

    public companion object {
        public const val RANGE: Int = 2000
        public const val MAX_SUGGESTIONS: Int = 5
        public const val MAX_SUGGESTED_WORDS: Int = 20

        /** Delay of a proofing run after its trigger (04 §7.2). */
        public const val RUN_DELAY_MS: Long = 250
    }
}
