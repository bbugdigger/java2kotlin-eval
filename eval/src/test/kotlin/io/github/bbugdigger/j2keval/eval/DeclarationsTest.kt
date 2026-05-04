package io.github.bbugdigger.j2keval.eval

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DeclarationsTest {

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
    fun `collects top-level and nested declarations with FQNs`(@TempDir tmp: Path) {
        val src = tmp.resolve("Demo.kt").also {
            Files.writeString(
                it,
                """
                package com.example

                class Outer {
                    val x: Int = 1
                    fun greet(name: String) = "hi"
                    class Inner {
                        fun doIt() = Unit
                    }
                    companion object {
                        fun create(): Outer = Outer()
                    }
                }

                object Singleton

                fun topLevel(): Int = 42
                """.trimIndent()
            )
        }

        val ktFile = loader.loadFile(src)
        val decls = ktFile.collectDeclarations(Path.of("Demo.kt"))
        val byFqn = decls.associateBy { it.fqn }

        // Top-level
        assertTrue(byFqn.containsKey("com.example.Outer"))
        assertTrue(byFqn.containsKey("com.example.Singleton"))
        assertTrue(byFqn.containsKey("com.example.topLevel"))
        // Nested
        assertTrue(byFqn.containsKey("com.example.Outer.x"))
        assertTrue(byFqn.containsKey("com.example.Outer.greet"))
        assertTrue(byFqn.containsKey("com.example.Outer.Inner"))
        assertTrue(byFqn.containsKey("com.example.Outer.Inner.doIt"))
        assertTrue(byFqn.containsKey("com.example.Outer.Companion"))
        assertTrue(byFqn.containsKey("com.example.Outer.Companion.create"))
    }

    @Test
    fun `function signature carries arity for overload disambiguation`(@TempDir tmp: Path) {
        val src = tmp.resolve("Overloads.kt").also {
            Files.writeString(
                it,
                """
                package o
                fun f() = 1
                fun f(x: Int) = x
                fun f(x: Int, y: Int) = x + y
                """.trimIndent()
            )
        }
        val decls = loader.loadFile(src).collectDeclarations(Path.of("Overloads.kt"))
        val sigs = decls.filter { it.kind == DeclarationKind.FUNCTION }.map { it.signature }.sorted()
        assertEquals(listOf("f(0)", "f(1)", "f(2)"), sigs)
    }

    @Test
    fun `kinds are classified correctly`(@TempDir tmp: Path) {
        val src = tmp.resolve("Kinds.kt").also {
            Files.writeString(
                it,
                """
                package k
                class C
                interface I
                object O
                class WithCompanion { companion object Comp }
                fun f() = Unit
                val v = 1
                """.trimIndent()
            )
        }
        val byFqn = loader.loadFile(src).collectDeclarations(Path.of("Kinds.kt")).associateBy { it.fqn }
        assertEquals(DeclarationKind.CLASS, byFqn.getValue("k.C").kind)
        assertEquals(DeclarationKind.INTERFACE, byFqn.getValue("k.I").kind)
        assertEquals(DeclarationKind.OBJECT, byFqn.getValue("k.O").kind)
        assertEquals(DeclarationKind.COMPANION_OBJECT, byFqn.getValue("k.WithCompanion.Comp").kind)
        assertEquals(DeclarationKind.FUNCTION, byFqn.getValue("k.f").kind)
        assertEquals(DeclarationKind.PROPERTY, byFqn.getValue("k.v").kind)
    }
}
