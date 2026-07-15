package top.kagg886.milky.console.util.process

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/15 14:23
 * ================================================
 */

@Target(AnnotationTarget.TYPE,AnnotationTarget.CLASS)
@DslMarker
annotation class ProcessDslMarker

@ProcessDslMarker
class ProcessBuilderScope {
    private var executable: String = ""
    private var arguments: MutableList<String> = mutableListOf()
    private var environment: MutableMap<String, String> = mutableMapOf()
    private var workingDirectory: String? = null
    private var stdin: ProcessConfig.IOOptions = ProcessConfig.IOOptions.Inherited
    private var stdout: ProcessConfig.IOOptions = ProcessConfig.IOOptions.Inherited
    private var stderr: ProcessConfig.IOOptions = ProcessConfig.IOOptions.Inherited
    private var inheritedFD: MutableSet<ULong> = mutableSetOf()

    private var context: CoroutineContext = Dispatchers.IO + SupervisorJob()

    fun executable(value: String) {
        executable = value
    }

    fun argument(value: String) {
        arguments.add(value)
    }

    fun arguments(vararg values: String) {
        arguments.addAll(values)
    }

    fun environment(key: String, value: String) {
        environment[key] = value
    }

    fun environment(map: Map<String, String>) {
        environment.putAll(map)
    }

    fun workingDirectory(value: String) {
        workingDirectory = value
    }

    fun stdin(value: ProcessConfig.IOOptions) {
        stdin = value
    }

    fun stdout(value: ProcessConfig.IOOptions) {
        stdout = value
    }

    fun stderr(value: ProcessConfig.IOOptions) {
        stderr = value
    }

    fun inheritFD(vararg fds: ULong) {
        inheritedFD.addAll(fds.asList())
    }

    fun context(value: CoroutineContext) {
        context = value
    }

    fun build(): ProcessConfig = ProcessConfig(
        executable = executable,
        arguments = arguments.toList(),
        environment = environment.toMap(),
        workingDirectory = workingDirectory,
        stdin = stdin,
        stdout = stdout,
        stderr = stderr,
        inheritedFD = inheritedFD.toSet(),
        context = context
    )
}
