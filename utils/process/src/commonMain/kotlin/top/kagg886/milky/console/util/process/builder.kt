package top.kagg886.milky.console.util.process

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext


data class ProcessConfig(
    val executable: String,
    val arguments: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val stdin: IOOptions = IOOptions.Inherited,
    val stdout: IOOptions = IOOptions.Inherited,
    val stderr: IOOptions = IOOptions.Inherited,
    val inheritedFD: Set<ULong> = emptySet(),
    val context: CoroutineContext = Dispatchers.IO + SupervisorJob()
) {
    init {
        logger.v { "enter ProcessConfig init: executable=$executable, arguments=${arguments.size}, environment=${environment.size}, stdin=$stdin, stdout=$stdout, stderr=$stderr, inheritedFD=${inheritedFD.size}" }
        if (executable.isBlank()) {
            logger.e { "ProcessConfig validation failed: executable is blank" }
        }
        require(executable.isNotBlank()) { "executable must not be blank" }
        if (environment.keys.any { '=' in it }) {
            logger.e { "ProcessConfig validation failed: environment key contains '='" }
        }
        require(environment.keys.none { '=' in it }) {
            "environment variable names must not contain '='"
        }
        logger.d { "ProcessConfig validation completed: executableNotBlank=${executable.isNotBlank()}, environmentNamesValid=${environment.keys.none { '=' in it }}" }
        logger.v { "exit ProcessConfig init" }
    }

    enum class IOOptions {
        None,
        Inherited,
        Redirected,
    }
}
