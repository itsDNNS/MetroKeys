package app.metrokeys.ime

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** [CachingEditor] always answers like the field it wraps, and reads the field only when its copy cannot answer. */
class CachingEditorTest {
    @Test
    fun ownEditsKeepTheCopyExact() {
        val inner = FakeEditor()
        val e = CachingEditor(inner)
        val r = Random(7)
        val alphabet = "abc de.,\n😀"
        repeat(6000) { step ->
            when (r.nextInt(15)) {
                0, 1, 2, 3 -> e.commitText(buildString { repeat(r.nextInt(1, 4)) { append(alphabet[r.nextInt(alphabet.length)]) } })
                4, 5 -> e.deleteBefore(r.nextInt(0, 4))
                6 -> e.setComposingText(if (r.nextBoolean()) "xy" else "")
                7 -> e.finishComposing()
                8 -> {
                    val a = r.nextInt(0, inner.text.length + 1)
                    e.setSelection(a, minOf(inner.text.length, a + r.nextInt(0, 3)))
                }
                9 -> {
                    // a caret move by the user: the keyboard drops the copy on the unexpected selection update
                    inner.userMove(r.nextInt(0, inner.text.length + 1))
                    e.invalidateCache()
                }
                10 -> e.beginBatch().also { e.commitText("q"); e.endBatch() }
                11 -> {
                    val a = r.nextInt(0, inner.text.length + 1)
                    e.setComposingRegion(a, minOf(inner.text.length, a + r.nextInt(0, 4)))
                }
                12 -> if (inner.text.length >= 2) {
                    val a = r.nextInt(0, inner.text.length - 1)
                    e.restyleWord(a, a + 2, inner.text.substring(a, a + 2), if (r.nextBoolean()) listOf("x") else null, inner.selStart)
                }
                13 -> e.setComposingText(buildString { repeat(r.nextInt(0, 4)) { append(alphabet[r.nextInt(alphabet.length)]) } })
                else -> {}
            }
            for (n in intArrayOf(1, 3, 100, 300, 2000)) {
                assertEquals("step $step before $n", inner.textBefore(n).toString(), e.textBefore(n).toString())
                assertEquals("step $step after $n", inner.textAfter(n).toString(), e.textAfter(n).toString())
            }
        }
    }

    @Test
    fun typingDoesNotReadTheField() {
        val inner = FakeEditor()
        val e = CachingEditor(inner)
        e.textBefore(100)
        e.textAfter(20)
        val reads = e.reads
        for (w in listOf("Hallo ", "wie ", "geht ", "es ")) {
            for (c in w) {
                e.commitText(c.toString())
                e.textBefore(100)
                e.textAfter(20)
                e.textBefore(1)
            }
            e.deleteBefore(1)
            e.commitText(" ")
        }
        assertEquals(reads, e.reads)
        assertEquals("Hallo wie geht es ", e.textBefore(1000).toString())
    }

    @Test
    fun aSwipedWordDoesNotReadTheField() {
        val inner = FakeEditor()
        val e = CachingEditor(inner)
        e.commitText("Das ")
        e.textBefore(100)
        val reads = e.reads
        // a swiped word is composing text before the caret; a second pick replaces it; the next key finishes it
        e.setComposingText("ist")
        assertEquals("Das ist", e.textBefore(100).toString())
        e.setComposingText("ost")
        e.deleteBefore(1)
        assertEquals("Dasost", e.textBefore(100).toString())
        e.commitText("ist")
        e.finishComposing()
        e.commitText(" gut")
        assertEquals(reads, e.reads)
        assertEquals(inner.textBefore(100).toString(), e.textBefore(100).toString())
    }
}
