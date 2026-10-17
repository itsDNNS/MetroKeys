package app.metrokeys.ime

/** The shift transitions of 03 §4 (states 0 off, 1 shift, 2 caps lock), free of Android. */
object ShiftRules {
    /** A double tap: less than this after the previous shift touch-down (03 §4 item 1). */
    const val DOUBLE_TAP_WINDOW_MS = 500L

    /** A touch-down on Shift (03 §4 item 2): the new state; a caps lock that is tapped off sets "just unlocked". */
    fun tap(state: Shift, double: Boolean, justUnlocked: Boolean): Shift = when (state) {
        Shift.OFF -> if (double) (if (justUnlocked) Shift.OFF else Shift.CAPS_LOCK) else Shift.ONE_SHOT
        Shift.ONE_SHOT -> if (double) Shift.CAPS_LOCK else Shift.OFF
        Shift.CAPS_LOCK -> Shift.OFF
    }

    /**
     * Shift still held when its hold timer fires (03 §4 item 2a): the new state and whether the function sound plays
     * again. 1 → 2 with the sound; 0 → 2 without it, unless "just unlocked" is set (then it stays 0); 2 stays 2.
     */
    fun hold(state: Shift, justUnlocked: Boolean): Pair<Shift, Boolean> = when (state) {
        Shift.ONE_SHOT -> Shift.CAPS_LOCK to true
        Shift.OFF -> (if (justUnlocked) Shift.OFF else Shift.CAPS_LOCK) to false
        Shift.CAPS_LOCK -> Shift.CAPS_LOCK to false
    }
}
