package io.github.bbugdigger.j2keval.eval

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class EdgeCasesTest {

    @Test
    fun `extractPackage parses standard java package declaration`() {
        val pkg = EdgeCases.extractPackage("/* header */\npackage com.example.demo;\n\nclass A {}")
        assertEquals("com.example.demo", pkg)
    }

    @Test
    fun `extractPackage returns null when no package declaration`() {
        assertEquals(null, EdgeCases.extractPackage("class A {}"))
    }

    @Test
    fun `extractHypothesisIds picks up Hn tokens and dedupes`() {
        val ids = EdgeCases.extractHypothesisIds("Tests H1 and H4. Also see H1 again. Not h2 (lowercase ignored).")
        assertEquals(listOf("H1", "H4"), ids)
    }

    @Test
    fun `discoverFrom finds case directories with the canonical triple`(@TempDir tmp: Path) {
        // Lay out one valid case + one incomplete (missing notes.md) — only the valid one is found.
        val valid = tmp.resolve("category-a/case-1").also { Files.createDirectories(it) }
        Files.writeString(valid.resolve("source.java"), "package edge.cat_a.case_1;\nclass C {}\n")
        Files.writeString(valid.resolve("expected.kt"), "package edge.cat_a.case_1\nclass C\n")
        Files.writeString(valid.resolve("notes.md"), "Tests H7 and H9.\n")

        val incomplete = tmp.resolve("category-b/case-2").also { Files.createDirectories(it) }
        Files.writeString(incomplete.resolve("source.java"), "package edge.cat_b.case_2;\nclass D {}\n")
        // No expected.kt and no notes.md.

        val found = EdgeCases.discoverFrom(tmp)
        assertEquals(1, found.size)
        val case = found.single()
        assertEquals("category-a", case.category)
        assertEquals("case-1", case.name)
        assertEquals("category-a/case-1", case.id)
        assertEquals("edge.cat_a.case_1", case.javaPackage)
        assertEquals(listOf("H7", "H9"), case.hypothesisIds)
    }

    @Test
    fun `stagedRelativeKtPath maps package dots to slashes and renames extension`(@TempDir tmp: Path) {
        val dir = tmp.resolve("any/leaf").also { Files.createDirectories(it) }
        Files.writeString(dir.resolve("source.java"), "package edge.expression_body.simple_return_fn;\nclass C {}\n")
        Files.writeString(dir.resolve("expected.kt"), "package edge.expression_body.simple_return_fn\nclass C\n")
        Files.writeString(dir.resolve("notes.md"), "H1\n")

        val case = EdgeCases.discoverFrom(tmp).single()
        assertEquals(Path.of("edge", "expression_body", "simple_return_fn", "source.kt"), case.stagedRelativeKtPath())
    }

    @Test
    fun `HypothesisRollup status follows the predicate rules`() {
        assertEquals("Confirmed", HypothesisRollup("H1", passCount = 0, partialCount = 2, failCount = 0).status())
        assertEquals("Confirmed", HypothesisRollup("H1", passCount = 0, partialCount = 0, failCount = 3).status())
        assertEquals("Refuted", HypothesisRollup("H1", passCount = 3, partialCount = 0, failCount = 0).status())
        assertEquals("Partial", HypothesisRollup("H1", passCount = 1, partialCount = 1, failCount = 0).status())
        assertEquals("Untested", HypothesisRollup("H1", passCount = 0, partialCount = 0, failCount = 0).status())
    }
}
