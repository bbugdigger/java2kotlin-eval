package io.github.bbugdigger.j2keval.eval

import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Parses `.kt` files into [KtFile] PSI trees using the embedded Kotlin compiler.
 *
 * One [PsiLoader] holds a single [KotlinCoreEnvironment]; create one per eval run and
 * close it via [dispose]. Files loaded through the same loader share a [com.intellij.openapi.project.Project]
 * which is what PSI element creation needs to function.
 *
 * No semantic resolution is performed — this is purely lexical/syntactic parsing.
 * That's intentional: the eval engine compares PSI trees structurally, not by
 * resolved type.
 */
class PsiLoader : Disposable {

    private val parentDisposable: Disposable = Disposer.newDisposable("PsiLoader")
    private val environment: KotlinCoreEnvironment
    private val factory: KtPsiFactory

    init {
        val config = CompilerConfiguration().apply {
            put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
            put(CommonConfigurationKeys.MODULE_NAME, "j2k-eval")
        }
        environment = KotlinCoreEnvironment.createForProduction(
            parentDisposable,
            config,
            EnvironmentConfigFiles.JVM_CONFIG_FILES
        )
        factory = KtPsiFactory(environment.project, markGenerated = false)
    }

    /** Parse a single `.kt` file. The file's name in the PSI is its basename. */
    fun loadFile(path: Path): KtFile {
        // KtPsiFactory.createFile expects LF line endings; CRLF (common on Windows-checked-out
        // source) makes the parser misattribute the leading comment block, dropping the
        // package directive. Normalize defensively.
        val text = Files.readString(path).replace("\r\n", "\n")
        return factory.createFile(path.fileName.toString(), text)
    }

    /**
     * Walk [root] recursively, parsing every `.kt` file found.
     * Returns each file paired with its path relative to [root] for stable display.
     */
    fun loadDirectory(root: Path): List<LoadedFile> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .map { LoadedFile(relativePath = root.relativize(it), ktFile = loadFile(it)) }
                .toList()
        }
    }

    override fun dispose() {
        Disposer.dispose(parentDisposable)
    }
}

/** A parsed `.kt` file paired with its location relative to the loader root. */
data class LoadedFile(val relativePath: Path, val ktFile: KtFile)
