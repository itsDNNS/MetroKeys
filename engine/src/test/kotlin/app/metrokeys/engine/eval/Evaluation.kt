package app.metrokeys.engine.eval

import app.metrokeys.engine.PredictionEngine
import app.metrokeys.engine.lexicon.Lexicon
import app.metrokeys.engine.predict.LanguageParams
import app.metrokeys.engine.predict.Params
import app.metrokeys.engine.text.toKey
import app.metrokeys.engine.touch.KeyboardGeometry
import java.io.File
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/** Tiers of 05c §10. */
internal enum class Tier { LINT, FAST, FULL }

/**
 * The dictionary evaluation of spec 05c: lint (§4), engine runs (§5), metrics (§6), gates and ratchet (§7, report-only
 * in 0.7.0), report (§8). Deterministic: the same inputs give the same report apart from the run date.
 */
internal class Evaluation(
    private val root: File,
    private val dictDir: File,
    private val tier: Tier,
    private val threads: Int = Runtime.getRuntime().availableProcessors().coerceAtMost(8),
    val languages: List<String> = ALL_LANGUAGES,
) {
    private val evalDir = File(root, "evaluation")
    private val layoutCode = mapOf("de-DE" to "de", "en-US" to "en", "es-ES" to "es", "fr-FR" to "fr", "it-IT" to "it", "pt-BR" to "pt")

    val data: Map<String, LanguageData> = languages.associateWith { tag ->
        val prov = File(root, "build-data/export/$tag.forms.tsv.gz")
        LanguageData(tag, File(dictDir, "$tag.words.gz"), File(dictDir, "$tag.pairs.gz"),
            EvalLexicon(File(evalDir, "lexicon/$tag.lexicon.tsv.gz")), if (prov.exists()) Provenance(prov) else null,
            File(root, "spec/data/layouts-${layoutCode.getValue(tag)}.json"))
    }

    private fun testSet(tag: String, salt: String) = TestSet.read(File(evalDir, "testsets/$tag.$salt.tsv"))

    // ------------------------------------------------------------------ runs

    class Job(val profile: String, val salt: String, val selected: String, val languages: List<String>,
              val items: List<Pair<EvalItem, String>>)

    class JobResult(val job: Job, val outcomes: List<Outcome>, val millis: Long)

    private fun fastFilter(i: EvalItem) = i.half == "dev" && i.variant == "N" && i.band != "B3" && i.set != "E9" && i.set != "R"

    fun jobs(): List<Job> {
        val out = ArrayList<Job>()
        val salts = if (tier == Tier.FULL) listOf("A", "B") else listOf("A")
        for (salt in salts) {
            val sets = languages.associateWith { testSet(it, salt) }
            for (tag in languages) {
                var items = sets.getValue(tag).items
                if (tier == Tier.FAST) items = items.filter(::fastFilter)
                out.add(Job("single", salt, tag, listOf(tag), items.map { it to tag }))
            }
            if (tier == Tier.FULL && "de-DE" in languages && "en-US" in languages) {
                val de = sets.getValue("de-DE").items.map { it to "de-DE" }
                val en = sets.getValue("en-US").items.filter { it.set in DE_EN_ENGLISH_SETS }.map { it to "en-US" }
                out.add(Job("de+en", salt, "de-DE", listOf("de-DE", "en-US"), de + en))
            }
        }
        return out
    }

    fun runJobs(jobs: List<Job>): List<JobResult> {
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures = jobs.map { job ->
                pool.submit(Callable {
                    val t0 = System.nanoTime()
                    val runner = ProfileRunner(job.profile, data.getValue(job.selected), job.languages.map { data.getValue(it) })
                    val outcomes = job.items.map { (item, lang) -> runner.run(item, lang) }
                    JobResult(job, outcomes, (System.nanoTime() - t0) / 1_000_000)
                })
            }
            return futures.map { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    // ------------------------------------------------------------------ lint

    fun lint(): Map<String, List<Lint.Result>> {
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures = languages.associateWith { tag ->
                pool.submit(Callable {
                    val d = data.getValue(tag)
                    val list: Lexicon = d.dictionary.lexicon
                    Lint(tag, list, d.lexicon, d.provenance, KeyboardGeometry.fromLayoutJson(d.layoutJson, "text.letters")).run()
                })
            }
            return futures.mapValues { it.value.get() }
        } finally {
            pool.shutdown()
        }
    }

    // ------------------------------------------------------------------ metrics (§6)

    class Cells {
        val cells = HashMap<CellKey, Counts>()

        fun record(profile: String, o: Outcome) {
            val i = o.item
            val key = CellKey(profile, o.language, countedSet(o), i.variant, i.half, i.band, i.lengthBand)
            cells.getOrPut(key) { Counts() }.record(o, isErrorSet(i.set, o.language))
        }

        fun sum(filter: (CellKey) -> Boolean): Counts {
            val c = Counts()
            for ((k, v) in cells) if (filter(k)) c.add(v)
            return c
        }
    }

    companion object {
        val ALL_LANGUAGES = listOf("de-DE", "en-US", "es-ES", "fr-FR", "it-IT", "pt-BR")
        val DE_EN_ENGLISH_SETS = setOf("E1", "E2", "E2a", "E3", "E4", "E5", "F1")
        val ERROR_SETS = listOf("E1", "E2", "E2a", "E3", "E4", "E5", "E6", "E7", "E8", "E9", "R")
        val FP_SETS = listOf("F1", "F2", "F2z", "F3", "F4", "F5", "F5z", "F6", "F7")
        val ALL_ERRORS = listOf("E1", "E2", "E3", "E4", "E5", "E6", "E7", "E8")
        val ALL_FP = listOf("F1", "F2", "F3", "F4", "F5", "F6", "F7")
        const val RATCHET_TOL_LARGE = 0.5
        const val RATCHET_TOL_SMALL = 1.0

        fun recall(c: Counts) = if (c.n == 0) 0.0 else 100.0 * c.fixed / c.n
        fun precision(c: Counts) = if (c.fixed + c.wrong == 0) 100.0 else 100.0 * c.fixed / (c.fixed + c.wrong)
        fun rate(a: Int, n: Int) = if (n == 0) 0.0 else 100.0 * a / n

        /** The metrics of a cell sum (§6) by name. */
        fun metrics(c: Counts, error: Boolean, set: String): Map<String, Double> =
            if (error) linkedMapOf("recall" to recall(c), "precision" to precision(c), "wrong" to rate(c.wrong, c.n),
                "kept" to rate(c.kept, c.n), "top1" to rate(c.top1, c.n), "top3" to rate(c.top3, c.n),
                "offer" to rate(c.offer, c.n))
            else if (set == "F7") linkedMapOf("falseCorrection" to rate(c.changed, c.n), "offer3" to rate(c.offer3, c.n),
                "acceptedReplacement" to rate(c.accepted, c.n))
            else linkedMapOf("falseCorrection" to rate(c.changed, c.n))

        /** True if a higher value of the metric is better. */
        fun higherIsBetter(metric: String) = metric !in setOf("wrong", "falseCorrection", "kept")
    }

    /**
     * Metric rows (id → (n, value)) of a run: per profile, language, set, variant, half; each also by band and by
     * length band; E1–E4 together; "all errors" and "all false positives" (equal weight per type/set).
     */
    fun metricRows(cells: Cells): Map<String, Pair<Int, Double>> {
        val rows = java.util.TreeMap<String, Pair<Int, Double>>()
        val keys = cells.cells.keys
        val combos = keys.map { Triple(it.profile, it.language, it.set) }.toSortedSet(compareBy({ it.first }, { it.second }, { it.third }))
        for ((profile, lang, set) in combos) {
            val error = isErrorSet(set, lang)
            for (half in listOf("test", "dev")) for (variant in listOf("N", "S", "*")) {
                fun put(band: String, lb: String) {
                    val c = cells.sum { it.profile == profile && it.language == lang && it.set == set && it.half == half &&
                        (variant == "*" || it.variant == variant) && (band == "*" || it.band == band) && (lb == "*" || it.lengthBand == lb) }
                    if (c.n == 0) return
                    for ((m, v) in metrics(c, error, set)) rows["$profile|$lang|$set|$variant|$half|$band|$lb|$m"] = c.n to v
                    rows["$profile|$lang|$set|$variant|$half|$band|$lb|collisions"] = (c.n + c.collisions) to c.collisions.toDouble()
                    if (c.flagged > 0) rows["$profile|$lang|$set|$variant|$half|$band|$lb|flagged"] = (c.n + c.flagged) to c.flagged.toDouble()
                }
                put("*", "*")
                for (b in listOf("B1", "B2", "B3", "-")) put(b, "*")
                for (lb in listOf("L1", "L2", "L3")) put("*", lb)
            }
        }
        val pl = keys.map { it.profile to it.language }.toSortedSet(compareBy({ it.first }, { it.second }))
        for ((profile, lang) in pl) for (half in listOf("test", "dev")) {
            for (variant in listOf("N", "S", "*")) {
                val vf = { k: CellKey -> variant == "*" || k.variant == variant }
                val e14 = cells.sum { it.profile == profile && it.language == lang && it.half == half && vf(it) &&
                    it.set in setOf("E1", "E2", "E3", "E4") && it.band in setOf("B1", "B2") && it.lengthBand in setOf("L2", "L3") }
                if (e14.n > 0) rows["$profile|$lang|E1-E4|$variant|$half|B1+B2|L2+L3|precision"] = e14.n to precision(e14)
                val e5 = cells.sum { it.profile == profile && it.language == lang && it.half == half && vf(it) && it.set == "E5" && it.band in setOf("B1", "B2") }
                if (e5.n > 0) rows["$profile|$lang|E5|$variant|$half|B1+B2|*|recall"] = e5.n to recall(e5)
                // 0.7.1: omissions are judged by offer, top-3 and wrong (§6, gates G8 and G9)
                for (s in listOf("E2", "E2a")) {
                    val c = cells.sum { it.profile == profile && it.language == lang && it.half == half && vf(it) && it.set == s && it.band in setOf("B1", "B2") }
                    if (c.n == 0) continue
                    rows["$profile|$lang|$s|$variant|$half|B1+B2|*|wrong"] = c.n to rate(c.wrong, c.n)
                    rows["$profile|$lang|$s|$variant|$half|B1+B2|*|top3"] = c.n to rate(c.top3, c.n)
                }
                val er = ALL_ERRORS.filter { isErrorSet(it, lang) }.mapNotNull { s ->
                    cells.sum { it.profile == profile && it.language == lang && it.half == half && vf(it) && it.set == s }.takeIf { it.n > 0 }
                }
                if (er.isNotEmpty()) rows["$profile|$lang|allErrors|$variant|$half|*|*|recall"] = er.sumOf { it.n } to er.map { recall(it) }.average()
                val fp = (ALL_FP + if (lang == "de-DE") listOf("E8") else emptyList()).filter { it in ALL_FP }.mapNotNull { s ->
                    cells.sum { it.profile == profile && it.language == lang && it.half == half && vf(it) && it.set == s }.takeIf { it.n > 0 }
                }
                if (fp.isNotEmpty()) rows["$profile|$lang|allFalsePositives|$variant|$half|*|*|falseCorrection"] =
                    fp.sumOf { it.n } to fp.map { rate(it.changed, it.n) }.average()
            }
        }
        return rows
    }

    // ------------------------------------------------------------------ severity and worst cases (§8.3)

    private fun severity(o: Outcome, lex: EvalLexicon): Double {
        val i = o.item
        val error = isErrorSet(i.set, o.language)
        val z = max(1.0, if (lex.zipf(i.baseKey) > 0) lex.zipf(i.baseKey) else lex.zipf(toKey(i.expected)))
        var w = 0.0
        if (error) {
            val x = i.expected
            w = when {
                o.autoCorrection == null && x in o.bar.take(3) -> 0.3
                o.autoCorrection == null -> 1.0
                o.autoCorrection == x -> 0.0
                else -> 3.0
            }
            if (w > 0 && o.bar.firstOrNull() != x) {
                val first = o.diagnostics?.entries?.firstOrNull()
                if (first != null && (first.unattested || toKey(first.word) in lex.names)) w += 1.0
            }
        } else if (o.autoCorrection != null && o.autoCorrection != o.typed && !o.acceptedReplacement) {
            // an accepted F7 replacement (§3.7) is no false correction
            w = 3.0
        }
        return w * z
    }

    private fun worst(outcomes: List<Outcome>, lex: Map<String, EvalLexicon>, prov: Map<String, Provenance?>): List<Map<String, Any?>> {
        val scored = outcomes.filter { !it.collision && !it.flagged }.map { it to severity(it, lex.getValue(it.language)) }.filter { it.second > 0 }
        val top = scored.sortedWith(compareBy<Pair<Outcome, Double>>({ -it.second }, { it.first.item.set }, { it.first.typed }, { it.first.item.id })).take(50)
        return top.map { (o, s) -> worstEntry(o, s, lex.getValue(o.language), prov) }
    }

    private fun worstEntry(o: Outcome, s: Double, lex: EvalLexicon, prov: Map<String, Provenance?>): Map<String, Any?> {
        val d = o.diagnostics
        fun flags(lang: String, word: String): String = prov[lang]?.flags(toKey(word).replace("'", "").replace("-", "").let {
            if (prov[lang]?.rows?.containsKey(toKey(word)) == true) toKey(word) else it }) ?: "-"
        val bar = d?.entries?.take(5)?.map { e ->
            val kind = when {
                e.unattested -> "${e.kind}, unattested"
                toKey(e.word) in lex.names -> "${e.kind}, name"
                else -> e.kind
            }
            "${e.text} ($kind, ${e.source}, ${e.languageTag}, lm ${e.lm}, ed ${e.ed ?: "-"}, score ${e.score ?: "-"}, AC score ${e.acScore ?: "-"}, flags ${flags(e.languageTag, e.word)})"
        } ?: o.bar.take(5)
        val v = d?.validity
        return linkedMapOf(
            "severity" to Num.of(s, 2),
            "id" to o.item.id,
            "half" to o.item.half,
            "set" to o.item.set,
            "variant" to o.item.variant,
            "sentenceId" to o.item.sentenceId,
            "typed" to o.typed,
            "expected" to o.item.expected,
            "autocorrection" to (o.autoCorrection ?: "none"),
            "bar" to bar,
            "typedIsValid" to (v?.valid ?: false),
            "validityRule" to (v?.rule ?: 0),
            "typedUnattested" to (d?.typedUnattested ?: false),
            "typedFlags" to flags(o.language, o.typed),
            "decisionStep" to (d?.step ?: "-"),
            "decidingLanguage" to (d?.languageTag ?: "-"),
            "line" to worstLine(o, d),
        )
    }

    private val stepNames = mapOf("1" to "1 character before blocks", "2" to "2 one-letter word", "2a" to "2a prefix letters",
        "3" to "3 digit or undone", "4" to "4 no correction candidate", "4t" to "4t best is the typed word",
        "4f" to "4f best is flagged", "4o" to "4o orthographic candidate applied", "4m" to "4m orthographic runner-up too close",
        "5" to "5 typed word valid", "6" to "6 AC score above the limit", "7" to "7 runner-up too close", "7e" to "7e elision guard",
        "7o" to "7o omission guard", "7r" to "7r omission reading applied", "7s" to "7s missing-space guard", "8" to "8 applied")

    private fun worstLine(o: Outcome, d: app.metrokeys.engine.QueryDiagnostics?): String {
        val bar = d?.entries?.take(5)?.joinToString(" · ") { e ->
            "${e.text} (${e.kind}, ${e.source}, lm ${e.lm}, ed ${e.ed ?: "-"}, score ${e.score ?: "-"}${e.acScore?.let { ", AC score $it" } ?: ""})"
        } ?: o.bar.take(5).joinToString(" · ")
        val v = d?.validity
        val valid = if (v != null && v.valid) "typed valid (rule ${v.rule}${if (d.typedUnattested) ", unattested" else ""})" else
            "typed invalid${if (d?.typedUnattested == true) " (unattested)" else ""}"
        val step = d?.step?.let { stepNames[it] ?: it } ?: "-"
        return "${o.language.take(2)} ${o.item.set} ${o.item.variant} \"${o.typed}\" → \"${o.item.expected}\" | autocorrection: " +
            "${o.autoCorrection ?: "none"} | bar: $bar | $valid | step $step"
    }

    // ------------------------------------------------------------------ report

    class Baseline(val name: String, val metrics: Map<String, Pair<Int, Double>>, val lint: Map<String, Int>, val lintLists: Map<String, Set<String>>)

    private fun readBaseline(f: File): Baseline {
        @Suppress("UNCHECKED_CAST")
        val j = app.metrokeys.engine.touch.Json.parse(f.readText()) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val m = (j["metrics"] as Map<String, List<Any?>>).mapValues { (it.value[0] as Double).toInt() to (it.value[1] as Double) }
        @Suppress("UNCHECKED_CAST")
        val lint = (j["lint"] as Map<String, Double>).mapValues { it.value.toInt() }
        @Suppress("UNCHECKED_CAST")
        val lists = (j["lintLists"] as Map<String, List<String>>).mapValues { it.value.toSet() }
        return Baseline(j["name"] as String, m, lint, lists)
    }

    fun baselines(): List<Baseline> {
        val dir = File(evalDir, "baseline")
        return (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).sortedBy { it.name }.map(::readBaseline)
    }

    /** The baseline the ratchet compares with: evaluation/baseline/RATCHET names its file. */
    fun ratchetBaseline(all: List<Baseline>): Baseline? {
        val f = File(evalDir, "baseline/RATCHET")
        if (!f.exists()) return null
        val name = f.readText().trim().removeSuffix(".json")
        return all.firstOrNull { it.name == name }
    }

    private fun lintKey(r: Lint.Result, entry: String): String = when (r.code) {
        // the entry without its class and flags (they may change while the shadow stays)
        "L1", "L2" -> entry.substringBefore(" (")
        else -> entry
    }

    fun run(writeTo: File, baselineOut: File?, baselineName: String?): Map<String, Any?> {
        val t0 = System.nanoTime()
        val lint = lint()
        val tLint = (System.nanoTime() - t0) / 1_000_000
        println("lint: $tLint ms for ${languages.size} languages")
        val results = if (tier == Tier.LINT) emptyList() else runJobs(jobs())
        for (r in results) println("${r.job.profile} ${r.job.salt} ${r.job.selected}: ${r.outcomes.size} queries in ${r.millis} ms")
        if (System.getProperty("eval.dump") == "true") dump(writeTo, results)
        val cellsBySalt = HashMap<String, Cells>()
        for (r in results) {
            val c = cellsBySalt.getOrPut(r.job.salt) { Cells() }
            for (o in r.outcomes) c.record(r.job.profile, o)
        }
        val rowsA = cellsBySalt["A"]?.let(::metricRows) ?: emptyMap()
        val rowsB = cellsBySalt["B"]?.let(::metricRows) ?: emptyMap()
        val all = baselines()
        val ratchet = ratchetBaseline(all)
        val lintCounts = linkedMapOf<String, Int>()
        val lintLists = linkedMapOf<String, Set<String>>()
        for ((tag, rs) in lint) for (r in rs) {
            lintCounts["$tag|${r.code}"] = r.count
            for ((k, v) in r.counts) lintCounts["$tag|${r.code}|$k"] = v
            if (r.code == "L1" || r.code == "L2") lintLists["$tag|${r.code}"] = r.entries.map { lintKey(r, it) }.toSortedSet()
        }

        val report = linkedMapOf<String, Any?>()
        report["header"] = header(results)
        report["gates"] = gates(rowsA, lint, ratchet)
        report["ratchet"] = ratchetSection(rowsA, lintCounts, lintLists, ratchet)
        report["results"] = resultsSection(rowsA, all, cellsBySalt["A"], results)
        report["lint"] = lint.mapValues { (tag, rs) -> rs.associate { r -> r.code to lintEntry(tag, r, all) } }
        if (results.isNotEmpty()) {
            val lex = data.mapValues { it.value.lexicon }
            val prov = data.mapValues { it.value.provenance }
            report["worst"] = results.filter { it.job.salt == "A" }.groupBy { it.job.profile }.mapValues { (_, rs) ->
                rs.flatMap { it.outcomes }.groupBy { it.language }.mapValues { (_, os) -> worst(os, lex, prov) }
            }
            report["devTestGap"] = gap(rowsA)
        }
        if (rowsB.isNotEmpty()) report["freshness"] = freshness(rowsA, rowsB)
        // run date excluded from the report hash
        val h = report["header"] as MutableMap<String, Any?>
        h["runDate"] = ""
        h["reportHash"] = ""
        val hash = sha256(JsonOut.write(report))
        h["reportHash"] = hash
        h["runDate"] = Instant.now().toString().substringBefore('T')
        writeTo.mkdirs()
        File(writeTo, "report.json").writeText(JsonOut.write(report))
        File(writeTo, "report.md").writeText(Markdown(report).text())
        if (baselineOut != null) {
            val b = linkedMapOf<String, Any?>(
                "name" to (baselineName ?: baselineOut.name.removeSuffix(".json")),
                "evalVersion" to (h["evalVersion"]),
                "dataFiles" to h["dataFiles"],
                "metrics" to rowsA.mapValues { listOf(it.value.first, Num.of(it.value.second, 3)) },
                "lint" to lintCounts,
                "lintLists" to lintLists.mapValues { it.value.toList() },
            )
            baselineOut.parentFile.mkdirs()
            baselineOut.writeText(JsonOut.write(b))
        }
        println("report: ${File(writeTo, "report.md")} (hash $hash), total ${(System.nanoTime() - t0) / 1_000_000} ms")
        return report
    }

    /** Every outcome as TSV (outcomes.tsv.gz in the report directory), for inspection only. */
    private fun dump(dir: File, results: List<JobResult>) {
        dir.mkdirs()
        java.util.zip.GZIPOutputStream(File(dir, "outcomes.tsv.gz").outputStream()).bufferedWriter().use { w ->
            w.write("profile\tsalt\tlanguage\tid\thalf\tset\tvariant\ttyped\texpected\tcollision\tautocorrection\tstep\tvalidity\tbar\n")
            for (r in results) for (o in r.outcomes) {
                val d = o.diagnostics
                w.write(listOf(r.job.profile, r.job.salt, o.language, o.item.id, o.item.half, o.item.set, o.item.variant, o.typed,
                    o.item.expected, o.collision, o.autoCorrection ?: "-", d?.step ?: "-",
                    d?.validity?.let { "${it.valid}/${it.rule}/${it.cost}" } ?: "-",
                    d?.entries?.take(5)?.joinToString(" · ") { "${it.text} ${it.lm}/${it.ed ?: "-"}/${it.score ?: "-"}" } ?: "-")
                    .joinToString("\t") + "\n")
            }
        }
    }

    private fun header(results: List<JobResult>): MutableMap<String, Any?> {
        val h = linkedMapOf<String, Any?>()
        val salts = results.map { it.job.salt }.distinct().sorted()
        h["evalVersion"] = 1
        h["tier"] = tier.name.lowercase()
        h["salts"] = salts.ifEmpty { listOf("A") }
        h["profiles"] = results.map { it.job.profile }.distinct().sorted()
        h["dataFiles"] = data.values.flatMap { listOf(it.wordsFile, it.pairsFile) }.associate { it.name to sha256(it) }
        h["testSets"] = (if (results.isEmpty()) emptyList() else salts.flatMap { s -> languages.map { File(evalDir, "testsets/$it.$s.tsv") } })
            .associate { "testsets/${it.name}" to sha256(it) }
        h["lexicons"] = data.values.associate { "lexicon/${it.lexicon.file.name}" to it.lexicon.sha256 }
        h["provenance"] = data.mapValues { it.value.provenance?.sha256 ?: "absent" }
        h["engineBuildId"] = engineBuildId()
        h["parameterHash"] = sha256(LanguageParams.ALL.joinToString("\n") { it.toString() } + "\n" +
            Params::class.java.declaredFields.filter { java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .sortedBy { it.name }.joinToString("\n") { f -> f.isAccessible = true; "${f.name}=${f.get(null)}" })
        h["gatesReportOnly"] = true
        h["runDate"] = ""
        h["reportHash"] = ""
        return h
    }

    /** SHA-256 over the engine's compiled classes (file names and bytes, sorted by path). */
    private fun engineBuildId(): String {
        val url = PredictionEngine::class.java.getResource("PredictionEngine.class") ?: return "unknown"
        if (url.protocol != "file") return "unknown"
        var dir = File(url.toURI()).parentFile
        repeat(3) { dir = dir.parentFile }   // …/classes/kotlin/main/app/metrokeys/engine → …/main
        val files = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.sortedBy { it.relativeTo(dir).path }.toList()
        val md = java.security.MessageDigest.getInstance("SHA-256")
        for (f in files) {
            md.update(f.relativeTo(dir).path.toByteArray())
            md.update(f.readBytes())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------ gates (§7)

    private fun gates(rows: Map<String, Pair<Int, Double>>, lint: Map<String, List<Lint.Result>>, ratchet: Baseline?): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        fun gate(profile: String, lang: String, code: String, what: String, value: Double?, n: Int?, limit: String, pass: Boolean?, metricId: String?, unit: String = "%") {
            val base = metricId?.let { ratchet?.metrics?.get(it) }?.second
            out.add(linkedMapOf("profile" to profile, "language" to lang, "gate" to code, "condition" to what,
                "value" to value?.let { if (unit == "%") Num.pct(it) else Num.of(it, 0) }, "n" to n, "limit" to limit,
                "baseline" to base?.let { if (unit == "%") Num.pct(it) else Num.of(it, 0) },
                "difference" to if (base != null && value != null) Num.of(value - base, 1) else null,
                "result" to when (pass) { null -> "n/a"; true -> "PASS"; false -> "FAIL" }, "reportOnly" to true))
        }
        val lintBy = lint.mapValues { (_, rs) -> rs.associateBy { it.code } }
        val profiles = rows.keys.map { it.split('|').take(2) }.distinct().sortedWith(compareBy({ it[0] }, { it[1] }))
        val pls = if (profiles.isEmpty()) languages.map { listOf("single", it) } else profiles
        for ((profile, lang) in pls) {
            val l = lintBy[lang] ?: continue
            if (profile == "single") {
                val l1 = l.getValue("L1").count
                gate(profile, lang, "G1", "L1 count", l1.toDouble(), null, "0", l1 == 0, null, "n")
                val l2 = l.getValue("L2").counts["L1"] ?: 0
                gate(profile, lang, "G2", "L2 count in length band L1", l2.toDouble(), null, "0", l2 == 0, null, "n")
                val cov = l.getValue("L3").shares.getValue("top20000")
                gate(profile, lang, "G7", "L3 coverage of ranks 1…20 000", cov, l.getValue("L3").counts["top20000"], "≥ 99.5 %", cov >= 99.5, null)
            }
            if (rows.isEmpty()) continue
            val f1 = listOf("N", "S").map { rows["$profile|$lang|F1|$it|test|*|*|falseCorrection"] }
            val f1n = f1.sumOf { it?.first ?: 0 }
            val f1c = f1.sumOf { (it?.let { it.first * it.second / 100.0 } ?: 0.0) }
            if (f1n > 0) gate(profile, lang, "G3", "F1 false corrections (N and S), items", Math.round(f1c).toDouble(), f1n, "0 items", Math.round(f1c) == 0L, null, "n")
            rows["$profile|$lang|E5|N|test|B1+B2|*|recall"]?.let {
                gate(profile, lang, "G4", "E5 AC recall, B1+B2, N", it.second, it.first, "≥ 95 %", it.second >= 95.0, "$profile|$lang|E5|N|test|B1+B2|*|recall")
            }
            rows["$profile|$lang|E1-E4|N|test|B1+B2|L2+L3|precision"]?.let {
                gate(profile, lang, "G5", "AC precision E1–E4, B1+B2, L2+L3, N", it.second, it.first, "≥ 97 %", it.second >= 97.0,
                    "$profile|$lang|E1-E4|N|test|B1+B2|L2+L3|precision")
            }
            for (set in listOf("F2", "F3", "F4", "F5", "F6")) for (v in listOf("N", "S")) {
                val id = "$profile|$lang|$set|$v|test|*|*|falseCorrection"
                rows[id]?.let { gate(profile, lang, "G6", "$set $v false-correction rate", it.second, it.first, "≤ 1.0 %", it.second <= 1.0, id) }
            }
            // 0.7.1 (report only): omissions by wrong rate and top-3
            for ((set, limit) in listOf("E2" to 3.0, "E2a" to 5.0)) {
                val id = "$profile|$lang|$set|N|test|B1+B2|*|wrong"
                rows[id]?.let { gate(profile, lang, "G8", "$set wrong rate, B1+B2, N", it.second, it.first, "≤ ${"%.1f".format(java.util.Locale.ROOT, limit)} %", it.second <= limit, id) }
            }
            for (set in listOf("E2", "E2a")) {
                val id = "$profile|$lang|$set|N|test|B1+B2|*|top3"
                rows[id]?.let { gate(profile, lang, "G9", "$set top-3, B1+B2, N", it.second, it.first, "≥ 60 %", it.second >= 60.0, id) }
            }
        }
        return out
    }

    private fun excludedFromRatchet(id: String): Boolean {
        val p = id.split('|')
        val set = p[2]
        val half = p[4]
        val metric = p[7]
        return half != "test" || set == "E9" || set == "R" || set == "F2z" || set == "F5z" || metric == "offer3" || metric == "collisions" ||
            metric == "flagged" || metric == "acceptedReplacement" || ((set == "E2" || set == "E2a") && metric == "recall")
    }

    private fun ratchetSection(rows: Map<String, Pair<Int, Double>>, lintCounts: Map<String, Int>, lintLists: Map<String, Set<String>>, base: Baseline?): Map<String, Any?> {
        if (base == null) return linkedMapOf("baseline" to null, "result" to "n/a (no ratchet baseline)", "reportOnly" to true)
        val worse = ArrayList<String>()
        var compared = 0
        for ((id, cur) in rows) {
            if (excludedFromRatchet(id)) continue
            val b = base.metrics[id] ?: continue
            val n = minOf(cur.first, b.first)
            if (n < 200) continue
            compared++
            val tol = if (n >= 1000) RATCHET_TOL_LARGE else RATCHET_TOL_SMALL
            val metric = id.substringAfterLast('|')
            val d = cur.second - b.second
            val bad = if (higherIsBetter(metric)) d < -tol else d > tol
            if (bad) worse.add("$id: ${Num.pct(b.second).text} → ${Num.pct(cur.second).text} (n $n, tolerance $tol)")
        }
        val newEntries = ArrayList<String>()
        for ((k, entries) in lintLists) {
            val b = base.lintLists[k] ?: emptySet()
            for (e in entries) if (e !in b) newEntries.add("$k: $e")
        }
        return linkedMapOf("baseline" to base.name, "metricsCompared" to compared, "worse" to worse,
            "newLintEntries" to newEntries.take(200), "newLintEntryCount" to newEntries.size,
            "result" to if (worse.isEmpty() && newEntries.isEmpty()) "PASS" else "FAIL", "reportOnly" to true)
    }

    private fun resultsSection(rows: Map<String, Pair<Int, Double>>, baselines: List<Baseline>, cells: Cells?, results: List<JobResult>): Map<String, Any?> {
        val out = java.util.TreeMap<String, Any?>()
        if (cells == null) return out
        val pls = cells.cells.keys.map { it.profile to it.language }.distinct()
        for ((profile, lang) in pls) {
            val sets = (ERROR_SETS + FP_SETS).filter { s -> cells.cells.keys.any { it.profile == profile && it.language == lang && it.set == s } }
            val table = ArrayList<Map<String, Any?>>()
            for (set in sets) for (half in listOf("test", "dev")) {
                val row = linkedMapOf<String, Any?>("set" to set, "half" to half)
                for (v in listOf("N", "S")) {
                    val prefix = "$profile|$lang|$set|$v|$half|*|*|"
                    val metricNames = if (isErrorSet(set, lang)) listOf("recall", "wrong", "kept", "precision", "top1", "top3", "offer")
                        else if (set == "F7") listOf("falseCorrection", "offer3", "acceptedReplacement") else listOf("falseCorrection")
                    val cell = linkedMapOf<String, Any?>()
                    val n = metricNames.firstNotNullOfOrNull { rows[prefix + it]?.first }
                    cell["n"] = n ?: 0
                    for (m in metricNames) {
                        val r = rows[prefix + m] ?: continue
                        cell[m] = Num.pct(r.second)
                        val diffs = linkedMapOf<String, Any?>()
                        for (b in baselines) b.metrics[prefix + m]?.let { diffs[b.name] = Num.of(r.second - it.second, 1) }
                        if (diffs.isNotEmpty()) cell["$m vs baseline"] = diffs
                    }
                    rows[prefix + "collisions"]?.let { cell["collisions"] = it.second.toInt() }
                    rows[prefix + "flagged"]?.let { cell["flagged"] = it.second.toInt() }
                    row[v] = cell
                }
                table.add(row)
            }
            val ts = results.firstOrNull { it.job.salt == "A" && it.job.selected == lang && it.job.profile == profile }
            val noContext = if (profile == "single") testSet(lang, "A").generation["baseWordsWithoutContext"] else null
            val aggregates = linkedMapOf<String, Any?>()
            for (half in listOf("test", "dev")) for (v in listOf("N", "S", "*")) {
                rows["$profile|$lang|allErrors|$v|$half|*|*|recall"]?.let { aggregates["allErrors recall $v $half"] = Num.pct(it.second) }
                rows["$profile|$lang|allFalsePositives|$v|$half|*|*|falseCorrection"]?.let { aggregates["allFalsePositives $v $half"] = Num.pct(it.second) }
            }
            // 0.7.1: the flagged buckets (§3.5) and the F7 accepted replacements (§3.7) with their items
            val own = ts?.outcomes?.filter { it.language == lang && !it.collision }.orEmpty()
            fun item(o: Outcome) = "${o.item.id} ${o.item.set} ${o.item.variant} ${o.item.half} \"${o.typed}\" → \"${o.item.expected}\"" +
                (o.autoCorrection?.let { " (autocorrection $it)" } ?: "")
            out["$profile $lang"] = linkedMapOf("sets" to table, "aggregates" to aggregates,
                "baseWordsWithoutContext" to (noContext?.let { (it as Double).toInt() }), "queries" to (ts?.outcomes?.size),
                "flaggedItems" to own.filter { it.flagged }.sortedBy { it.item.id }.map(::item),
                "acceptedReplacements" to own.filter { it.acceptedReplacement }.sortedBy { it.item.id }.map(::item))
        }
        return out
    }

    private fun lintEntry(tag: String, r: Lint.Result, baselines: List<Baseline>): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("count" to r.count)
        if (r.counts.isNotEmpty()) m["counts"] = r.counts
        if (r.shares.isNotEmpty()) m["shares"] = r.shares.mapValues { Num.of(it.value, 2) }
        val diffs = linkedMapOf<String, Any?>()
        for (b in baselines) b.lint["$tag|${r.code}"]?.let { diffs[b.name] = r.count - it }
        if (diffs.isNotEmpty()) m["count vs baseline"] = diffs
        m["first50"] = r.entries.take(50)
        return m
    }

    private fun gap(rows: Map<String, Pair<Int, Double>>): Map<String, Any?> {
        val out = java.util.TreeMap<String, Any?>()
        val pls = rows.keys.map { it.split('|').take(2) }.distinct()
        for ((profile, lang) in pls) {
            val ed = rows["$profile|$lang|allErrors|*|dev|*|*|recall"]?.second
            val et = rows["$profile|$lang|allErrors|*|test|*|*|recall"]?.second
            val fd = rows["$profile|$lang|allFalsePositives|*|dev|*|*|falseCorrection"]?.second
            val ft = rows["$profile|$lang|allFalsePositives|*|test|*|*|falseCorrection"]?.second
            val m = linkedMapOf<String, Any?>()
            if (ed != null && et != null) {
                m["allErrors recall dev − test"] = Num.of(ed - et, 1)
                if (ed - et > 2.0) m["flag recall"] = "possible overfitting"
            }
            if (fd != null && ft != null) {
                m["allFalsePositives dev − test"] = Num.of(fd - ft, 1)
                if (ft - fd > 2.0) m["flag false positives"] = "possible overfitting"
            }
            out["$profile $lang"] = m
        }
        return out
    }

    private fun freshness(a: Map<String, Pair<Int, Double>>, b: Map<String, Pair<Int, Double>>): Map<String, Any?> {
        val out = java.util.TreeMap<String, Any?>()
        val pls = a.keys.map { it.split('|').take(2) }.distinct()
        for ((profile, lang) in pls) {
            val m = linkedMapOf<String, Any?>()
            for ((name, metric) in listOf("allErrors" to "recall", "allFalsePositives" to "falseCorrection")) {
                val id = "$profile|$lang|$name|*|test|*|*|$metric"
                val x = a[id]?.second ?: continue
                val y = b[id]?.second ?: continue
                m["$name salt A"] = Num.pct(x)
                m["$name salt B"] = Num.pct(y)
                if (abs(x - y) > 2.0) m["flag $name"] = "difference above 2 points"
            }
            out["$profile $lang"] = m
        }
        return out
    }
}
