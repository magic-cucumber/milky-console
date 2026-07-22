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
    logger.v { "enter create: executable=${config.executable}, arguments=${config.arguments.size}, stdin=${config.stdin}, stdout=${config.stdout}, stderr=${config.stderr}, inheritedFD=${config.inheritedFD.size}" }
    val stdin = config.stdin.takeIf { it == ProcessConfig.IOOptions.Redirected }?.let { IPCAnonymousPipe.create() }
    val stdout = config.stdout.takeIf { it == ProcessConfig.IOOptions.Redirected }?.let { IPCAnonymousPipe.create() }
    val stderr = config.stderr.takeIf { it == ProcessConfig.IOOptions.Redirected }?.let { IPCAnonymousPipe.create() }
    logger.d { "created requested redirected pipes: stdin=${stdin != null}, stdout=${stdout != null}, stderr=${stderr != null}" }

    return try {
        memScoped {
            logger.v { "enter posix_spawn scope: executable=${config.executable}" }
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
                logger.d { "prepared spawn data: argv=${arguments.size}, environmentOverrides=${config.environment.size}, expected=true" }
                logger.i { "starting process: executable=${config.executable}, arguments=${config.arguments.size}" }
                withInheritedFileDescriptors(config.inheritedFD) {
                    checkSpawn(
                        posix_spawnp(pid.ptr, config.executable, actions.ptr, null, argv, environment),
                        "posix_spawnp",
                    )
                }
                logger.i { "process started: pid=${pid.value}, executable=${config.executable}" }

                stdin?.source?.close()
                stdout?.sink?.close()
                stderr?.sink?.close()
                logger.d { "closed parent-owned child pipe ends after start: stdin=${stdin != null}, stdout=${stdout != null}, stderr=${stderr != null}, expected=true" }
                processWithRedirectedIO(
                    UnixProcess(pid.value.toLong(), config.context, stdin?.sink, stdout?.source, stderr?.source),
                )
            } finally {
                logger.v { "destroying posix spawn file actions" }
                posix_spawn_file_actions_destroy(actions.ptr)
                logger.v { "destroyed posix spawn file actions" }
            }
        }
    } catch (cause: Throwable) {
        logger.e(cause) { "process create failed; closing pipe ends: executable=${config.executable}" }
        closeAllEnds(stdin, stdout, stderr)
        throw cause
    }
}

private fun MemScope.cStringArray(values: List<String>): CPointer<CPointerVar<ByteVar>> {
    logger.v { "enter cStringArray: count=${values.size}" }
    val result = allocArray<CPointerVar<ByteVar>>(values.size + 1)
    values.forEachIndexed { index, value -> result[index] = value.cstr.getPointer(this) }
    result[values.size] = null
    logger.d { "created C string array: count=${values.size}, nullTerminated=${result[values.size] == null}" }
    logger.v { "exit cStringArray: count=${values.size}" }
    return result
}

private fun mergedEnvironment(overrides: Map<String, String>): List<String> {
    logger.v { "enter mergedEnvironment: overrides=${overrides.size}" }
    val values = linkedMapOf<String, String>()
    var index = 0
    while (true) {
        val entry = __environ?.get(index++) ?: break
        val text = entry.toKString()
        val separator = text.indexOf('=')
        if (separator > 0) values[text.substring(0, separator)] = text.substring(separator + 1)
        else logger.w { "ignored malformed environment entry while merging: index=${index - 1}" }
    }
    values.putAll(overrides)
    val merged = values.map { (name, value) -> "$name=$value" }
    logger.d { "merged environment: inherited=${values.size - overrides.size}, overrides=${overrides.size}, result=${merged.size}, expected=true" }
    logger.v { "exit mergedEnvironment: result=${merged.size}" }
    return merged
}

private fun addInputAction(actions: CPointer<posix_spawn_file_actions_t>, option: ProcessConfig.IOOptions, pipe: IPCAnonymousPipe?) {
    logger.v { "enter addInputAction: option=$option" }
    when (option) {
        ProcessConfig.IOOptions.Inherited -> logger.v { "stdin inherited; no action added" }
        ProcessConfig.IOOptions.None -> checkSpawn(
            posix_spawn_file_actions_addopen(actions, STDIN_FILENO, "/dev/null", O_RDONLY, 0u),
            "posix_spawn_file_actions_addopen",
        )
        ProcessConfig.IOOptions.Redirected -> {
            requireNotNull(pipe)
            addPipeAction(actions, pipe.source.fd.toInt(), pipe.sink.fd.toInt(), STDIN_FILENO)
        }
    }
    logger.d { "input action configured: option=$option, expected=true" }
    logger.v { "exit addInputAction: option=$option" }
}

private fun addOutputAction(actions: CPointer<posix_spawn_file_actions_t>, option: ProcessConfig.IOOptions, pipe: IPCAnonymousPipe?, target: Int) {
    logger.v { "enter addOutputAction: option=$option, target=$target" }
    when (option) {
        ProcessConfig.IOOptions.Inherited -> logger.v { "output inherited; no action added: target=$target" }
        ProcessConfig.IOOptions.None -> checkSpawn(
            posix_spawn_file_actions_addopen(actions, target, "/dev/null", O_WRONLY, 0u),
            "posix_spawn_file_actions_addopen",
        )
        ProcessConfig.IOOptions.Redirected -> {
            requireNotNull(pipe)
            addPipeAction(actions, pipe.sink.fd.toInt(), pipe.source.fd.toInt(), target)
        }
    }
    logger.d { "output action configured: option=$option, target=$target, expected=true" }
    logger.v { "exit addOutputAction: option=$option, target=$target" }
}

private fun addPipeAction(actions: CPointer<posix_spawn_file_actions_t>, childEnd: Int, parentEnd: Int, target: Int) {
    logger.v { "enter addPipeAction: childEnd=$childEnd, parentEnd=$parentEnd, target=$target" }
    checkSpawn(posix_spawn_file_actions_addclose(actions, parentEnd), "posix_spawn_file_actions_addclose")
    if (childEnd != target) {
        logger.v { "pipe child end differs from target; adding dup2 and close: childEnd=$childEnd, target=$target" }
        checkSpawn(posix_spawn_file_actions_adddup2(actions, childEnd, target), "posix_spawn_file_actions_adddup2")
        checkSpawn(posix_spawn_file_actions_addclose(actions, childEnd), "posix_spawn_file_actions_addclose")
    } else {
        logger.v { "pipe child end already matches target; dup2 skipped: target=$target" }
    }
    logger.d { "pipe action configured: childEnd=$childEnd, parentEnd=$parentEnd, target=$target, expected=true" }
    logger.v { "exit addPipeAction: target=$target" }
}

/** Gives the child Windows HANDLE_LIST-style descriptor inheritance semantics. */
private fun addCloseActionsForUninheritedDescriptors(
    actions: CPointer<posix_spawn_file_actions_t>,
    inheritedFD: Set<ULong>,
    stdin: IPCAnonymousPipe?,
    stdout: IPCAnonymousPipe?,
    stderr: IPCAnonymousPipe?,
) {
    logger.v { "enter addCloseActionsForUninheritedDescriptors: inheritedFD=${inheritedFD.size}" }
    val inherited = inheritedFD.mapTo(mutableSetOf()) { fd ->
        val descriptor = fd.toInt()
        if (descriptor < 0 || descriptor.toULong() != fd) {
            logger.e { "invalid inherited file descriptor: fd=$fd" }
        }
        require(descriptor >= 0 && descriptor.toULong() == fd) { "invalid file descriptor: $fd" }
        descriptor
    }
    inherited += setOf(STDIN_FILENO, STDOUT_FILENO, STDERR_FILENO)
    // The preceding pipe actions consume and close these descriptors.
    val pipeDescriptors = listOfNotNull(stdin, stdout, stderr)
        .flatMap { pipe -> listOf(pipe.source.fd.toInt(), pipe.sink.fd.toInt()) }
    openFileDescriptors()
        .asSequence()
        // FD_CLOEXEC already provides the same non-inheritance guarantee without
        // a child close action. Avoid actions for descriptors another coroutine
        // can close between this snapshot and posix_spawn.
        .filter { descriptor ->
            descriptor !in inherited &&
                descriptor !in pipeDescriptors &&
                fcntl(descriptor, F_GETFD).let { flags -> flags >= 0 && flags and FD_CLOEXEC == 0 }
        }
        .forEach { descriptor ->
            logger.v { "adding close action for uninherited descriptor: descriptor=$descriptor" }
            checkSpawn(
                posix_spawn_file_actions_addclose(actions, descriptor),
                "posix_spawn_file_actions_addclose",
            )
        }
    logger.d { "close actions configured: inherited=${inherited.size}, pipeDescriptors=${pipeDescriptors.size}, expected=true" }
    logger.v { "exit addCloseActionsForUninheritedDescriptors" }
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
    logger.v { "enter checkSpawn: operation=$operation, result=$result" }
    if (result != 0) {
        logger.e { "$operation failed: errno=$result" }
        error("$operation failed: errno $result")
    }
    logger.d { "$operation completed: expected=true" }
    logger.v { "exit checkSpawn: operation=$operation" }
}

private inline fun <T> withInheritedFileDescriptors(fds: Set<ULong>, block: () -> T): T {
    logger.v { "enter withInheritedFileDescriptors: count=${fds.size}" }
    val originalFlags = fds.associate { fd ->
        val descriptor = fd.toInt()
        if (descriptor < 0 || descriptor.toULong() != fd) {
            logger.e { "invalid inherited file descriptor: fd=$fd" }
        }
        require(descriptor >= 0 && descriptor.toULong() == fd) { "invalid file descriptor: $fd" }
        val flags = fcntl(descriptor, F_GETFD)
        if (flags < 0) {
            logger.e { "fcntl(F_GETFD) failed before spawn: descriptor=$descriptor, errno=$errno" }
        }
        check(flags >= 0) { "fcntl(F_GETFD) failed for fd $descriptor: errno $errno" }
        descriptor to flags
    }
    val closeOnExecDescriptors = originalFlags.filterValues { it and FD_CLOEXEC != 0 }

    try {
        closeOnExecDescriptors.forEach { (descriptor, flags) ->
            logger.v { "clearing FD_CLOEXEC before spawn: descriptor=$descriptor" }
            check(fcntl(descriptor, F_SETFD, flags and FD_CLOEXEC.inv()) == 0) {
                "fcntl(F_SETFD) failed for fd $descriptor: errno $errno"
            }
        }
        val result = block()
        logger.d { "block executed with inherited descriptors: temporarilyOpened=${closeOnExecDescriptors.size}, expected=true" }
        logger.v { "exit withInheritedFileDescriptors via success" }
        return result
    } finally {
        closeOnExecDescriptors.forEach { (descriptor, flags) ->
            logger.v { "restoring FD_CLOEXEC after spawn: descriptor=$descriptor" }
            if (fcntl(descriptor, F_SETFD, flags) != 0) {
                logger.e { "failed restoring fd flags: descriptor=$descriptor, errno=$errno" }
                error("fcntl(F_SETFD) failed while restoring fd $descriptor: errno $errno")
            }
        }
        logger.d { "restored inherited fd flags: count=${closeOnExecDescriptors.size}, expected=true" }
    }
}

private fun closeAllEnds(stdin: IPCAnonymousPipe?, stdout: IPCAnonymousPipe?, stderr: IPCAnonymousPipe?) {
    logger.v { "enter closeAllEnds: stdin=${stdin != null}, stdout=${stdout != null}, stderr=${stderr != null}" }
    stdin?.sink?.close(); stdin?.source?.close()
    stdout?.sink?.close(); stdout?.source?.close()
    stderr?.sink?.close(); stderr?.source?.close()
    logger.d { "closed all pipe ends: expected=true" }
    logger.v { "exit closeAllEnds" }
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
        logger.v { "enter await: pid=$pidValue" }
        logger.i { "waiting for process: pid=$pidValue" }
        memScoped {
            val status = alloc<IntVar>()
            while (true) {
                logger.v { "waitpid enter: pid=$pidValue" }
                val result = waitpid(pidValue.toInt(), status.ptr, 0)
                if (result >= 0) {
                    logger.v { "waitpid exit successfully: pid=$pidValue, result=$result, status=${status.value}" }
                    val exitStatus = exitStatus(status.value)
                    logger.i { "process ended: pid=$pidValue, status=$exitStatus" }
                    logger.v { "exit await: pid=$pidValue" }
                    return@memScoped exitStatus
                }
                if (errno != EINTR) {
                    logger.e { "waitpid failed: pid=$pidValue, errno=$errno" }
                    error("waitpid failed: errno $errno")
                }
                logger.v { "waitpid interrupted; retrying: pid=$pidValue" }
            }
            error("unreachable")
        }
    }

    override fun shutdown(): Boolean {
        logger.v { "enter shutdown: pid=$pidValue" }
        val result = kill(pidValue.toInt(), SIGTERM) == 0
        if (result) logger.i { "process shutdown requested: pid=$pidValue" }
        else logger.w { "process shutdown request failed without throwing: pid=$pidValue, errno=$errno" }
        logger.v { "exit shutdown: pid=$pidValue, result=$result" }
        return result
    }

    override fun kill(): Boolean {
        logger.v { "enter kill: pid=$pidValue" }
        val result = kill(pidValue.toInt(), SIGKILL) == 0
        if (result) logger.i { "process kill requested: pid=$pidValue" }
        else logger.w { "process kill request failed without throwing: pid=$pidValue, errno=$errno" }
        logger.v { "exit kill: pid=$pidValue, result=$result" }
        return result
    }

    private fun exitStatus(status: Int): Process.ExitStatus {
        logger.v { "enter exitStatus: pid=$pidValue, rawStatus=$status" }
        val result = if ((status and 0x7f) == 0) {
            logger.v { "process exited normally: pid=$pidValue" }
            Process.ExitStatus.Result((status ushr 8) and 0xff)
        } else {
            logger.v { "process killed by signal: pid=$pidValue" }
            Process.ExitStatus.Killed
        }
        logger.d { "resolved exit status: pid=$pidValue, status=$result, expected=true" }
        logger.v { "exit exitStatus: pid=$pidValue" }
        return result
    }
}

private fun processWithRedirectedIO(process: UnixProcess): Process {
    logger.v { "enter processWithRedirectedIO: pid=${process.pid}, stdin=${process.stdinValue != null}, stdout=${process.stdoutValue != null}, stderr=${process.stderrValue != null}" }
    val result = when {
        process.stdinValue != null && process.stdoutValue != null && process.stderrValue != null -> {
            logger.v { "wrapping process with stdin/stdout/stderr redirection: pid=${process.pid}" }
            object : Process by process, InheritedStdIn, InheritedStdOut, InheritedStdErr {
                override val stdin get() = process.stdinValue
                override val stdout get() = process.stdoutValue
                override val stderr get() = process.stderrValue
            }
        }
        process.stdinValue != null && process.stdoutValue != null -> {
            logger.v { "wrapping process with stdin/stdout redirection: pid=${process.pid}" }
            object : Process by process, InheritedStdIn, InheritedStdOut {
                override val stdin get() = process.stdinValue
                override val stdout get() = process.stdoutValue
            }
        }
        process.stdinValue != null && process.stderrValue != null -> {
            logger.v { "wrapping process with stdin/stderr redirection: pid=${process.pid}" }
            object : Process by process, InheritedStdIn, InheritedStdErr {
                override val stdin get() = process.stdinValue
                override val stderr get() = process.stderrValue
            }
        }
        process.stdoutValue != null && process.stderrValue != null -> {
            logger.v { "wrapping process with stdout/stderr redirection: pid=${process.pid}" }
            object : Process by process, InheritedStdOut, InheritedStdErr {
                override val stdout get() = process.stdoutValue
                override val stderr get() = process.stderrValue
            }
        }
        process.stdinValue != null -> {
            logger.v { "wrapping process with stdin redirection: pid=${process.pid}" }
            object : Process by process, InheritedStdIn { override val stdin get() = process.stdinValue }
        }
        process.stdoutValue != null -> {
            logger.v { "wrapping process with stdout redirection: pid=${process.pid}" }
            object : Process by process, InheritedStdOut { override val stdout get() = process.stdoutValue }
        }
        process.stderrValue != null -> {
            logger.v { "wrapping process with stderr redirection: pid=${process.pid}" }
            object : Process by process, InheritedStdErr { override val stderr get() = process.stderrValue }
        }
        else -> {
            logger.v { "no redirected IO wrapper needed: pid=${process.pid}" }
            process
        }
    }
    logger.d { "process IO wrapper resolved: pid=${process.pid}, expected=true" }
    logger.v { "exit processWithRedirectedIO: pid=${process.pid}" }
    return result
}
