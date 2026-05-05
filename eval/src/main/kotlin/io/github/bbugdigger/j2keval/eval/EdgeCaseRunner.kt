package io.github.bbugdigger.j2keval.eval

import java.nio.file.Files
import java.nio.file.Path

/**
 * Per-case verdict from comparing the converted Kotlin against the human-written
 * `expected.kt`.
 *
 * Verdict rules (evaluated top-down, first match wins):
 *  - **MissingConverted** — no `.kt` file produced for this case (J2K skipped it,
 *    or the staging path didn't match). Counts as Fail.
 *  - **DoesNotCompile** — converted file produced but `kotlinc` rejects it. Fail.
 *  - **NoMatchingDeclarations** — every expected declaration is on the gold-only side
 *    of the pairing (i.e., J2K didn't produce anything matching by FQN). Fail.
 *  - **Pass** — every expected declaration is paired AND every paired declaration's
 *    idiom counts match exactly.
 *  - **Partial** — every expected declaration is paired BUT some idiom counts differ
 *    (the structural conversion succeeded; idiomatic refinement was missed — typical
 *    "hypothesis confirmed" outcome).
 */
enum class EdgeCaseVerdict { Pass, Partial, Fail }

/** What kind of failure produced a Fail verdict. */
enum class FailReason { None, MissingConverted, DoesNotCompile, NoMatchingDeclarations }

/** Result for a single edge case. */
data class EdgeCaseResult(
    val case: EdgeCase,
    val verdict: EdgeCaseVerdict,
    val failReason: FailReason,
    val convertedExists: Boolean,
    val compiles: Boolean?,             // null if not checked (e.g., missing converted)
    val expectedDeclarations: Int,
    val matchedDeclarations: Int,
    val goldOnlyDeclarations: Int,
    val convertedOnlyDeclarations: Int,
    /** Idiom delta accumulated across all paired declarations (converted minus expected). */
    val idiomDelta: Map<String, Int>,
    /** Compile errors if the file failed to compile, otherwise empty. */
    val compileErrors: List<String> = emptyList(),
)

/** Per-hypothesis aggregate: roll up all cases that test the same hypothesis. */
data class HypothesisRollup(
    val hypothesisId: String,
    val passCount: Int,
    val partialCount: Int,
    val failCount: Int,
) {
    val total: Int get() = passCount + partialCount + failCount

    /**
     * Map this rollup to a Status field for `docs/hypotheses.md`.
     *
     * - **Confirmed**: every case is Partial or Fail (J2K consistently missed the predicted
     *   idiom — exactly what the hypothesis predicted).
     * - **Refuted**: every case is Pass (J2K did the right thing across the board — the
     *   hypothesis's prediction of failure was wrong).
     * - **Partial**: mixed — some cases pass, some don't.
     */
    fun status(): String = when {
        total == 0 -> "Untested"
        passCount == total -> "Refuted"
        passCount == 0 -> "Confirmed"
        else -> "Partial"
    }
}

/** Top-level run summary. */
data class EdgeCaseRunSummary(
    val results: List<EdgeCaseResult>,
    val byHypothesis: List<HypothesisRollup>,
) {
    val totalCases: Int get() = results.size
    val passCount: Int get() = results.count { it.verdict == EdgeCaseVerdict.Pass }
    val partialCount: Int get() = results.count { it.verdict == EdgeCaseVerdict.Partial }
    val failCount: Int get() = results.count { it.verdict == EdgeCaseVerdict.Fail }
}

/**
 * Drives the comparison loop. Reuses [PsiLoader], [collectDeclarations],
 * [DeclarationPairer], and [IdiomCounter] from the real-world eval engine —
 * only the verdict-deciding logic is new.
 */
class EdgeCaseRunner(
    private val loader: PsiLoader,
    private val compilabilityChecker: CompilabilityChecker = CompilabilityChecker(),
) {

    /**
     * Run every [cases] entry. [convertedRoot] is the directory under which the j2k-runner
     * placed the converted `.kt` files; we look up each case's converted file via its
     * [EdgeCase.stagedRelativeKtPath].
     */
    fun run(cases: List<EdgeCase>, convertedRoot: Path): EdgeCaseRunSummary {
        val results = cases.map { evaluate(it, convertedRoot) }

        // Aggregate per-hypothesis. A case can test multiple hypotheses; it contributes
        // to each.
        val byHypothesis = results
            .flatMap { res -> res.case.hypothesisIds.map { hyp -> hyp to res } }
            .groupBy({ it.first }, { it.second })
            .map { (hyp, casesForHyp) ->
                HypothesisRollup(
                    hypothesisId = hyp,
                    passCount = casesForHyp.count { it.verdict == EdgeCaseVerdict.Pass },
                    partialCount = casesForHyp.count { it.verdict == EdgeCaseVerdict.Partial },
                    failCount = casesForHyp.count { it.verdict == EdgeCaseVerdict.Fail },
                )
            }
            .sortedBy { hypothesisIdNumeric(it.hypothesisId) }

        return EdgeCaseRunSummary(results = results, byHypothesis = byHypothesis)
    }

    private fun evaluate(case: EdgeCase, convertedRoot: Path): EdgeCaseResult {
        val convertedFile = convertedRoot.resolve(case.stagedRelativeKtPath())

        if (!Files.isRegularFile(convertedFile)) {
            return EdgeCaseResult(
                case = case,
                verdict = EdgeCaseVerdict.Fail,
                failReason = FailReason.MissingConverted,
                convertedExists = false,
                compiles = null,
                expectedDeclarations = 0,
                matchedDeclarations = 0,
                goldOnlyDeclarations = 0,
                convertedOnlyDeclarations = 0,
                idiomDelta = emptyMap(),
            )
        }

        val compileResult = compilabilityChecker.check(convertedFile)
        if (!compileResult.compiled) {
            return EdgeCaseResult(
                case = case,
                verdict = EdgeCaseVerdict.Fail,
                failReason = FailReason.DoesNotCompile,
                convertedExists = true,
                compiles = false,
                expectedDeclarations = 0,
                matchedDeclarations = 0,
                goldOnlyDeclarations = 0,
                convertedOnlyDeclarations = 0,
                idiomDelta = emptyMap(),
                compileErrors = compileResult.errors,
            )
        }

        val expectedDecls = loader.loadFile(case.expectedKt)
            .collectDeclarations(case.expectedKt.fileName)
        val convertedDecls = loader.loadFile(convertedFile)
            .collectDeclarations(convertedFile.fileName)

        val pairing = DeclarationPairer.pair(gold = expectedDecls, converted = convertedDecls)

        if (pairing.matched.isEmpty() && expectedDecls.isNotEmpty()) {
            return EdgeCaseResult(
                case = case,
                verdict = EdgeCaseVerdict.Fail,
                failReason = FailReason.NoMatchingDeclarations,
                convertedExists = true,
                compiles = true,
                expectedDeclarations = expectedDecls.size,
                matchedDeclarations = 0,
                goldOnlyDeclarations = pairing.goldOnly.size,
                convertedOnlyDeclarations = pairing.convertedOnly.size,
                idiomDelta = emptyMap(),
            )
        }

        // Aggregate idiom delta across all matched pairs.
        val totalDelta = pairing.matched.fold(IdiomCounts.EMPTY to IdiomCounts.EMPTY) { (g, c), pair ->
            (g + IdiomCounter.collect(pair.gold.psi)) to (c + IdiomCounter.collect(pair.converted.psi))
        }
        val deltaMap = idiomsAsDelta(expected = totalDelta.first, converted = totalDelta.second)

        // Pass iff every expected declaration is paired AND no idiom delta.
        // Partial iff every expected declaration is paired but some idiom mismatch.
        val allPaired = pairing.goldOnly.isEmpty() && pairing.matched.size == expectedDecls.size
        val zeroDelta = deltaMap.values.all { it == 0 }

        val verdict = when {
            allPaired && zeroDelta -> EdgeCaseVerdict.Pass
            allPaired -> EdgeCaseVerdict.Partial
            else -> EdgeCaseVerdict.Fail
        }
        val failReason = if (verdict == EdgeCaseVerdict.Fail) FailReason.NoMatchingDeclarations else FailReason.None

        return EdgeCaseResult(
            case = case,
            verdict = verdict,
            failReason = failReason,
            convertedExists = true,
            compiles = true,
            expectedDeclarations = expectedDecls.size,
            matchedDeclarations = pairing.matched.size,
            goldOnlyDeclarations = pairing.goldOnly.size,
            convertedOnlyDeclarations = pairing.convertedOnly.size,
            idiomDelta = deltaMap,
        )
    }

    private fun idiomsAsDelta(expected: IdiomCounts, converted: IdiomCounts): Map<String, Int> {
        val e = expected.toMap()
        val c = converted.toMap()
        return e.keys.associateWith { key -> (c[key] ?: 0) - (e[key] ?: 0) }
    }

    private fun hypothesisIdNumeric(id: String): Int =
        id.removePrefix("H").toIntOrNull() ?: Int.MAX_VALUE
}
