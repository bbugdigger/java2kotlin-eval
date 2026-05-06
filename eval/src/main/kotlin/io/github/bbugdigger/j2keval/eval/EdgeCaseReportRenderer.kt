package io.github.bbugdigger.j2keval.eval

object EdgeCaseReportRenderer {

    fun renderMarkdown(summary: EdgeCaseRunSummary, fixup: DirectoryFixupResult? = null): String = buildString {
        appendLine("# J2K edge case eval")
        appendLine()
        appendLine("Hand-curated stress tests under `edge-cases/`. Each case has a hypothesis-driven prediction; the verdict here either confirms or refutes it.")
        appendLine()
        appendBanner(summary, fixup)
        appendLine()
        appendByHypothesis(summary)
        appendLine()
        appendPerCategory(summary)
        appendLine()
        appendPerCase(summary)
        appendLine()
        appendFailures(summary)
    }

    fun renderJson(summary: EdgeCaseRunSummary, fixup: DirectoryFixupResult? = null): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"totalCases\": ${summary.totalCases},\n")
        sb.append("  \"pass\": ${summary.passCount},\n")
        sb.append("  \"partial\": ${summary.partialCount},\n")
        sb.append("  \"fail\": ${summary.failCount},\n")
        if (fixup != null) {
            sb.append("  \"fixups\": {")
            sb.append("\"applied\": ${fixup.totalFixupsApplied}, ")
            sb.append("\"filesWithFixups\": ${fixup.filesWithFixups}, ")
            sb.append("\"filesNowCompiling\": ${fixup.filesNowCompiling}, ")
            sb.append("\"errorsResolved\": ${fixup.totalErrorsResolved}")
            sb.append("},\n")
        }
        sb.append("  \"hypotheses\": [\n")
        summary.byHypothesis.forEachIndexed { i, h ->
            sb.append("    {")
            sb.append("\"id\": ").append(jsonStr(h.hypothesisId)).append(", ")
            sb.append("\"status\": ").append(jsonStr(h.status())).append(", ")
            sb.append("\"pass\": ${h.passCount}, \"partial\": ${h.partialCount}, \"fail\": ${h.failCount}")
            sb.append("}")
            if (i < summary.byHypothesis.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ],\n")
        sb.append("  \"cases\": [\n")
        summary.results.forEachIndexed { i, r ->
            sb.append("    {")
            sb.append("\"id\": ").append(jsonStr(r.case.id)).append(", ")
            sb.append("\"verdict\": ").append(jsonStr(r.verdict.name)).append(", ")
            sb.append("\"hypotheses\": [")
            sb.append(r.case.hypothesisIds.joinToString(", ") { jsonStr(it) })
            sb.append("], ")
            sb.append("\"matched\": ${r.matchedDeclarations}, ")
            sb.append("\"goldOnly\": ${r.goldOnlyDeclarations}, ")
            sb.append("\"convertedOnly\": ${r.convertedOnlyDeclarations}, ")
            sb.append("\"compiles\": ${r.compiles?.toString() ?: "null"}, ")
            sb.append("\"failReason\": ").append(jsonStr(r.failReason.name))
            sb.append("}")
            if (i < summary.results.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    // -- Markdown sections ----

    private fun StringBuilder.appendBanner(s: EdgeCaseRunSummary, fixup: DirectoryFixupResult?) {
        appendLine("## Aggregate")
        appendLine()
        appendLine("| Metric | Value |")
        appendLine("|---|---:|")
        appendLine("| Total cases | ${s.totalCases} |")
        appendLine("| ✅ Pass | ${s.passCount} |")
        appendLine("| 🟡 Partial | ${s.partialCount} |")
        appendLine("| ❌ Fail | ${s.failCount} |")
        if (fixup != null) {
            appendLine("| Phase-12 fixups applied (`override` modifier) | ${fixup.totalFixupsApplied} (across ${fixup.filesWithFixups} file${if (fixup.filesWithFixups == 1) "" else "s"}) |")
            appendLine("| Files now compiling cleanly after fixup | ${fixup.filesNowCompiling} |")
            appendLine("| Compile errors resolved by fixup | ${fixup.totalErrorsResolved} |")
        } else {
            appendLine("| Phase-12 fixups | _disabled (`--apply-fixups=false`)_ |")
        }
    }

    private fun StringBuilder.appendByHypothesis(s: EdgeCaseRunSummary) {
        appendLine("## By hypothesis")
        appendLine()
        if (s.byHypothesis.isEmpty()) {
            appendLine("_No hypothesis-tagged cases._")
            return
        }
        appendLine("| Hypothesis | Status | ✅ | 🟡 | ❌ |")
        appendLine("|---|---|---:|---:|---:|")
        for (h in s.byHypothesis) {
            appendLine("| `${h.hypothesisId}` | ${h.status()} | ${h.passCount} | ${h.partialCount} | ${h.failCount} |")
        }
    }

    private fun StringBuilder.appendPerCategory(s: EdgeCaseRunSummary) {
        appendLine("## By category")
        appendLine()
        if (s.results.isEmpty()) return
        val byCat = s.results.groupBy { it.case.category }
        appendLine("| Category | Total | ✅ | 🟡 | ❌ |")
        appendLine("|---|---:|---:|---:|---:|")
        for ((cat, list) in byCat.toSortedMap()) {
            val pass = list.count { it.verdict == EdgeCaseVerdict.Pass }
            val partial = list.count { it.verdict == EdgeCaseVerdict.Partial }
            val fail = list.count { it.verdict == EdgeCaseVerdict.Fail }
            appendLine("| `${cat}` | ${list.size} | $pass | $partial | $fail |")
        }
    }

    private fun StringBuilder.appendPerCase(s: EdgeCaseRunSummary) {
        appendLine("## Per-case verdicts")
        appendLine()
        appendLine("| Case | Hypotheses | Verdict | Notes |")
        appendLine("|---|---|---|---|")
        for (r in s.results) {
            val hyps = r.case.hypothesisIds.joinToString(", ") { "`$it`" }.ifEmpty { "—" }
            val verdictBadge = when (r.verdict) {
                EdgeCaseVerdict.Pass -> "✅ Pass"
                EdgeCaseVerdict.Partial -> "🟡 Partial"
                EdgeCaseVerdict.Fail -> "❌ Fail"
            }
            val notes = perCaseNotes(r)
            appendLine("| `${r.case.id}` | $hyps | $verdictBadge | $notes |")
        }
    }

    private fun StringBuilder.appendFailures(s: EdgeCaseRunSummary) {
        val failed = s.results.filter { it.verdict == EdgeCaseVerdict.Fail }
        if (failed.isEmpty()) return
        appendLine("## Failure details")
        appendLine()
        for (r in failed) {
            appendLine("### `${r.case.id}` — ${r.failReason.name}")
            appendLine()
            when (r.failReason) {
                FailReason.MissingConverted -> appendLine("J2K did not produce a `.kt` for this case (likely a runner / staging error).")
                FailReason.DoesNotCompile -> {
                    appendLine("Converted file produced but `kotlinc` rejected it. Errors:")
                    appendLine()
                    appendLine("```")
                    r.compileErrors.take(20).forEach { appendLine(it) }
                    if (r.compileErrors.size > 20) appendLine("... (${r.compileErrors.size - 20} more)")
                    appendLine("```")
                }
                FailReason.NoMatchingDeclarations -> {
                    appendLine("Converted file compiles but contains no declarations matching gold by FQN. Check that the converter preserved the package + class names.")
                }
                FailReason.None -> {} // shouldn't happen for failed cases
            }
            appendLine()
        }
    }

    private fun perCaseNotes(r: EdgeCaseResult): String {
        if (r.verdict == EdgeCaseVerdict.Pass) return "matches expected"
        if (r.verdict == EdgeCaseVerdict.Fail) return r.failReason.name
        // Partial — surface up to 3 biggest idiom deltas.
        val nonZero = r.idiomDelta
            .filter { it.value != 0 }
            .entries
            .sortedByDescending { kotlin.math.abs(it.value) }
            .take(3)
            .joinToString(", ") { (k, v) -> "$k: ${if (v >= 0) "+$v" else "$v"}" }
        return if (nonZero.isEmpty()) "structurally identical, no idiom delta" else "Δ idioms — $nonZero"
    }

    private fun jsonStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
