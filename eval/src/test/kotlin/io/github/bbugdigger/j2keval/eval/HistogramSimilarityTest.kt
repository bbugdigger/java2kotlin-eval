package io.github.bbugdigger.j2keval.eval

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class HistogramSimilarityTest {

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
    fun `identical sources produce similarity 1`(@TempDir tmp: Path) {
        val src = """
            package p
            class A {
                val x: Int = 1
                fun f(): Int = x + 1
            }
        """.trimIndent()
        val a = loader.loadFile(tmp.resolve("a.kt").also { Files.writeString(it, src) }).collectDeclarations(Path.of("a.kt")).single { it.fqn == "p.A" }
        val b = loader.loadFile(tmp.resolve("b.kt").also { Files.writeString(it, src) }).collectDeclarations(Path.of("b.kt")).single { it.fqn == "p.A" }
        val ha = HistogramSimilarity.histogramOf(a.psi)
        val hb = HistogramSimilarity.histogramOf(b.psi)
        val sim = HistogramSimilarity.cosineSimilarity(ha, hb)
        assertEquals(1.0, sim, 1e-9)
    }

    @Test
    fun `disjoint shapes produce a similarity less than 1`(@TempDir tmp: Path) {
        val a = parse(tmp, "A.kt", """
            package p
            class A
        """.trimIndent()).single { it.fqn == "p.A" }
        val b = parse(tmp, "B.kt", """
            package p
            class B {
                val x: Int = 1
                val y: String = "hi"
                fun f(a: Int, b: Int): Int = a + b
                fun g(): Int = 42
                fun h(): String = "x"
            }
        """.trimIndent()).single { it.fqn == "p.B" }
        val ha = HistogramSimilarity.histogramOf(a.psi)
        val hb = HistogramSimilarity.histogramOf(b.psi)
        val sim = HistogramSimilarity.cosineSimilarity(ha, hb)
        assertTrue(sim in 0.0..1.0, "similarity within bounds: $sim")
        assertTrue(sim < 0.95, "similarity should be noticeably under 1.0 for very different bodies: $sim")
    }

    @Test
    fun `empty histograms yield zero similarity`() {
        assertEquals(0.0, HistogramSimilarity.cosineSimilarity(emptyMap(), emptyMap()), 1e-9)
        assertEquals(0.0, HistogramSimilarity.cosineSimilarity(mapOf("KtClass" to 1), emptyMap()), 1e-9)
    }

    private fun parse(tmpDir: Path, name: String, source: String): List<Declaration> {
        val p = tmpDir.resolve(name)
        Files.writeString(p, source)
        return loader.loadFile(p).collectDeclarations(Path.of(name))
    }
}
