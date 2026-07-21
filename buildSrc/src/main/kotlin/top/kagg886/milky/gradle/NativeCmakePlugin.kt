package top.kagg886.milky.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

@DisableCachingByDefault(because = "CMake manages its own build cache and dependency graph.")
abstract class NativeCmakeBuild @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Internal
    abstract val baseDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        // CMake owns dependency tracking, including CMake files included from
        // outside baseDir. Always let it decide whether compilation is needed.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun build() {
        val sourceDirectory = baseDir.get().asFile
        val buildDirectory = sourceDirectory.resolve("build")
        val pluginOutputDirectory = outputDir.get().asFile

        execOperations.exec {
            workingDir = sourceDirectory
            commandLine(cmakeCommand(sourceDirectory, buildDirectory, pluginOutputDirectory))
        }.assertNormalExitValue()
    }

    private fun cmakeCommand(
        sourceDirectory: File,
        buildDirectory: File,
        pluginOutputDirectory: File,
    ): List<String> {
        val osName = System.getProperty("os.name").lowercase()
        return when {
            osName.startsWith("windows") -> listOf(
                "pwsh", "-NoProfile", "-Command",
                """
                    cmake -S "${sourceDirectory.absolutePath}" -B "${buildDirectory.absolutePath}" "-DMILKY_CMAKE_OUTPUT_DIR=${pluginOutputDirectory.absolutePath}"
                    if (${ '$' }LASTEXITCODE -ne 0) { exit ${ '$' }LASTEXITCODE }
                    cmake --build "${buildDirectory.absolutePath}" --config Debug
                    if (${ '$' }LASTEXITCODE -ne 0) { exit ${ '$' }LASTEXITCODE }
                """.trimIndent(),
            )
            osName.startsWith("linux") -> listOf(
                "bash", "-c",
                """
                    cmake -S "${sourceDirectory.absolutePath}" -B "${buildDirectory.absolutePath}" "-DMILKY_CMAKE_OUTPUT_DIR=${pluginOutputDirectory.absolutePath}" && \\
                    cmake --build "${buildDirectory.absolutePath}" --config Debug
                """.trimIndent(),
            )
            osName.startsWith("mac") -> listOf(
                "zsh", "-c",
                """
                    cmake -S "${sourceDirectory.absolutePath}" -B "${buildDirectory.absolutePath}" "-DMILKY_CMAKE_OUTPUT_DIR=${pluginOutputDirectory.absolutePath}" && \\
                    cmake --build "${buildDirectory.absolutePath}" --config Debug
                """.trimIndent(),
            )
            else -> throw GradleException("Unsupported operating system: $osName")
        }
    }
}

open class NativeCmakeExtension internal constructor(
    private val project: Project,
) {
    fun build(
        baseDir: File,
        outputDir: Provider<Directory>,
    ) = project.tasks.register(
        "buildNative${baseDir.name.toTaskNameSegment()}",
        NativeCmakeBuild::class.java,
    ) {
        group = "build"
        description = "Builds the native plugin in ${baseDir.relativeTo(project.projectDir)}."
        this.baseDir.set(baseDir)
        this.outputDir.set(outputDir)
    }

    private fun String.toTaskNameSegment(): String =
        split(Regex("[^A-Za-z0-9]+"))
            .filter(String::isNotEmpty)
            .joinToString("") { it.replaceFirstChar(Char::uppercase) }
}

class NativeCmakePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.extensions.create("nativeCmake", NativeCmakeExtension::class.java, project)
    }
}
