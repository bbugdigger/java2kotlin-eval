package io.github.bbugdigger.j2keval.eval

/**
 * Per-pair score: pairs PSI similarity with idiom counts on both sides for the same
 * gold↔converted declaration pair.
 */
data class PairScore(
    val pair: DeclarationPair,
    val similarity: Double,        // [0.0, 1.0] — PSI histogram cosine
    val goldIdioms: IdiomCounts,
    val convertedIdioms: IdiomCounts,
)

/**
 * Aggregates per-file rollups so the report can show a per-file table without
 * recomputing from individual pair scores.
 */
data class FileScore(
    val sourceFile: String,            // path display string for stable rendering
    val matchedCount: Int,
    val goldOnlyCount: Int,
    val convertedOnlyCount: Int,
    val meanSimilarity: Double,
)

/** Top-level run summary. */
data class RunSummary(
    val goldDeclarations: Int,
    val convertedDeclarations: Int,
    val matched: Int,
    val goldOnly: Int,
    val convertedOnly: Int,
    val meanSimilarity: Double,
    val pairScores: List<PairScore>,
    val fileScores: List<FileScore>,
)

object Scoring {

    fun run(gold: List<Declaration>, converted: List<Declaration>): RunSummary {
        val pairing = DeclarationPairer.pair(gold, converted)

        val pairScores = pairing.matched.map { p ->
            val gh = HistogramSimilarity.histogramOf(p.gold.psi)
            val ch = HistogramSimilarity.histogramOf(p.converted.psi)
            PairScore(
                pair = p,
                similarity = HistogramSimilarity.cosineSimilarity(gh, ch),
                goldIdioms = IdiomCounter.collect(p.gold.psi),
                convertedIdioms = IdiomCounter.collect(p.converted.psi),
            )
        }

        val fileScores = buildFileScores(pairing, pairScores)

        val meanSim = if (pairScores.isEmpty()) 0.0 else pairScores.sumOf { it.similarity } / pairScores.size

        return RunSummary(
            goldDeclarations = gold.size,
            convertedDeclarations = converted.size,
            matched = pairing.matched.size,
            goldOnly = pairing.goldOnly.size,
            convertedOnly = pairing.convertedOnly.size,
            meanSimilarity = meanSim,
            pairScores = pairScores,
            fileScores = fileScores,
        )
    }

    private fun buildFileScores(pairing: PairingResult, pairScores: List<PairScore>): List<FileScore> {
        // Group everything by gold's source-file path. Converted-only orphans have no
        // gold counterpart; bucket them under their own file path so they're surfaced.
        val byFile = LinkedHashMap<String, MutableFileBucket>()
        fun bucket(path: String) = byFile.getOrPut(path) { MutableFileBucket() }

        for (ps in pairScores) {
            val key = ps.pair.gold.sourceFile.toString()
            val b = bucket(key)
            b.matched++
            b.similaritySum += ps.similarity
        }
        for (g in pairing.goldOnly) bucket(g.sourceFile.toString()).goldOnly++
        for (c in pairing.convertedOnly) bucket(c.sourceFile.toString()).convertedOnly++

        return byFile
            .map { (path, b) ->
                FileScore(
                    sourceFile = path,
                    matchedCount = b.matched,
                    goldOnlyCount = b.goldOnly,
                    convertedOnlyCount = b.convertedOnly,
                    meanSimilarity = if (b.matched == 0) 0.0 else b.similaritySum / b.matched,
                )
            }
            .sortedBy { it.sourceFile }
    }

    private class MutableFileBucket(
        var matched: Int = 0,
        var goldOnly: Int = 0,
        var convertedOnly: Int = 0,
        var similaritySum: Double = 0.0,
    )
}
