package io.github.bbugdigger.j2keval.eval

import kotlin.math.abs

/**
 * Renders a [RunSummary] in two formats:
 *  - [renderMarkdown] — the human-facing report, also fed to `$GITHUB_STEP_SUMMARY`.
 *  - [renderJson] — machine-readable for trend tracking, RL-dataset extraction, etc.
 *
 * The Markdown report has four sections (per the design):
 *  1. Aggregate banner — top-line numbers
 *  2. Per-file table
 *  3. Per-declaration drill-down
 *  4. Findings narrative — auto-generated top-N largest idiom gaps
 */
object ReportRenderer {

    fun renderMarkdown(target: String, summary: RunSummary): String = buildString {
        appendBanner(target, summary)
        appendLine()
        appendFileTable(summary.fileScores)
        appendLine()
        appendDeclarationDrilldown(summary.pairScores)
        appendLine()
        appendFindings(summary)
    }

    /** Hand-rolled JSON: keeps the dependency graph tiny, schema is small. */
    fun renderJson(target: String, summary: RunSummary): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"target\": ").append(jsonStr(target)).append(",\n")
        sb.append("  \"goldDeclarations\": ").append(summary.goldDeclarations).append(",\n")
        sb.append("  \"convertedDeclarations\": ").append(summary.convertedDeclarations).append(",\n")
        sb.append("  \"matched\": ").append(summary.matched).append(",\n")
        sb.append("  \"goldOnly\": ").append(summary.goldOnly).append(",\n")
        sb.append("  \"convertedOnly\": ").append(summary.convertedOnly).append(",\n")
        sb.append("  \"meanSimilarity\": ").append(jsonNum(summary.meanSimilarity)).append(",\n")
        sb.append("  \"files\": [\n")
        summary.fileScores.forEachIndexed { i, f ->
            sb.append("    {")
            sb.append("\"path\": ").append(jsonStr(f.sourceFile)).append(", ")
            sb.append("\"matched\": ").append(f.matchedCount).append(", ")
            sb.append("\"goldOnly\": ").append(f.goldOnlyCount).append(", ")
            sb.append("\"convertedOnly\": ").append(f.convertedOnlyCount).append(", ")
            sb.append("\"meanSimilarity\": ").append(jsonNum(f.meanSimilarity))
            sb.append("}")
            if (i < summary.fileScores.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ],\n")
        sb.append("  \"declarations\": [\n")
        summary.pairScores.forEachIndexed { i, ps ->
            sb.append("    {")
            sb.append("\"fqn\": ").append(jsonStr(ps.pair.gold.fqn)).append(", ")
            sb.append("\"kind\": ").append(jsonStr(ps.pair.gold.kind.name)).append(", ")
            sb.append("\"similarity\": ").append(jsonNum(ps.similarity)).append(", ")
            sb.append("\"goldIdioms\": ").append(jsonIdioms(ps.goldIdioms)).append(", ")
            sb.append("\"convertedIdioms\": ").append(jsonIdioms(ps.convertedIdioms))
            sb.append("}")
            if (i < summary.pairScores.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    // -- Markdown sections ------------------------------------------------

    private fun StringBuilder.appendBanner(target: String, s: RunSummary) {
        appendLine("# J2K eval — `$target`")
        appendLine()
        appendLine("| Metric | Value |")
        appendLine("|---|---|")
        appendLine("| Gold declarations | ${s.goldDeclarations} |")
        appendLine("| Converted declarations | ${s.convertedDeclarations} |")
        appendLine("| Matched (paired) | ${s.matched} |")
        appendLine("| Gold-only (orphans) | ${s.goldOnly} |")
        appendLine("| Converted-only (orphans) | ${s.convertedOnly} |")
        appendLine("| Mean PSI similarity (paired) | ${pct(s.meanSimilarity)} |")
        appendLine("| Pairing rate (matched ÷ gold) | ${if (s.goldDeclarations == 0) "n/a" else pct(s.matched.toDouble() / s.goldDeclarations)} |")
    }

    private fun StringBuilder.appendFileTable(files: List<FileScore>) {
        appendLine("## Per-file rollup")
        appendLine()
        if (files.isEmpty()) {
            appendLine("_No files analyzed._")
            return
        }
        appendLine("| File | Matched | Gold-only | Converted-only | Mean similarity |")
        appendLine("|---|---:|---:|---:|---:|")
        for (f in files) {
            appendLine("| `${f.sourceFile}` | ${f.matchedCount} | ${f.goldOnlyCount} | ${f.convertedOnlyCount} | ${pct(f.meanSimilarity)} |")
        }
    }

    private fun StringBuilder.appendDeclarationDrilldown(pairs: List<PairScore>) {
        appendLine("## Per-declaration drill-down")
        appendLine()
        if (pairs.isEmpty()) {
            appendLine("_No declarations paired._")
            return
        }
        appendLine("Sorted ascending by similarity (worst pairs first).")
        appendLine()
        appendLine("| FQN | Kind | Similarity | Idiom delta |")
        appendLine("|---|---|---:|---|")
        for (ps in pairs.sortedBy { it.similarity }) {
            val delta = idiomDelta(ps.goldIdioms, ps.convertedIdioms)
            appendLine("| `${ps.pair.gold.fqn}` | ${ps.pair.gold.kind.name.lowercase()} | ${pct(ps.similarity)} | ${delta.ifBlank { "—" }} |")
        }
    }

    private fun StringBuilder.appendFindings(summary: RunSummary) {
        appendLine("## Findings")
        appendLine()
        if (summary.pairScores.isEmpty()) {
            appendLine("_No paired declarations to analyze._")
            return
        }

        // Aggregate idiom totals across all paired declarations.
        val goldTotals = summary.pairScores.fold(IdiomCounts.EMPTY) { a, b -> a + b.goldIdioms }
        val convTotals = summary.pairScores.fold(IdiomCounts.EMPTY) { a, b -> a + b.convertedIdioms }
        val rows = goldTotals.toMap().entries.zip(convTotals.toMap().entries) { g, c -> Triple(g.key, g.value, c.value) }

        appendLine("### Aggregate idiom usage (paired declarations only)")
        appendLine()
        appendLine("| Idiom | Gold | Converted | Δ (converted − gold) |")
        appendLine("|---|---:|---:|---:|")
        for ((label, gv, cv) in rows) {
            appendLine("| $label | $gv | $cv | ${signed(cv - gv)} |")
        }
        appendLine()

        // Top-N largest similarity gaps (the "what diverged most" narrative).
        appendLine("### Lowest-similarity pairs (top 5)")
        appendLine()
        val worst = summary.pairScores.sortedBy { it.similarity }.take(5)
        if (worst.isEmpty()) {
            appendLine("_None._")
        } else {
            appendLine("| FQN | Similarity | Source |")
            appendLine("|---|---:|---|")
            for (ps in worst) {
                appendLine("| `${ps.pair.gold.fqn}` | ${pct(ps.similarity)} | `${ps.pair.gold.sourceFile}` |")
            }
        }
    }

    // -- helpers ----------------------------------------------------------

    private fun pct(v: Double): String = String.format("%.1f%%", v * 100.0)
    private fun signed(n: Int): String = if (n >= 0) "+$n" else n.toString()

    /** Compact one-liner of the largest absolute deltas in idiom counts for a pair. */
    private fun idiomDelta(gold: IdiomCounts, conv: IdiomCounts): String {
        val gMap = gold.toMap()
        val cMap = conv.toMap()
        return gMap.keys
            .map { key -> key to ((cMap[key] ?: 0) - (gMap[key] ?: 0)) }
            .filter { it.second != 0 }
            .sortedByDescending { abs(it.second) }
            .take(3)
            .joinToString(", ") { (k, d) -> "$k: ${signed(d)}" }
    }

    private fun jsonStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun jsonNum(d: Double): String =
        if (d.isFinite()) String.format("%.6f", d) else "null"

    private fun jsonIdioms(c: IdiomCounts): String {
        val map = c.toMap()
        return map.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "${jsonStr(k)}: $v"
        }
    }
}
