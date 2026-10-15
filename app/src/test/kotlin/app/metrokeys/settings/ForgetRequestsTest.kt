package app.metrokeys.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The preference that tells the running keyboard which words were forgotten in the settings (07 §9.13 item 5). */
class ForgetRequestsTest {
    @Test
    fun requestsAreAppendedAndKeptInOrder() {
        assertTrue(Prefs.forgetRequests(null).isEmpty())
        var v = Prefs.withForgetRequest(null, 10, "Zwuckel")
        v = Prefs.withForgetRequest(v, 20, "Wort mit\tTab")
        assertEquals(listOf(10L to "Zwuckel", 20L to "Wort mit\tTab"), Prefs.forgetRequests(v))
        // at most FORGET_KEEP entries, newest last; broken lines are skipped
        for (i in 0 until 30) v = Prefs.withForgetRequest(v, 100L + i, "w$i")
        val r = Prefs.forgetRequests(v + "\nbroken\n\t\nx\ty")
        assertEquals(Prefs.FORGET_KEEP, r.size)
        assertEquals(129L to "w29", r.last())
    }
}
