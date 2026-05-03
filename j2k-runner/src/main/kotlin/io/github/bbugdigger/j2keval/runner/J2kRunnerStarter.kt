package io.github.bbugdigger.j2keval.runner

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationStarter
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.idea.actions.JavaToKotlinAction
import org.jetbrains.kotlin.psi.KtFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.system.exitProcess

/**
 * Headless J2K driver. Invoked from the IDE launcher as:
 *
 *     idea j2k --project=<dir> --input=<dir> --output=<dir>
 *
 * Behaviour:
 *  - `--input` is the source tree containing `.java` files (typically `<project>/src/main/java`).
 *  - `--project` is the IntelliJ project root (the dir with `pom.xml` / `build.gradle`).
 *    Defaults to `--input` if omitted. Used so IntelliJ's project model can resolve
 *    classpaths via Maven/Gradle import.
 *  - `--output` is where converted `.kt` files land. The runner copies `--input` to
 *    `<output>/.work/`, opens that copy as the project, runs J2K (which mutates files
 *    in place — `.java` → `.kt`), then moves the resulting `.kt` files into `--output`
 *    preserving the input-relative path. The original `--input` tree is never mutated.
 *
 * Why a copy: `JavaToKotlinAction.Handler.convertFiles` modifies files in place,
 * deleting `.java` and creating `.kt` siblings. Mutating a git submodule is not
 * acceptable for a CI-friendly pipeline, so we sandbox the conversion in a working
 * copy under `<output>/.work/`.
 */
class J2kRunnerStarter : ApplicationStarter {

    // ApplicationStarter is deprecated in newer platforms in favour of
    // ModernApplicationStarter, but the modern variant doesn't exist in 2024.1.
    @Suppress("OVERRIDE_DEPRECATION")
    override val commandName: String = "j2k"

    override fun main(args: List<String>) {
        // ApplicationStarter.main runs on the EDT under a write-intent lock.
        // J2K's Handler.convertFiles internally schedules WriteCommandActions back to
        // the EDT, so blocking here would self-deadlock. Hand off to a pooled thread
        // and exit the application from there once the work is done.
        val app = ApplicationManager.getApplication()
        app.executeOnPooledThread {
            var exitCode = 0
            try {
                runConversion(parseArgs(args))
                log("success")
            } catch (t: Throwable) {
                System.err.println("[j2k-runner] FAILED: ${t.javaClass.simpleName}: ${t.message}")
                t.printStackTrace(System.err)
                exitCode = 1
            } finally {
                app.exit(/*force =*/ true, /*exitConfirmed =*/ true, /*restart =*/ false)
                if (exitCode != 0) {
                    // Application.exit doesn't carry an exit code. Force one for CI
                    // after a brief grace period so app.exit's own shutdown can run.
                    Thread {
                        Thread.sleep(2000)
                        exitProcess(exitCode)
                    }.start()
                }
            }
        }
    }

    private data class Args(val projectPath: Path, val inputPath: Path, val outputPath: Path)

    private fun parseArgs(args: List<String>): Args {
        // args[0] == "j2k" (command name)
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
        val inp = requireNotNull(inputPath) { "--input is required" }
        val out = requireNotNull(outputPath) { "--output is required" }
        return Args(
            projectPath = Paths.get(projectPath ?: inp).toAbsolutePath().normalize(),
            inputPath = Paths.get(inp).toAbsolutePath().normalize(),
            outputPath = Paths.get(out).toAbsolutePath().normalize()
        )
    }

    private fun runConversion(args: Args) {
        log("project=${args.projectPath}")
        log("input  =${args.inputPath}")
        log("output =${args.outputPath}")
        require(args.projectPath.isDirectory()) { "Project path is not a directory: ${args.projectPath}" }
        require(args.inputPath.isDirectory()) { "Input path is not a directory: ${args.inputPath}" }

        // Sandbox the conversion in <output>/.work/ so the original input tree is preserved.
        val workRoot = args.outputPath.resolve(".work")
        if (workRoot.exists()) {
            log("step 0: cleaning previous work directory at $workRoot")
            workRoot.toFile().deleteRecursively()
        }
        workRoot.createDirectories()

        // The work root mirrors the project layout. We need both the project descriptors
        // (pom.xml / build.gradle / .idea / etc.) AND the input source tree under the
        // same relative path so IntelliJ resolves them consistently.
        val workProjectRoot = workRoot.resolve("project")
        val inputRelToProject = args.projectPath.relativize(args.inputPath)
        log("step 1: copying project tree ${args.projectPath} -> $workProjectRoot")
        copyProjectTree(args.projectPath, workProjectRoot)
        val workInputRoot = workProjectRoot.resolve(inputRelToProject)
        require(workInputRoot.isDirectory()) {
            "After copy, work input root is missing: $workInputRoot"
        }

        log("step 2: opening project at $workProjectRoot")
        val project = openProject(workProjectRoot)
        log("step 2 OK: project=${project.name}, modules=${ModuleManager.getInstance(project).modules.size}")

        try {
            log("step 3: waiting for indexing (smart mode)")
            DumbService.getInstance(project).waitForSmartMode()
            log("step 3 OK")

            log("step 4: invoking J2K via JavaToKotlinAction.Handler.convertFiles")
            val stats = convertFilesInPlace(project, workInputRoot)
            log("step 4 OK: attempted=${stats.attempted} converted=${stats.converted} failed=${stats.failed}")

            log("step 5: moving converted .kt files to ${args.outputPath}")
            val moved = collectAndMoveKtFiles(workInputRoot, args.outputPath)
            log("step 5 OK: moved $moved .kt file(s)")
        } finally {
            log("step 6: closing project")
            // forceCloseProject mutates project state and must run on the EDT.
            ApplicationManager.getApplication().invokeAndWait {
                ProjectManagerEx.getInstanceEx().forceCloseProject(project)
            }
            log("step 6 OK")
        }
    }

    private fun openProject(path: Path): Project {
        val task = OpenProjectTask {
            isNewProject = false
            forceOpenInNewFrame = true
            // Run configurators so Maven/Gradle import attaches an SDK + dependencies.
            // Without this, J2K falls back to lexical conversion (no type resolution).
            runConfigurators = true
        }
        return ProjectManagerEx.getInstanceEx().openProject(path, task)
            ?: error("Failed to open project at $path")
    }

    private fun convertFilesInPlace(project: Project, inputRoot: Path): ConversionStats {
        val inputVfs = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(inputRoot)
            ?: error("Input directory not found in VFS: $inputRoot")

        // Collect PsiJavaFiles + a module to attribute conversion to. Both touch the
        // workspace model, so do it inside a read action.
        val (javaFiles, module) = ReadAction.compute<Pair<List<PsiJavaFile>, Module?>, RuntimeException> {
            val files = collectJavaFiles(inputVfs, project)
            val mod = files.firstOrNull()?.let { pickModule(it) }
            files to mod
        }
        log("  collected ${javaFiles.size} .java file(s); module=${module?.name ?: "<none>"}")
        if (javaFiles.isEmpty()) {
            return ConversionStats(0, 0, 0)
        }
        requireNotNull(module) {
            "No module owns the input files. Project import may have failed — check that " +
                "the project root has a build.gradle / pom.xml IntelliJ can resolve."
        }

        // Handler.convertFiles wraps things in WriteCommandAction + ProgressManager,
        // both of which require the EDT. Dispatch from our pooled thread via invokeAndWait.
        val app = ApplicationManager.getApplication()
        val resultRef = AtomicReference<List<KtFile>>()
        val errorRef = AtomicReference<Throwable?>(null)
        app.invokeAndWait {
            try {
                resultRef.set(
                    JavaToKotlinAction.Handler.convertFiles(
                        files = javaFiles,
                        project = project,
                        module = module,
                        enableExternalCodeProcessing = true,
                        askExternalCodeProcessing = false,
                        forceUsingOldJ2k = false
                    )
                )
            } catch (t: Throwable) {
                errorRef.set(t)
            }
        }
        errorRef.get()?.let {
            throw RuntimeException("J2K conversion threw on batch of ${javaFiles.size} files", it)
        }
        val converted = resultRef.get() ?: emptyList()
        return ConversionStats(
            attempted = javaFiles.size,
            converted = converted.size,
            failed = javaFiles.size - converted.size
        )
    }

    private fun collectJavaFiles(root: VirtualFile, project: Project): List<PsiJavaFile> {
        val psiManager = PsiManager.getInstance(project)
        val out = mutableListOf<PsiJavaFile>()
        VfsUtil.visitChildrenRecursively(root, object : VirtualFileVisitor<Unit>() {
            override fun visitFile(file: VirtualFile): Boolean {
                if (file.isDirectory) return true
                if (file.extension != "java") return true
                (psiManager.findFile(file) as? PsiJavaFile)?.let(out::add)
                return true
            }
        })
        return out
    }

    private fun pickModule(file: PsiJavaFile): Module? {
        val vFile = file.virtualFile ?: return null
        val mm = ModuleManager.getInstance(file.project)
        return mm.modules.firstOrNull { it.moduleContentScope.contains(vFile) }
            ?: mm.modules.firstOrNull()
    }

    /**
     * Copies the project tree from [src] to [dst], skipping noisy/derived directories
     * that would balloon the copy and aren't needed for IntelliJ to open the project.
     */
    private fun copyProjectTree(src: Path, dst: Path) {
        val excludedDirNames = setOf(
            ".git", ".gradle", ".idea", "build", "target", "out", "node_modules"
        )
        Files.walk(src).use { stream ->
            stream.forEach { source ->
                val rel = src.relativize(source)
                // Skip any path component that matches an excluded dir name.
                if ((0 until rel.nameCount).any { rel.getName(it).toString() in excludedDirNames }) {
                    return@forEach
                }
                val target = dst.resolve(rel)
                if (Files.isDirectory(source)) {
                    if (!target.exists()) Files.createDirectories(target)
                } else {
                    target.parent?.let { if (!it.exists()) Files.createDirectories(it) }
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    /**
     * After conversion, walks [workInputRoot] for `.kt` files and moves them to
     * [outputPath] preserving the relative path under [workInputRoot]. Returns the
     * number of files moved.
     */
    private fun collectAndMoveKtFiles(workInputRoot: Path, outputPath: Path): Int {
        var moved = 0
        Files.walk(workInputRoot).use { stream ->
            stream.forEach { source ->
                if (Files.isRegularFile(source) && source.toString().endsWith(".kt")) {
                    val rel = workInputRoot.relativize(source)
                    val dest = outputPath.resolve(rel)
                    dest.parent?.createDirectories()
                    Files.move(source, dest, StandardCopyOption.REPLACE_EXISTING)
                    moved++
                }
            }
        }
        return moved
    }

    private fun log(msg: String) {
        println("[j2k-runner] $msg")
        System.out.flush()
    }
}

private data class ConversionStats(val attempted: Int, val converted: Int, val failed: Int)
