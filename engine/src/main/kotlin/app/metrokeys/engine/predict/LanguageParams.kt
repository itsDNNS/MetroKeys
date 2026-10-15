package app.metrokeys.engine.predict

/** Per-language parameters of spec 05 §1.1. */
public data class LanguageParams(
    val languageTag: String,
    val edInsert: Int,
    val edDelete: Int,
    val edSubstitute: Int,
    val edSwap: Int,
    val edSpace: Int,
    val edCase: Int,
    val edDiacritic: Int,
    val edFiller: Int,
    val edNearDiv: Int,
    val edNearBase: Int,
    val acLimit: Int,
    val acEditFactor: Int,
    val acMargin: Int,
    val acValidRare: Int,
    val acValidTarget: Int,
    /** Accent variants tried for completions (lower case base → accented). */
    val prefixAccents: Map<Char, Char>,
    /** Linking elements of the compound rule; null = no compounds. */
    val compoundLinks: List<String>?,
    /** Missing-space split candidates (05 §8.2); off for languages that form compounds. */
    val missingSpaceSplit: Boolean,
    /** One-letter words that are autocorrected. */
    val singleLetterFix: Map<String, String>,
    /** "…in" counts as valid like "…ing". */
    val ingRule: Boolean,
    /** Emoji table (Appendix A), lower-cased word → emoji. */
    val emoji: Map<String, String>,
) {
    public companion object {
        @JvmField
        public val GERMAN: LanguageParams = LanguageParams(
            languageTag = "de-DE",
            edInsert = 210, edDelete = 175, edSubstitute = 100, edSwap = 50, edSpace = 225, edCase = 44,
            edDiacritic = 4, edFiller = 0, edNearDiv = 13, edNearBase = 1,
            acLimit = 212, acEditFactor = 5, acMargin = 33, acValidRare = 157, acValidTarget = 168,
            prefixAccents = mapOf('a' to 'ä', 'o' to 'ö', 'u' to 'ü', 's' to 'ß'),
            compoundLinks = listOf("", "s", "es", "n", "en", "er"),
            missingSpaceSplit = false,
            singleLetterFix = emptyMap(),
            ingRule = false,
            emoji = EmojiTables.GERMAN,
        )

        @JvmField
        public val ENGLISH: LanguageParams = LanguageParams(
            languageTag = "en-US",
            edInsert = 119, edDelete = 119, edSubstitute = 140, edSwap = 56, edSpace = 131, edCase = 11,
            edDiacritic = 0, edFiller = 0, edNearDiv = 13, edNearBase = 20,
            acLimit = 260, acEditFactor = 5, acMargin = 16, acValidRare = 202, acValidTarget = 150,
            prefixAccents = mapOf('e' to 'é'),
            compoundLinks = null,
            missingSpaceSplit = true,
            singleLetterFix = mapOf("i" to "I"),
            ingRule = true,
            emoji = EmojiTables.ENGLISH,
        )

        /** Parameters for "de…" or "en…" language tags. */
        @JvmStatic
        public fun forLanguage(tag: String): LanguageParams = when {
            tag.startsWith("de") -> GERMAN
            tag.startsWith("en") -> ENGLISH
            else -> throw IllegalArgumentException("unsupported language $tag")
        }
    }
}

/** Language-independent parameters of spec 05 §1.2. */
public object Params {
    public const val BAR_MAX: Int = 11
    public const val WORD_MAX_LEN: Int = 27
    public const val ED_MAX_LEN: Int = 18
    public const val ED_OVERFLOW: Int = 128000
    public const val CORR_KEEP: Int = 5
    public const val FUZZY_POOL: Int = 48
    public const val UNIT_FLOOR: Int = 3
    public const val UNIT_SLOPE: Double = 0.60
    public const val COMP_SURCHARGE: Int = 10
    public const val COMP_EDIT_FACTOR: Int = 400
    public const val COMP_LIMIT: Int = 400

    /** Missing-space split (05 §8.2): surcharge on the mean part cost, cost of a part missing from the word list. */
    public const val SPLIT_SURCHARGE: Int = 80
    public const val SPLIT_UNKNOWN: Int = 300
    public const val COMPLETIONS_TYPED: Int = 24
    public const val COMPLETIONS_VARIANT: Int = 6
    public const val PREFIX_VARIANTS_MAX: Int = 24
    public const val VARIANT_NEIGHBOURS: Int = 6
    public const val VARIANT_NEAR_MAX: Int = 700
    public const val VARIANT_MAX_LEN: Int = 12
    public const val LM_COST_AT_P1: Double = 15.0
    public const val LM_COST_PER_DECADE: Double = 18.0
    public const val PAIR_LAMBDA: Double = 0.5
    public const val USER_LAMBDA_STEP: Double = 0.06
    public const val USER_LAMBDA_CAP: Double = 0.24
    public const val USER_TOTAL_OFFSET: Int = 15000
    public const val RECENT_FACTOR: Int = 2
    public const val RECENT_WINDOW: Int = 1000
    public const val LEARN_MIN_COUNT: Int = 2

    /** Uses before a word the dictionary does not know becomes valid / suggestible (extension, 05 §12.5). */
    public const val LEARN_MIN_UNKNOWN: Int = 3

    /** Undone corrections before a word the dictionary does not know becomes valid (extension, 05 §10.4). */
    public const val REJECT_MIN_UNKNOWN: Int = 2
    public const val LEARN_MIN_LEN: Int = 2
    public const val LEARN_CONTEXT: Int = 2
    public const val USER_MAX_WORDS: Int = 10000
    public const val USER_MAX_CONTEXTS: Int = 20000
    public const val USER_MAX_NEXT: Int = 64
    public const val UNDO_MAX: Int = 1000
    public const val ADD_WORD_COUNT: Int = 3
    public const val NEXT_BACKOFF: Double = 0.4
    public const val NEXT_PAIR_FETCH: Int = 22
    public const val NEXT_FILLER: Int = 8
    public const val NAME_SURCHARGE: Int = 69
    public const val NAME_RUN_MAX: Int = 5
    public const val NAME_MAX: Int = 10000
    public const val NAME_MAX_LEN: Int = 127
    public const val TOUCH_KX: Double = 13516.8
    public const val TOUCH_KY: Double = 18432.0
    public const val TOUCH_UNIT: Double = 35.0
    public const val NEAR_MAX: Int = 1000

    // multilingual (§14)
    public const val LANG_PRIOR: Double = 0.65
    public const val LANG_WORDS: Int = 8
    public const val LANG_DECAY: Double = 0.8
    public const val LANG_CLAMP: Double = 2.0
    public const val LANG_UNKNOWN: Double = -9.0
    public const val LANG_W_MIN: Double = 1e-4
    public const val LANG_AC_MIN: Double = 0.3
    public const val LANG_TYPO: Double = 2.0
}
