@file:OptIn(ExperimentalForeignApi::class)

package top.kagg886.milky.console.util.process

import kotlinx.cinterop.*
import kotlinx.coroutines.withContext
import platform.linux.*
import platform.posix.*
import top.kagg886.milky.console.util.pipe.IPCAnonymousPipe
import top.kagg886.milky.console.util.pipe.IPCAnonymousPipeSink
import top.kagg886.milky.console.util.pipe.IPCAnonymousPipeSource
import top.kagg886.milky.console.util.pipe.create

actual fun Process.Companion.create(config: ProcessConfig): Process {
    require(config.workingDirectory == null) {
        "workingDirectory is not supported on Unix until a portable posix_spawn chdir file action is available"
    }

    val stdin = config.stdin.takeIf { it == ProcessConfig.IOOptions.Redirected }?.let { IPCAnonymousPipe.create() }
    val stdout = config.stdout.takeIf { it == ProcessConfig.IOOptions.Redirected }?.let { IPCAnonymousPipe.create() }
    val stderr = config.stderr.takeIf { it == ProcessConfig.IOOptions.Redirected }?.let { IPCAnonymousPipe.create() }

    return try {
        memScoped {
            val actions = alloc<posix_spawn_file_actions_t>()
            checkSpawn(posix_spawn_file_actions_init(actions.ptr), "posix_spawn_file_actions_init")
            try {
                addInputAction(actions.ptr, config.stdin, stdin)
                addOutputAction(actions.ptr, config.stdout, stdout, STDOUT_FILENO)
                addOutputAction(actions.ptr, config.stderr, stderr, STDERR_FILENO)
                addCloseActionsForUninheritedDescriptors(
                    actions.ptr,
                    config.inheritedFD,
                    stdin,
                    stdout,
                    stderr,
                )

                val arguments = listOf(config.executable) + config.arguments
                val argv = cStringArray(arguments)
                val environment = cStringArray(mergedEnvironment(config.environment))
                val pid = alloc<IntVar>()
                withInheritedFileDescriptors(config.inheritedFD) {
                    checkSpawn(
                        posix_spawnp(pid.ptr, config.executable, actions.ptr, null, argv, environment),
                        "posix_spawnp",
                    )
                }

                stdin?.source?.close()
                stdout?.sink?.close()
                stderr?.sink?.close()
                processWithRedirectedIO(
                    UnixProcess(pid.value.toLong(), config.context, stdin?.sink, stdout?.source, stderr?.source),
                )
            } finally {
                posix_spawn_file_actions_destroy(actions.ptr)
            }
        }
    } catch (cause: Throwable) {
        closeAllEnds(stdin, stdout, stderr)
        throw cause
    }
}

private fun MemScope.cStringArray(values: List<String>): CPointer<CPointerVar<ByteVar>> {
    val result = allocArray<CPointerVar<ByteVar>>(values.size + 1)
    values.forEachIndexed { index, value -> result[index] = value.cstr.getPointer(this) }
    result[values.size] = null
    return result
}

private fun mergedEnvironment(overrides: Map<String, String>): List<String> {
    val values = linkedMapOf<String, String>()
    var index = 0
    while (true) {
        val entry = __environ?.get(index++) ?: break
        val text = entry.toKString()
        val separator = text.indexOf('=')
        if (separator > 0) values[text.substring(0, separator)] = text.substring(separator + 1)
    }
    values.putAll(overrides)
    return values.map { (name, value) -> "$name=$value" }
}

private fun addInputAction(actions: CPointer<posix_spawn_file_actions_t>, option: ProcessConfig.IOOptions, pipe: IPCAnonymousPipe?) {
    when (option) {
        ProcessConfig.IOOptions.Inherited -> Unit
        ProcessConfig.IOOptions.None -> checkSpawn(
            posix_spawn_file_actions_addopen(actions, STDIN_FILENO, "/dev/null", O_RDONLY, 0u),
            "posix_spawn_file_actions_addopen",
        )
        ProcessConfig.IOOptions.Redirected -> {
            requireNotNull(pipe)
            addPipeAction(actions, pipe.source.fd.toInt(), pipe.sink.fd.toInt(), STDIN_FILENO)
        }
    }
}

private fun addOutputAction(actions: CPointer<posix_spawn_file_actions_t>, option: ProcessConfig.IOOptions, pipe: IPCAnonymousPipe?, target: Int) {
    when (option) {
        ProcessConfig.IOOptions.Inherited -> Unit
        ProcessConfig.IOOptions.None -> checkSpawn(
            posix_spawn_file_actions_addopen(actions, target, "/dev/null", O_WRONLY, 0u),
            "posix_spawn_file_actions_addopen",
        )
        ProcessConfig.IOOptions.Redirected -> {
            requireNotNull(pipe)
            addPipeAction(actions, pipe.sink.fd.toInt(), pipe.source.fd.toInt(), target)
        }
    }
}

private fun addPipeAction(actions: CPointer<posix_spawn_file_actions_t>, childEnd: Int, parentEnd: Int, target: Int) {
    checkSpawn(posix_spawn_file_actions_addclose(actions, parentEnd), "posix_spawn_file_actions_addclose")
    if (childEnd != target) {
        checkSpawn(posix_spawn_file_actions_adddup2(actions, childEnd, target), "posix_spawn_file_actions_adddup2")
        checkSpawn(posix_spawn_file_actions_addclose(actions, childEnd), "posix_spawn_file_actions_addclose")
    }
}

/** Gives the child Windows HANDLE_LIST-style descriptor inheritance semantics. */
private fun addCloseActionsForUninheritedDescriptors(
    actions: CPointer<posix_spawn_file_actions_t>,
    inheritedFD: Set<ULong>,
    stdin: IPCAnonymousPipe?,
    stdout: IPCAnonymousPipe?,
    stderr: IPCAnonymousPipe?,
) {
    val inherited = inheritedFD.mapTo(mutableSetOf()) { fd ->
        val descriptor = fd.toInt()
        require(descriptor >= 0 && descriptor.toULong() == fd) { "invalid file descriptor: $fd" }
        descriptor
    }
    inherited += setOf(STDIN_FILENO, STDOUT_FILENO, STDERR_FILENO)

    // The preceding pipe actions consume and close these descriptors.
    val pipeDescriptors = listOfNotNull(stdin, stdout, stderr)
        .flatMap { pipe -> listOf(pipe.source.fd.toInt(), pipe.sink.fd.toInt()) }

    openFileDescriptors()
        .asSequence()
        .filter { it !in inherited && it !in pipeDescriptors }
        .forEach { descriptor ->
            checkSpawn(
                posix_spawn_file_actions_addclose(actions, descriptor),
                "posix_spawn_file_actions_addclose",
            )
        }
}

private fun openFileDescriptors(): Set<Int> {
    val directory = opendir("/proc/self/fd") ?: error("opendir(/proc/self/fd) failed: errno $errno")
    return try {
        buildSet {
            while (true) {
                val entry = readdir(directory) ?: break
                entry.pointed.d_name.toKString().toIntOrNull()?.let(::add)
            }
        }
    } finally {
        closedir(directory)
    }
}

private fun checkSpawn(result: Int, operation: String) {
    if (result != 0) error("$operation failed: errno $result")
}

private inline fun <T> withInheritedFileDescriptors(fds: Set<ULong>, block: () -> T): T {
    val originalFlags = fds.associate { fd ->
        val descriptor = fd.toInt()
        require(descriptor >= 0 && descriptor.toULong() == fd) { "invalid file descriptor: $fd" }
        val flags = fcntl(descriptor, F_GETFD)
        check(flags >= 0) { "fcntl(F_GETFD) failed for fd $descriptor: errno $errno" }
        descriptor to flags
    }
    val closeOnExecDescriptors = originalFlags.filterValues { it and FD_CLOEXEC != 0 }

    try {
        closeOnExecDescriptors.forEach { (descriptor, flags) ->
            check(fcntl(descriptor, F_SETFD, flags and FD_CLOEXEC.inv()) == 0) {
                "fcntl(F_SETFD) failed for fd $descriptor: errno $errno"
            }
        }
        return block()
    } finally {
        closeOnExecDescriptors.forEach { (descriptor, flags) ->
            if (fcntl(descriptor, F_SETFD, flags) != 0) {
                error("fcntl(F_SETFD) failed while restoring fd $descriptor: errno $errno")
            }
        }
    }
}

private fun closeAllEnds(stdin: IPCAnonymousPipe?, stdout: IPCAnonymousPipe?, stderr: IPCAnonymousPipe?) {
    stdin?.sink?.close(); stdin?.source?.close()
    stdout?.sink?.close(); stdout?.source?.close()
    stderr?.sink?.close(); stderr?.source?.close()
}

private class UnixProcess(
    private val pidValue: Long,
    private val context: kotlin.coroutines.CoroutineContext,
    internal val stdinValue: IPCAnonymousPipeSink?,
    internal val stdoutValue: IPCAnonymousPipeSource?,
    internal val stderrValue: IPCAnonymousPipeSource?,
) : Process {
    override val pid: Long get() = pidValue

    override suspend fun await(): Process.ExitStatus = withContext(context) {
        memScoped {
            val status = alloc<IntVar>()
            while (true) {
                val result = waitpid(pidValue.toInt(), status.ptr, 0)
                if (result >= 0) return@memScoped exitStatus(status.value)
                if (errno != EINTR) error("waitpid failed: errno $errno")
            }
            error("unreachable")
        }
    }

    override fun shutdown(): Boolean = kill(pidValue.toInt(), SIGTERM) == 0
    override fun kill(): Boolean = kill(pidValue.toInt(), SIGKILL) == 0

    private fun exitStatus(status: Int): Process.ExitStatus =
        if ((status and 0x7f) == 0) Process.ExitStatus.Result((status ushr 8) and 0xff)
        else Process.ExitStatus.Killed
}

private fun processWithRedirectedIO(process: UnixProcess): Process = when {
    process.stdinValue != null && process.stdoutValue != null && process.stderrValue != null -> object : Process by process, InheritedStdIn, InheritedStdOut, InheritedStdErr {
        override val stdin get() = process.stdinValue
        override val stdout get() = process.stdoutValue
        override val stderr get() = process.stderrValue
    }
    process.stdinValue != null && process.stdoutValue != null -> object : Process by process, InheritedStdIn, InheritedStdOut {
        override val stdin get() = process.stdinValue
        override val stdout get() = process.stdoutValue
    }
    process.stdinValue != null && process.stderrValue != null -> object : Process by process, InheritedStdIn, InheritedStdErr {
        override val stdin get() = process.stdinValue
        override val stderr get() = process.stderrValue
    }
    process.stdoutValue != null && process.stderrValue != null -> object : Process by process, InheritedStdOut, InheritedStdErr {
        override val stdout get() = process.stdoutValue
        override val stderr get() = process.stderrValue
    }
    process.stdinValue != null -> object : Process by process, InheritedStdIn { override val stdin get() = process.stdinValue }
    process.stdoutValue != null -> object : Process by process, InheritedStdOut { override val stdout get() = process.stdoutValue }
    process.stderrValue != null -> object : Process by process, InheritedStdErr { override val stderr get() = process.stderrValue }
    else -> process
}
