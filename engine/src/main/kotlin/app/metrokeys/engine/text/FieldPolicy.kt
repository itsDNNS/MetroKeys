package app.metrokeys.engine.text

/**
 * Per-input-type policy (spec 05 §15, 04 §8.2). Since 0.6.8 search fields also get the double-space period (04 §2).
 */
public enum class FieldPolicy(
    public val prediction: Boolean,
    public val autocorrection: Boolean,
    public val autoCapitalisation: Boolean,
    public val doubleSpacePeriod: Boolean,
    public val learning: Boolean,
    public val emojiSuggestion: Boolean,
    public val spellingMarks: Boolean,
    public val shapeWriting: Boolean,
) {
    TEXT(true, true, true, true, true, true, true, true),
    DEFAULT(true, true, true, true, true, true, false, true),
    SEARCH(true, false, false, true, true, true, true, true),
    URL(true, false, false, false, false, false, false, false),
    EMAIL(true, false, false, false, false, false, false, false),
    NUMBER(false, false, false, false, false, false, false, false),
    PHONE(false, false, false, false, false, false, false, false),
    PASSWORD(false, false, false, false, false, false, false, false),
    ;

    public companion object {
        // android.text.InputType constants (public platform API values)
        private const val TYPE_MASK_CLASS = 0x0000000f
        private const val TYPE_MASK_VARIATION = 0x00000ff0
        private const val TYPE_CLASS_TEXT = 1
        private const val TYPE_CLASS_NUMBER = 2
        private const val TYPE_CLASS_PHONE = 3
        private const val TYPE_CLASS_DATETIME = 4
        private const val TYPE_TEXT_VARIATION_URI = 0x10
        private const val TYPE_TEXT_VARIATION_EMAIL_ADDRESS = 0x20
        private const val TYPE_TEXT_VARIATION_SHORT_MESSAGE = 0x40
        private const val TYPE_TEXT_VARIATION_PERSON_NAME = 0x60
        private const val TYPE_TEXT_VARIATION_POSTAL_ADDRESS = 0x70
        private const val TYPE_TEXT_VARIATION_PASSWORD = 0x80
        private const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x90
        private const val TYPE_TEXT_VARIATION_FILTER = 0xb0
        private const val TYPE_TEXT_VARIATION_PHONETIC = 0xc0
        private const val TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS = 0xd0
        private const val TYPE_TEXT_VARIATION_WEB_PASSWORD = 0xe0
        private const val TYPE_NUMBER_VARIATION_PASSWORD = 0x10
        private const val TYPE_TEXT_FLAG_NO_SUGGESTIONS = 0x80000
        private const val IME_MASK_ACTION = 0xff
        private const val IME_ACTION_GO = 2
        private const val IME_ACTION_SEARCH = 3

        /**
         * Maps an Android editor (input type and IME options) to a policy (05 §15). [hasEditorInfo] false → TEXT.
         * Numeric passwords map to [PASSWORD].
         */
        @JvmStatic
        public fun fromAndroid(inputType: Int, imeOptions: Int, hasEditorInfo: Boolean = true): FieldInfo {
            if (!hasEditorInfo) return FieldInfo(TEXT, nameField = false, spellingMarksAllowed = true)
            val cls = inputType and TYPE_MASK_CLASS
            val variation = inputType and TYPE_MASK_VARIATION
            return when (cls) {
                TYPE_CLASS_NUMBER ->
                    FieldInfo(if (variation == TYPE_NUMBER_VARIATION_PASSWORD) PASSWORD else NUMBER, false, false)
                TYPE_CLASS_PHONE -> FieldInfo(PHONE, false, false)
                TYPE_CLASS_DATETIME -> FieldInfo(NUMBER, false, false)
                TYPE_CLASS_TEXT -> {
                    val noSuggestions = inputType and TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
                    val marks = !noSuggestions && variation != TYPE_TEXT_VARIATION_SHORT_MESSAGE
                    val action = imeOptions and IME_MASK_ACTION
                    when (variation) {
                        TYPE_TEXT_VARIATION_URI -> FieldInfo(URL, false, false)
                        TYPE_TEXT_VARIATION_EMAIL_ADDRESS, TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldInfo(EMAIL, false, false)
                        TYPE_TEXT_VARIATION_PASSWORD, TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, TYPE_TEXT_VARIATION_WEB_PASSWORD ->
                            FieldInfo(PASSWORD, false, false)
                        TYPE_TEXT_VARIATION_PERSON_NAME -> FieldInfo(DEFAULT, true, false)
                        TYPE_TEXT_VARIATION_POSTAL_ADDRESS, TYPE_TEXT_VARIATION_PHONETIC -> FieldInfo(DEFAULT, false, false)
                        TYPE_TEXT_VARIATION_FILTER -> FieldInfo(SEARCH, false, marks)
                        else ->
                            if (action == IME_ACTION_SEARCH || action == IME_ACTION_GO) FieldInfo(SEARCH, false, marks)
                            else FieldInfo(TEXT, false, marks)
                    }
                }
                else -> FieldInfo(TEXT, false, false)
            }
        }
    }
}

/**
 * A field as seen by the engine: its [policy], whether it asks for a person's name ([nameField], contact names get no
 * surcharge) and whether spelling marks are possible at all ([spellingMarksAllowed]: text class, no "no suggestions"
 * flag, not a short-message field; the setting and the policy are checked in addition).
 */
public data class FieldInfo(
    val policy: FieldPolicy,
    val nameField: Boolean = false,
    val spellingMarksAllowed: Boolean = policy.spellingMarks,
) {
    public val spellingMarks: Boolean get() = spellingMarksAllowed && policy.spellingMarks
}
