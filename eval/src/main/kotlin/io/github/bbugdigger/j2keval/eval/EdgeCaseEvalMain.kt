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
 *             [--apply-fixups=true|false]
 *
 * Reads the dataset, looks up each case's converted `.kt` under `--converted`, runs the
 * verdict logic, and writes `report.md` + `report.json` under `--out`.
 *
 * The conversion itself is upstream — the j2k-runner produces `--converted/<package>/source.kt`
 * for each case before this main runs. When `--apply-fixups=true` (default), the
 * Phase-12 [OverrideFixup] pass mutates the converted files in-place to add missing
 * `override` modifiers before the verdict logic runs. The pre-fixup files are copied to
 * `<out>/converted-pre-fixup/` for inspection.
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

private data class EdgeCaseArgs(
    val dataset: Path,
    val converted: Path,
    val out: Path,
    val applyFixups: Boolean,
)

private fun parseEdgeCaseArgs(args: Array<String>): EdgeCaseArgs {
    var dataset: String? = null
    var converted: String? = null
    var out: String? = null
    var applyFixups = true
    for (arg in args) {
        when {
            arg.startsWith("--dataset=") -> dataset = arg.substringAfter("=")
            arg.startsWith("--converted=") -> converted = arg.substringAfter("=")
            arg.startsWith("--out=") -> out = arg.substringAfter("=")
            arg.startsWith("--apply-fixups=") -> applyFixups = arg.substringAfter("=").toBoolean()
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
        applyFixups = applyFixups,
    )
}

private fun runEdgeCases(args: EdgeCaseArgs) {
    require(args.dataset.isDirectory()) { "--dataset is not a directory: ${args.dataset}" }
    require(args.converted.isDirectory()) { "--converted is not a directory: ${args.converted}" }
    args.out.createDirectories()

    println("[edge-cases] dataset=${args.dataset}")
    println("[edge-cases] converted=${args.converted}")
    println("[edge-cases] out=${args.out}")
    println("[edge-cases] apply-fixups=${args.applyFixups}")

    val fixupResult: DirectoryFixupResult? = if (args.applyFixups) {
        // Snapshot pre-fixup output so the original is preserved for inspection / diffing.
        val backupDir = args.out.resolve("converted-pre-fixup")
        if (Files.exists(backupDir)) backupDir.toFile().deleteRecursively()
        copyTree(args.converted, backupDir)
        println("[edge-cases] pre-fixup backup at $backupDir")

        println("[edge-cases] running OverrideFixup pass…")
        val r = OverrideFixup().applyToDirectory(args.converted)
        println(
            "[edge-cases] fixups: applied=${r.totalFixupsApplied} across files=${r.filesWithFixups}; " +
                "files now compiling cleanly after fixup=${r.filesNowCompiling}; " +
                "errors resolved=${r.totalErrorsResolved}"
        )
        r
    } else {
        println("[edge-cases] fixups disabled (--apply-fixups=false)")
        null
    }

    PsiLoader().use { loader ->
        val cases = EdgeCases.discoverFrom(args.dataset)
        println("[edge-cases] discovered ${cases.size} case(s)")

        val runner = EdgeCaseRunner(loader = loader)
        val summary = runner.run(cases, convertedRoot = args.converted)

        println("[edge-cases] verdicts: pass=${summary.passCount} partial=${summary.partialCount} fail=${summary.failCount}")
        for (h in summary.byHypothesis) {
            println("[edge-cases]   ${h.hypothesisId}: ${h.status()} (✅${h.passCount} 🟡${h.partialCount} ❌${h.failCount})")
        }

        val md = EdgeCaseReportRenderer.renderMarkdown(summary, fixupResult)
        val json = EdgeCaseReportRenderer.renderJson(summary, fixupResult)
        val mdPath = args.out.resolve("report.md")
        val jsonPath = args.out.resolve("report.json")
        mdPath.writeText(md)
        jsonPath.writeText(json)
        println("[edge-cases] wrote $mdPath (${Files.size(mdPath)} bytes)")
        println("[edge-cases] wrote $jsonPath (${Files.size(jsonPath)} bytes)")
    }
}

/**
 * Copy the directory tree at [src] to [dst]. Preserves directory structure; no Linux-only
 * permission tricks. Used to snapshot the pre-fixup converted output so the user can diff
 * `<out>/converted-pre-fixup/` against `--converted/` to see exactly what the fixup changed.
 */
private fun copyTree(src: Path, dst: Path) {
    Files.walk(src).use { stream ->
        stream.forEach { source ->
            val rel = src.relativize(source)
            val target = dst.resolve(rel.toString())
            if (Files.isDirectory(source)) {
                if (!Files.exists(target)) Files.createDirectories(target)
            } else {
                target.parent?.let { if (!Files.exists(it)) Files.createDirectories(it) }
                Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
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
