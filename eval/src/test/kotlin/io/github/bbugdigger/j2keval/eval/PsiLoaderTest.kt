package io.github.bbugdigger.j2keval.eval

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PsiLoaderTest {

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
    fun `parses a simple file and exposes its package + top-level declarations`(@TempDir tmp: Path) {
        val src = tmp.resolve("Demo.kt").also {
            Files.writeString(
                it,
                """
                package com.example.demo

                class Demo {
                    fun greet(name: String): String = "hi, ${'$'}name"
                }

                fun topLevel(): Int = 42
                """.trimIndent()
            )
        }
        val ktFile = loader.loadFile(src)
        assertEquals("com.example.demo", ktFile.packageFqName.asString())
        assertEquals(2, ktFile.declarations.size)
    }

    @Test
    fun `loadDirectory walks subdirectories and returns relative paths`(@TempDir tmp: Path) {
        tmp.resolve("pkg/A.kt").also { Files.createDirectories(it.parent); Files.writeString(it, "package pkg\nclass A") }
        tmp.resolve("pkg/sub/B.kt").also { Files.createDirectories(it.parent); Files.writeString(it, "package pkg.sub\nclass B") }
        Files.writeString(tmp.resolve("ignored.txt"), "not a kotlin file")

        val files = loader.loadDirectory(tmp).sortedBy { it.relativePath.toString() }
        assertEquals(2, files.size)
        assertEquals(Path.of("pkg", "A.kt"), files[0].relativePath)
        assertEquals(Path.of("pkg", "sub", "B.kt"), files[1].relativePath)
        assertTrue(files.all { it.ktFile.declarations.isNotEmpty() })
    }
}
