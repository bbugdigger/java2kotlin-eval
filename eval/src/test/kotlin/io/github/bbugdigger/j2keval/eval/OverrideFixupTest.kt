package io.github.bbugdigger.j2keval.eval

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class OverrideFixupTest {

    @Test
    fun `single missing override modifier is added and the file compiles`(@TempDir tmp: Path) {
        // Mirror the J2K bug pattern: @Override annotation present, override modifier missing.
        // (java.util.function.IntSupplier is in the JDK, so it's resolvable without extra
        // classpath even though our Compilability checker uses stdlib-only.)
        val src = tmp.resolve("Counter.kt")
        Files.writeString(
            src,
            """
            package t
            import java.util.function.IntSupplier
            class Factory {
                fun build(start: Int): IntSupplier = object : IntSupplier {
                    private var n = start
                    @Override
                    fun getAsInt(): Int {
                        n += 1
                        return n
                    }
                }
            }
            """.trimIndent()
        )

        val fixup = OverrideFixup()
        val result = fixup.applyTo(src)

        assertEquals(1, result.appliedCount, "exactly one override modifier should have been added")
        assertTrue(result.compilesAfter, "file should compile cleanly after fixup; errors after = ${result.errorsAfter}")

        val fixed = Files.readString(src)
        assertTrue(fixed.contains("override fun getAsInt"), "expected `override fun getAsInt` in fixed file:\n$fixed")
        assertFalse(fixed.contains("@Override"), "@Override annotation should have been removed:\n$fixed")
    }

    @Test
    fun `file that already compiles is left untouched`(@TempDir tmp: Path) {
        val src = tmp.resolve("Clean.kt")
        Files.writeString(src, "package t\nclass Clean { fun answer(): Int = 42 }\n")
        val before = Files.readString(src)
        val result = OverrideFixup().applyTo(src)
        assertEquals(0, result.appliedCount)
        assertTrue(result.compilesAfter)
        assertEquals(before, Files.readString(src), "file content should be unchanged")
    }

    @Test
    fun `multiple missing overrides in one file are all fixed in a single pass`(@TempDir tmp: Path) {
        val src = tmp.resolve("Multi.kt")
        Files.writeString(
            src,
            """
            package t
            interface Greet { fun hello(): String; fun bye(): String }
            class Impl : Greet {
                @Override
                fun hello(): String { return "hi" }

                @Override
                fun bye(): String { return "bye" }
            }
            """.trimIndent()
        )
        val result = OverrideFixup().applyTo(src)

        assertEquals(2, result.appliedCount, "both functions should be fixed")
        assertTrue(result.compilesAfter)
        val fixed = Files.readString(src)
        assertTrue(fixed.contains("override fun hello"))
        assertTrue(fixed.contains("override fun bye"))
        assertFalse(fixed.contains("@Override"))
    }

    @Test
    fun `applyToDirectory aggregates fixups across files`(@TempDir tmp: Path) {
        val a = tmp.resolve("a/A.kt").also { Files.createDirectories(it.parent) }
        val b = tmp.resolve("b/B.kt").also { Files.createDirectories(it.parent) }
        Files.writeString(
            a,
            """
            package a
            interface I { fun f() }
            class A : I { @Override fun f() {} }
            """.trimIndent()
        )
        Files.writeString(b, "package b\nclass B { fun ok(): Int = 1 }\n")

        val agg = OverrideFixup().applyToDirectory(tmp)

        assertEquals(2, agg.totalFiles)
        assertEquals(1, agg.filesWithFixups, "only A.kt needed fixing")
        assertEquals(1, agg.totalFixupsApplied)
        assertEquals(1, agg.filesNowCompiling, "the fixed file should compile")
    }
}
