package io.github.bbugdigger.j2keval.eval

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class IdiomCounterTest {

    private lateinit var loader: PsiLoader

    @BeforeEach
    fun setUp() {
        loader = PsiLoader()
    }

    @AfterEach
    fun tearDown() {
        loader.dispose()
    }

    @Test
    fun `counts vals vars expr-bodies and other idioms in a single declaration`(@TempDir tmp: Path) {
        val src = tmp.resolve("Demo.kt").also {
            Files.writeString(
                it,
                """
                package x
                data class Demo(val name: String, var count: Int = 0) {
                    val derived: String = name.uppercase()
                    fun greet(prefix: String? = null): String =
                        prefix?.let { "${'$'}it ${'$'}name" } ?: name
                    fun forced(other: String?): String = other!!
                }
                """.trimIndent()
            )
        }
        val ktFile = loader.loadFile(src)
        val demo = ktFile.collectDeclarations(Path.of("Demo.kt"))
            .single { it.fqn == "x.Demo" }
        val counts = IdiomCounter.collect(demo.psi)

        assertEquals(1, counts.dataClasses, "data class on Demo")
        // primary-ctor properties: `val name`, `var count` => 2
        assertEquals(2, counts.primaryCtorProperties)
        // expr-body fns: `greet` (= ...) and `forced` (= ...) => 2
        assertEquals(2, counts.expressionBodyFunctions)
        // safe call: prefix?.let
        assertEquals(1, counts.safeCalls)
        // !! on `other!!`
        assertEquals(1, counts.notNullAssertions)
        // ?:: prefix?.let { ... } ?: name
        assertEquals(1, counts.elvis)
        // scope fn: `let`
        assertEquals(1, counts.scopeFunctionCalls)
        // string template with expression: "$it $name"
        assertEquals(1, counts.stringTemplatesWithExpressions)
        // default arg: `var count: Int = 0` AND `prefix: String? = null` => 2
        assertEquals(2, counts.defaultArguments)
    }

    @Test
    fun `block-body vs expression-body distinction`(@TempDir tmp: Path) {
        val src = tmp.resolve("Bodies.kt").also {
            Files.writeString(
                it,
                """
                package b
                fun expr(): Int = 1
                fun block(): Int { return 2 }
                """.trimIndent()
            )
        }
        val ktFile = loader.loadFile(src)
        val totals = ktFile.collectDeclarations(Path.of("Bodies.kt"))
            .map { IdiomCounter.collect(it.psi) }
            .fold(IdiomCounts.EMPTY) { a, b -> a + b }

        assertEquals(1, totals.expressionBodyFunctions)
        assertEquals(1, totals.blockBodyFunctions)
    }

    @Test
    fun `dataClasses count is at the data class declaration`(@TempDir tmp: Path) {
        val src = tmp.resolve("DC.kt").also {
            Files.writeString(it, "package d\ndata class P(val a: Int, val b: Int)")
        }
        val decl = loader.loadFile(src).collectDeclarations(Path.of("DC.kt")).single { it.fqn == "d.P" }
        val counts = IdiomCounter.collect(decl.psi)
        assertEquals(1, counts.dataClasses)
        assertEquals(2, counts.primaryCtorProperties)
    }
}
