package io.github.bbugdigger.j2keval.eval

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * CLI for the edge-case evaluation pass.
 *
 * Usage:
 *   edgeCases --dataset=<edge-cases-dir> --converted=<converted-out-dir> --out=<report-dir>
 *
 * Reads the dataset, looks up each case's converted `.kt` under `--converted`, runs the
 * verdict logic, and writes `report.md` + `report.json` under `--out`.
 *
 * The conversion itself is upstream — the j2k-runner produces `--converted/<package>/source.kt`
 * for each case before this main runs.
 */
fun main(args: Array<String>) {
    try {
        val parsed = parseEdgeCaseArgs(args)
        runEdgeCases(parsed)
    } catch (t: Throwable) {
        System.err.println("edge-cases eval failed: ${t.message}")
        t.printStackTrace(System.err)
        exitProcess(1)
    }
}

private data class EdgeCaseArgs(val dataset: Path, val converted: Path, val out: Path)

private fun parseEdgeCaseArgs(args: Array<String>): EdgeCaseArgs {
    var dataset: String? = null
    var converted: String? = null
    var out: String? = null
    for (arg in args) {
        when {
            arg.startsWith("--dataset=") -> dataset = arg.substringAfter("=")
            arg.startsWith("--converted=") -> converted = arg.substringAfter("=")
            arg.startsWith("--out=") -> out = arg.substringAfter("=")
            else -> error("Unknown argument: $arg")
        }
    }
    val d = requireNotNull(dataset) { "--dataset is required" }
    val c = requireNotNull(converted) { "--converted is required" }
    val o = requireNotNull(out) { "--out is required" }
    return EdgeCaseArgs(
        dataset = Paths.get(d).toAbsolutePath().normalize(),
        converted = Paths.get(c).toAbsolutePath().normalize(),
        out = Paths.get(o).toAbsolutePath().normalize(),
    )
}

private fun runEdgeCases(args: EdgeCaseArgs) {
    require(args.dataset.isDirectory()) { "--dataset is not a directory: ${args.dataset}" }
    require(args.converted.isDirectory()) { "--converted is not a directory: ${args.converted}" }
    args.out.createDirectories()

    println("[edge-cases] dataset=${args.dataset}")
    println("[edge-cases] converted=${args.converted}")
    println("[edge-cases] out=${args.out}")

    PsiLoader().use { loader ->
        val cases = EdgeCases.discoverFrom(args.dataset)
        println("[edge-cases] discovered ${cases.size} case(s)")

        val runner = EdgeCaseRunner(loader = loader)
        val summary = runner.run(cases, convertedRoot = args.converted)

        println("[edge-cases] verdicts: pass=${summary.passCount} partial=${summary.partialCount} fail=${summary.failCount}")
        for (h in summary.byHypothesis) {
            println("[edge-cases]   ${h.hypothesisId}: ${h.status()} (✅${h.passCount} 🟡${h.partialCount} ❌${h.failCount})")
        }

        val md = EdgeCaseReportRenderer.renderMarkdown(summary)
        val json = EdgeCaseReportRenderer.renderJson(summary)
        val mdPath = args.out.resolve("report.md")
        val jsonPath = args.out.resolve("report.json")
        mdPath.writeText(md)
        jsonPath.writeText(json)
        println("[edge-cases] wrote $mdPath (${Files.size(mdPath)} bytes)")
        println("[edge-cases] wrote $jsonPath (${Files.size(jsonPath)} bytes)")
    }
}

// Same Disposable-friendly use() helper we wrote in EvalMain.
private inline fun <R> PsiLoader.use(block: (PsiLoader) -> R): R {
    try {
        return block(this)
    } finally {
        dispose()
    }
}
