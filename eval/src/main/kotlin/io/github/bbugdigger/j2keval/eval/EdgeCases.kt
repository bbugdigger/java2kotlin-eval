package io.github.bbugdigger.j2keval.eval

import java.nio.file.Files
import java.nio.file.Path

/**
 * One curated edge case under `edge-cases/<category>/<case>/`.
 *
 * Discovered by walking the dataset root for directories containing the canonical
 * triple `source.java` + `expected.kt` + `notes.md`. The hypothesis IDs the case
 * tests are extracted from `notes.md` by regex (any `H<digits>` token).
 */
data class EdgeCase(
    /** Category folder name, e.g. `expression-body`, `nullability`. */
    val category: String,
    /** Case folder name, e.g. `simple-return-fn`. */
    val name: String,
    /** Path to the case directory. */
    val dir: Path,
    /** Path to the Java source. */
    val sourceJava: Path,
    /** Path to the human-written expected Kotlin. */
    val expectedKt: Path,
    /** Path to notes.md (kept for the report linking). */
    val notes: Path,
    /** Hypothesis IDs this case tests, parsed from notes.md (e.g., `["H1", "H4"]`). */
    val hypothesisIds: List<String>,
    /** Java package declared by source.java; used to compute staging paths. */
    val javaPackage: String,
) {
    /** A stable identifier suitable for filenames / report keys. */
    val id: String get() = "$category/$name"

    /**
     * The package-derived path under a staged source root. `edge.expression_body.simple_return_fn`
     * → `edge/expression_body/simple_return_fn/source.java`.
     */
    fun stagedRelativeJavaPath(): Path =
        Path.of(javaPackage.replace('.', '/'), "source.java")

    /** Same shape but `.kt` (post-conversion). */
    fun stagedRelativeKtPath(): Path =
        Path.of(javaPackage.replace('.', '/'), "source.kt")
}

object EdgeCases {

    private val PACKAGE_REGEX = Regex("""^\s*package\s+([\w.]+)\s*;""", RegexOption.MULTILINE)
    private val HYPOTHESIS_REGEX = Regex("""\bH\d+\b""")

    /**
     * Walk [datasetRoot] (typically `edge-cases/`) for case directories. A case is any
     * directory containing all three of `source.java`, `expected.kt`, `notes.md`.
     *
     * The walker skips the root itself and its `README.md`. Cases are returned sorted
     * by id (`category/name`) for stable report ordering.
     */
    fun discoverFrom(datasetRoot: Path): List<EdgeCase> {
        require(Files.isDirectory(datasetRoot)) { "Not a directory: $datasetRoot" }
        val out = mutableListOf<EdgeCase>()
        Files.walk(datasetRoot, 3).use { stream ->
            stream
                .filter { Files.isDirectory(it) && it != datasetRoot }
                .forEach { caseDir ->
                    val src = caseDir.resolve("source.java")
                    val exp = caseDir.resolve("expected.kt")
                    val nts = caseDir.resolve("notes.md")
                    if (Files.isRegularFile(src) && Files.isRegularFile(exp) && Files.isRegularFile(nts)) {
                        val notesText = Files.readString(nts)
                        val srcText = Files.readString(src)
                        out += EdgeCase(
                            category = caseDir.parent.fileName.toString(),
                            name = caseDir.fileName.toString(),
                            dir = caseDir,
                            sourceJava = src,
                            expectedKt = exp,
                            notes = nts,
                            hypothesisIds = extractHypothesisIds(notesText),
                            javaPackage = extractPackage(srcText)
                                ?: error("source.java in $caseDir is missing a package declaration"),
                        )
                    }
                }
        }
        return out.sortedBy { it.id }
    }

    internal fun extractPackage(javaSource: String): String? =
        PACKAGE_REGEX.find(javaSource)?.groupValues?.get(1)

    internal fun extractHypothesisIds(notesText: String): List<String> =
        HYPOTHESIS_REGEX.findAll(notesText).map { it.value }.distinct().toList()
}
