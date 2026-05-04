package io.github.bbugdigger.j2keval.eval

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiRecursiveElementVisitor
import kotlin.math.sqrt

/**
 * Counts each PSI element type that appears in a subtree, keyed by the element class's
 * simple name (e.g. `KtBinaryExpression`, `KtCallExpression`).
 *
 * Coupled with [cosineSimilarity], this gives one principled per-declaration similarity
 * score in `[0.0, 1.0]` that complements the interpretable [IdiomCounts] table.
 */
typealias PsiHistogram = Map<String, Int>

object HistogramSimilarity {

    fun histogramOf(root: PsiElement): PsiHistogram {
        val counts = HashMap<String, Int>()
        root.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val key = element.javaClass.simpleName
                counts.merge(key, 1) { old, _ -> old + 1 }
                super.visitElement(element)
            }
        })
        return counts
    }

    /**
     * Cosine similarity of two histograms.
     *
     * Returns `1.0` for identical histograms, `0.0` for fully disjoint ones, in between
     * otherwise. If either histogram is empty (or all zeros) the result is `0.0` —
     * undefined-but-useful: an empty declaration contributes no similarity.
     */
    fun cosineSimilarity(a: PsiHistogram, b: PsiHistogram): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val keys = a.keys + b.keys
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (key in keys) {
            val av = a.getOrDefault(key, 0).toDouble()
            val bv = b.getOrDefault(key, 0).toDouble()
            dot += av * bv
            normA += av * av
            normB += bv * bv
        }
        if (normA == 0.0 || normB == 0.0) return 0.0
        return dot / (sqrt(normA) * sqrt(normB))
    }
}
