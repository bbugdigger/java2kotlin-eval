package io.github.bbugdigger.j2keval.runner

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.application.ModernApplicationStarter
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginModeProvider
import org.jetbrains.kotlin.j2k.ConverterSettings
import org.jetbrains.kotlin.j2k.J2kConverterExtension
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * Headless J2K driver. Invoked from the IDE launcher as:
 *
 *     idea j2k --project=<dir> --input=<dir> --output=<dir>
 *
 * `--project` is the project root (used for module/SDK resolution); defaults to `--input`
 * when not provided. Walks all `.java` files under `--input`, converts each via
 * J2kConverterExtension (K1_NEW for headless reliability), and writes `.kt` files to
 * `--output` preserving the input-relative path.
 */
class J2kRunnerStarter : ModernApplicationStarter() {
    @Suppress("OVERRIDE_DEPRECATION")
    override val commandName: String = "j2k"

    override suspend fun start(args: List<String>) {
        try {
            val parsed = parseArgs(args)
            runConversion(parsed)
            exitProcess(0)
        } catch (t: Throwable) {
            System.err.println("J2K runner failed: ${t.message}")
            t.printStackTrace(System.err)
            exitProcess(1)
        }
    }

    private data class Args(val projectPath: Path, val inputPath: Path, val outputPath: Path)

    private fun parseArgs(args: List<String>): Args {
        var projectPath: String? = null
        var inputPath: String? = null
        var outputPath: String? = null
        for (arg in args.drop(1)) {
            when {
                arg.startsWith("--project=") -> projectPath = arg.substringAfter("=")
                arg.startsWith("--input=") -> inputPath = arg.substringAfter("=")
                arg.startsWith("--output=") -> outputPath = arg.substringAfter("=")
                else -> error("Unknown argument: $arg")
            }
        }
        require(inputPath != null) { "--input is required" }
        require(outputPath != null) { "--output is required" }
        return Args(
            projectPath = Paths.get(projectPath ?: inputPath!!).toAbsolutePath().normalize(),
            inputPath = Paths.get(inputPath!!).toAbsolutePath().normalize(),
            outputPath = Paths.get(outputPath!!).toAbsolutePath().normalize()
        )
    }

    private suspend fun runConversion(args: Args) {
        log("project=${args.projectPath}")
        log("input  =${args.inputPath}")
        log("output =${args.outputPath}")
        require(Files.isDirectory(args.projectPath)) { "Project path is not a directory: ${args.projectPath}" }
        require(Files.isDirectory(args.inputPath)) { "Input path is not a directory: ${args.inputPath}" }
        args.outputPath.createDirectories()

        log("step 1: openProject…")
        val project = openProject(args.projectPath)
        log("step 1 OK: project=${project.name}, modules=${ModuleManager.getInstance(project).modules.size}")

        // Don't explicitly close the project here. forceCloseProject requires EDT +
        // write-intent threading we don't hold, and exitProcess() at the end of start()
        // tears down the application cleanly anyway.
        log("step 2: convertFiles…")
        convertFiles(project, args.inputPath, args.outputPath)
        log("step 2 OK")
    }

    private fun openProject(path: Path): Project {
        val task = OpenProjectTask {
            forceOpenInNewFrame = true
            isNewProject = false
            // Skip auto-import / external system configuration; we don't need a build model
            // for J2K to produce reasonable output, and configurators tend to hang in headless
            // mode on bare directories.
            runConfigurators = false
        }
        // Try the auto-import-aware path first (handles Maven/Gradle/.idea projects).
        val project = ProjectUtil.openOrImport(path, task)
            ?: ProjectManagerEx.getInstanceEx().openProject(path, task)
            ?: error("Failed to open project at $path")
        return project
    }

    private fun log(msg: String) {
        println("[j2k-runner] $msg")
        System.out.flush()
    }

    private suspend fun convertFiles(project: Project, inputPath: Path, outputPath: Path) {
        log("  walk: scanning .java files under $inputPath")
        val javaFiles = mutableListOf<Path>()
        Files.walk(inputPath).use { stream ->
            stream.forEach { p ->
                if (Files.isRegularFile(p) && p.toString().endsWith(".java")) {
                    javaFiles.add(p)
                }
            }
        }
        if (javaFiles.isEmpty()) {
            log("  walk: no .java files found")
            return
        }
        log("  walk: discovered ${javaFiles.size} .java file(s)")

        log("  psi: resolving files via PsiManager (read action)")
        val psiFiles: List<PsiJavaFile> = ReadAction.compute<List<PsiJavaFile>, Throwable> {
            val psiManager = PsiManager.getInstance(project)
            val lfs = LocalFileSystem.getInstance()
            javaFiles.mapNotNull { p ->
                val vf = lfs.refreshAndFindFileByPath(p.absolutePathString())
                if (vf == null) {
                    System.err.println("[j2k-runner] VFS miss: $p"); null
                } else {
                    val psi = psiManager.findFile(vf)
                    if (psi !is PsiJavaFile) {
                        System.err.println("[j2k-runner] not PsiJavaFile (got ${psi?.javaClass?.simpleName ?: "null"}): $p"); null
                    } else {
                        psi
                    }
                }
            }
        }
        log("  psi: resolved ${psiFiles.size}/${javaFiles.size} as PsiJavaFile")
        if (psiFiles.isEmpty()) error("Could not resolve any .java files as PsiJavaFile")

        // The Kotlin plugin's mode determines which converter implementation is loaded;
        // pick the matching kind so we don't ask for an extension that wasn't registered.
        val kind = if (KotlinPluginModeProvider.isK2Mode()) {
            J2kConverterExtension.Kind.K2
        } else {
            J2kConverterExtension.Kind.K1_NEW
        }
        log("  j2k: kind=$kind (K2 mode=${KotlinPluginModeProvider.isK2Mode()})")
        val ext = J2kConverterExtension.extension(kind)
        val module: Module? = ModuleManager.getInstance(project).modules.firstOrNull()
        val settings = ConverterSettings.defaultSettings
        log("  j2k: building converter (module=${module?.name ?: "<none>"})")
        val converter = ext.createJavaToKotlinConverter(project, module, settings)
        val postProcessor = ext.createPostProcessor()

        log("  j2k: calling filesToKotlin (suspend, dispatched off-EDT by ModernApplicationStarter)…")
        val result = try {
            converter.filesToKotlin(psiFiles, postProcessor)
        } catch (t: Throwable) {
            System.err.println("[j2k-runner]   j2k: filesToKotlin threw ${t.javaClass.name}: ${t.message}")
            t.printStackTrace(System.err)
            throw t
        }
        log("  j2k: filesToKotlin returned ${result.results.size} result(s)")

        // FilesResult.results is List<String> parallel to the input file list.
        check(result.results.size == psiFiles.size) {
            "J2K returned ${result.results.size} results for ${psiFiles.size} input files"
        }
        var written = 0
        for ((index, kotlinCode) in result.results.withIndex()) {
            val psi = psiFiles[index]
            val src = Paths.get(psi.virtualFile.path)
            val rel = src.relativeTo(inputPath)
            val parentRel = rel.parent ?: Paths.get("")
            val ktName = rel.fileName.toString().removeSuffix(".java") + ".kt"
            val out = outputPath.resolve(parentRel).resolve(ktName)
            out.parent?.createDirectories()
            out.writeText(kotlinCode)
            log("    wrote ${out.relativeTo(outputPath)}")
            written++
        }
        log("converted $written / ${javaFiles.size} file(s)")
    }
}
