package app.metrokeys.engine.predict

import app.metrokeys.engine.lexicon.CasedForms
import app.metrokeys.engine.lexicon.Lexicon
import java.text.Normalizer

/** Checked by long-running query parts (05 §2.3, 05d §7): true once the query has become outdated. */
public fun interface CancellationCheck {
    public fun isCancelled(): Boolean
}

/** Thrown out of a query whose [CancellationCheck] reported it outdated (05 §2.3). */
public class QueryCancelledException : RuntimeException("query outdated") {
    override fun fillInStackTrace(): Throwable = this
}

/**
 * The spelling source of spec 05d: up to ten words of the word list that the user may have meant by a typed word
 * (missing, extra, doubled, swapped, substituted or accent-less letters, letter-group confusions), found by a walk over
 * the cased forms in code-unit order with alignment states, a shrinking limit, two rounds and a confidence test.
 * The engine takes the first `SPELL_SLOTS` words (05 §8.1b). One instance per word list; [suggest] is thread-safe.
 */
public class SpellingSource(private val lex: Lexicon, private val sp: SpellingParams) {
    private val forms = CasedForms(lex)

    /** Result of one call: the words in order, the node visits, whether the visit budget stopped the search. */
    public class Result internal constructor(
        public val words: List<String>,
        public val visits: Int,
        public val budgetReached: Boolean,
        /** True if the round-0 confidence test (§5.2) held. */
        public val confident: Boolean,
        internal val costs: List<Int>,
    )

    /**
     * The spelling suggestions for [typed] (the current word or the remainder after an elided prefix) whose validity
     * (05 §7) is [valid]; [cancel] is checked at least every 1 000 node visits.
     */
    @JvmOverloads
    public fun suggest(typed: String, valid: Boolean, cancel: CancellationCheck? = null): Result {
        val t = prepare(typed)
        if (t.isEmpty()) return Result(emptyList(), 0, false, false, emptyList())
        if (!valid && sp.digraphs.isNotEmpty()) transliterated(t)?.let { return it }
        return Search(t, cancel).run(valid)
    }

    // ------------------------------------------------------------------ §5.5 transliterated input

    private fun transliterated(t: String): Result? {
        val occ = ArrayList<IntArray>() // start, digraph index
        for (i in t.indices) for ((d, pair) in sp.digraphs.withIndex()) if (t.startsWith(pair.first, i)) occ.add(intArrayOf(i, d))
        if (occ.isEmpty() || occ.size > 12) return null
        val found = HashMap<String, Int>()
        val n = occ.size
        for (mask in 1 until (1 shl n)) {
            var last = -1
            var ok = true
            var onlySs = true
            val sb = StringBuilder()
            var pos = 0
            for (b in 0 until n) {
                if (mask and (1 shl b) == 0) continue
                val (start, d) = occ[b].let { it[0] to it[1] }
                val from = sp.digraphs[d].first
                if (start < last) { ok = false; break }
                sb.append(t, pos, start).append(sp.digraphs[d].second)
                pos = start + from.length
                last = pos
                if (from.lowercase() != "ss") onlySs = false
            }
            if (!ok) continue
            sb.append(t, pos, t.length)
            val v = sb.toString()
            val base = if (onlySs) 1 else 2
            for (cand in listOf(v, flipFirst(v))) {
                if (!lex.exact(cand)) continue
                val cost = base + if (Character.isUpperCase(cand[0]) != Character.isUpperCase(t[0])) 1 else 0
                val recased = recase(cand, t)
                val old = found[recased]
                if (old == null || cost < old) found[recased] = cost
            }
        }
        if (found.isEmpty()) return null
        val list = found.entries.sortedWith(compareBy({ it.value }, { it.key }))
        return Result(list.map { it.key }, 0, false, false, list.map { it.value })
    }

    private fun flipFirst(s: String): String {
        if (s.isEmpty()) return s
        val c = s[0]
        val f = if (Character.isUpperCase(c)) Character.toLowerCase(c) else Character.toUpperCase(c)
        return f + s.substring(1)
    }

    // ------------------------------------------------------------------ the search (§3–§5)

    private class Entry(val word: String, var cost: Int)

    private inner class Search(private val typedText: String, private val cancel: CancellationCheck?) {
        private val m = typedText.length
        private val end = m - 1
        private val tOrig = typedText.toCharArray()
        private val tLow = CharArray(m) { low(tOrig[it]) }
        private val tFold = CharArray(m) { fold(tOrig[it]) }

        private val cap = 300
        private val cOrig = CharArray(cap + 8)
        private val cLow = CharArray(cap + 8)
        private val cFold = CharArray(cap + 8)
        private var cLen = 0

        private var limit = 0
        private val list = ArrayList<Entry>()
        private var lowest = Int.MAX_VALUE
        private var secondWord = false
        private var secondStart = 0
        var visits = 0
        var stopped = false

        private val levels = arrayOfNulls<CasedForms.Level>(cap + 8)
        private val succ = arrayOfNulls<LongArray>(cap + 8)
        private val twoBuf = arrayOfNulls<LongArray>(cap + 8)
        private val resolveBuf = LongArray(MAX_SUCC + 2)

        private fun level(n: Int): CasedForms.Level = levels[n] ?: CasedForms.Level().also { levels[n] = it }
        private fun succBuf(n: Int): LongArray = succ[n] ?: LongArray(MAX_SUCC + 2).also { succ[n] = it }
        private fun twoWordBuf(n: Int): LongArray = twoBuf[n] ?: LongArray(MAX_SUCC + 2).also { twoBuf[n] = it }

        // ---- characters

        private fun t(i: Int): Char = if (i in 0..end) tFold[i] else EMPTY
        private fun tl(i: Int): Char = if (i in 0..end) tLow[i] else EMPTY
        private fun to(i: Int): Char = if (i in 0..end) tOrig[i] else EMPTY
        private fun c(j: Int): Char = if (j in 0 until cLen) cFold[j] else EMPTY
        private fun cl(j: Int): Char = if (j in 0 until cLen) cLow[j] else EMPTY
        private fun co(j: Int): Char = if (j in 0 until cLen) cOrig[j] else EMPTY

        private fun setC(j: Int, ch: Char) {
            cOrig[j] = ch
            cLow[j] = low(ch)
            cFold[j] = fold(ch)
        }

        // ---- states: cost 0-7, bonus 8-15, pending 16-21, space mark 22, p + 16 23-31, rule + 1 32-47

        private fun cost(s: Long) = (s and 0xFF).toInt()
        private fun bonus(s: Long) = ((s ushr 8) and 0xFF).toInt()
        private fun pending(s: Long) = ((s ushr 16) and 0x3F).toInt()
        private fun space(s: Long) = (s ushr 22) and 1L != 0L
        private fun p(s: Long) = ((s ushr 23) and 0x1FF).toInt() - 16
        private fun rule(s: Long) = ((s ushr 32) and 0xFFFF).toInt() - 1
        private fun net(s: Long) = (cost(s) - bonus(s)).coerceIn(0, 255)

        private fun make(cost: Int, bonus: Int, pending: Int, space: Boolean, p: Int, rule: Int): Long =
            cost.toLong() or (bonus.toLong() shl 8) or (pending.coerceIn(0, 63).toLong() shl 16) or
                ((if (space) 1L else 0L) shl 22) or ((p + 16).coerceIn(0, 511).toLong() shl 23) or ((rule + 1).toLong() shl 32)

        private fun add(s: Long, v: Int): Long = if (v >= 0) {
            make((cost(s) + v).coerceIn(0, 255), bonus(s), pending(s), space(s), p(s), rule(s))
        } else {
            make(cost(s), (bonus(s) - v).coerceIn(0, 255), pending(s), space(s), p(s), rule(s))
        }

        private fun withP(s: Long, p: Int) = make(cost(s), bonus(s), pending(s), space(s), p, rule(s))
        private fun withPending(s: Long, k: Int) = make(cost(s), bonus(s), k, space(s), p(s), rule(s))
        private fun withSpace(s: Long, v: Boolean) = make(cost(s), bonus(s), pending(s), v, p(s), rule(s))
        private fun withRule(s: Long, r: Int) = make(cost(s), bonus(s), pending(s), space(s), p(s), r)

        private val P = sp.p
        private val B = sp.b
        private val E = sp.e
        private val french = sp.languageTag.startsWith("fr")

        // ---- §4.4 accent check

        private fun accentCheck(s: Long, n: Int, dc: Int, dt: Int): Long {
            if (!sp.accentCheck) return s
            val ci = n - dc
            val ti = p(s) - dt
            val cf = c(ci)
            val tf = t(ti)
            if (cf == EMPTY || tf == EMPTY || cf != tf) return s
            val lc = cl(ci)
            val lt = tl(ti)
            if (lc == lt) return s
            val typedPlain = lt == tf
            val candAccented = lc != cf
            return when {
                typedPlain && candAccented -> if (french && Character.isUpperCase(to(ti))) s else add(s, P[28])
                !typedPlain && !candAccented -> add(s, P[29])
                !typedPlain && candAccented -> add(s, P[27])
                else -> s
            }
        }

        // ---- §4 advance

        /** Successors of [s] for the candidate letter at [n] (c(n) empty when resolving), into [out]; returns the count. */
        private fun advance(s0: Long, n: Int, out: LongArray): Int {
            var s = s0
            val k = pending(s)
            val pp = p(s)
            // 4.2 space mark
            if (space(s)) {
                if (t(pp - k) != c(n - k)) return 0
                s = withSpace(s, false)
            }
            // 4.3 inside a rule
            val ri = rule(s)
            if (ri >= 0) {
                val r = sp.rules[ri]
                val want = r.intended[k]
                if (want != cl(n) && want != c(n)) return 0
                out[0] = if (r.intended.length > k + 1) {
                    accentCheck(withPending(s, k + 1), n, 0, k + 1)
                } else {
                    var x = make(cost(s), bonus(s), 0, ' ' in r.intended, pp - k + r.typed.length - 1, -1)
                    x = accentCheck(x, n, 0, 0)
                    x
                }
                return 1
            }
            // 4.1 no pending letter
            if (k == 0) {
                val cn = c(n)
                val match = (cn == EMPTY && pp > end) || (pp <= end && t(pp) == cn && tl(pp) == cl(n))
                out[0] = withPending(s, if (match) 0 else 1)
                return 1
            }
            // 4.5 edits
            outBuf = out
            outCount = 0
            // 1. group rules
            val shapes = groupRules(s, n, k, pp)
            val cn = c(n)
            // 2. extra letters (unless shape 0)
            if (shapes and 1 == 0) {
                if (pp + 1 <= end && t(pp + 1) == c(n - 1)) {
                    var x = extraLetter(s, n - 1, pp)
                    x = withP(x, pp + 1)
                    if (!look(pp + 1, cn)) emit(add(withPending(x, 1), P[12] / 4))
                }
                if (t(pp) == c(n - 1)) {
                    var x = extraLetter(s, n - 1, pp)
                    x = withP(x, pp + 1)
                    x = withPending(x, if (look(pp + 1, cn)) 0 else 1)
                    x = accentCheck(x, n, 0, 0)
                    x = accentCheck(x, n, 1, 1)
                    emit(x)
                } else if (pp >= 2 && t(pp - 1) == t(pp - 2)) {
                    var x = extraLetter(s, n - 1, pp)
                    x = withP(x, pp + 1)
                    val y = substitution(x, n, pp + 1)
                    if (y != NONE) emit(accentCheck(y, n, 2, 2))
                }
            }
            // 3. substitution (unless shape 2)
            if (shapes and 4 == 0) {
                val y = substitution(s, n, pp)
                if (y != NONE) emit(y)
            }
            // 4. omission and swap
            if (cn == t(pp - 1)) {
                if (shapes and 2 == 0) {
                    var x = withP(s, pp - 1)
                    x = omission(x, n, pp - 1)
                    x = withPending(x, if (look(pp - 1, cn)) 0 else 1)
                    x = accentCheck(x, n, 0, 0)
                    x = accentCheck(x, n, 0, 1)
                    emit(x)
                }
                if (shapes and 8 == 0 && t(pp) == c(n - 1)) {
                    var x = add(s, if (isVowel(t(pp)) && isVowel(cn)) P[4] else P[6])
                    x = if (end < pp + 1) add(x, -E[5]) else if (n == 1) add(x, B[5]) else x
                    if (pp <= end && sp.isAdjacent(t(pp), cn)) x = add(x, -P[21])
                    if (t(pp) == ' ' || cn == ' ' || t(pp - 1) == ' ' || c(n - 1) == ' ') x = withSpace(x, true)
                    x = withPending(x, 0)
                    x = accentCheck(x, n, 0, 1)
                    x = accentCheck(x, n, 0, 0)
                    emit(x)
                }
            } else if (shapes and 2 == 0 && n > 1 && c(n - 1) == c(n - 2)) {
                var x = withP(s, pp - 1)
                x = omission(x, n, pp - 1)
                emit(withPending(x, 1))
            }
            return outCount
        }

        private var outBuf: LongArray = LongArray(0)
        private var outCount = 0

        private fun emit(x: Long) {
            if (outCount < MAX_SUCC) outBuf[outCount++] = x
        }

        /** The look-ahead matches at typed index [p] for the candidate letter [cn]. */
        private fun look(p: Int, cn: Char): Boolean = (cn == EMPTY && p > end) || (p <= end && t(p) == cn)

        /** §4.5.1: the extra typed letter t(p − 1) after candidate position [j]; [p] the state's p at this moment. */
        private fun extraLetter(s0: Long, j: Int, p: Int): Long {
            var s = s0
            val x = t(p - 1)
            if (j > 1 && p >= 2 && c(j - 1) == x && t(p - 2) == x) {
                s = add(s, if (isVowel(x)) P[3] else P[2])
                if (isSpaceLike(t(p))) s = withSpace(s, true)
                return s
            }
            when {
                x == '-' -> s = add(s, P[25])
                isPunct(x) -> s = add(s, P[23])
                else -> {
                    s = add(s, P[12])
                    s = if (c(j) == EMPTY) add(s, -E[0]) else if (p - 1 == 0) add(s, B[0]) else s
                    if (p - 1 >= 3 && j > 3 && c(j - 3) == x && c(j - 1) == t(p - 2) && c(j - 2) == t(p - 3) &&
                        c(j - 4) == t(p - 4)
                    ) s = add(s, -P[19])
                }
            }
            if (isSpaceLike(c(j))) s = withSpace(s, true)
            return s
        }

        /** §4.5.2: the omitted candidate letter c(n − 1); [p] already moved back. */
        private fun omission(s0: Long, n: Int, p: Int): Long {
            var s = s0
            val y = c(n - 1)
            if (p > 0 && n > 1 && y == t(p - 1) && y == c(n - 2)) return add(s, if (isVowel(y)) P[1] else P[0])
            when {
                y == ' ' -> s = if (net(s) > 5) add(s, 200) else withSpace(add(s, P[13]), true)
                y == '.' -> s = add(s, P[15])
                y == '-' -> s = add(s, P[26])
                isPunct(y) -> s = add(s, P[24])
                else -> {
                    s = add(s, P[11])
                    s = if (p > end) add(s, -E[1]) else if (n - 1 == 0) add(s, B[1]) else s
                    if (p > 3 && n > 3 && y == t(p - 3) && c(n - 2) == t(p - 1) && c(n - 3) == t(p - 2) && c(n - 4) == t(p - 4)) {
                        s = add(s, -P[19])
                    }
                }
            }
            return s
        }

        /** §4.5.3: typed t(p − 1) for the intended c(n − 1), or [NONE] if not allowed; the successor keeps p = [p]. */
        private fun substitution(s0: Long, n: Int, p: Int): Long {
            var s = withP(s0, p)
            val a = t(p - 1)
            val b = c(n - 1)
            val cn = c(n)
            val ok = (cn == EMPTY && p > end) || (p <= end && t(p) == cn)
            s = withPending(s, if (ok) 0 else 1)
            var pairAccented = false
            if (b == ' ') {
                s = if (net(s) < 6) withSpace(add(s, P[14]), true) else add(s, 200)
            } else if (a < 'a' || a > '{') {
                if (!ok) return NONE
                s = add(s, if (isPunct(a) && !isPunct(b)) P[22] else P[10])
            } else {
                var found = -1
                val lc = cl(n - 1)
                val ints = sp.pairIntended[a - 'a']
                for (x in ints.indices) {
                    val it = ints[x]
                    if (it > lc) break
                    if (it == lc || it == b) {
                        found = sp.pairCost[a - 'a'][x]
                        pairAccented = low(it) != fold(it)
                        break
                    }
                }
                if (found >= 0) {
                    s = add(s, found)
                } else if (a == b && tl(p - 1) != lc) {
                    val typedPlain = tl(p - 1) == a
                    val candAccented = lc != b
                    s = add(s, when {
                        typedPlain && candAccented -> P[28]
                        !typedPlain && !candAccented -> P[29]
                        else -> P[27]
                    })
                } else if (isVowel(a) && isVowel(b)) {
                    s = add(s, if (sp.sameVowelClass(a, b)) P[7] else P[8])
                } else {
                    if (!ok) return NONE
                    if (n >= 3 && p >= 3 && b == t(p - 3) && c(n - 3) == a && c(n - 2) == t(p - 2) && (n == 3 || b != c(n - 4))) {
                        // second half of a reversed letter triple: no cost
                    } else {
                        s = add(s, if (!isVowel(a) && !isVowel(b)) P[9] else P[10])
                        if (sp.isAdjacent(a, b)) s = add(s, -P[20])
                    }
                }
            }
            s = if (p > end) add(s, -E[2]) else if (p - 1 == 0) add(s, B[2]) else s
            if (pending(s) == 0 && !pairAccented) s = accentCheck(s, n, 0, 0)
            return s
        }

        /** §4.6 group rules into the successor buffer; returns the shape mask (bit = 1 shl shape for shapes 0–3). */
        private fun groupRules(s: Long, n: Int, k: Int, pp: Int): Int {
            val i = pp - k
            val j = n - k
            var shapes = 0
            val lists = candidateRules(i, c(j))
            for (r in lists) {
                if (outCount >= MAX_SUCC) break
                var typedAccented = false
                var candAccented = false
                var candPlain = false
                // 1. typed side
                var q = i
                var ok = true
                for (ch in r.typed) {
                    if (q > end) { ok = false; break }
                    if (ch == tl(q)) {
                        // exact
                    } else if (ch == t(q)) {
                        if (low(to(q)) != t(q)) typedAccented = true
                    } else { ok = false; break }
                    q++
                }
                if (!ok) continue
                // 2. intended side from j up to n
                var idx = j
                var compared = 0
                for (ch in r.intended) {
                    if (idx > n || c(idx) == EMPTY) break
                    when {
                        ch == cl(idx) -> {}
                        ch == c(idx) -> candAccented = true
                        fold(ch) == c(idx) && fold(ch) != ch && cl(idx) == c(idx) -> candPlain = true
                        else -> { ok = false }
                    }
                    if (!ok) break
                    idx++
                    compared++
                }
                if (!ok) continue
                val rr = idx
                val rem = r.intended.length - compared
                // 3. context
                val L = r.context.length
                if (L > 0) {
                    if (j - L < 0) continue
                    for (x in 0 until L) {
                        val ch = r.context[x]
                        val ci = j - L + x
                        if (ch == cl(ci)) continue
                        if (ch == c(ci)) {
                            candAccented = true
                            if (low(to(i - L + x)) != t(i - L + x)) typedAccented = true
                            continue
                        }
                        ok = false
                        break
                    }
                    if (!ok) continue
                }
                // 5. successor
                var x = s
                if (candAccented) x = add(x, P[28])
                if (typedAccented || candPlain) x = add(x, P[29])
                val d = q - pp
                val shape = when {
                    rr < n -> if (d == 0) 0 else 4
                    rr == n -> if (d == 0) 2 else if (d > 0) 0 else 1
                    rr == n + 1 && rem == 0 -> if (d > 0) 3 else 1
                    else -> 4
                }
                if (n == 1) x = add(x, B[shape])
                x = add(x, r.cost)
                if (q > end) x = add(x, -E[shape])
                if (rem == 0) {
                    var pend = 0
                    var np = q - 1
                    if (rr <= n) {
                        np = q + (n - rr)
                        for (ci in rr..n) {
                            val tp = q + (ci - rr)
                            val same = if (tp > end) c(ci) == EMPTY else tl(tp) == cl(ci)
                            if (!same) {
                                pend = n - ci + 1
                                break
                            }
                        }
                    }
                    x = make(cost(x), bonus(x), pend, (j > 0 && c(j - 1) == ' ') || c(rr) == ' ', np, -1)
                } else {
                    x = make(cost(x), bonus(x), k + 1, space(x), pp, r.index)
                }
                emit(x)
                if (shape in 0..3) shapes = shapes or (1 shl shape)
            }
            return shapes
        }

        /** The rules tried at typed index i (§4.6), split by the first letter of their intended side. */
        private val rulesAt: Array<SpellingParams.RuleIndex> = Array(m) { sp.rulesFor(tl(it), t(it)) }

        /** The rules tried at typed index [i] where the candidate has the folded letter [x] at the rule start. */
        private fun candidateRules(i: Int, x: Char): List<SpellingParams.Rule> =
            (if (i in 0 until m) rulesAt[i] else sp.noTypedLetterRules).at(x)

        // ---- §4.7 candidate letter after the typed end

        private fun afterEnd(s0: Long, n: Int): Long {
            var s = s0
            if (space(s)) {
                s = add(s, 200)
                return withP(withPending(s, 0), p(s) - 1)
            }
            val y = c(n)
            for ((idx, r) in sp.rules.withIndex()) {
                if (r.typed.isNotEmpty()) break
                if (r.intended.isEmpty()) continue
                val first = r.intended[0]
                if (first != cl(n) && first != y) continue
                val L = r.context.length
                if (n - L < 0) continue
                var ok = true
                for (x in 0 until L) if (r.context[x] != cl(n - L + x)) { ok = false; break }
                if (!ok) continue
                s = add(add(s, r.cost), -E[4])
                return if (r.intended.length > 1) withPending(withRule(s, idx), 1) else withP(withPending(s, 0), p(s) - 1)
            }
            val pp = p(s)
            s = when {
                y == ' ' -> if (net(s) > 5) add(s, 200) else withSpace(add(s, P[13]), true)
                y == '.' -> add(add(s, P[15]), -E[1])
                y == '-' -> add(add(s, P[26]), -E[1])
                isPunct(y) -> add(add(s, P[24]), -E[1])
                pp > 0 && n > 1 && y == t(pp - 1) && y == c(n - 1) -> add(s, if (isVowel(y)) P[1] else P[0])
                else -> add(add(s, P[11]), -E[1])
            }
            return withP(withPending(s, 0), pp - 1)
        }

        // ---- §4.8 resolving at the word end; §4.9 case penalty; §3.2 word end

        private fun resolve(s: Long, n: Int): Long {
            val cnt = advance(withP(s, p(s) + 1), n, resolveBuf)
            var best = NONE
            for (i in 0 until cnt) {
                var x = resolveBuf[i]
                if (pending(x) == 0) x = withP(x, p(x) + 1)
                val pp = p(x)
                if (pp <= end) {
                    if (space(x)) break
                    var matched = false
                    val first = tl(pp)
                    val ft = t(pp)
                    if (ft in 'a'..'{') {
                        val dl = (sp.deletionRulesByFirst[first] ?: emptyList()) +
                            (if (ft != first) sp.deletionRulesByFirst[ft] ?: emptyList() else emptyList())
                        for (r in if (ft != first) dl.sortedBy { it.index } else dl) {
                            var q = pp
                            var folded = false
                            var ok = true
                            for (ch in r.typed) {
                                if (q > end) { ok = false; break }
                                if (ch == tl(q)) {
                                } else if (ch == t(q)) folded = true else { ok = false; break }
                                q++
                            }
                            if (!ok) continue
                            val L = r.context.length
                            if (pp - L < 0) continue
                            for (y in 0 until L) if (r.context[y] != tl(pp - L + y)) { ok = false; break }
                            if (!ok) continue
                            x = add(x, r.cost)
                            if (folded) x = add(x, P[29])
                            x = withP(x, q)
                            x = if (q > end) add(x, -E[4]) else add(x, (end - q + 1) * P[10])
                            matched = true
                            break
                        }
                    }
                    if (!matched) {
                        x = withP(x, pp + 1)
                        x = extraLetter(x, n, pp + 1)
                        x = add(x, (end - (pp + 1) + 1) * P[10])
                    }
                }
                if (best == NONE || net(x) < net(best)) best = x
            }
            return best
        }

        private fun casePenalty(s: Long, typed: CharArray, typedFrom: Int, typedLen: Int, cand: CharArray, cFrom: Int, l: Int): Long {
            fun tc(i: Int): Char = if (i < typedLen && typedFrom + i < typed.size) typed[typedFrom + i] else EMPTY
            val m = typedLen
            var mism = 0
            if (m == l && l >= 2) {
                for (i in 1 until m) {
                    val a = tc(i)
                    val b = cand[cFrom + i]
                    if (!Character.isLetter(a) || !Character.isLetter(b)) continue
                    if ((!Character.isLowerCase(a) && Character.isLowerCase(b)) || (Character.isLowerCase(a) && Character.isUpperCase(b))) mism++
                }
            }
            val t0 = tc(0)
            val c0 = cand[cFrom]
            if (Character.isLowerCase(t0) && Character.isUpperCase(c0)) {
                var noLower = true
                for (i in 0 until l) if (Character.isLowerCase(cand[cFrom + i])) { noLower = false; break }
                return if (net(s) < 6) {
                    if (mism > 0 && l > 1 && noLower) {
                        add(s, if (mism != l - 1) P[16] + mism * P[18] / (l - 1) else P[16] + P[18] / maxOf(mism, 10))
                    } else add(s, P[16])
                } else {
                    add(s, if (l > 1 && noLower) P[18] else P[17])
                }
            }
            var hasLower = false
            for (i in 0 until m) if (Character.isLowerCase(tc(i))) { hasLower = true; break }
            if (mism > 0 && hasLower) {
                var equal = true
                for (i in 0 until m) if (Character.toLowerCase(tc(i)) != Character.toLowerCase(cand[cFrom + i])) { equal = false; break }
                return add(s, if (equal) P[17] / 2 else P[17])
            }
            return s
        }

        private fun wordEnd(s: Long, n: Int, spelling: Int) {
            val y = add(s, sp.f[spelling])
            val saved = cLen
            cLen = n
            var r = resolve(y, n)
            cLen = saved
            if (r == NONE) return
            r = casePenalty(r, tOrig, 0, m, cOrig, 0, n)
            if (secondWord) {
                var skip = 0
                while (secondStart + skip < m && tOrig[secondStart + skip] == ' ') skip++
                r = casePenalty(r, tOrig, minOf(secondStart + skip, m), m - skip, cOrig, secondStart, n - secondStart)
            }
            val net = net(r)
            if (net > limit) return
            val word = String(cOrig, 0, n)
            if (word == typedText) return
            list.add(Entry(word, net))
            if (net < lowest) lowest = net
            val nl = minOf(limit, lowest + sp.s(1))
            if (nl < limit) {
                limit = nl
                list.removeAll { it.cost > limit }
            }
        }

        private fun twoWord(s: Long, n: Int, spelling: Int) {
            setC(n, ' ')
            cLen = n + 1
            val buf = twoWordBuf(n)
            val cnt = advance(withP(s, p(s) + 1), n, buf)
            for (i in 0 until cnt) {
                var y = buf[i]
                if (p(y) > end && rule(y) < 0) y = afterEnd(y, n)
                if (net(y) > limit) continue
                y = add(y, sp.f[spelling])
                y = withP(y, p(y) + 1)
                secondWord = true
                secondStart = n + 1
                walk(0, forms.rootModes, 0, n + 1, 0, y)
                secondWord = false
                if (stopped) return
            }
        }

        // ---- §3.1 walk

        private fun walk(keyNode: Int, modes: Int, expNode: Int, n: Int, depth: Int, s: Long) {
            if (n >= cap) return
            val lv = level(n)
            // deeper calls and the two-word walk use the levels and buffers of higher candidate indices only
            forms.children(keyNode, modes, expNode, depth, lv)
            val size = lv.size
            val chars = lv.char
            val kn = lv.keyNode
            val md = lv.modes
            val en = lv.expNode
            val hc = lv.hasChildren
            val out = succBuf(n)
            for (ci in 0 until size) {
                visits++
                if (visits % 1000 == 0 && cancel != null && cancel.isCancelled()) throw QueryCancelledException()
                if (visits >= SPELL_VISIT_MAX) {
                    stopped = true
                    return
                }
                val x = chars[ci]
                setC(n, x)
                cLen = n + 1
                val cnt = advance(s, n, out)
                for (si in 0 until cnt) {
                    var y = out[si]
                    if (p(y) > end && rule(y) < 0) y = afterEnd(y, n)
                    // 2.2 the first letter of a second word takes the typed capital
                    val pi = p(y)
                    if (n > 0 && cOrig[n - 1] == ' ' && pi in 0..end && Character.isUpperCase(tOrig[pi])) setC(n, Character.toUpperCase(x))
                    else setC(n, x)
                    cLen = n + 1
                    if (net(y) > limit) continue
                    val sc = forms.spellingAt(lv, ci)
                    if (sc >= 0) {
                        wordEnd(y, n + 1, sc)
                        if (stopped) return
                        if ((net(y) == 0 || inSpaceRule(y)) && !secondWord) {
                            twoWord(y, n + 1, sc)
                            if (stopped) return
                        }
                    }
                    if (hc[ci]) {
                        cLen = n + 1
                        walk(kn[ci], md[ci], en[ci], n + 1, depth + 1, withP(y, p(y) + 1))
                        if (stopped) return
                    }
                }
            }
        }

        private fun inSpaceRule(s: Long): Boolean {
            val r = rule(s)
            return r >= 0 && ' ' in sp.rules[r].intended
        }

        // ---- §5 rounds and result

        fun run(valid: Boolean): Result {
            var prevLimit = 0
            var confident = false
            for (r in 0 until sp.s(2)) {
                val l = if (list.isEmpty() || prevLimit < lowestOf() + sp.s(1)) (r + 1) * sp.s(0) else lowestOf() + sp.s(1)
                limit = l
                lowest = lowestOf()
                walk(0, forms.rootModes, 0, 0, 0, make(0, 0, 0, false, 0, -1))
                mergeByWord()
                prevLimit = limit
                if (r == 0 && m > 3 && !Character.isUpperCase(tOrig[0]) && !Character.isUpperCase(tOrig[1])) {
                    costSort(list)
                    if (list.isNotEmpty()) confident = confidence()
                    if (confident) break
                }
                if (stopped || list.size >= 5 || r + 1 == sp.s(2)) break
            }
            val out = if (valid) validResult() else invalidResult(confident)
            return Result(out.map { it.word }, visits, stopped, confident, out.map { it.cost })
        }

        private fun lowestOf(): Int = list.minOfOrNull { it.cost } ?: Int.MAX_VALUE

        private fun mergeByWord() {
            list.sortWith { a, b -> a.word.compareTo(b.word) }
            var w = 0
            for (i in list.indices) {
                if (w > 0 && list[w - 1].word == list[i].word) {
                    if (list[i].cost < list[w - 1].cost) list[w - 1].cost = list[i].cost
                    continue
                }
                list[w++] = list[i]
            }
            while (list.size > w) list.removeAt(list.size - 1)
        }

        private fun confidence(): Boolean {
            val S = sp.s
            val r = maxOf(S[8], (S[4] - m) * S[7] + S[6])
            var th = (m - S[4]) * S[5] + S[10]
            if (th * r / 32 > S[9]) th = S[9] * 32 / r
            val best = list[0]
            if (best.cost > th) return false
            if (best.word.length > 1 && Character.isUpperCase(best.word[1])) return false
            if (Character.isLowerCase(tOrig[0]) && Character.isUpperCase(best.word[0]) &&
                best.word.substring(1) != typedText.substring(1)
            ) return false
            val runners = if (sp.languageTag.startsWith("en")) {
                list.drop(1).filter { it.word != best.word + "s" && it.word != best.word + "d" }
            } else list.drop(1).take(1)
            for (e in runners) {
                if (S[11] > 0) {
                    if (e.cost - best.cost < S[11]) return false
                } else if (e.cost * 32 <= best.cost * r) return false
            }
            return true
        }

        private fun invalidResult(confident: Boolean): List<Entry> {
            val tmp = ArrayList<Entry>()
            for (e in list) {
                if (e.cost > 200) continue
                val w = recase(e.word, typedText)
                tmp.add(Entry(w, e.cost))
                if (french) {
                    val un = unaccentCapitals(w)
                    if (un != w) tmp.add(Entry(un, e.cost))
                }
            }
            tmp.sortWith { a, b -> a.word.compareTo(b.word) }
            val merged = ArrayList<Entry>()
            for (e in tmp) {
                if (merged.isNotEmpty() && merged.last().word == e.word) {
                    if (e.cost < merged.last().cost) merged.last().cost = e.cost
                } else merged.add(Entry(e.word, e.cost))
            }
            merged.removeAll { it.word == typedText }
            val sorted = merged.sortedBy { it.cost } // stable
            val top = sorted.take(10)
            return if (confident && top.isNotEmpty()) top.take(1) else top
        }

        private fun validResult(): List<Entry> {
            val out = ArrayList(list)
            out.add(Entry(typedText, 0))
            return out.take(5)
        }
    }

    // ------------------------------------------------------------------ Appendix A: order of equal costs

    private fun costSort(a: MutableList<Entry>) {
        sortRange(a, 0, a.size, a.size)
    }

    private fun less(x: Entry, y: Entry) = x.cost < y.cost

    private fun sortRange(a: MutableList<Entry>, f0: Int, l0: Int, ideal0: Int) {
        var first = f0
        var last = l0
        var ideal = ideal0
        while (last - first > 32 && ideal > 0) {
            val (mf, ml) = partition(a, first, last)
            ideal /= 2
            ideal += ideal / 2
            if (mf - first < last - ml) {
                sortRange(a, first, mf, ideal)
                first = ml
            } else {
                sortRange(a, ml, last, ideal)
                last = mf
            }
        }
        if (last - first > 32) {
            val sub = a.subList(first, last).sortedBy { it.cost }
            for (i in sub.indices) a[first + i] = sub[i]
        } else if (last - first > 1) {
            for (i in first + 1 until last) {
                val v = a[i]
                var j = i
                while (j > first && less(v, a[j - 1])) {
                    a[j] = a[j - 1]
                    j--
                }
                a[j] = v
            }
        }
    }

    private fun swap(a: MutableList<Entry>, i: Int, j: Int) {
        val t = a[i]
        a[i] = a[j]
        a[j] = t
    }

    private fun median3(a: MutableList<Entry>, f: Int, m: Int, l: Int) {
        if (less(a[m], a[f])) swap(a, m, f)
        if (less(a[l], a[m])) {
            swap(a, l, m)
            if (less(a[m], a[f])) swap(a, m, f)
        }
    }

    private fun median(a: MutableList<Entry>, f: Int, m: Int, l: Int) {
        if (l - f > 40) {
            val step = (l - f + 1) / 8
            median3(a, f, f + step, f + 2 * step)
            median3(a, m - step, m, m + step)
            median3(a, l - 2 * step, l - step, l)
            median3(a, f + step, m, l - step)
        } else median3(a, f, m, l)
    }

    private fun partition(a: MutableList<Entry>, first: Int, last: Int): Pair<Int, Int> {
        val mid = first + (last - first) / 2
        median(a, first, mid, last - 1)
        var pf = mid
        var pl = mid + 1
        while (first < pf && !less(a[pf - 1], a[pf]) && !less(a[pf], a[pf - 1])) pf--
        while (pl < last && !less(a[pl], a[pf]) && !less(a[pf], a[pl])) pl++
        var gf = pl
        var gl = pf
        while (true) {
            while (gf < last) {
                if (less(a[pf], a[gf])) {
                    // pass
                } else if (less(a[gf], a[pf])) break
                else {
                    if (pl != gf) swap(a, pl, gf)
                    pl++
                }
                gf++
            }
            while (first < gl) {
                if (less(a[gl - 1], a[pf])) {
                    // pass
                } else if (less(a[pf], a[gl - 1])) break
                else {
                    pf--
                    if (pf != gl - 1) swap(a, pf, gl - 1)
                }
                gl--
            }
            if (gl == first && gf == last) return pf to pl
            if (gl == first) {
                if (pl != gf) swap(a, pf, pl)
                pl++
                swap(a, pf, gf)
                pf++
                gf++
            } else if (gf == last) {
                gl--
                pf--
                if (gl != pf) swap(a, gl, pf)
                pl--
                swap(a, pf, pl)
            } else {
                gl--
                swap(a, gf, gl)
                gf++
            }
        }
    }

    public companion object {
        /** `SPELL_VISIT_MAX` (05d §7). */
        public const val SPELL_VISIT_MAX: Int = 200_000

        private const val MAX_SUCC = 8
        private const val EMPTY = '\u0000'
        private const val NONE = Long.MIN_VALUE

        private val FOLD = CharArray(0x3000) { fold0(it.toChar()) }

        /** `low(c)` of 05d §1: the simple lower-case form, U+0130 and U+0131 → i. */
        internal fun low(c: Char): Char = if (c == 'İ' || c == 'ı') 'i' else Character.toLowerCase(c)

        private fun fold0(c: Char): Char {
            val l = low(c)
            val d = Normalizer.normalize(l.toString(), Normalizer.Form.NFD)
            if (d.length > 1 && d.substring(1).all { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }) return d[0]
            return l
        }

        /** `fold(c)` of 05d §1: [low] with the diacritic removed when it decomposes into a base letter and marks. */
        internal fun fold(c: Char): Char = if (c.code < FOLD.size) FOLD[c.code] else fold0(c)

        /** Vowels of 05d §1: a e i o u in either case and their accented Latin-1 forms. */
        internal fun isVowel(c: Char): Boolean {
            when (c) {
                'a', 'e', 'i', 'o', 'u', 'A', 'E', 'I', 'O', 'U' -> return true
            }
            val x = c.code
            return x in 0xE0..0xE5 || x in 0xE8..0xEF || x in 0xF2..0xF6 || x == 0xF8 || x in 0xF9..0xFC ||
                x in 0xC0..0xC5 || x in 0xC8..0xCF || x in 0xD2..0xD6 || x == 0xD8 || x in 0xD9..0xDC
        }

        /** Punctuation of 05d §1. */
        internal fun isPunct(c: Char): Boolean {
            if (c == EMPTY) return false
            val x = c.code
            if (x < 0x80) return c in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
            if (x in 0xA1..0xBF || c == '×' || c == '÷' || c == '€') return true
            if (x < 0x100) return false
            return when (Character.getType(c).toByte()) {
                Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
                Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
                -> true
                else -> false
            }
        }

        internal fun isSpaceLike(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

        /** §1 preparation: soft hyphens dropped, typographic quotes and dashes as ASCII, at most 127 characters. */
        internal fun prepare(s: String): String {
            val sb = StringBuilder(s.length)
            for (c in s) {
                when (c) {
                    '­' -> {}
                    '‘', '’', '‚', '‛' -> sb.append('\'')
                    '“', '”', '„', '‟' -> sb.append('"')
                    '‐', '‑', '‒', '–', '—', '―' -> sb.append('-')
                    else -> sb.append(c)
                }
                if (sb.length >= 127) break
            }
            return sb.toString()
        }

        /** §5.3 re-casing to the typed case pattern. */
        internal fun recase(word: String, typed: String): String {
            val letters = typed.count { Character.isLetter(it) }
            return when {
                letters > 1 && typed.all { !Character.isLetter(it) || Character.isUpperCase(it) } ->
                    String(CharArray(word.length) { i -> Character.toUpperCase(word[i]) })
                typed.isNotEmpty() && Character.isUpperCase(typed[0]) -> app.metrokeys.engine.text.capitalizeFirst(word)
                else -> word
            }
        }

        private fun unaccentCapitals(w: String): String {
            val ch = w.toCharArray()
            for (i in ch.indices) {
                val c = ch[i]
                if (Character.isUpperCase(c) && fold(c) != low(c)) ch[i] = Character.toUpperCase(fold(c))
            }
            return String(ch)
        }
    }
}
