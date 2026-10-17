package app.metrokeys.engine.lexicon

import app.metrokeys.engine.text.capitalizeFirst
import app.metrokeys.engine.text.upperPerChar

/**
 * The derived data of spec 05b §1.1 (0.7.1): cost anchors, guard class, offered bits and spelling classes. A version-3
 * word list stores them (written by the build from the form counts); for older files and for lexicons built from
 * entries (tests) they are derived here from the classes, with the class standing in for the form count (ties by the
 * form in code-unit order, as in the build).
 */
internal class DerivedData(val anchorClasses: IntArray, val anchorCosts: IntArray, val guardClass: Int) {
    companion object {
        /** `LM_ANCHORS` ranks (05 §1.1). */
        val ANCHOR_RANKS = intArrayOf(1, 3, 10, 30, 100, 300, 1000, 3000, 10000, 30000, 50000)
        private val ROW_DE = intArrayOf(112, 118, 122, 129, 140, 153, 166, 178, 192, 210, 222)
        private val ROW_EN = intArrayOf(99, 113, 130, 142, 153, 163, 172, 182, 199, 222, 235)
        private val ROW_FR = intArrayOf(107, 111, 117, 126, 139, 156, 173, 187, 205, 225, 234)
        private val ROW_MEDIAN = intArrayOf(91, 105, 117, 126, 140, 157, 172, 182, 199, 222, 234)

        /** `LM_TAIL_COST` (05 §1.2). */
        const val LM_TAIL_COST = 254

        /** `SUGGEST_MAX_RANK` (05 §1.2). */
        const val SUGGEST_MAX_RANK = 90000

        /** `AC_SPLIT_RANK` (05 §1.2). */
        const val AC_SPLIT_RANK = 5000

        /** The anchor row of a language (05b §7 profile). */
        fun anchorRow(tag: String): IntArray = when (tag.substringBefore('-')) {
            "de" -> ROW_DE
            "en" -> ROW_EN
            "fr" -> ROW_FR
            else -> ROW_MEDIAN
        }

        /** (`SPELL_RANK_1`, `SPELL_RANK_2`, `SPELL_TAIL_3`) of 05d §6. */
        private fun spellingRule(tag: String): Triple<Int, Int, Double> = when (tag.substringBefore('-')) {
            "de" -> Triple(3250, 8550, 0.044)
            "fr" -> Triple(0, 0, 0.024)
            else -> Triple(1000, 38000, 0.11)
        }

        /**
         * Anchors from the classes of the forms with class ≥ 1, sorted descending ([classesDesc]); the rule of 05b §1.1.
         */
        fun anchors(tag: String, classesDesc: IntArray): Pair<IntArray, IntArray> {
            val row = anchorRow(tag)
            val n = classesDesc.size
            val ac = ArrayList<Int>()
            val co = ArrayList<Int>()
            for (i in ANCHOR_RANKS.indices) {
                val r = ANCHOR_RANKS[i]
                if (r >= n) break
                val k = classesDesc[r - 1]
                if (ac.isNotEmpty() && ac.last() == k) continue
                ac.add(k)
                co.add(row[i])
            }
            val kMin = if (n > 0) classesDesc[n - 1] else 1
            if (ac.isNotEmpty() && ac.last() == kMin) {
                co[co.size - 1] = LM_TAIL_COST
            } else {
                ac.add(kMin)
                co.add(LM_TAIL_COST)
            }
            return ac.toIntArray() to co.toIntArray()
        }

        /**
         * The derived data of a lexicon under construction: from the file when [anchors] is given, else derived from
         * the classes, writing the offered bit (2) and the spelling class (bits 3-4) into [formKind].
         */
        fun of(
            tag: String,
            formStart: IntArray,
            formClass: ByteArray,
            formKind: ByteArray,
            keyNode: IntArray,
            ch: CharArray,
            parent: IntArray,
            explicitIndex: IntArray,
            explicitText: Array<String>,
            anchors: Pair<IntArray, IntArray>?,
            guard: Int,
        ): DerivedData {
            if (anchors != null) return DerivedData(anchors.first, anchors.second, guard)
            val keyCount = formStart.size - 1
            val formCount = formStart[keyCount]
            val texts = arrayOfNulls<String>(formCount)
            for (k in 0 until keyCount) {
                var node = keyNode[k]
                val sb = StringBuilder()
                while (node != 0) {
                    sb.append(ch[node])
                    node = parent[node]
                }
                val key = sb.reverse().toString()
                for (f in formStart[k] until formStart[k + 1]) {
                    texts[f] = when (formKind[f].toInt() and 3) {
                        0 -> key
                        1 -> capitalizeFirst(key)
                        2 -> upperPerChar(key)
                        else -> explicitText[java.util.Arrays.binarySearch(explicitIndex, f)]
                    }
                }
            }
            val cls = IntArray(formCount) { formClass[it].toInt() and 0xFF }
            val order = (0 until formCount).sortedWith(compareBy<Int>({ -cls[it] }, { texts[it]!! }))
            val ranked = order.filter { cls[it] >= 1 }
            val (ac, co) = anchors(tag, IntArray(ranked.size) { cls[ranked[it]] })
            val g = if (ranked.size >= AC_SPLIT_RANK) cls[ranked[AC_SPLIT_RANK - 1]] else if (ranked.isEmpty()) 1 else cls[ranked.last()]
            val (r1, r2, tail) = spellingRule(tag)
            for ((i, f) in ranked.withIndex()) {
                if (i < SUGGEST_MAX_RANK) formKind[f] = (formKind[f].toInt() or 0x04).toByte()
            }
            for ((i, f) in order.withIndex()) {
                val r = i + 1
                val sc = when {
                    r <= r1 -> 1
                    r <= r2 -> 2
                    r > formCount * (1 - tail) -> 3
                    else -> 0
                }
                formKind[f] = ((formKind[f].toInt() and 0x27) or (sc shl 3)).toByte()
            }
            return DerivedData(ac, co, g)
        }
    }
}
