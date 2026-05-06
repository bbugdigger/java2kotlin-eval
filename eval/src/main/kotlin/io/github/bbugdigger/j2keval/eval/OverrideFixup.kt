package io.github.bbugdigger.j2keval.eval

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory

/**
 * Pipeline-side fixup for the largest concrete bug class identified by the edge-case
 * eval (Phase 10): J2K does not emit Kotlin's `override` modifier on functions that
 * override hidden supertype members.
 *
 * **Why this happens upstream** (see `docs/proposed-j2k-fix.md` for the full writeup):
 * J2K's `JavaToJKTreeBuilder` uses `findSuperMethods()` to detect overrides, which
 * relies on resolved Java PSI super-method links. In our headless invocation context
 * this resolution is incomplete (Maven import doesn't fully complete before J2K
 * runs), so override flags don't get set on the JK tree. Meanwhile the Java
 * `@Override` annotation passes through unchanged (`JavaAnnotationsConversion`
 * doesn't process it), surfacing in the converted output as a literal `@Override`
 * annotation that has no semantic meaning to Kotlin.
 *
 * **The fix** — text-level, driven by `kotlinc` errors:
 *  1. Compile the converted file via [CompilabilityChecker].
 *  2. Filter errors matching `"... hides member of supertype ... and needs 'override' modifier"` —
 *     `kotlinc` already pinpoints the exact line (it has the resolution info that J2K's
 *     headless invocation lacked).
 *  3. For each such error: read the file, prepend `override ` to the function declaration
 *     at that line, and remove the now-redundant `@Override` annotation immediately above
 *     it (if present).
 *  4. Re-compile to verify the error count actually went down.
 *
 * Text fixup over PSI rewrite because (a) `kotlinc` already gave us line numbers, no PSI
 * walk needed; (b) deterministic and easy to verify (recompile, check diagnostic gone);
 * (c) ~80 LOC instead of a few hundred.
 */
class OverrideFixup(private val checker: CompilabilityChecker = CompilabilityChecker()) {

    /**
     * Apply the fixup to a single file. Returns a [FixupResult] capturing what was changed
     * and whether the recompile succeeded. The file is mutated in place; the caller is
     * responsible for backing up the original if it needs the unfixed version.
     */
    fun applyTo(file: Path): FixupResult {
        require(Files.isRegularFile(file)) { "Not a regular file: $file" }
        val initial = checker.check(file)
        if (initial.compiled) return FixupResult.noFixupsNeeded(file)

        val fixableLines = initial.errors
            .filter { it.contains("needs 'override' modifier") }
            .mapNotNull(::extractErrorLine)
            .distinct()
            .sortedDescending()  // descending so removing @Override above one fix doesn't shift later indices

        if (fixableLines.isEmpty()) {
            return FixupResult(
                file = file,
                fixedLines = emptyList(),
                errorsBefore = initial.errors.size,
                errorsAfter = initial.errors.size,
                compilesAfter = false,
            )
        }

        val lines = Files.readAllLines(file).toMutableList()
        val applied = mutableListOf<Int>()

        for (line1Based in fixableLines) {
            val idx = line1Based - 1
            if (idx !in lines.indices) continue
            val originalLine = lines[idx]
            val funIdx = originalLine.indexOf("fun ")
            if (funIdx < 0) continue  // shouldn't happen — kotlinc said this line had a fun

            // Skip if `override ` is already there (e.g., a previous fixup fired in this run).
            if (originalLine.substring(0, funIdx).trimEnd().endsWith("override")) continue

            // Insert `override ` immediately before `fun`. This places it after any
            // visibility modifiers (private/protected/public/internal), which matches
            // Kotlin's modifier ordering convention.
            lines[idx] = originalLine.substring(0, funIdx) + "override " + originalLine.substring(funIdx)
            applied += line1Based

            // Drop the now-redundant `@Override` annotation if it sits immediately above.
            // Kotlin tolerates `@Override` (resolves to java.lang.Override on JVM target)
            // but it does nothing — leaving it in is just noise.
            if (idx > 0 && lines[idx - 1].trim() == "@Override") {
                lines.removeAt(idx - 1)
            }
        }

        Files.write(file, lines)

        val after = checker.check(file)
        return FixupResult(
            file = file,
            fixedLines = applied.sorted(),
            errorsBefore = initial.errors.size,
            errorsAfter = after.errors.size,
            compilesAfter = after.compiled,
        )
    }

    /**
     * Walk [root] for `.kt` files and apply the fixup to each. Returns aggregate stats.
     * Files that already compile are skipped.
     */
    fun applyToDirectory(root: Path): DirectoryFixupResult {
        require(root.isDirectory()) { "Not a directory: $root" }
        val results = Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .map { applyTo(it) }
                .toList()
        }
        return DirectoryFixupResult(perFile = results)
    }

    /** Extract the line number from a `kotlinc` error string of the form `<path>:<line>:<col>: <msg>`. */
    private fun extractErrorLine(error: String): Int? {
        // Path may contain colons (Windows drive letter). The location pattern `:digits:digits:`
        // is unique within the error string — pick the last match to be safe.
        return errorLocRegex.findAll(error).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
    }

    private companion object {
        // Matches `:<line>:<col>` immediately followed by `:` (the message separator).
        // Doesn't match Windows paths because they don't contain `:NN:NN:`.
        val errorLocRegex = Regex(""":(\d+):\d+(?=:)""")
    }
}

/**
 * Result of applying [OverrideFixup] to a single file.
 *
 * @property file The file that was processed (mutated in place if [fixedLines] is non-empty).
 * @property fixedLines 1-based line numbers where `override ` was inserted.
 * @property errorsBefore Total compile error count before fixups.
 * @property errorsAfter Total compile error count after fixups.
 * @property compilesAfter Whether the file compiles cleanly after fixups (i.e., zero errors).
 */
data class FixupResult(
    val file: Path,
    val fixedLines: List<Int>,
    val errorsBefore: Int,
    val errorsAfter: Int,
    val compilesAfter: Boolean,
) {
    val appliedCount: Int get() = fixedLines.size
    val errorsResolved: Int get() = errorsBefore - errorsAfter

    companion object {
        fun noFixupsNeeded(file: Path) = FixupResult(file, emptyList(), 0, 0, true)
    }
}

/** Aggregate result for a directory walk. */
data class DirectoryFixupResult(val perFile: List<FixupResult>) {
    val totalFiles: Int get() = perFile.size
    val filesWithFixups: Int get() = perFile.count { it.appliedCount > 0 }
    val totalFixupsApplied: Int get() = perFile.sumOf { it.appliedCount }
    val filesNowCompiling: Int get() = perFile.count { it.appliedCount > 0 && it.compilesAfter }
    val totalErrorsResolved: Int get() = perFile.sumOf { it.errorsResolved }
}
