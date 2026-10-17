package app.metrokeys.keyboard

import java.util.zip.CRC32

/**
 * The keyboard's own sounds (spec 03 §1.4 "Generated sounds", Appendix A), computed at runtime: mono, 44 100 Hz,
 * signed 16-bit PCM. Bit-exact with the appendix: binary64 arithmetic in the order written there, the tabulated
 * constants used literally, no fused multiply-add (the JVM never contracts `a * b + c`). Pure Kotlin.
 */
enum class GeneratedSound(val fileName: String) {
    LETTER("letter"), SPACE("space"), FUNCTION("function"), PICKER("picker"), DICTIONARY("dictionary"), PASTE("paste"),
}

object SoundGenerator {
    const val SAMPLE_RATE = 44_100

    /** A damped sinusoid of a voice: fixed frequency (rotation by [rc], [rs]) or a glide. */
    sealed class Sinusoid {
        abstract val zc: Double
        abstract val zs: Double
    }

    class Fixed(val rc: Double, val rs: Double, override val zc: Double, override val zs: Double) : Sinusoid()

    class Glide(
        val rho: Double, val we: Double, val dw: Double, val gamma: Double,
        override val zc: Double, override val zs: Double,
    ) : Sinusoid()

    /** One voice: start sample [o], attack [na], noise attack [nna], sinusoids and the noise band-pass. */
    class Voice(
        val o: Int, val na: Int, val nna: Int, val sinusoids: List<Sinusoid>,
        val b0: Double, val f1: Double, val f2: Double, val g: Double, val d: Double,
    )

    class Spec(val n: Int, val nf: Int, val p: Int, val voices: List<Voice>)

    // polynomial constants, each one double division (and negation)
    private val K2 = -(1.0 / 2.0)
    private val K4 = 1.0 / 24.0
    private val K6 = -(1.0 / 720.0)
    private val K8 = 1.0 / 40320.0
    private val K10 = -(1.0 / 3628800.0)
    private val K3 = -(1.0 / 6.0)
    private val K5 = 1.0 / 120.0
    private val K7 = -(1.0 / 5040.0)
    private val K9 = 1.0 / 362880.0
    private val K11 = -(1.0 / 39916800.0)

    private const val NOISE_SEED = 0x6B3D9A71

    /** Computes the PCM of [spec] (Appendix A "Algorithm"). */
    fun render(spec: Spec): ShortArray {
        val n = spec.n
        val buf = DoubleArray(n)
        for (v in spec.voices) {
            val k = v.sinusoids.size
            val zc = DoubleArray(k) { v.sinusoids[it].zc }
            val zs = DoubleArray(k) { v.sinusoids[it].zs }
            val h = DoubleArray(k) { 1.0 }
            var x1 = 0.0
            var x2 = 0.0
            var y1 = 0.0
            var y2 = 0.0
            var g = 1.0
            var r = NOISE_SEED
            for (j in 0 until n - v.o) {
                // 1. sinusoids
                var m = 0.0
                for (i in 0 until k) {
                    m = m + zs[i]
                    val pc: Double
                    val ps: Double
                    when (val s = v.sinusoids[i]) {
                        is Fixed -> {
                            pc = s.rc
                            ps = s.rs
                        }
                        is Glide -> {
                            val theta = s.we + s.dw * h[i]
                            h[i] = h[i] * s.gamma
                            val u = theta * theta
                            val c = 1.0 + u * (K2 + u * (K4 + u * (K6 + u * (K8 + u * K10))))
                            val sn = theta * (1.0 + u * (K3 + u * (K5 + u * (K7 + u * (K9 + u * K11)))))
                            pc = s.rho * c
                            ps = s.rho * sn
                        }
                    }
                    val c = zc[i] * pc - zs[i] * ps
                    val q = zc[i] * ps + zs[i] * pc
                    zc[i] = c
                    zs[i] = q
                }
                // 2. attack
                if (j < v.na) {
                    val u = j.toDouble() / v.na.toDouble()
                    m = m * (u * u * (3.0 - 2.0 * u))
                }
                // 3. noise (xorshift32) and band-pass
                r = r xor (r shl 13)
                r = r xor (r ushr 17)
                r = r xor (r shl 5)
                val noise = r.toDouble() / 2147483648.0
                val y = v.b0 * noise - v.b0 * x2 - v.f1 * y1 - v.f2 * y2
                x2 = x1
                x1 = noise
                y2 = y1
                y1 = y
                // 4. noise envelope
                var e = v.g * g
                if (j < v.nna) e = e * (j.toDouble() / v.nna.toDouble())
                g = g * v.d
                // 5.
                buf[v.o + j] = buf[v.o + j] + (m + e * y)
            }
        }
        for (i in n - spec.nf until n) {
            val u = (n - i).toDouble() / spec.nf.toDouble()
            buf[i] = buf[i] * (u * u * (3.0 - 2.0 * u))
        }
        var max = 0.0
        for (x in buf) max = maxOf(max, Math.abs(x))
        val scale = spec.p.toDouble() / max
        return ShortArray(n) { Math.floor(buf[it] * scale + 0.5).coerceIn(-32768.0, 32767.0).toInt().toShort() }
    }

    /** CRC-32 of [pcm] as little-endian 16-bit words (Appendix A "Conformance check"). */
    fun crc32(pcm: ShortArray): Long {
        val b = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            b[2 * i] = pcm[i].toInt().toByte()
            b[2 * i + 1] = (pcm[i].toInt() shr 8).toByte()
        }
        return CRC32().apply { update(b) }.value
    }

    /** The PCM of [sound], computed once per process. */
    fun pcm(sound: GeneratedSound): ShortArray = cache[sound.ordinal]

    private val cache: Array<ShortArray> by lazy { Array(GeneratedSound.entries.size) { render(spec(GeneratedSound.entries[it])) } }

    /** The check values of Appendix A: CRC-32 of the PCM bytes. */
    val EXPECTED_CRC: Map<GeneratedSound, Long> = mapOf(
        GeneratedSound.LETTER to 0x5c32375dL,
        GeneratedSound.SPACE to 0xa946dd5aL,
        GeneratedSound.FUNCTION to 0xfe09340aL,
        GeneratedSound.PICKER to 0xff3d8900L,
        GeneratedSound.DICTIONARY to 0xb0860c75L,
        GeneratedSound.PASTE to 0x85052057L,
    )

    /** A WAV file (RIFF, PCM 16-bit mono 44.1 kHz) holding [pcm]. */
    fun wav(pcm: ShortArray): ByteArray {
        val data = pcm.size * 2
        val b = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + data).put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1).putInt(SAMPLE_RATE)
            .putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        b.put("data".toByteArray(Charsets.US_ASCII)).putInt(data)
        for (s in pcm) b.putShort(s)
        return b.array()
    }

    // ---------------------------------------------------------------- constants (Appendix A tables)

    fun spec(sound: GeneratedSound): Spec = when (sound) {
        GeneratedSound.LETTER -> keySound(
            1345, 52,
            Fixed(0.9895780735909884, 0.07997433073315026, 1.0875009233215835, 1.5531122759719285),
            Fixed(0.9869193975896019, 0.09904022921577559, -1.9635846737685883, -2.2588457736067564),
            Fixed(0.9695387438962315, 0.17437625118939767, 0.6820940964082584, 0.2895317662105519),
            Fixed(0.9947113565672595, 0.09896335082256061, -0.05591929034707467, 0.08290375725550418),
            0.017097802468808657, -1.9642847048587966, 0.9658043950623827, 0.999622142456792,
        )
        GeneratedSound.SPACE -> keySound(
            1063, 48,
            Fixed(0.9882589073619366, 0.08749727533726241, 1.0875009233215835, 1.5531122759719285),
            Fixed(0.985170609102228, 0.10833054340995697, -1.9635846737685883, -2.2588457736067564),
            Fixed(0.9650734847229084, 0.190475341055392, 0.6820940964082584, 0.2895317662105519),
            Fixed(0.9936995823956297, 0.1083227824761231, -0.05591929034707467, 0.08290375725550418),
            0.018689578937497262, -1.960801933865563, 0.9626208421250054, 0.9995862570366894,
        )
        GeneratedSound.FUNCTION -> keySound(
            1063, 40,
            Fixed(0.9852156871976789, 0.10366387881289421, 1.0875009233215835, 1.5531122759719285),
            Fixed(0.9810979003084391, 0.1282895255807912, -1.9635846737685883, -2.2588457736067564),
            Fixed(0.9545500630153042, 0.22484924156836844, 0.6820940964082584, 0.2895317662105519),
            Fixed(0.9912152191105771, 0.12849225663710007, -0.05591929034707467, 0.08290375725550418),
            0.022109868763850366, -1.9532253095060483, 0.9557802624722993, 0.99950881467455,
        )
        GeneratedSound.PICKER -> Spec(
            2590, 132, 29000,
            listOf(
                Voice(
                    0, 483, 928,
                    listOf(
                        Fixed(0.9856451569102135, 0.14277911963938014, 0.16535907193609445, 0.1541998292101309),
                        Fixed(0.9856365057419078, 0.1544544644230107, -0.002172824601441793, 0.12448103804697071),
                    ),
                    0.019645180247223358, -1.9434858645989024, 0.9607096395055532, 1.137, 0.9974758018413427,
                ),
                Voice(
                    2148, 19, 6,
                    listOf(
                        Fixed(0.9672522237821836, 0.185270311100773, 0.21040663110757257, -0.9898853719426113),
                        Fixed(0.9729734583533975, 0.22446801278714104, -0.01351047169785782, 0.035195982078944504),
                        Fixed(0.9761149315299521, 0.14033381614087642, -0.10086894305794084, 0.6368614420157449),
                        Fixed(0.947163456418191, 0.3077371431356718, 0.006448881310036008, 0.006227612291164911),
                    ),
                    0.015509804082994547, -1.967276676566002, 0.9689803918340109, 0.07802, 0.9989579806606724,
                ),
            ),
        )
        GeneratedSound.DICTIONARY -> Spec(
            3330, 132, 29000,
            listOf(
                Voice(
                    0, 14, 671,
                    listOf(
                        Fixed(0.9919250917869593, 0.09003001739389933, -0.6590905680066658, 0.04608810220274373),
                        Fixed(0.9929300029686038, 0.07913465980358521, 0.18459895085723127, -0.02923760151601915),
                        Fixed(0.9862768899924975, 0.037199387329403026, -0.15180746662420067, -0.005301233549109924),
                        Fixed(0.93895215517978, 0.11507156238878902, 1.2327349154731633, -1.4180993012385885),
                    ),
                    0.3003272018599374, -1.3420170793894317, 0.3993455962801253, 0.06382, 0.9997165934552059,
                ),
                Voice(
                    2445, 1, 19,
                    listOf(
                        Fixed(0.9558661667925926, 0.2012548071726333, 1.9714310975927325, 0.8777376757575768),
                        Fixed(0.9745973778399479, 0.12224572806995077, -0.8671932658410803, -0.963115693818703),
                        Fixed(0.9647730554143443, 0.250262670100207, -0.11026493673746192, -0.049093112819249114),
                        Fixed(0.9854476639666077, 0.036099577675934594, 0.023450667511771033, -0.1668601695829546),
                    ),
                    0.06898856075952996, -1.8422795730312036, 0.8620228784809402, 0.2179, 0.9975364088321695,
                ),
            ),
        )
        GeneratedSound.PASTE -> Spec(
            6888, 132, 14500,
            listOf(
                Voice(
                    0, 661, 770,
                    listOf(
                        Glide(0.995378284758926, 0.07374550374140938, 0.3981487832406656, 0.9894056335359873, -2.0029402093509963, 0.9339863584469459),
                        Fixed(0.9983959880219655, 0.04392583358676094, 0.10372083294292388, 0.050588030339459536),
                        Fixed(0.9962136327131518, 0.06082418618698282, -0.08148342685484039, -0.08437855857972378),
                    ),
                    0.019803885683981566, -1.9599080263272748, 0.9603922286320369, 0.07405, 0.9979103287561322,
                ),
                Voice(
                    4394, 71, 0,
                    listOf(
                        Glide(0.9980006124795066, 0.18453473038229024, 0.1641749303733115, 0.9453259577036901, 0.0037522673840159736, -0.2149672544586241),
                        Fixed(0.9621233035084166, 0.17790077185458347, -0.11479252303167432, -0.4972210440599055),
                        Fixed(0.9597794005025043, 0.13354976956008374, -0.15592854489888652, 0.08290867798798832),
                        Fixed(0.9984331987371875, 0.04404149368234475, -0.0016372822487650412, 0.004755012916688964),
                    ),
                    0.04712474474502281, -0.5041201091710835, 0.9057505105099545, 0.009247, 0.8555983977608765,
                ),
            ),
        )
    }

    /** The three key sounds: one voice at O = 0, NA = 3, NF = 132, P = 29000, noise gain 0.133. */
    private fun keySound(n: Int, nna: Int, s1: Fixed, s2: Fixed, s3: Fixed, s4: Fixed, b0: Double, f1: Double, f2: Double, d: Double) =
        Spec(n, 132, 29000, listOf(Voice(0, 3, nna, listOf(s1, s2, s3, s4), b0, f1, f2, 0.133, d)))
}
