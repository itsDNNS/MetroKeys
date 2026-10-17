package app.metrokeys.engine.predict

/**
 * Shape-writing constants that differ between keyboard languages (spec 06 §7.1). Every language has its own column;
 * a multilingual search uses each language's own constants on its index (06 §4.1).
 */
public data class ShapeParams(
    /** Cost per key of the trace the word does not use. */
    val unusedKeyCost: Double,
    /** Neighbour match = factor × distance value. */
    val neighbourFactor: Double,
    /** A real double letter on one key. */
    val doubleLetterCost: Double,
    /** Accent cost = `swAccentDistance` (50) × factor. */
    val accentFactor: Double,
    /** Corner threshold in degrees. */
    val cornerAngle: Float,
) {
    /** Cost of a letter that reaches its key only through its base letter (06 §4.3 A2). */
    val accentCost: Double get() = ACCENT_DISTANCE * accentFactor

    public companion object {
        public const val ACCENT_DISTANCE: Double = 50.0
    }
}

/**
 * Per-language parameters of spec 05 §1.1 (weights, limits and language rules), the fallback letter grid of 05 §4,
 * the shape-writing constants of 06 §7.1 and the emoji table of 05 Appendix A. A further language adds one instance
 * with every value of its own (no inheritance from another language) and registers it in [ALL].
 */
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
    /** `AC_INSERT`, `AC_DELETE`, `AC_SPACE`, `AC_SWAP`: the edit weights of the autocorrection decision (§10.1, 0.7.1). */
    val acInsert: Int,
    val acDelete: Int,
    val acSpace: Int,
    val acSwap: Int,
    /** `SLIP_NEAR`, `SLIP_ACCENT`, `SLIP_FILLER`: slips of the key-slip search (§8.1, 0.7.1). */
    val slipNear: Int,
    val slipAccent: Int,
    val slipFiller: Int,
    /**
     * `SLIP_ACCENTS` (up to 0.7.0 `PREFIX_ACCENTS`): lower-case base letter → the accented key letters a typed base
     * letter may stand for in the key-slip search (§8.1; one direction only).
     */
    val slipAccents: Map<Char, List<Char>>,
    /** `ONE_LETTER_WORDS`: the only one-letter dictionary keys, also the only one-letter parts of a split (§8.2). */
    val oneLetterWords: Set<String>,
    /** `SINGLE_LETTER_FIX`: one-letter words that are autocorrected (§10.1). */
    val singleLetterFix: Map<String, String>,
    /** `COMPOUND_LINKS`: linking elements of the compound rule (§7 rule 6); null switches the rule off. */
    val compoundLinks: List<String>?,
    /** `SPLIT_SPACE`: missing-space split candidates (§8.2); off for languages that form compounds. */
    val missingSpaceSplit: Boolean,
    /** `ING_RULE`: "…in" counts as valid like "…ing" (§7 rule 7). */
    val ingRule: Boolean,
    /**
     * `ELISION_PREFIXES` (extension, §2.2): elided prefixes the engine strips from the current word, lower case with the
     * ASCII apostrophe ("d'" for pt-BR). Empty for no elision.
     */
    val elisionPrefixes: List<String>,
    /**
     * `ACCENT_SWAP` (extension, §5): a typed accented letter for a differently accented candidate letter with the same
     * base letter ("è" for "é") costs `ED_DIACRITIC` instead of a substitution.
     */
    val accentSwap: Boolean,
    /**
     * Shape writing (06 §4.1) leaves out dictionary forms with class 0 (the dominated spellings and real-word typos of
     * 05b; they are valid but never a swipe result). The candidate bar never offers class-0 forms in any language
     * (05 §8 "Offered forms", 0.7.1).
     */
    val hideClassZero: Boolean = false,
    /**
     * The full words of the elision prefixes (05b §10.6, §11.7): the unelided phrase "full word + F" in the pair table
     * keeps the unpaired elision candidate of 05 §8.2.1 item 1a away ("un altro" for `un'` + "altro").
     */
    val elisionFullWords: Map<String, List<String>> = emptyMap(),
    /**
     * `ELISION_VALID_SLACK` (extension, §8.2.1 item 1): an elision split of a valid typed word is kept only if its
     * correction cost is at most the typed word's validity cost plus this value; null = no limit.
     */
    val elisionValidSlack: Int? = null,
    /**
     * `CAPS_UNACCENTED` (§5, §7): an upper-case letter without diacritic may stand for the same upper-case letter with a
     * diacritic ("Etat" for "État").
     */
    val capsUnaccented: Boolean = false,
    /**
     * `UMLAUT_TRANSLIT` (extension, §5, §8.4 item 5; 0.7.0): typed "ae"/"oe"/"ue" for a candidate ä/ö/ü costs
     * `ED_DIACRITIC` (umlaut rule; 1 unit), and a prefix with "ae"/"oe"/"ue" also completes with the umlaut.
     */
    val umlautTranslit: Boolean = false,
    /**
     * The unattested-key rule of §10.1 step 5: a word valid only through an unattested lower-case key no longer blocks a
     * clearly better correction. Off in 0.6.9 (owner decision: in effect from 0.7.0 with the spelling dictionaries);
     * the one switch is [Params.UNATTESTED_KEYS_RULE].
     */
    val unattestedKeysRule: Boolean = Params.UNATTESTED_KEYS_RULE,
    /** Letter rows of the fallback grid (§4), top to bottom. */
    val fallbackRows: List<String>,
    /** Start of fallback row 2 as a fraction of the width (§4: 0.05, Spanish 0). */
    val fallbackRow2Start: Float,
    /** Start of fallback row 3 as a fraction of the width (§4: 0.15, French 0.2). */
    val fallbackRow3Start: Float = 0.15f,
    /** Shape-writing constants (06 §7.1). */
    val shape: ShapeParams,
    /** Emoji table (Appendix A), lower-cased word → emoji. */
    val emoji: Map<String, String>,
) {
    public companion object {
        @JvmField
        public val GERMAN: LanguageParams = LanguageParams(
            languageTag = "de-DE",
            edInsert = 210, edDelete = 175, edSubstitute = 100, edSwap = 50, edSpace = 225, edCase = 44,
            edDiacritic = 4, edFiller = 0, edNearDiv = 13, edNearBase = 1,
            acLimit = 255, acEditFactor = 5, acMargin = 33, acValidRare = 157, acValidTarget = 168,
            acInsert = 60, acDelete = 60, acSpace = 60, acSwap = 30,
            slipNear = 34, slipAccent = 24, slipFiller = 0,
            slipAccents = mapOf('a' to listOf('ä'), 'o' to listOf('ö'), 'u' to listOf('ü'), 's' to listOf('ß'), 'e' to listOf('é')),
            oneLetterWords = emptySet(),
            singleLetterFix = emptyMap(),
            compoundLinks = listOf("", "s", "es", "n", "en", "er"),
            missingSpaceSplit = false,
            ingRule = false,
            elisionPrefixes = emptyList(),
            accentSwap = false,
            // 0.6.4: real-word typos of the German build (05b §3.6) are class 0: valid, never offered
            hideClassZero = true,
            // 0.7.0: "fuer" → "für" costs ED_DIACRITIC (umlaut rule, §5)
            umlautTranslit = true,
            fallbackRows = listOf("qwertzuiop", "asdfghjkl", "yxcvbnm"),
            fallbackRow2Start = 0.05f,
            shape = ShapeParams(unusedKeyCost = 10.0, neighbourFactor = 0.096, doubleLetterCost = 14.0, accentFactor = 0.24, cornerAngle = 45f),
            emoji = EmojiTables.GERMAN,
        )

        @JvmField
        public val ENGLISH: LanguageParams = LanguageParams(
            languageTag = "en-US",
            edInsert = 119, edDelete = 119, edSubstitute = 140, edSwap = 56, edSpace = 131, edCase = 11,
            edDiacritic = 0, edFiller = 0, edNearDiv = 13, edNearBase = 20,
            acLimit = 303, acEditFactor = 5, acMargin = 16, acValidRare = 202, acValidTarget = 150,
            acInsert = 79, acDelete = 79, acSpace = 79, acSwap = 49,
            slipNear = 30, slipAccent = 1, slipFiller = 5,
            slipAccents = mapOf('e' to listOf('é')),
            oneLetterWords = setOf("a", "i"),
            singleLetterFix = mapOf("i" to "I"),
            compoundLinks = null,
            missingSpaceSplit = true,
            ingRule = true,
            elisionPrefixes = emptyList(),
            accentSwap = false,
            fallbackRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
            fallbackRow2Start = 0.05f,
            shape = ShapeParams(unusedKeyCost = 8.0, neighbourFactor = 0.083, doubleLetterCost = 14.0, accentFactor = 0.01, cornerAngle = 45f),
            emoji = EmojiTables.ENGLISH,
        )

        @JvmField
        public val SPANISH: LanguageParams = LanguageParams(
            languageTag = "es-ES",
            edInsert = 225, edDelete = 240, edSubstitute = 185, edSwap = 65, edSpace = 208, edCase = 44,
            edDiacritic = 0, edFiller = 0, edNearDiv = 13, edNearBase = 20,
            acLimit = 260, acEditFactor = 4, acMargin = 33, acValidRare = 157, acValidTarget = 137,
            acInsert = 79, acDelete = 79, acSpace = 79, acSwap = 49,
            slipNear = 20, slipAccent = 24, slipFiller = 0,
            slipAccents = mapOf(
                'a' to listOf('á'), 'e' to listOf('é'), 'i' to listOf('í'), 'o' to listOf('ó'),
                'u' to listOf('ú', 'ü'), 'n' to listOf('ñ'),
            ),
            oneLetterWords = setOf("a", "e", "o", "u", "y"),
            singleLetterFix = emptyMap(),
            compoundLinks = null,
            missingSpaceSplit = true,
            ingRule = false,
            elisionPrefixes = emptyList(),
            accentSwap = false,
            fallbackRows = listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm"),
            fallbackRow2Start = 0f,
            shape = ShapeParams(unusedKeyCost = 8.0, neighbourFactor = 0.092, doubleLetterCost = 19.0, accentFactor = 0.24, cornerAngle = 45f),
            emoji = EmojiTables.SPANISH,
        )

        @JvmField
        public val PORTUGUESE: LanguageParams = LanguageParams(
            languageTag = "pt-BR",
            edInsert = 165, edDelete = 240, edSubstitute = 155, edSwap = 65, edSpace = 213, edCase = 44,
            edDiacritic = 30, edFiller = 0, edNearDiv = 13, edNearBase = 15,
            acLimit = 293, acEditFactor = 5, acMargin = 33, acValidRare = 201, acValidTarget = 130,
            acInsert = 74, acDelete = 74, acSpace = 74, acSwap = 44,
            slipNear = 48, slipAccent = 16, slipFiller = 0,
            slipAccents = mapOf(
                'a' to listOf('á', 'à', 'ã', 'â'), 'c' to listOf('ç'), 'e' to listOf('é', 'ê'), 'i' to listOf('í'),
                'o' to listOf('ó', 'õ', 'ô'), 'u' to listOf('ú', 'ü'),
            ),
            oneLetterWords = setOf("a", "à", "e", "é", "o"),
            singleLetterFix = emptyMap(),
            compoundLinks = null,
            missingSpaceSplit = true,
            ingRule = false,
            elisionPrefixes = listOf("d'"),
            accentSwap = false,
            fallbackRows = listOf("qwertyuiop", "asdfghjklç", "zxcvbnm"),
            fallbackRow2Start = 0f,
            shape = ShapeParams(unusedKeyCost = 12.0, neighbourFactor = 0.092, doubleLetterCost = 14.0, accentFactor = 0.16, cornerAngle = 45f),
            emoji = EmojiTables.PORTUGUESE,
        )

        /** The 41 Italian elision prefixes of 05 §1.1 (notes on it-IT), in order of their frequency. */
        @JvmField
        public val ITALIAN_ELISION_PREFIXES: List<String> = (
            "l' c' un' d' dell' all' cos' dov' nell' dall' quell' com' anch' quest' sull' n' dev' gliel' m' qualcos' " +
                "quand' nient' s' bell' t' mezz' quant' brav' vent' neanch' tutt' senz' nessun' qualcun' trent' sott' v' " +
                "sant' grand' quarant' cinquant'"
            ).split(' ')

        @JvmField
        public val ITALIAN: LanguageParams = LanguageParams(
            languageTag = "it-IT",
            edInsert = 200, edDelete = 240, edSubstitute = 160, edSwap = 65, edSpace = 225, edCase = 44,
            edDiacritic = 2, edFiller = 0, edNearDiv = 11, edNearBase = 16,
            acLimit = 290, acEditFactor = 5, acMargin = 28, acValidRare = 157, acValidTarget = 130,
            acInsert = 81, acDelete = 81, acSpace = 81, acSwap = 51,
            slipNear = 41, slipAccent = 16, slipFiller = 0,
            slipAccents = mapOf(
                'e' to listOf('é', 'è'), 'i' to listOf('ì'), 'o' to listOf('ò', 'ó'), 'u' to listOf('ù'), 'a' to listOf('à'),
            ),
            oneLetterWords = setOf("a", "e", "è", "i", "o"),
            singleLetterFix = emptyMap(),
            compoundLinks = null,
            missingSpaceSplit = true,
            ingRule = false,
            elisionPrefixes = ITALIAN_ELISION_PREFIXES,
            accentSwap = true,
            hideClassZero = true,
            // 05b §10.6: the prefix's letters as a word of their own
            elisionFullWords = ITALIAN_ELISION_PREFIXES.associateWith { listOf(it.dropLast(1)) },
            fallbackRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"),
            fallbackRow2Start = 0.05f,
            shape = ShapeParams(unusedKeyCost = 8.0, neighbourFactor = 0.083, doubleLetterCost = 14.0, accentFactor = 0.16, cornerAngle = 45f),
            emoji = EmojiTables.ITALIAN,
        )

        /** The 14 French elision prefixes of 05 §1.1 (notes on fr-FR), in order of their frequency. */
        @JvmField
        public val FRENCH_ELISION_PREFIXES: List<String> =
            "c' l' j' d' qu' n' t' m' s' jusqu' lorsqu' puisqu' quoiqu' ç'".split(' ')

        @JvmField
        public val FRENCH: LanguageParams = LanguageParams(
            languageTag = "fr-FR",
            edInsert = 125, edDelete = 215, edSubstitute = 125, edSwap = 60, edSpace = 156, edCase = 44,
            edDiacritic = 14, edFiller = 0, edNearDiv = 13, edNearBase = 5,
            acLimit = 282, acEditFactor = 5, acMargin = 32, acValidRare = 163, acValidTarget = 136,
            acInsert = 64, acDelete = 64, acSpace = 64, acSwap = 34,
            slipNear = 48, slipAccent = 16, slipFiller = 0,
            slipAccents = mapOf(
                'a' to listOf('à', 'â', 'æ'), 'e' to listOf('é', 'è', 'ê', 'ë'), 'y' to listOf('ÿ'),
                'u' to listOf('ù', 'û', 'ü'), 'i' to listOf('ï', 'î'), 'o' to listOf('ô', 'œ'), 'c' to listOf('ç'),
            ),
            oneLetterWords = setOf("a", "à", "y"),
            singleLetterFix = emptyMap(),
            compoundLinks = null,
            missingSpaceSplit = true,
            ingRule = false,
            elisionPrefixes = FRENCH_ELISION_PREFIXES,
            accentSwap = true,
            hideClassZero = true,
            // 05b §11.7 item 1
            elisionFullWords = mapOf(
                "c'" to listOf("ce"), "l'" to listOf("le", "la"), "j'" to listOf("je"), "d'" to listOf("de"),
                "qu'" to listOf("que"), "n'" to listOf("ne"), "s'" to listOf("se", "si"), "m'" to listOf("me"),
                "t'" to listOf("te"), "jusqu'" to listOf("jusque"), "lorsqu'" to listOf("lorsque"),
                "puisqu'" to listOf("puisque"), "quoiqu'" to listOf("quoique"), "ç'" to listOf("ça"),
            ),
            elisionValidSlack = 30,
            capsUnaccented = true,
            fallbackRows = listOf("azertyuiop", "qsdfghjklm", "wxcvbn"),
            fallbackRow2Start = 0f,
            fallbackRow3Start = 0.2f,
            shape = ShapeParams(unusedKeyCost = 14.0, neighbourFactor = 0.140, doubleLetterCost = 18.0, accentFactor = 0.16, cornerAngle = 45f),
            emoji = EmojiTables.FRENCH,
        )

        /** Every supported keyboard language, in the fixed language order (alphabetical by tag, 07 §9.5). */
        @JvmField
        public val ALL: List<LanguageParams> = listOf(GERMAN, ENGLISH, SPANISH, FRENCH, ITALIAN, PORTUGUESE)

        /**
         * Parameters of a supported language tag ("de-DE", "en-US", "es-ES", "fr-FR", "it-IT", "pt-BR"; a bare "de" also
         * matches).
         */
        @JvmStatic
        public fun forLanguage(tag: String): LanguageParams =
            ALL.firstOrNull { it.languageTag == tag } ?: ALL.firstOrNull { it.languageTag.substringBefore('-') == tag.substringBefore('-') }
                ?: throw IllegalArgumentException("unsupported language $tag")

        /** True if [tag] is a supported keyboard language. */
        @JvmStatic
        public fun isSupported(tag: String): Boolean = ALL.any { it.languageTag == tag }
    }
}

/** Language-independent parameters of spec 05 §1.2. */
public object Params {
    public const val BAR_MAX: Int = 11
    public const val WORD_MAX_LEN: Int = 27
    public const val ED_MAX_LEN: Int = 18
    public const val ED_OVERFLOW: Int = 128000
    public const val UNIT_FLOOR: Int = 3
    public const val UNIT_SLOPE: Double = 0.60
    public const val COMP_SURCHARGE: Int = 10
    public const val COMP_EDIT_FACTOR: Int = 400
    public const val COMP_LIMIT: Int = 400

    /** Missing-space split (05 §8.2): surcharge on the mean part cost, cost of a part missing from the word list. */
    public const val SPLIT_SURCHARGE: Int = 80
    public const val SPLIT_UNKNOWN: Int = 300

    /** Key-slip search (§8.1): same-length entries kept, completions kept, entries handed over. */
    public const val SLIP_SAME_KEEP: Int = 5
    public const val SLIP_COMP_KEEP: Int = 12
    public const val SLIP_HANDOVER: Int = 12

    /** Spelling source (§8.1b): words taken, lm of a spelling suggestion that is no offered form. */
    public const val SPELL_SLOTS: Int = 5
    public const val SPELL_UNKNOWN_LM: Int = 300

    /** Forms beyond this frequency rank are valid but not offered (§8, 05b §1.1). */
    public const val SUGGEST_MAX_RANK: Int = 90000

    /** Omission guard (§10.1 step 7a). */
    public const val AC_OMISSION_MARGIN: Int = 20

    /** Missing-space guard: frequency rank limit of both parts (§10.1 step 7a; the header's guard class). */
    public const val AC_SPLIT_RANK: Int = 5000

    /** Anchored cost scale (§3.2): cost per decade above the first anchor, cost at the lowest class ≥ 1. */
    public const val LM_HEAD_SLOPE: Double = 23.0
    public const val LM_TAIL_COST: Int = 254

    /** Multilingual cost penalty per decade of language weight (§14.2). */
    public const val LANG_DECADE_COST: Double = 23.0
    public const val PAIR_LAMBDA: Double = 0.5
    public const val USER_LAMBDA_STEP: Double = 0.06
    public const val USER_LAMBDA_CAP: Double = 0.24
    public const val USER_TOTAL_OFFSET: Int = 15000
    public const val RECENT_FACTOR: Int = 2
    public const val RECENT_WINDOW: Int = 1000
    public const val LEARN_MIN_COUNT: Int = 2

    /**
     * Default of [LanguageParams.unattestedKeysRule] for every language (05 §10.1 step 5): false in 0.6.9, the flags of
     * the word lists are stored but not used for autocorrection until 0.7.0.
     */
    public const val UNATTESTED_KEYS_RULE: Boolean = false

    /** Uses before a word the dictionary does not know becomes valid / suggestible (extension, 05 §12.5). */
    public const val LEARN_MIN_UNKNOWN: Int = 3

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

    /** Followers stored per elision prefix (05b §1.1) and examined by 05 §2.2 and §8.2.1. */
    public const val PREFIX_FOLLOWERS_MAX: Int = 4000
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

    /** Most languages that multilingual typing runs (and keeps loaded) at once (05 §14.5). */
    public const val LANG_ACTIVE_MAX: Int = 3
}
