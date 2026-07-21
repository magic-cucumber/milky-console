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
    private var stdin: ProcessConfig.IOOptions = ProcessConfig.IOOptions.Inherited
    private var stdout: ProcessConfig.IOOptions = ProcessConfig.IOOptions.Inherited
    private var stderr: ProcessConfig.IOOptions = ProcessConfig.IOOptions.Inherited
    private var inheritedFD: MutableSet<ULong> = mutableSetOf()

    private var context: CoroutineContext = Dispatchers.IO + SupervisorJob()

    fun executable(value: String) {
        logger.v { "enter builder executable: value=$value" }
        executable = value
        logger.d { "builder executable configured: executable=$executable, expected=${executable.isNotBlank()}" }
        logger.v { "exit builder executable" }
    }

    fun argument(value: String) {
        logger.v { "enter builder argument: value=$value" }
        arguments.add(value)
        logger.d { "builder argument added: count=${arguments.size}, expected=true" }
        logger.v { "exit builder argument" }
    }

    fun arguments(vararg values: String) {
        logger.v { "enter builder arguments: count=${values.size}" }
        arguments.addAll(values)
        logger.d { "builder arguments added: total=${arguments.size}, expected=true" }
        logger.v { "exit builder arguments" }
    }

    fun environment(key: String, value: String) {
        logger.v { "enter builder environment entry: key=$key" }
        environment[key] = value
        logger.d { "builder environment entry configured: key=$key, total=${environment.size}, expected=${'=' !in key}" }
        logger.v { "exit builder environment entry" }
    }

    fun environment(map: Map<String, String>) {
        logger.v { "enter builder environment map: count=${map.size}" }
        environment.putAll(map)
        logger.d { "builder environment map configured: total=${environment.size}, expected=${map.keys.none { '=' in it }}" }
        logger.v { "exit builder environment map" }
    }

    fun stdin(value: ProcessConfig.IOOptions) {
        logger.v { "enter builder stdin: value=$value" }
        stdin = value
        logger.d { "builder stdin configured: stdin=$stdin, expected=true" }
        logger.v { "exit builder stdin" }
    }

    fun stdout(value: ProcessConfig.IOOptions) {
        logger.v { "enter builder stdout: value=$value" }
        stdout = value
        logger.d { "builder stdout configured: stdout=$stdout, expected=true" }
        logger.v { "exit builder stdout" }
    }

    fun stderr(value: ProcessConfig.IOOptions) {
        logger.v { "enter builder stderr: value=$value" }
        stderr = value
        logger.d { "builder stderr configured: stderr=$stderr, expected=true" }
        logger.v { "exit builder stderr" }
    }

    fun inheritFD(vararg fds: ULong) {
        logger.v { "enter builder inheritFD: count=${fds.size}" }
        inheritedFD.addAll(fds.asList())
        logger.d { "builder inherited fd configured: total=${inheritedFD.size}, expected=true" }
        logger.v { "exit builder inheritFD" }
    }

    fun context(value: CoroutineContext) {
        logger.v { "enter builder context" }
        context = value
        logger.d { "builder context configured: expected=true" }
        logger.v { "exit builder context" }
    }

    fun build(): ProcessConfig {
        logger.v { "enter builder build" }
        val config = ProcessConfig(
            executable = executable,
            arguments = arguments.toList(),
            environment = environment.toMap(),
            stdin = stdin,
            stdout = stdout,
            stderr = stderr,
            inheritedFD = inheritedFD.toSet(),
            context = context
        )
        logger.d { "builder build completed: executable=${config.executable}, arguments=${config.arguments.size}, environment=${config.environment.size}, expected=true" }
        logger.v { "exit builder build" }
        return config
    }
}
