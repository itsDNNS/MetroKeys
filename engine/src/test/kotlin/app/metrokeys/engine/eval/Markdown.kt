package app.metrokeys.engine.eval

import java.io.File

/** The human-readable summary of a report (05c §8.1), deterministic like the JSON. */
internal class Markdown(private val r: Map<String, Any?>) {
    private val sb = StringBuilder()

    private fun line(s: String = "") {
        sb.append(s).append('\n')
    }

    private fun v(x: Any?): String = when (x) {
        null -> "–"
        is Num -> x.text
        is Map<*, *> -> x.entries.joinToString(", ") { "${it.key} ${v(it.value)}" }
        else -> x.toString()
    }

    private fun esc(s: String) = s.replace("|", "\\|")

    @Suppress("UNCHECKED_CAST")
    fun text(): String {
        val h = r["header"] as Map<String, Any?>
        line("# Dictionary evaluation report")
        line()
        line("Spec 05c. Tier `${h["tier"]}`, EVAL_VERSION ${h["evalVersion"]}, salts ${v(h["salts"])}, profiles ${v(h["profiles"])}. " +
            "Gates and ratchet are report-only in this release.")
        line()
        line("- Run date: ${h["runDate"]} (not part of the report hash)")
        line("- Report hash: `${h["reportHash"]}`")
        line("- Engine build id: `${h["engineBuildId"]}`")
        line("- Parameter hash (05 §1): `${h["parameterHash"]}`")
        line()
        line("<details><summary>Input hashes</summary>")
        line()
        for (k in listOf("dataFiles", "testSets", "lexicons", "provenance")) {
            val m = h[k] as? Map<String, Any?> ?: continue
            for ((f, s) in m.toSortedMap()) line("- $k `$f`: `$s`")
        }
        line()
        line("</details>")
        line()
        gates()
        ratchet()
        results()
        lint()
        worst()
        gap()
        freshness()
        return sb.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun gates() {
        val gates = r["gates"] as? List<Map<String, Any?>> ?: return
        line("## Gates (test half, report-only)")
        line()
        line("| Profile | Language | Gate | Condition | Value | n | Limit | Baseline | Δ | Result |")
        line("|---|---|---|---|---:|---:|---|---:|---:|---|")
        for (g in gates) {
            line("| ${g["profile"]} | ${g["language"]} | ${g["gate"]} | ${g["condition"]} | ${v(g["value"])} | ${v(g["n"])} | ${g["limit"]} | " +
                "${v(g["baseline"])} | ${v(g["difference"])} | ${g["result"]} |")
        }
        line()
    }

    @Suppress("UNCHECKED_CAST")
    private fun ratchet() {
        val rt = r["ratchet"] as? Map<String, Any?> ?: return
        line("## Ratchet (report-only)")
        line()
        line("Baseline: ${v(rt["baseline"])}; result: ${rt["result"]}; metrics compared: ${v(rt["metricsCompared"])}; " +
            "new L1/L2 entries: ${v(rt["newLintEntryCount"])}.")
        line()
        (rt["worse"] as? List<String>)?.forEach { line("- worse: $it") }
        (rt["newLintEntries"] as? List<String>)?.take(50)?.forEach { line("- new lint entry: $it") }
        line()
    }

    @Suppress("UNCHECKED_CAST")
    private fun results() {
        val res = r["results"] as? Map<String, Any?> ?: return
        if (res.isEmpty()) return
        line("## Results per profile and language")
        line()
        line("Rates in percent. Error sets: fixed (AC recall), wrong, kept, precision, top-1, top-3; false-positive sets: " +
            "false corrections (F7 also the offer rate of the accented word and the accepted replacements by the more frequent " +
            "spelling, 05c §3.7). `coll` = collision bucket (typed text valid, not counted), `flag` = flagged bucket (expected " +
            "text a do-not-suggest form, not counted). E2 and E2a are judged by top-3, offer and wrong (05c §6, 0.7.1). " +
            "Differences to each baseline in percentage points.")
        line()
        for ((name, body) in res.toSortedMap()) {
            val b = body as Map<String, Any?>
            val agg = b["aggregates"] as Map<String, Any?>
            val rows = b["sets"] as List<Map<String, Any?>>
            for (half in listOf("test", "dev")) {
            if (rows.none { it["half"] == half && listOf("N", "S").any { vv -> ((it[vv] as Map<String, Any?>)["n"] as Int) > 0 } }) continue
            line("### $name, $half half")
            line()
            line("All errors (recall, N+S): ${v(agg["allErrors recall * $half"])} · all false positives (N+S): " +
                "${v(agg["allFalsePositives * $half"])} · base words without a context occurrence: ${v(b["baseWordsWithoutContext"])} · queries: ${v(b["queries"])}")
            line()
            line("| Set | Var | n | fixed | wrong | kept | prec | top-1 | top-3 | offer | false corr | coll | flag | Δ vs baselines |")
            line("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|")
            for (row in rows) {
                if (row["half"] != half) continue
                for (vv in listOf("N", "S")) {
                    val c = row[vv] as Map<String, Any?>
                    if ((c["n"] as Int) == 0 && c["collisions"] == null) continue
                    val main = if (c.containsKey("recall")) "recall" else "falseCorrection"
                    val d = c["$main vs baseline"]?.let { v(it) } ?: ""
                    val fc = c["falseCorrection"]?.let {
                        v(it) + (c["offer3"]?.let { o -> " (offer ${v(o)})" } ?: "") +
                            (c["acceptedReplacement"]?.let { a -> " (accepted ${v(a)})" } ?: "")
                    } ?: ""
                    line("| ${row["set"]} | $vv | ${c["n"]} | ${c["recall"]?.let(::v) ?: ""} | ${c["wrong"]?.let(::v) ?: ""} | " +
                        "${c["kept"]?.let(::v) ?: ""} | ${c["precision"]?.let(::v) ?: ""} | ${c["top1"]?.let(::v) ?: ""} | " +
                        "${c["top3"]?.let(::v) ?: ""} | ${c["offer"]?.let(::v) ?: ""} | $fc | ${v(c["collisions"])} | ${v(c["flagged"])} | $d |")
                }
            }
            line()
            }
            for ((key, title) in listOf("flaggedItems" to "flagged bucket (expected text a do-not-suggest form, 05c §3.5)",
                "acceptedReplacements" to "F7 accepted replacements (05c §3.7)")) {
                val items = b[key] as? List<String> ?: continue
                if (items.isEmpty()) continue
                line("<details><summary>$name: ${items.size} items of the $title</summary>")
                line()
                for (i in items.take(200)) line("- $i")
                line()
                line("</details>")
                line()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun lint() {
        val lint = r["lint"] as? Map<String, Any?> ?: return
        line("## Lint")
        line()
        line("| Language | L1 | L2 (L1/L2/L3 bands) | L3 coverage 20 000 / 100 000 | L4 | L5 |")
        line("|---|---:|---|---|---:|---:|")
        for ((tag, x) in lint.toSortedMap()) {
            val m = x as Map<String, Map<String, Any?>>
            val l3 = m.getValue("L3")["shares"] as Map<String, Any?>
            line("| $tag | ${m.getValue("L1")["count"]} | ${m.getValue("L2")["count"]} (${v(m.getValue("L2")["counts"])}) | " +
                "${v(l3["top20000"])} % / ${v(l3["top100000"])} % | ${m.getValue("L4")["count"]} | ${m.getValue("L5")["count"]} |")
        }
        line()
        for ((tag, x) in lint.toSortedMap()) {
            val m = x as Map<String, Map<String, Any?>>
            line("<details><summary>$tag: first 50 entries of each list</summary>")
            line()
            for (code in listOf("L1", "L2", "L3", "L4", "L5")) {
                val e = m.getValue(code)["first50"] as List<String>
                line("**$code** (${m.getValue(code)["count"]}${m.getValue(code)["count vs baseline"]?.let { ", Δ ${v(it)}" } ?: ""}): " +
                    if (e.isEmpty()) "none" else esc(e.joinToString("; ")))
                line()
            }
            line("</details>")
            line()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun worst() {
        val w = r["worst"] as? Map<String, Any?> ?: return
        line("## Worst 50 per profile and language")
        line()
        line("Severity = outcome weight × max(1, zipf); provenance flags from the build's source-form export.")
        line()
        for ((profile, byLang) in w.toSortedMap()) for ((lang, items) in (byLang as Map<String, Any?>).toSortedMap()) {
            line("### $profile $lang")
            line()
            for ((i, it) in (items as List<Map<String, Any?>>).withIndex()) {
                line("${i + 1}. [${v(it["severity"])}] ${esc(it["line"] as String)}")
                line("   (${it["half"]} half, sentence ${it["sentenceId"]}; provenance: typed ${it["typedFlags"]}; " +
                    (it["bar"] as List<String>).joinToString("; ") { e -> esc(e.substringBefore(" (") + " " + e.substringAfterLast("flags ").removeSuffix(")")) } + ")")
            }
            line()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun gap() {
        val g = r["devTestGap"] as? Map<String, Any?> ?: return
        line("## Dev/test gap")
        line()
        for ((k, x) in g.toSortedMap()) line("- $k: ${v(x)}")
        line()
    }

    @Suppress("UNCHECKED_CAST")
    private fun freshness() {
        val f = r["freshness"] as? Map<String, Any?> ?: return
        line("## Freshness run (salt B, test half)")
        line()
        for ((k, x) in f.toSortedMap()) line("- $k: ${v(x)}")
        line()
    }
}

/** Entry point of the Gradle tasks evalLint (T0), evalFast (T1) and evalFull (T2). */
internal object EvaluationMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val opts = args.associate { it.substringBefore('=').removePrefix("--") to it.substringAfter('=', "") }
        val root = File(opts["root"] ?: ".").absoluteFile
        val tier = Tier.valueOf((opts["tier"] ?: "fast").uppercase())
        val dict = File(opts["dict"]?.takeIf { it.isNotEmpty() } ?: File(root, "engine/src/main/resources/app/metrokeys/engine/dict").path)
        val out = File(opts["out"]?.takeIf { it.isNotEmpty() } ?: File(root, "engine/build/evaluation/${tier.name.lowercase()}").path)
        val baseline = opts["writeBaseline"]?.takeIf { it.isNotEmpty() }?.let { File(it).let { f -> if (f.isAbsolute) f else File(root, it) } }
        val outDir = if (out.isAbsolute) out else File(root, out.path)
        val langs = opts["languages"]?.takeIf { it.isNotEmpty() }?.split(',') ?: Evaluation.ALL_LANGUAGES
        Evaluation(root, if (dict.isAbsolute) dict else File(root, dict.path), tier, languages = langs).run(outDir, baseline, opts["baselineName"]?.takeIf { it.isNotEmpty() })
    }
}
