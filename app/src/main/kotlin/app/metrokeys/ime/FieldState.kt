package app.metrokeys.ime

import app.metrokeys.engine.text.FieldInfo
import app.metrokeys.engine.text.FieldPolicy

/**
 * What the keyboard knows about the focused field (01 §10, 03 §12.5, 04 §8/§9), derived from the Android editor
 * info. Only plain int constants are used so that it can be built in unit tests.
 */
data class FieldState(
    val info: FieldInfo,
    /** Input type of 01 §10.1: text, default, url, email, search, password, number, phone, pin. */
    val inputKind: String,
    val inputType: Int,
    val imeOptions: Int,
    /** The editor action (IME_ACTION_*), 0 for none/unspecified. */
    val action: Int,
    /** Enter performs [action] (04 §9.1). */
    val enterPerformsAction: Boolean,
    val multiLine: Boolean,
    /** The editor has no text type at all (raw key input, 04 §9.2). */
    val rawInput: Boolean,
    val capsSentences: Boolean,
    val capsWordsOrChars: Boolean,
    val hasActionLabel: Boolean = false,
) {
    val policy: FieldPolicy get() = info.policy
    val isPassword: Boolean get() = info.policy == FieldPolicy.PASSWORD

    /** Go, search and send show the highlighted arrow on the Enter key (01 §10.4, 04 §9.1). */
    val enterShowsGo: Boolean get() = enterPerformsAction && (action == ACTION_GO || action == ACTION_SEARCH || action == ACTION_SEND)

    /** The action of the application-bar field button (03 §12.5.1), 0 = none. */
    val appBarAction: Int get() = when {
        action == 0 -> 0
        imeOptions and FLAG_NO_ENTER_ACTION != 0 && !hasActionLabel -> if (action == ACTION_SEND) ACTION_SEND else 0
        else -> action
    }

    /**
     * The fast-typing backspace exclusion of 03 §1.1 rule 5 applies: input type `text` without the short-message
     * variation.
     */
    val fastTypingExclusion: Boolean get() = inputKind == "text" && inputType and TYPE_MASK_VARIATION != VAR_SHORT_MESSAGE

    /** Hardware keys run through the keyboard logic except in password, number and phone fields (03 §13.2). */
    val hardwareThroughLogic: Boolean get() = info.policy !in setOf(FieldPolicy.PASSWORD, FieldPolicy.NUMBER, FieldPolicy.PHONE)

    companion object {
        // android.text.InputType / android.view.inputmethod.EditorInfo constants (public platform API values)
        private const val TYPE_MASK_CLASS = 0x0f
        private const val TYPE_MASK_VARIATION = 0xff0
        private const val TYPE_CLASS_TEXT = 1
        private const val TYPE_CLASS_NUMBER = 2
        private const val TYPE_CLASS_PHONE = 3
        private const val TYPE_CLASS_DATETIME = 4
        private const val TYPE_TEXT_FLAG_CAP_CHARACTERS = 0x1000
        private const val TYPE_TEXT_FLAG_CAP_WORDS = 0x2000
        private const val TYPE_TEXT_FLAG_CAP_SENTENCES = 0x4000
        private const val TYPE_TEXT_FLAG_MULTI_LINE = 0x20000
        private const val TYPE_TEXT_FLAG_IME_MULTI_LINE = 0x40000
        private const val VAR_URI = 0x10
        private const val VAR_EMAIL = 0x20
        private const val VAR_SHORT_MESSAGE = 0x40
        private const val VAR_PERSON_NAME = 0x60
        private const val VAR_POSTAL = 0x70
        private const val VAR_PASSWORD = 0x80
        private const val VAR_VISIBLE_PASSWORD = 0x90
        private const val VAR_FILTER = 0xb0
        private const val VAR_PHONETIC = 0xc0
        private const val VAR_WEB_EMAIL = 0xd0
        private const val VAR_WEB_PASSWORD = 0xe0
        private const val NUMBER_VAR_PASSWORD = 0x10
        const val IME_MASK_ACTION = 0xff
        const val ACTION_UNSPECIFIED = 0
        const val ACTION_NONE = 1
        const val ACTION_GO = 2
        const val ACTION_SEARCH = 3
        const val ACTION_SEND = 4
        const val ACTION_NEXT = 5
        const val ACTION_DONE = 6
        const val ACTION_PREVIOUS = 7
        const val FLAG_NO_ENTER_ACTION = 0x40000000

        val EMPTY = from(0, 0, hasEditorInfo = false)

        fun from(inputType: Int, imeOptions: Int, hasEditorInfo: Boolean = true, hasActionLabel: Boolean = false): FieldState {
            val info = FieldPolicy.fromAndroid(inputType, imeOptions, hasEditorInfo)
            val cls = inputType and TYPE_MASK_CLASS
            val variation = inputType and TYPE_MASK_VARIATION
            val rawAction = imeOptions and IME_MASK_ACTION
            val action = if (rawAction == ACTION_NONE || rawAction == ACTION_UNSPECIFIED) 0 else rawAction
            val kind = if (!hasEditorInfo) "text" else when (cls) {
                TYPE_CLASS_NUMBER -> if (variation == NUMBER_VAR_PASSWORD) "pin" else "number"
                TYPE_CLASS_DATETIME -> "number"
                TYPE_CLASS_PHONE -> "phone"
                TYPE_CLASS_TEXT -> when (variation) {
                    VAR_URI -> "url"
                    VAR_EMAIL, VAR_WEB_EMAIL -> "email"
                    VAR_PASSWORD, VAR_VISIBLE_PASSWORD, VAR_WEB_PASSWORD -> "password"
                    VAR_PERSON_NAME, VAR_POSTAL, VAR_PHONETIC -> "default"
                    VAR_FILTER -> "search"
                    else -> if (action == ACTION_SEARCH || action == ACTION_GO) "search" else "text"
                }
                else -> "text"
            }
            val text = cls == TYPE_CLASS_TEXT
            return FieldState(
                info = info,
                inputKind = kind,
                inputType = inputType,
                imeOptions = imeOptions,
                action = action,
                enterPerformsAction = action != 0 && imeOptions and FLAG_NO_ENTER_ACTION == 0,
                multiLine = text && inputType and (TYPE_TEXT_FLAG_MULTI_LINE or TYPE_TEXT_FLAG_IME_MULTI_LINE) != 0,
                rawInput = hasEditorInfo && cls == 0,
                capsSentences = text && inputType and TYPE_TEXT_FLAG_CAP_SENTENCES != 0,
                capsWordsOrChars = text && inputType and (TYPE_TEXT_FLAG_CAP_WORDS or TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0,
                hasActionLabel = hasActionLabel,
            )
        }
    }
}
