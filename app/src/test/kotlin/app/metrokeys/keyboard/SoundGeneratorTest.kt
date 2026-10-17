package app.metrokeys.keyboard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The generated sounds against the conformance checks of 03 Appendix A (CRC-32, listed samples, lengths, peaks). */
class SoundGeneratorTest {
    private fun samples(s: GeneratedSound, from: Int) = SoundGenerator.pcm(s).copyOfRange(from, from + 8).map { it.toInt() }.toIntArray()

    @Test
    fun crc32OfEverySound() {
        for (s in GeneratedSound.entries) {
            assertEquals("CRC-32 of $s", "%08x".format(SoundGenerator.EXPECTED_CRC.getValue(s)), "%08x".format(SoundGenerator.crc32(SoundGenerator.pcm(s))))
        }
    }

    @Test
    fun appendixCrcValues() {
        // the literal check rows of the appendix, independent of the table in the code
        val expected = mapOf(
            GeneratedSound.LETTER to "5c32375d", GeneratedSound.SPACE to "a946dd5a", GeneratedSound.FUNCTION to "fe09340a",
            GeneratedSound.PICKER to "ff3d8900", GeneratedSound.DICTIONARY to "b0860c75", GeneratedSound.PASTE to "85052057",
        )
        for ((s, crc) in expected) assertEquals("$s", crc, "%08x".format(SoundGenerator.crc32(SoundGenerator.render(SoundGenerator.spec(s)))))
    }

    @Test
    fun listedSamples() {
        assertArrayEquals(intArrayOf(0, -1333, -3706, -4913, -4888, -4940, -5078, -5301), samples(GeneratedSound.LETTER, 0))
        assertArrayEquals(intArrayOf(0, -1328, -3687, -4894, -4894, -4990, -5188, -5482), samples(GeneratedSound.SPACE, 0))
        assertArrayEquals(intArrayOf(0, -1319, -3654, -4879, -4956, -5168, -5514, -5961), samples(GeneratedSound.FUNCTION, 0))
        assertArrayEquals(intArrayOf(0, 0, 0, -1, -1, 4, 5, 2), samples(GeneratedSound.PICKER, 0))
        assertArrayEquals(intArrayOf(14, -73, -251, -396, -327, 109, 941, 2197), samples(GeneratedSound.PICKER, 2148))
        assertArrayEquals(intArrayOf(0, -335, -1093, -1958, -2692, -3141, -3233, -2956), samples(GeneratedSound.DICTIONARY, 0))
        assertArrayEquals(intArrayOf(86, -996, 2838, 5887, 7769, 8534, 8265, 7067), samples(GeneratedSound.DICTIONARY, 2445))
        assertArrayEquals(intArrayOf(0, 0, -2, -7, -14, -20, -22, -16), samples(GeneratedSound.PASTE, 0))
        assertArrayEquals(intArrayOf(-367, -363, -420, -509, -559, -581, -589, -525), samples(GeneratedSound.PASTE, 4394))
    }

    @Test
    fun lengthsAndPeaks() {
        val n = mapOf(
            GeneratedSound.LETTER to 1345, GeneratedSound.SPACE to 1063, GeneratedSound.FUNCTION to 1063,
            GeneratedSound.PICKER to 2590, GeneratedSound.DICTIONARY to 3330, GeneratedSound.PASTE to 6888,
        )
        for (s in GeneratedSound.entries) {
            val pcm = SoundGenerator.pcm(s)
            assertEquals("$s length", n.getValue(s), pcm.size)
            val peak = pcm.maxOf { Math.abs(it.toInt()) }
            assertEquals("$s peak", if (s == GeneratedSound.PASTE) 14500 else 29000, peak)
        }
    }

    @Test
    fun wavHeader() {
        val pcm = SoundGenerator.pcm(GeneratedSound.LETTER)
        val w = SoundGenerator.wav(pcm)
        assertEquals(44 + 2 * pcm.size, w.size)
        assertEquals("RIFF", String(w, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(w, 8, 4, Charsets.US_ASCII))
        assertEquals(pcm[1], ((w[46].toInt() and 0xFF) or (w[47].toInt() shl 8)).toShort())
    }
}
