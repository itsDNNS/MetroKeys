package app.metrokeys.engine.swipe

import app.metrokeys.engine.lexicon.Lexicon
import app.metrokeys.engine.lexicon.WordForm
import app.metrokeys.engine.predict.LanguageEngine
import app.metrokeys.engine.predict.LmCost
import app.metrokeys.engine.predict.Params
import app.metrokeys.engine.predict.ShapeParams
import app.metrokeys.engine.text.PrevContext
import app.metrokeys.engine.text.TextModel
import app.metrokeys.engine.text.capitalizeFirst
import app.metrokeys.engine.text.toKey
import app.metrokeys.engine.text.upperDisplay
import app.metrokeys.engine.touch.KeyboardGeometry
import app.metrokeys.engine.touch.LetterKey
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** A shape-writing trace: every touch sample in key-area pixels, with the time in ms since touch-down. */
public class ShapeTrace(public val xs: FloatArray, public val ys: FloatArray, public val times: LongArray = LongArray(xs.size)) {
    init {
        require(xs.size == ys.size && ys.size == times.size) { "sample arrays differ in size" }
    }

    public val size: Int get() = xs.size

    public companion object {
        /** Builds a trace from (x, y) pairs, one sample every [stepMs] ms. */
        @JvmStatic
        public fun of(points: List<Pair<Float, Float>>, stepMs: Long = 8): ShapeTrace =
            ShapeTrace(FloatArray(points.size) { points[it].first }, FloatArray(points.size) { points[it].second },
                LongArray(points.size) { it * stepMs })
    }
}

/** One recognised word. */
public data class ShapeCandidate(
    val text: String,
    /** total = searchCost − contextBonus + 2.2 · shapeDistance (lower is better). */
    val total: Double,
    val searchCost: Double,
    val shapeDistance: Double,
    val contextBonus: Int,
    val languageTag: String,
) {
    val score: Int get() = -LmCost.roundHalfAway(total)
}

/** Shift state at lift-off (06 §5.2). */
public enum class ShiftState { OFF, ONE_SHOT, CAPS_LOCK }

/** Result of a recognition: up to 5 candidates, best first, and whether the gesture counts as recognised. */
public data class ShapeResult(val candidates: List<ShapeCandidate>, val recognized: Boolean) {
    /** Candidate texts with the capitalisation of 06 §5.2; duplicates that became equal are removed. */
    public fun texts(shift: ShiftState = ShiftState.OFF): List<String> {
        val out = LinkedHashSet<String>()
        for (c in candidates) out.add(
            when (shift) {
                ShiftState.OFF -> c.text
                ShiftState.ONE_SHOT -> capitalizeFirst(c.text)
                ShiftState.CAPS_LOCK -> upperDisplay(c.text)
            },
        )
        return out.toList()
    }

    public companion object {
        @JvmField
        public val NONE: ShapeResult = ShapeResult(emptyList(), false)

        /** Merges per-language results, each with its language penalty (06 §4.1); equal texts keep the lowest total. */
        @JvmStatic
        public fun merge(results: List<Pair<ShapeResult, Double>>): ShapeResult {
            val all = results.flatMap { (r, pen) -> r.candidates.map { it.copy(total = it.total + pen) to it } }
                .sortedWith(compareBy({ it.first.total }, { it.first.text }))
            val seen = HashSet<String>()
            val out = ArrayList<Pair<ShapeCandidate, ShapeCandidate>>()
            for (p in all) {
                if (out.size >= ShapeWriter.SHOWN_CANDIDATES) break
                if (seen.add(p.first.text)) out.add(p)
            }
            if (out.isEmpty()) return NONE
            val best = out[0].second
            val recognized = !(best.total > ShapeWriter.UNKNOWN_SCORE && best.shapeDistance > ShapeWriter.UNKNOWN_SHAPE)
            return ShapeResult(out.map { it.first }, recognized)
        }
    }
}

/**
 * Shape-writing recogniser of one language (spec 06 §3–§4). The word index is the language's lexicon prefix tree
 * (keys = lower-cased forms, nodes know the best class below); contact words not in the lexicon get a second index.
 */
public class ShapeWriter @JvmOverloads constructor(
    private val engine: LanguageEngine,
    /** The language's constants of 06 §7.1. */
    public val shape: ShapeParams = engine.params.shape,
) {
    public val geometry: KeyboardGeometry get() = engine.geometry

    @Volatile
    private var contactIndex: Lexicon? = null

    @Volatile
    private var contactIndexBuilt = false

    internal fun invalidateContacts() {
        contactIndexBuilt = false
        contactIndex = null
    }

    /**
     * Index of the elided words (06 §4.1, extension; languages whose dictionary stores elision prefixes): every prefix +
     * static pair follower starting with a vowel or h, with the class whose probability is p_pre(prefix) · P(F | prefix).
     */
    private val elidedIndex: Lexicon? by lazy {
        val lex = engine.lexicon
        val pairs = engine.dictionary.pairs
        if (!lex.hasPrefixTable || pairs == null) return@lazy null
        val entries = HashMap<String, MutableList<WordForm>>()
        for (prefix in engine.params.elisionPrefixes) {
            val k = lex.prefixClass(prefix)
            if (k < 0) continue
            val pre = engine.unigramLog10(k)
            for (f in engine.staticFollowers(prefix)) {
                val text = prefix + f.text
                val cls = LmCost.roundHalfAway((pre + f.log10p - engine.a) / engine.bc).coerceIn(0, 255)
                entries.getOrPut(toKey(text)) { ArrayList() }.add(WordForm(text, cls))
            }
        }
        if (entries.isEmpty()) null else Lexicon.fromEntries(
            engine.languageTag, lex.scale, lex.maxCount, lex.corpusTokens,
            entries.mapValues { (_, v) -> v.sortedByDescending { it.frequencyClass } },
        )
    }

    /** Number of elided entries in the index (06 §4.1). */
    internal val elidedEntryCount: Int get() = elidedIndex?.formCount ?: 0

    /** Key sequence with entry points and turn angles (06 §3.2–§3.3). */
    public class KeySequence(public val keys: IntArray, public val entryX: FloatArray, public val entryY: FloatArray, public val angles: FloatArray) {
        public val size: Int get() = keys.size
    }

    public fun recognize(trace: ShapeTrace, before: String = ""): ShapeResult {
        val out = recognizeAll(trace, before)
        if (out.isEmpty()) return ShapeResult.NONE
        val best = out[0]
        val recognized = !(best.total > UNKNOWN_SCORE && best.shapeDistance > UNKNOWN_SHAPE)
        return ShapeResult(out.take(SHOWN_CANDIDATES), recognized)
    }

    /** Every word of stage A (up to [SEARCH_LIST_SIZE] per index) with its final score, best first. */
    internal fun recognizeAll(trace: ShapeTrace, before: String = ""): List<ShapeCandidate> {
        val g = geometry
        if (trace.size < 2) return emptyList()
        if (trace.ys[0] >= NO_START_BOTTOM * g.height) return emptyList()
        if (!exceedsThreshold(trace, g)) return emptyList()
        val seq = keySequence(trace, g) ?: return emptyList()
        if (seq.size < 2) return emptyList()
        val sx = 480.0 / (10 * g.keyWidth)
        val sy = 336.0 / (4 * g.keyHeight)
        val search = Search(g, seq)
        val found = LinkedHashMap<String, Pair<Double, Int>>()
        val rawPrev = TextModel.previousWordForPairs(before, before.length)
        // directly after an elision prefix ("l'" + swipe) the previous word is that prefix (06 §4.5); no elided entries then
        val afterPrefix = rawPrev is PrevContext.Word && engine.isElisionPrefix(rawPrev.word)
        val prev = if (afterPrefix) PrevContext.Word(Lexicon.normalizePrefix((rawPrev as PrevContext.Word).word)) else engine.pairPrev(rawPrev)
        for (index in listOfNotNull(engine.lexicon, contacts(), if (afterPrefix) null else elidedIndex)) {
            for ((form, cost) in search.run(index)) {
                val old = found[form.text]
                if (old == null || cost < old.first) found[form.text] = cost to form.frequencyClass
            }
        }
        if (found.isEmpty()) return emptyList()
        val traceRu = trimmedTrace(trace, sx, sy)
        val out = ArrayList<ShapeCandidate>()
        for ((text, pair) in found) {
            val (searchCost, cls) = pair
            val template = template(text, g, sx, sy) ?: continue
            val shape = dtw(traceRu, template)
            val bonus = contextBonus(text, cls, prev)
            out.add(ShapeCandidate(text, searchCost - bonus + SHAPE_WEIGHT * shape, searchCost, shape, bonus, engine.languageTag))
        }
        out.sortWith(compareBy({ it.total }, { it.text }))
        return out
    }

    private fun contacts(): Lexicon? {
        if (contactIndexBuilt) return contactIndex
        synchronized(this) {
            if (contactIndexBuilt) return contactIndex
            val cn = engine.contacts
            val entries = HashMap<String, MutableList<WordForm>>()
            for (w in cn.words) {
                if (w.length < 2 || !w.all { Character.isLetter(it) || it in JOINERS }) continue
                if (engine.lexicon.exact(w)) continue
                val cost = cn.cost(w, false)
                val log10p = (Params.LM_COST_AT_P1 - cost) / Params.LM_COST_PER_DECADE
                val cls = LmCost.roundHalfAway((log10p - engine.a) / engine.bc).coerceIn(0, 255)
                entries.getOrPut(toKey(w)) { ArrayList() }.add(WordForm(w, cls))
            }
            contactIndex = if (entries.isEmpty()) null else Lexicon.fromEntries(
                engine.languageTag, engine.lexicon.scale, engine.lexicon.maxCount, engine.lexicon.corpusTokens,
                entries.mapValues { (_, v) -> v.sortedByDescending { it.frequencyClass } },
            )
            contactIndexBuilt = true
            return contactIndex
        }
    }

    /**
     * Context bonus of 06 §4.5 (in class units = cost). An elided entry ("l'acqua") uses the pair (previous word,
     * prefix): its prefix probability is mixed with that pair's probability.
     */
    internal fun contextBonus(word: String, cls: Int, prev: PrevContext): Int {
        val key = prev.pairKey ?: return 0
        val n = if (engine.lexicon.hasPrefixTable) engine.elisionPrefixLength(word) else 0
        val pUni = 10.0.pow(engine.unigramLog10(cls))
        val p = if (n in 1 until word.length) {
            val prefix = Lexicon.normalizePrefix(word.substring(0, n))
            val b = engine.dictionary.pairs?.bigram(key, prefix) ?: -1
            if (b < 0) return 0
            val pre = 10.0.pow(engine.unigramLog10(engine.lexicon.prefixClass(prefix)))
            val preCtx = maxOf(pre, CONTEXT_MIX * LmCost.pairProbability(b) + (1 - CONTEXT_MIX) * pre)
            pUni * preCtx / pre
        } else {
            val b = engine.dictionary.pairs?.bigram(key, word) ?: -1
            if (b < 0) return 0
            maxOf(pUni, CONTEXT_MIX * LmCost.pairProbability(b) + (1 - CONTEXT_MIX) * pUni)
        }
        val ctxClass = LmCost.roundHalfAway((log10(p) - engine.a) / engine.bc).coerceIn(cls, 255)
        return ctxClass - cls
    }

    // ------------------------------------------------------------------ stage A

    private inner class Search(val g: KeyboardGeometry, val seq: KeySequence) {
        val n = seq.size
        val keyIdx = HashMap<Char, Int>()
        val keyList: List<LetterKey> = g.keys
        val nk = keyList.size
        val neighbour = FloatArray(nk * nk) { Float.NaN }

        init {
            for (i in 0 until nk) for (j in 0 until nk) {
                if (i == j) continue
                val d = hypot(keyList[i].centerX - keyList[j].centerX, keyList[i].centerY - keyList[j].centerY)
                if (d <= NEIGHBOUR_RADIUS * g.keyWidth) neighbour[i * nk + j] = NEIGHBOUR_DIST_PER_KEY * (d / g.keyWidth)
            }
        }

        fun keyOf(c: Char): Int = keyIdx.getOrPut(c) {
            if (c in JOINERS) JOINER else g.shapeKey(c)?.let { k -> keyList.indexOfFirst { it === k } } ?: -1
        }

        val unusedKeyCost = shape.unusedKeyCost
        val neighbourFactor = shape.neighbourFactor
        val doubleLetterCost = shape.doubleLetterCost
        val accentCost = shape.accentCost
        val cornerAngle = shape.cornerAngle

        fun accent(c: Char): Double = if (g.isViaBaseLetter(c)) accentCost else 0.0

        lateinit var lex: Lexicon
        val results = HashMap<WordForm, Double>()
        var worst = Double.MAX_VALUE
        val dominance = LongDoubleMap()
        var states = 0

        fun run(index: Lexicon): List<Pair<WordForm, Double>> {
            lex = index
            results.clear()
            dominance.clear()
            worst = Double.MAX_VALUE
            states = 0
            val root = index.root
            val first = index.firstChild(root)
            for (c in orderedChildren(root, first, index.childCount(root))) {
                val ch = index.charOf(c)
                val k = keyOf(ch)
                if (k < 0) continue
                val k0 = seq.keys[0]
                val cost = when {
                    k == k0 -> accent(ch)
                    !neighbour[k * nk + k0].isNaN() && !(n > 1 && seq.keys[1] == k) && !(n > 2 && seq.keys[2] == k) ->
                        neighbourFactor * neighbour[k * nk + k0] + accent(ch)
                    else -> continue
                }
                expand(c, 0, 1, cost, false)
                if (states >= MAX_STATES) break
            }
            return results.entries.sortedWith(compareBy({ it.value }, { it.key.text })).take(SEARCH_LIST_SIZE).map { it.key to it.value }
        }

        private fun orderedChildren(node: Int, first: Int, count: Int): List<Int> =
            (first until first + count).sortedByDescending { lex.maxClassOf(it) }

        private fun offer(form: WordForm, cost: Double) {
            val old = results[form]
            if (old != null && old <= cost) return
            if (old == null && results.size >= SEARCH_LIST_SIZE && cost >= worst) return
            results[form] = cost
            if (results.size > SEARCH_LIST_SIZE) {
                val w = results.entries.maxWith(compareBy({ it.value }, { it.key.text }))
                results.remove(w.key)
            }
            worst = if (results.size >= SEARCH_LIST_SIZE) results.values.max() else Double.MAX_VALUE
        }

        /** [node] has just been reached with its letter assigned to position [p]. */
        fun expand(node: Int, p: Int, letters: Int, cost: Double, prevDouble: Boolean) {
            if (states >= MAX_STATES) return
            states++
            val dk = (node.toLong() * (n + 1) + p) * 2 + if (prevDouble) 1 else 0
            if (!dominance.putIfLower(dk, cost)) return
            val bound = cost + unusedKeyCost * maxOf(0, p - letters) + LM_PER_CLASS * (255 - lex.maxClassOf(node))
            if (results.size >= SEARCH_LIST_SIZE && bound >= worst) return
            val key = lex.keyAt(node)
            if (key >= 0 && p == n - 1) {
                for (form in lex.formsOf(key)) {
                    if (form.text.length < 2 || !form.text.all { Character.isLetter(it) || it in JOINERS }) continue
                    if (form.frequencyClass == 0 && engine.params.hideClassZero && lex === engine.lexicon) continue
                    offer(form, cost + unusedKeyCost * maxOf(0, n - letters) + LM_PER_CLASS * (255 - form.frequencyClass))
                }
            }
            val first = lex.firstChild(node)
            val count = lex.childCount(node)
            if (count == 0) return
            for (c in orderedChildren(node, first, count)) {
                val ch = lex.charOf(c)
                val k = keyOf(ch)
                if (k == JOINER) {
                    expand(c, p, letters, cost + JOINER_COST, prevDouble)
                    continue
                }
                if (k < 0) continue
                val acc = accent(ch)
                // A5: a real double letter, the same letter twice (also when one of them is accented, "eé"),
                // on the key at position p (06 open point 10)
                if (!prevDouble && k == seq.keys[p] && node != lex.root && sameLetter(lex.charOf(node), ch)) {
                    expand(c, p, letters + 1, cost + doubleLetterCost + acc, true)
                }
                var skipped = 0.0
                var cornerSeen = false
                var j = p + 1
                while (j < n) {
                    val kj = seq.keys[j]
                    if (k == kj) {
                        expand(c, j, letters + 1, cost + acc + skipped / SKIP_ANGLE_DIVISOR, false)
                    } else {
                        val d = neighbour[k * nk + kj]
                        if (!d.isNaN() && (j == n - 1 || seq.angles[j] > cornerAngle || prevDouble) &&
                            !(j + 1 < n && seq.keys[j + 1] == k) && !(j + 2 < n && seq.keys[j + 2] == k)
                        ) {
                            expand(c, j, letters + 1, cost + neighbourFactor * d + acc + skipped / SKIP_ANGLE_DIVISOR, false)
                        }
                    }
                    if (j == n - 1) break
                    val tried = j - p
                    val corner = seq.angles[j] >= cornerAngle
                    if (tried >= FREE_SKIP_POSITIONS && corner && cornerSeen) break
                    if (corner) cornerSeen = true
                    skipped += seq.angles[j]
                    j++
                }
                if (states >= MAX_STATES) return
            }
        }
    }

    /**
     * Open-addressing map from search state to the lowest cost seen (stage-A dominance test). Primitive arrays keep
     * a large search (up to [MAX_STATES] states) at 16 bytes per slot instead of three objects per state.
     */
    private class LongDoubleMap {
        private var keys = LongArray(1 shl 12)
        private var values = DoubleArray(1 shl 12)
        private var used = BooleanArray(1 shl 12)
        private var size = 0

        fun clear() {
            if (keys.size > 1 shl 12) {
                keys = LongArray(1 shl 12)
                values = DoubleArray(1 shl 12)
                used = BooleanArray(1 shl 12)
            } else {
                used.fill(false)
            }
            size = 0
        }

        /** Stores [cost] for [key] unless a cost ≤ [cost] is stored; returns false if the state is dominated. */
        fun putIfLower(key: Long, cost: Double): Boolean {
            var i = slot(key, keys.size)
            while (used[i]) {
                if (keys[i] == key) {
                    if (values[i] <= cost) return false
                    values[i] = cost
                    return true
                }
                i = (i + 1) and (keys.size - 1)
            }
            used[i] = true
            keys[i] = key
            values[i] = cost
            if (++size * 2 > keys.size) grow()
            return true
        }

        private fun slot(key: Long, cap: Int): Int {
            var h = key * -0x61c8864680b583ebL
            h = h xor (h ushr 29)
            return h.toInt() and (cap - 1)
        }

        private fun grow() {
            val ok = keys
            val ov = values
            val ou = used
            val cap = ok.size * 2
            keys = LongArray(cap)
            values = DoubleArray(cap)
            used = BooleanArray(cap)
            for (j in ok.indices) {
                if (!ou[j]) continue
                var i = slot(ok[j], cap)
                while (used[i]) i = (i + 1) and (cap - 1)
                used[i] = true
                keys[i] = ok[j]
                values[i] = ov[j]
            }
        }
    }

    public companion object {
        public const val START_DISTANCE: Double = 50.0
        public const val OVERLAY_PIXELS: Double = 161280.0
        public const val NO_START_BOTTOM: Float = 0.75f
        public const val KEY_HYSTERESIS: Float = 0.12f
        public const val MAX_KEYS: Int = 70
        public const val END_ANGLE: Float = 180f
        public const val FREE_SKIP_POSITIONS: Int = 8
        public const val SKIP_ANGLE_DIVISOR: Double = 5.0
        public const val NEIGHBOUR_RADIUS: Float = 2.05f
        public const val NEIGHBOUR_DIST_PER_KEY: Float = 250f
        public const val JOINER_COST: Double = 0.0
        public const val LM_PER_CLASS: Double = 1.0
        public const val SEARCH_LIST_SIZE: Int = 16
        public const val MAX_STATES: Int = 400_000
        public const val TEMPLATE_POINTS: Int = 25
        public const val DTW_CUTOFF: Double = 1_000_000.0
        public const val SHAPE_WEIGHT: Double = 2.2
        public const val TAIL_TRIM_MAX: Int = 10
        public const val TAIL_TRIM_RADIUS: Double = 24.0
        public const val CONTEXT_MIX: Double = 0.5
        public const val UNKNOWN_SCORE: Double = 600.0
        public const val UNKNOWN_SHAPE: Double = 50.0
        public const val SHOWN_CANDIDATES: Int = 5
        private const val JOINER = -2

        /** The same letter, ignoring case and diacritics (ß counts as s). */
        internal fun sameLetter(a: Char, b: Char): Boolean {
            val x = KeyboardGeometry.baseLetter(a) ?: Character.toLowerCase(a)
            val y = KeyboardGeometry.baseLetter(b) ?: Character.toLowerCase(b)
            return x == y
        }
        private const val JOINERS = "-'’"

        /** Swipe start threshold T in px for a key area of [width] × [height] px (06 §2.2). */
        @JvmStatic
        public fun startThreshold(width: Float, height: Float): Double = START_DISTANCE * sqrt(width.toDouble() * height / OVERLAY_PIXELS)

        /** True if a move by ([dx], [dy]) from touch-down turns a swipe candidate into a swipe (per-axis rule). */
        @JvmStatic
        public fun isSwipeStart(dx: Float, dy: Float, width: Float, height: Float): Boolean {
            val t = startThreshold(width, height)
            return abs(dx) > t || abs(dy) > t
        }

        /** True if a touch-down at [y] on a letter key may become a swipe (upper 75 % of the key area). */
        @JvmStatic
        public fun isSwipeCandidate(y: Float, height: Float, onLetterKey: Boolean): Boolean = onLetterKey && y < NO_START_BOTTOM * height

        internal fun exceedsThreshold(trace: ShapeTrace, g: KeyboardGeometry): Boolean {
            val x0 = trace.xs[0]
            val y0 = trace.ys[0]
            for (i in 1 until trace.size) if (isSwipeStart(trace.xs[i] - x0, trace.ys[i] - y0, g.width, g.height)) return true
            return false
        }

        /** Key sequence of 06 §3.2 with the turn angles of §3.3; null if no key was hit. */
        @JvmStatic
        public fun keySequence(trace: ShapeTrace, g: KeyboardGeometry): KeySequence? {
            val keys = ArrayList<Int>()
            val ex = ArrayList<Float>()
            val ey = ArrayList<Float>()
            for (i in 0 until trace.size) {
                if (keys.size >= MAX_KEYS) break
                val x = trace.xs[i]
                val y = trace.ys[i]
                val k = g.keys.indexOfFirst { it.contains(x, y) }
                if (k < 0) continue
                if (keys.isEmpty()) {
                    keys.add(k)
                    ex.add(x)
                    ey.add(y)
                    continue
                }
                if (k != keys.last() && g.keys[k].containsShrunk(x, y, KEY_HYSTERESIS, KEY_HYSTERESIS)) {
                    keys.add(k)
                    ex.add(x)
                    ey.add(y)
                }
            }
            if (keys.isEmpty()) return null
            val n = keys.size
            val sx = 480.0 / (10 * g.keyWidth)
            val sy = 336.0 / (4 * g.keyHeight)
            val angles = FloatArray(n) { END_ANGLE }
            if (n >= 3) {
                val dir = DoubleArray(n)
                for (i in 1 until n) {
                    dir[i] = Math.toDegrees(atan2((ey[i] - ey[i - 1]) * sy, (ex[i] - ex[i - 1]) * sx))
                }
                for (i in 1..n - 2) {
                    var d = dir[i + 1] - dir[i]
                    while (d > 180) d -= 360
                    while (d <= -180) d += 360
                    angles[i] = abs(d).toFloat()
                }
            }
            return KeySequence(keys.toIntArray(), ex.toFloatArray(), ey.toFloatArray(), angles)
        }

        /** The trace in ru with the lift-off jitter removed (06 §3.4). */
        @JvmStatic
        public fun trimmedTrace(trace: ShapeTrace, sx: Double, sy: Double): Array<DoubleArray> {
            val n = trace.size
            val fx = trace.xs[n - 1] * sx
            val fy = trace.ys[n - 1] * sy
            var end = n
            while (n - end < TAIL_TRIM_MAX && end > 2) {
                val px = trace.xs[end - 2] * sx
                val py = trace.ys[end - 2] * sy
                if (abs(px - fx) < TAIL_TRIM_RADIUS && abs(py - fy) < TAIL_TRIM_RADIUS) end-- else break
            }
            return Array(end) { doubleArrayOf(trace.xs[it] * sx, trace.ys[it] * sy) }
        }

        /** The 25-point ideal shape of a word in ru (06 §4.4), or null if a letter has no key. */
        @JvmStatic
        public fun template(word: String, g: KeyboardGeometry, sx: Double, sy: Double): Array<DoubleArray>? {
            val centres = ArrayList<DoubleArray>()
            for (c in word) {
                if (c in JOINERS) continue
                val k = g.shapeKey(c) ?: return null
                centres.add(doubleArrayOf(k.centerX * sx, k.centerY * sy))
            }
            if (centres.isEmpty()) return null
            val out = ArrayList<DoubleArray>(TEMPLATE_POINTS)
            val c = centres.size
            if (c == 1) return Array(TEMPLATE_POINTS) { centres[0].copyOf() }
            val inner = maxOf(0, TEMPLATE_POINTS - c)
            val base = inner / (c - 1)
            val extra = inner % (c - 1)
            for (s in 0 until c - 1) {
                val steps = base + if (s < extra) 1 else 0
                val p = centres[s]
                val q = centres[s + 1]
                out.add(p.copyOf())
                for (t in 1..steps) {
                    out.add(doubleArrayOf(p[0] + (q[0] - p[0]) * t / steps, p[1] + (q[1] - p[1]) * t / steps))
                }
            }
            while (out.size < TEMPLATE_POINTS) out.add(centres[c - 1].copyOf())
            return Array(TEMPLATE_POINTS) { out[it] }
        }

        /** Mean DTW distance per path step between trace and template (06 §4.4). */
        @JvmStatic
        public fun dtw(trace: Array<DoubleArray>, template: Array<DoubleArray>): Double {
            val n = trace.size
            val m = template.size
            val cost = Array(n) { DoubleArray(m) }
            val steps = Array(n) { IntArray(m) }
            for (i in 0 until n) for (j in 0 until m) {
                val local = hypot(trace[i][0] - template[j][0], trace[i][1] - template[j][1])
                if (i == 0 && j == 0) {
                    cost[0][0] = local
                    steps[0][0] = 1
                    continue
                }
                var best = Double.MAX_VALUE
                var bs = 0
                if (i > 0 && j > 0) {
                    best = cost[i - 1][j - 1]
                    bs = steps[i - 1][j - 1]
                }
                if (i > 0 && cost[i - 1][j] < best) {
                    best = cost[i - 1][j]
                    bs = steps[i - 1][j]
                }
                if (j > 0 && cost[i][j - 1] < best) {
                    best = cost[i][j - 1]
                    bs = steps[i][j - 1]
                }
                cost[i][j] = if (best >= DTW_CUTOFF) DTW_CUTOFF else local + best
                steps[i][j] = bs + 1
            }
            return cost[n - 1][m - 1] / steps[n - 1][m - 1]
        }
    }
}
