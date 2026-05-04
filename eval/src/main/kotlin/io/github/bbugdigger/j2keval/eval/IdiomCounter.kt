package io.github.bbugdigger.j2keval.eval

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSafeQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * Counts occurrences of idiomatic Kotlin patterns inside a single declaration's PSI subtree.
 *
 * The set is deliberately interpretable, not exhaustive: each counter answers a
 * concrete question a reader of the eval report will care about, like "did J2K
 * fold getters into properties?" or "did J2K use scope functions?". Adding more
 * counters is cheap — extend [IdiomCounts] and add a case in [collect].
 */
data class IdiomCounts(
    val vals: Int = 0,
    val vars: Int = 0,
    val expressionBodyFunctions: Int = 0,
    val blockBodyFunctions: Int = 0,
    val defaultArguments: Int = 0,
    val safeCalls: Int = 0,             // ?.
    val notNullAssertions: Int = 0,     // !!
    val elvis: Int = 0,                 // ?:
    val scopeFunctionCalls: Int = 0,    // let / apply / also / run / with
    val dataClasses: Int = 0,
    val primaryCtorProperties: Int = 0, // val/var in primary constructor
    val stringTemplatesWithExpressions: Int = 0,
) {
    /** Total number of "Kotlin-y" idioms — useful for a cheap aggregate gauge. */
    val total: Int
        get() = vals + vars + expressionBodyFunctions + blockBodyFunctions + defaultArguments +
            safeCalls + notNullAssertions + elvis + scopeFunctionCalls + dataClasses +
            primaryCtorProperties + stringTemplatesWithExpressions

    operator fun plus(other: IdiomCounts): IdiomCounts = IdiomCounts(
        vals + other.vals,
        vars + other.vars,
        expressionBodyFunctions + other.expressionBodyFunctions,
        blockBodyFunctions + other.blockBodyFunctions,
        defaultArguments + other.defaultArguments,
        safeCalls + other.safeCalls,
        notNullAssertions + other.notNullAssertions,
        elvis + other.elvis,
        scopeFunctionCalls + other.scopeFunctionCalls,
        dataClasses + other.dataClasses,
        primaryCtorProperties + other.primaryCtorProperties,
        stringTemplatesWithExpressions + other.stringTemplatesWithExpressions,
    )

    companion object {
        val EMPTY = IdiomCounts()
    }

    /** Render as a parallel pair-of-rows table for two [IdiomCounts] (eval reports). */
    fun toMap(): Map<String, Int> = linkedMapOf(
        "val" to vals,
        "var" to vars,
        "expr-body fn" to expressionBodyFunctions,
        "block-body fn" to blockBodyFunctions,
        "default arg" to defaultArguments,
        "?." to safeCalls,
        "!!" to notNullAssertions,
        "?:" to elvis,
        "scope fn" to scopeFunctionCalls,
        "data class" to dataClasses,
        "primary-ctor prop" to primaryCtorProperties,
        "interpolated string" to stringTemplatesWithExpressions,
    )
}

private val SCOPE_FUNCTION_NAMES = setOf("let", "apply", "also", "run", "with")

object IdiomCounter {

    fun collect(root: PsiElement): IdiomCounts {
        val visitor = CountingVisitor()
        root.accept(visitor)
        return visitor.snapshot()
    }

    private class CountingVisitor : KtTreeVisitorVoid() {
        var vals = 0
        var vars = 0
        var expressionBodyFunctions = 0
        var blockBodyFunctions = 0
        var defaultArguments = 0
        var safeCalls = 0
        var notNullAssertions = 0
        var elvis = 0
        var scopeFunctionCalls = 0
        var dataClasses = 0
        var primaryCtorProperties = 0
        var stringTemplatesWithExpressions = 0

        override fun visitProperty(property: KtProperty) {
            if (property.isVar) vars++ else vals++
            super.visitProperty(property)
        }

        override fun visitNamedFunction(function: KtNamedFunction) {
            val body = function.bodyExpression
            when {
                body == null -> Unit  // abstract / interface — neither
                body is KtBlockExpression -> blockBodyFunctions++
                else -> expressionBodyFunctions++
            }
            super.visitNamedFunction(function)
        }

        override fun visitParameter(parameter: KtParameter) {
            if (parameter.defaultValue != null) defaultArguments++
            super.visitParameter(parameter)
        }

        override fun visitSafeQualifiedExpression(expression: KtSafeQualifiedExpression) {
            safeCalls++
            super.visitSafeQualifiedExpression(expression)
        }

        override fun visitPostfixExpression(expression: KtPostfixExpression) {
            if (expression.operationToken == KtTokens.EXCLEXCL) notNullAssertions++
            super.visitPostfixExpression(expression)
        }

        override fun visitBinaryExpression(expression: KtBinaryExpression) {
            if (expression.operationToken == KtTokens.ELVIS) elvis++
            super.visitBinaryExpression(expression)
        }

        override fun visitCallExpression(expression: KtCallExpression) {
            val name = expression.calleeExpression?.text
            if (name != null && name in SCOPE_FUNCTION_NAMES) scopeFunctionCalls++
            super.visitCallExpression(expression)
        }

        override fun visitClass(klass: KtClass) {
            if (klass.isData()) dataClasses++
            super.visitClass(klass)
        }

        override fun visitPrimaryConstructor(constructor: KtPrimaryConstructor) {
            for (param in constructor.valueParameters) {
                if (param.hasValOrVar()) primaryCtorProperties++
            }
            super.visitPrimaryConstructor(constructor)
        }

        override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
            // "Has interpolation" = at least one entry that isn't a plain literal piece.
            val hasExpr = expression.entries.any { it !is org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry }
            if (hasExpr) stringTemplatesWithExpressions++
            super.visitStringTemplateExpression(expression)
        }

        fun snapshot() = IdiomCounts(
            vals, vars, expressionBodyFunctions, blockBodyFunctions, defaultArguments,
            safeCalls, notNullAssertions, elvis, scopeFunctionCalls, dataClasses,
            primaryCtorProperties, stringTemplatesWithExpressions,
        )
    }
}

