package app.metrokeys.engine.lexicon

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Sequential reader over a stream (big-endian fixed-size values, unsigned LEB128 varints). Reads the decompressed
 * dictionary data as it streams in, so a file is never held in memory as a whole (05b §6 "Memory").
 */
internal class ByteReader(input: InputStream) {
    constructor(data: ByteArray) : this(ByteArrayInputStream(data))

    private val input: InputStream = if (input is BufferedInputStream || input is ByteArrayInputStream) input else BufferedInputStream(input, 1 shl 16)
    private var scratch = ByteArray(64)

    fun u8(): Int {
        val b = input.read()
        if (b < 0) throw EOFException("unexpected end of data")
        return b
    }

    private fun readFully(buf: ByteArray, n: Int) {
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw EOFException("unexpected end of data")
            off += r
        }
    }

    fun bytes(n: Int): ByteArray {
        if (n < 0) throw IOException("bad length")
        val out = ByteArray(n)
        readFully(out, n)
        return out
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = u8()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw IOException("varint too long")
        }
    }

    fun varintInt(): Int {
        val v = varint()
        if (v > Int.MAX_VALUE) throw IOException("value out of range")
        return v.toInt()
    }

    fun f32(): Float {
        var bits = 0
        repeat(4) { bits = (bits shl 8) or u8() }
        return Float.fromBits(bits)
    }

    fun utf8(): String {
        val n = varintInt()
        if (n > 1 shl 20) throw IOException("string too long")
        if (scratch.size < n) scratch = ByteArray(maxOf(n, scratch.size * 2))
        readFully(scratch, n)
        return String(scratch, 0, n, Charsets.UTF_8)
    }

    fun ascii(): String {
        val n = u8()
        return String(bytes(n), Charsets.US_ASCII)
    }

    fun expectMagic(magic: String) {
        val m = String(bytes(magic.length), Charsets.US_ASCII)
        if (m != magic) throw IOException("bad magic $m")
    }

    /** Reads and discards the rest of the stream (so a digest over it is complete). */
    fun drain() {
        while (input.read(scratch) >= 0) Unit
    }
}
