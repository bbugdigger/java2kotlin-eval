package io.github.bbugdigger.j2keval.eval

import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import java.nio.file.Path

/**
 * A named, locatable Kotlin declaration extracted from a [KtFile].
 *
 * The PSI node is retained so downstream analysis (idiom counting, histogram
 * similarity) can re-walk just this declaration's subtree.
 */
data class Declaration(
    /** Fully qualified name from the file's package + the declaration's nesting path. */
    val fqn: String,
    /** What kind of declaration this is. */
    val kind: DeclarationKind,
    /**
     * Stable signature for overload disambiguation. For functions: `name(arity)`.
     * For everything else: `name`.
     */
    val signature: String,
    /** File containing this declaration, relative to the loader root. */
    val sourceFile: Path,
    /** The PSI node. Held by reference; keep the [PsiLoader] alive while in use. */
    val psi: KtNamedDeclaration,
)

enum class DeclarationKind {
    CLASS,
    INTERFACE,
    OBJECT,
    COMPANION_OBJECT,
    FUNCTION,
    PROPERTY,
}

/**
 * Walk every named declaration in [file] (top-level and nested), returning a flat
 * list keyed by fully-qualified name.
 *
 * Why flat: the eval engine pairs gold against converted by FQN, so siblings,
 * nested types, member functions, and member properties all need to be addressable
 * uniformly. Anonymous declarations (lambdas, init blocks) are skipped — they
 * have no name to pair on.
 */
fun KtFile.collectDeclarations(sourceFile: Path): List<Declaration> {
    val packagePrefix = packageFqName.asString()
    val out = mutableListOf<Declaration>()

    fun visit(node: KtNamedDeclaration, parentFqn: String) {
        val name = node.name ?: return  // anonymous — skip
        val ownFqn = if (parentFqn.isEmpty()) "$name" else "$parentFqn.$name"
        val absFqn = if (packagePrefix.isEmpty()) ownFqn else "$packagePrefix.$ownFqn"

        val (kind, signature) = classify(node, name)
        out.add(
            Declaration(
                fqn = absFqn,
                kind = kind,
                signature = signature,
                sourceFile = sourceFile,
                psi = node,
            )
        )

        // Recurse into class/object bodies.
        if (node is KtClassOrObject) {
            for (child in node.declarations) {
                if (child is KtNamedDeclaration) visit(child, ownFqn)
            }
        }
    }

    for (top in declarations) {
        if (top is KtNamedDeclaration) visit(top, "")
    }
    return out
}

private fun classify(node: KtNamedDeclaration, name: String): Pair<DeclarationKind, String> = when (node) {
    is KtNamedFunction -> {
        val arity = node.valueParameters.size
        DeclarationKind.FUNCTION to "$name($arity)"
    }
    is KtProperty -> DeclarationKind.PROPERTY to name
    is KtObjectDeclaration -> {
        val kind = if (node.isCompanion()) DeclarationKind.COMPANION_OBJECT else DeclarationKind.OBJECT
        kind to name
    }
    is KtClass -> {
        val kind = if (node.isInterface()) DeclarationKind.INTERFACE else DeclarationKind.CLASS
        kind to name
    }
    else -> DeclarationKind.CLASS to name  // best-effort fallback
}
