package top.kagg886.milky.console.util.process

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext


data class ProcessConfig(
    val executable: String,
    val arguments: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val workingDirectory: String? = null,
    val stdin: IOOptions = IOOptions.Inherited,
    val stdout: IOOptions = IOOptions.Inherited,
    val stderr: IOOptions = IOOptions.Inherited,
    val inheritedFD: Set<ULong> = emptySet(),
    val context: CoroutineContext = Dispatchers.IO + SupervisorJob()
) {
    init {
        require(executable.isNotBlank()) { "executable must not be blank" }
        require(environment.keys.none { '=' in it }) {
            "environment variable names must not contain '='"
        }
    }

    enum class IOOptions {
        None,
        Inherited,
        Redirected,
    }
}

