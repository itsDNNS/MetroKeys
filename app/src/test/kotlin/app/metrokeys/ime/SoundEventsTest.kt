package app.metrokeys.ime

import app.metrokeys.keyboard.SoundKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which events play which slot (03 §1.4 "Other sounds", 04 §3.3, 06 §5.5) and the shift transitions (03 §4). */
class SoundEventsTest {
    @Test
    fun picksAndTheAddItem() {
        val list = StripContent(StripKind.TYPING, listOf(StripItem("Haus", bold = true), StripItem("Hase"), StripItem("hsus", addWord = true)), "hsus", 0)
        assertEquals(SoundKind.PICK, SoundEvents.pick(list, 0))
        assertEquals(SoundKind.PICK, SoundEvents.pick(list, 1))
        assertEquals(SoundKind.DICTIONARY, SoundEvents.pick(list, 2))
        assertNull(SoundEvents.pick(list, 3))
        for (k in listOf(StripKind.PREDICTION, StripKind.RESUMED, StripKind.SWIPE, StripKind.SWIPE_UNRECOGNISED, StripKind.SELECTION, StripKind.CARET_WORD)) {
            assertEquals("$k", SoundKind.PICK, SoundEvents.pick(list.copy(kind = k), 0))
        }
        // the live word of a running swipe cannot be picked and plays nothing
        assertNull(SoundEvents.pick(StripContent(StripKind.SWIPE_LIVE, listOf(StripItem("hallo"))), 0))
    }

    @Test
    fun autocorrectionAndPaste() {
        assertEquals(SoundKind.PICK, SoundEvents.AUTOCORRECTION)
        assertEquals(SoundKind.PASTE, SoundEvents.PASTE)
    }

    @Test
    fun swipeResults() {
        assertNull(SoundEvents.swipeResult(listOf("hallo", "halle"), recognized = true))
        assertEquals(SoundKind.NOT_RECOGNISED, SoundEvents.swipeResult(listOf("hallo", "halle"), recognized = false))
        assertEquals(SoundKind.NOT_RECOGNISED, SoundEvents.swipeResult(emptyList(), recognized = true))
        assertEquals(SoundKind.NOT_RECOGNISED, SoundEvents.swipeResult(emptyList(), recognized = false))
        assertEquals(SoundKind.DICTIONARY.slot, SoundKind.NOT_RECOGNISED.slot)
    }

    @Test
    fun shiftTaps() {
        assertEquals(Shift.ONE_SHOT, ShiftRules.tap(Shift.OFF, double = false, justUnlocked = false))
        assertEquals(Shift.CAPS_LOCK, ShiftRules.tap(Shift.OFF, double = true, justUnlocked = false))
        assertEquals(Shift.OFF, ShiftRules.tap(Shift.OFF, double = true, justUnlocked = true))
        assertEquals(Shift.OFF, ShiftRules.tap(Shift.ONE_SHOT, double = false, justUnlocked = false))
        assertEquals(Shift.CAPS_LOCK, ShiftRules.tap(Shift.ONE_SHOT, double = true, justUnlocked = false))
        assertEquals(Shift.OFF, ShiftRules.tap(Shift.CAPS_LOCK, double = false, justUnlocked = false))
        assertEquals(Shift.OFF, ShiftRules.tap(Shift.CAPS_LOCK, double = true, justUnlocked = false))
    }

    @Test
    fun holdingShiftLocksCaps() {
        // 03 §4 item 2a: the touch-down sets 0 → 1, the hold then 1 → 2 with a second function sound
        val down = ShiftRules.tap(Shift.OFF, double = false, justUnlocked = false)
        assertEquals(Shift.CAPS_LOCK to true, ShiftRules.hold(down, justUnlocked = false))
        // 0 → 2 without a sound (an automatic shift tapped off and held), unless "just unlocked" (stays 0)
        assertEquals(Shift.CAPS_LOCK to false, ShiftRules.hold(Shift.OFF, justUnlocked = false))
        assertEquals(Shift.OFF to false, ShiftRules.hold(Shift.OFF, justUnlocked = true))
        // caps lock stays (double tap held)
        assertEquals(Shift.CAPS_LOCK to false, ShiftRules.hold(Shift.CAPS_LOCK, justUnlocked = false))
        // a held caps lock: the touch-down unlocks (0, "just unlocked"), the hold keeps it off
        val unlocked = ShiftRules.tap(Shift.CAPS_LOCK, double = false, justUnlocked = false)
        assertEquals(Shift.OFF to false, ShiftRules.hold(unlocked, justUnlocked = true))
        assertTrue(ShiftRules.hold(Shift.ONE_SHOT, true).second)
        assertFalse(ShiftRules.hold(Shift.OFF, false).second)
    }
}
