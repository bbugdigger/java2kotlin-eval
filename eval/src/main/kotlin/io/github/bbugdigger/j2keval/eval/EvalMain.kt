package io.github.bbugdigger.j2keval.eval

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * CLI entry point.
 *
 * Usage:
 *   eval --target=<name> --converted=<dir> --gold=<dir> --out=<dir>
 *
 * Loads `.kt` trees from `--converted` (J2K output) and `--gold` (the Kotlin port
 * to compare against), pairs declarations, scores them, and writes:
 *   - `<out>/report.md`   for humans / `$GITHUB_STEP_SUMMARY`
 *   - `<out>/report.json` for machine consumption
 */
fun main(args: Array<String>) {
    try {
        val parsed = parseArgs(args)
        runEval(parsed)
    } catch (t: Throwable) {
        System.err.println("eval failed: ${t.message}")
        t.printStackTrace(System.err)
        exitProcess(1)
    }
}

private data class Args(val target: String, val converted: Path, val gold: Path, val out: Path)

private fun parseArgs(args: Array<String>): Args {
    var target: String? = null
    var converted: String? = null
    var gold: String? = null
    var out: String? = null
    for (arg in args) {
        when {
            arg.startsWith("--target=") -> target = arg.substringAfter("=")
            arg.startsWith("--converted=") -> converted = arg.substringAfter("=")
            arg.startsWith("--gold=") -> gold = arg.substringAfter("=")
            arg.startsWith("--out=") -> out = arg.substringAfter("=")
            else -> error("Unknown argument: $arg")
        }
    }
    val t = requireNotNull(target) { "--target is required" }
    val c = requireNotNull(converted) { "--converted is required" }
    val g = requireNotNull(gold) { "--gold is required" }
    val o = requireNotNull(out) { "--out is required" }
    return Args(
        target = t,
        converted = Paths.get(c).toAbsolutePath().normalize(),
        gold = Paths.get(g).toAbsolutePath().normalize(),
        out = Paths.get(o).toAbsolutePath().normalize(),
    )
}

private fun runEval(args: Args) {
    require(args.converted.isDirectory()) { "--converted is not a directory: ${args.converted}" }
    require(args.gold.isDirectory()) { "--gold is not a directory: ${args.gold}" }
    args.out.createDirectories()

    println("[eval] target=${args.target}")
    println("[eval] converted=${args.converted}")
    println("[eval] gold=${args.gold}")
    println("[eval] out=${args.out}")

    PsiLoader().use { loader ->
        val convertedFiles = loader.loadDirectory(args.converted)
        val goldFiles = loader.loadDirectory(args.gold)
        println("[eval] loaded converted=${convertedFiles.size} gold=${goldFiles.size} .kt file(s)")

        val convertedDecls = convertedFiles.flatMap { it.ktFile.collectDeclarations(it.relativePath) }
        val goldDecls = goldFiles.flatMap { it.ktFile.collectDeclarations(it.relativePath) }
        println("[eval] declarations: converted=${convertedDecls.size} gold=${goldDecls.size}")

        val summary = Scoring.run(gold = goldDecls, converted = convertedDecls)
        println("[eval] matched=${summary.matched} gold-only=${summary.goldOnly} converted-only=${summary.convertedOnly}")
        println("[eval] mean similarity=${"%.3f".format(summary.meanSimilarity)}")

        val md = ReportRenderer.renderMarkdown(args.target, summary)
        val json = ReportRenderer.renderJson(args.target, summary)

        val mdPath = args.out.resolve("report.md")
        val jsonPath = args.out.resolve("report.json")
        mdPath.writeText(md)
        jsonPath.writeText(json)
        println("[eval] wrote $mdPath (${Files.size(mdPath)} bytes)")
        println("[eval] wrote $jsonPath (${Files.size(jsonPath)} bytes)")
    }
}

// PsiLoader is Disposable; provide an inline use() helper since it doesn't implement
// java.lang.AutoCloseable in the kotlin-compiler-embeddable's relocated namespace.
private inline fun <R> PsiLoader.use(block: (PsiLoader) -> R): R {
    try {
        return block(this)
    } finally {
        dispose()
    }
}
