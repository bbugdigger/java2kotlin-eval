package io.github.bbugdigger.j2keval.eval

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DeclarationPairerTest {

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
    fun `pairs simple matching FQNs and surfaces orphans on both sides`(@TempDir tmp: Path) {
        val gold = parse(tmp, "Gold.kt", """
            package a
            class B
            class C
            class GoldOnly
        """.trimIndent())
        val converted = parse(tmp, "Conv.kt", """
            package a
            class B
            class C
            class ConvertedOnly
        """.trimIndent())

        val result = DeclarationPairer.pair(gold, converted)

        assertEquals(2, result.matched.size)
        assertEquals(setOf("a.B", "a.C"), result.matched.map { it.gold.fqn }.toSet())
        assertEquals(listOf("a.GoldOnly"), result.goldOnly.map { it.fqn })
        assertEquals(listOf("a.ConvertedOnly"), result.convertedOnly.map { it.fqn })
    }

    @Test
    fun `disambiguates overloaded functions by signature`(@TempDir tmp: Path) {
        val gold = parse(tmp, "GoldFns.kt", """
            package a
            fun f() = 0
            fun f(x: Int) = x
            fun f(x: Int, y: Int) = x + y
        """.trimIndent())
        val converted = parse(tmp, "ConvFns.kt", """
            package a
            fun f() = 0
            fun f(x: Int, y: Int) = x + y
            fun f(x: Int, y: Int, z: Int) = x + y + z
        """.trimIndent())

        val result = DeclarationPairer.pair(gold, converted)
        assertEquals(2, result.matched.size)
        assertTrue(result.matched.all { it.gold.signature == it.converted.signature })
        assertEquals(listOf("f(1)"), result.goldOnly.map { it.signature })
        assertEquals(listOf("f(3)"), result.convertedOnly.map { it.signature })
    }

    @Test
    fun `empty inputs yield empty result`() {
        val result = DeclarationPairer.pair(emptyList(), emptyList())
        assertEquals(0, result.matched.size)
        assertEquals(0, result.goldOnly.size)
        assertEquals(0, result.convertedOnly.size)
    }

    private fun parse(tmpDir: Path, name: String, source: String): List<Declaration> {
        val p = tmpDir.resolve(name)
        Files.writeString(p, source)
        return loader.loadFile(p).collectDeclarations(Path.of(name))
    }
}
