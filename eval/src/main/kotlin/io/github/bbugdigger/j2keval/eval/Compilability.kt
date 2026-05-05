package io.github.bbugdigger.j2keval.eval

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Compiles a set of `.kt` files via the embedded Kotlin compiler (no shell-out, no
 * external `kotlinc` on PATH required) and reports per-file compile success.
 *
 * Designed primarily for the **edge-case dataset**: each case has minimal external deps
 * so a stdlib-only classpath is enough. For real-world targets (spring-petclinic, OkHttp)
 * compilability against just stdlib will fail on framework references; the caller is
 * responsible for supplying the right classpath via [classpath] or using this only as
 * a partial signal.
 */
class CompilabilityChecker(
    /**
     * Additional classpath entries (jars or class directories) to pass to kotlinc.
     * Stdlib is added automatically (see [stdlibJars]). Named with the `extra` prefix to
     * avoid shadowing K2JVMCompilerArguments.classpath inside the apply block below.
     */
    private val extraClasspath: List<Path> = emptyList(),
) {
    /**
     * Locate the kotlin-stdlib jar(s) on this process's classpath. The embedded
     * K2JVMCompiler doesn't auto-resolve them the way the standalone `kotlinc` script
     * does — without this the compiler emits "Cannot access built-in declaration
     * 'kotlin.Unit'" / "'kotlin.Int'" / "'kotlin.Throws'" for everything.
     */
    private val stdlibJars: List<Path> by lazy {
        listOfNotNull(
            jarLocationOf("kotlin.Unit"),                     // kotlin-stdlib core
            jarLocationOf("kotlin.jvm.internal.Intrinsics"),  // sometimes a separate jar
            jarLocationOf("kotlin.io.path.PathsKt"),          // kotlin-stdlib-jdk7 / jdk8 split
        ).distinct()
    }

    private fun jarLocationOf(className: String): Path? {
        return try {
            val location = Class.forName(className).protectionDomain?.codeSource?.location ?: return null
            Path.of(location.toURI())
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Compiles every file individually (so a failure in one doesn't poison the others)
     * and returns a per-file verdict.
     */
    fun checkAll(files: List<Path>): CompilabilityResult {
        if (files.isEmpty()) return CompilabilityResult(emptyList())
        val perFile = files.map { check(it) }
        return CompilabilityResult(perFile)
    }

    /**
     * Compile a single `.kt` file. Returns [FileCompilability] with the captured
     * diagnostics. The kotlinc temp output dir is deleted before returning.
     */
    fun check(file: Path): FileCompilability {
        require(Files.isRegularFile(file)) { "Not a regular file: $file" }
        val tempOut = Files.createTempDirectory("j2k-eval-compile-")
        val collector = CapturingMessageCollector()
        try {
            val cpEntries = stdlibJars + extraClasspath
            val args = K2JVMCompilerArguments().apply {
                freeArgs = listOf(file.toAbsolutePath().toString())
                destination = tempOut.toAbsolutePath().toString()
                // We pass stdlib via classpath ourselves; tell the compiler not to try.
                noStdlib = true
                noReflect = true
                suppressWarnings = true
                if (cpEntries.isNotEmpty()) {
                    classpath = cpEntries.joinToString(File.pathSeparator) {
                        it.toAbsolutePath().toString()
                    }
                }
            }
            val exitCode: ExitCode = K2JVMCompiler().exec(collector, Services.EMPTY, args)
            return FileCompilability(
                file = file,
                compiled = exitCode == ExitCode.OK,
                errors = collector.errors.toList(),
            )
        } catch (t: Throwable) {
            return FileCompilability(
                file = file,
                compiled = false,
                errors = listOf("compiler threw ${t.javaClass.simpleName}: ${t.message}"),
            )
        } finally {
            tempOut.toFile().deleteRecursively()
        }
    }

    private class CapturingMessageCollector : MessageCollector {
        val errors = mutableListOf<String>()
        override fun clear() { errors.clear() }
        override fun hasErrors(): Boolean = errors.isNotEmpty()
        override fun report(
            severity: CompilerMessageSeverity,
            message: String,
            location: CompilerMessageSourceLocation?,
        ) {
            if (severity.isError) {
                val loc = location?.let { "${it.path}:${it.line}:${it.column}" } ?: ""
                errors += if (loc.isNotEmpty()) "$loc: $message" else message
            }
        }
    }
}

/** Compile result for one `.kt` file. */
data class FileCompilability(
    val file: Path,
    val compiled: Boolean,
    val errors: List<String>,
)

/** Aggregate result for a batch of files. */
data class CompilabilityResult(val perFile: List<FileCompilability>) {
    val total: Int get() = perFile.size
    val compiledCount: Int get() = perFile.count { it.compiled }
    val failedCount: Int get() = total - compiledCount
    val rate: Double get() = if (total == 0) 0.0 else compiledCount.toDouble() / total
}
