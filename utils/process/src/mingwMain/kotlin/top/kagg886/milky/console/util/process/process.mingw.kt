@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package top.kagg886.milky.console.util.process

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.value
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import platform.windows.*
import top.kagg886.milky.console.util.pipe.IPCAnonymousPipe
import top.kagg886.milky.console.util.pipe.IPCAnonymousPipeSink
import top.kagg886.milky.console.util.pipe.IPCAnonymousPipeSource
import top.kagg886.milky.console.util.pipe.create
import kotlin.text.buildString
import kotlin.text.forEach
import kotlin.text.isNotEmpty
import kotlin.text.none
import kotlin.text.repeat

actual fun Process.Companion.create(config: ProcessConfig): Process {
    logger.v { "enter create: executable=${config.executable}, arguments=${config.arguments.size}, stdin=${config.stdin}, stdout=${config.stdout}, stderr=${config.stderr}, inheritedFD=${config.inheritedFD.size}" }
    val stdin = config.stdin.takeIf { it == ProcessConfig.IOOptions.Redirected }?.let { IPCAnonymousPipe.create() }
    val stdout = config.stdout.takeIf { it == ProcessConfig.IOOptions.Redirected }?.let { IPCAnonymousPipe.create() }
    val stderr = config.stderr.takeIf { it == ProcessConfig.IOOptions.Redirected }?.let { IPCAnonymousPipe.create() }
    logger.d { "created requested redirected pipes: stdin=${stdin != null}, stdout=${stdout != null}, stderr=${stderr != null}" }

    return try {
        memScoped {
            logger.v { "enter Windows CreateProcess scope: executable=${config.executable}" }
            val inheritedHandles = mutableListOf<HANDLE>()
            val ownedChildHandles = mutableListOf<HANDLE>()
            val startup = alloc<STARTUPINFOA>()
            startup.cb = kotlinx.cinterop.sizeOf<STARTUPINFOA>().toUInt()
            startup.dwFlags = STARTF_USESTDHANDLES.toUInt()
            startup.hStdInput = standardInput(config.stdin, stdin, inheritedHandles, ownedChildHandles)
            startup.hStdOutput =
                standardOutput(config.stdout, stdout, STD_OUTPUT_HANDLE, inheritedHandles, ownedChildHandles)
            startup.hStdError =
                standardOutput(config.stderr, stderr, STD_ERROR_HANDLE, inheritedHandles, ownedChildHandles)
            logger.d { "configured standard handles: inherited=${inheritedHandles.size}, ownedChild=${ownedChildHandles.size}, expected=true" }

            config.inheritedFD.forEach { fd ->
                logger.v { "enter inherited fd setup: fd=$fd" }
                val handle = fd.toLong().toCPointer<ByteVar>() ?: error("null inherited handle")
                makeInheritable(handle)
                inheritedHandles += handle
                logger.d { "inherited fd setup completed: fd=$fd, inheritedHandles=${inheritedHandles.size}, expected=true" }
                logger.v { "exit inherited fd setup: fd=$fd" }
            }

            val processInfo = alloc<PROCESS_INFORMATION>()
            val commandLine = commandLine(config.executable, config.arguments)
            val environment = config.environment.takeIf { it.isNotEmpty() }
                ?.entries
                ?.joinToString(separator = "\u0000", postfix = "\u0000") { (name, value) -> "$name=$value" }
            logger.d { "prepared process launch data: commandLine=$commandLine, customEnvironment=${environment != null}, expected=true" }
            logger.i { "starting process: executable=${config.executable}, arguments=${config.arguments.size}" }
            val created = CreateProcessA(
                lpApplicationName = config.executable,
                lpCommandLine = commandLine.cstr.getPointer(this),
                lpProcessAttributes = null,
                lpThreadAttributes = null,
                bInheritHandles = 1,
                dwCreationFlags = 0u,
                lpEnvironment = environment?.cstr?.getPointer(this),
                lpCurrentDirectory = null,
                lpStartupInfo = startup.ptr,
                lpProcessInformation = processInfo.ptr,
            )
            inheritedHandles.forEach { makeInheritable(it.reinterpret()) }
            ownedChildHandles.forEach { CloseHandle(it) }
            logger.d { "restored/closed launch handles: inherited=${inheritedHandles.size}, ownedChild=${ownedChildHandles.size}, expected=true" }
            if (created == 0) {
                val error = GetLastError()
                logger.e { "CreateProcessA failed; process not started: executable=${config.executable}, error=$error" }
                error("CreateProcessA failed: Windows error $error")
            }
            logger.i { "process started: pid=${processInfo.dwProcessId}, executable=${config.executable}" }

            CloseHandle(processInfo.hThread)
            stdin?.source?.close()
            stdout?.sink?.close()
            stderr?.sink?.close()
            logger.d { "closed parent-owned child pipe ends after start: stdin=${stdin != null}, stdout=${stdout != null}, stderr=${stderr != null}, expected=true" }
            processWithRedirectedIO(
                WindowsProcess(
                    processInfo.hProcess!!,
                    processInfo.dwProcessId.toLong(),
                    config.context,
                    stdin?.sink,
                    stdout?.source,
                    stderr?.source
                ),
            )
        }
    } catch (cause: Throwable) {
        logger.e(cause) { "process create failed; closing pipe ends: executable=${config.executable}" }
        closeAllEnds(stdin, stdout, stderr)
        throw cause
    }
}

private fun standardInput(
    option: ProcessConfig.IOOptions,
    pipe: IPCAnonymousPipe?,
    inheritedHandles: MutableList<HANDLE>,
    ownedHandles: MutableList<HANDLE>,
): HANDLE = when (option) {
    ProcessConfig.IOOptions.Inherited -> {
        logger.v { "standard input inherited branch selected" }
        inheritedStdHandle(STD_INPUT_HANDLE, inheritedHandles)
    }
    ProcessConfig.IOOptions.None -> {
        logger.v { "standard input NUL branch selected" }
        nullHandle(GENERIC_READ, inheritedHandles, ownedHandles)
    }
    ProcessConfig.IOOptions.Redirected -> {
        logger.v { "standard input redirected branch selected" }
        requireNotNull(pipe).source.fd.toHandle()
            .also { makeInheritable(it); inheritedHandles += it; logger.d { "configured redirected stdin handle: fd=${pipe.source.fd}, expected=true" } }
    }
}

private fun standardOutput(
    option: ProcessConfig.IOOptions,
    pipe: IPCAnonymousPipe?,
    standardHandle: UInt,
    inheritedHandles: MutableList<HANDLE>,
    ownedHandles: MutableList<HANDLE>,
): HANDLE = when (option) {
    ProcessConfig.IOOptions.Inherited -> {
        logger.v { "standard output inherited branch selected: target=$standardHandle" }
        inheritedStdHandle(standardHandle, inheritedHandles)
    }
    ProcessConfig.IOOptions.None -> {
        logger.v { "standard output NUL branch selected: target=$standardHandle" }
        nullHandle(GENERIC_WRITE.toUInt(), inheritedHandles, ownedHandles)
    }
    ProcessConfig.IOOptions.Redirected -> {
        logger.v { "standard output redirected branch selected: target=$standardHandle" }
        requireNotNull(pipe).sink.fd.toHandle()
            .also { makeInheritable(it); inheritedHandles += it; logger.d { "configured redirected output handle: target=$standardHandle, fd=${pipe.sink.fd}, expected=true" } }
    }
}

private fun inheritedStdHandle(kind: UInt, inheritedHandles: MutableList<HANDLE>): HANDLE {
    logger.v { "enter inheritedStdHandle: kind=$kind" }
    val handle = GetStdHandle(kind)
    if (handle == null || handle == INVALID_HANDLE_VALUE) {
        val error = GetLastError()
        logger.e { "GetStdHandle failed: kind=$kind, error=$error" }
        error("GetStdHandle failed: Windows error $error")
    }
    makeInheritable(handle)
    inheritedHandles += handle
    logger.d { "configured inherited std handle: kind=$kind, inheritedHandles=${inheritedHandles.size}, expected=true" }
    logger.v { "exit inheritedStdHandle: kind=$kind" }
    return handle
}

private fun nullHandle(access: UInt, inheritedHandles: MutableList<HANDLE>, ownedHandles: MutableList<HANDLE>): HANDLE {
    logger.v { "enter nullHandle: access=$access" }
    val handle = CreateFileW(
        "NUL",
        access,
        (FILE_SHARE_READ or FILE_SHARE_WRITE).toUInt(),
        null,
        OPEN_EXISTING.toUInt(),
        0u,
        null
    )
    if (handle == null || handle == INVALID_HANDLE_VALUE) {
        val error = GetLastError()
        logger.e { "CreateFileW(NUL) failed: access=$access, error=$error" }
        error("CreateFileA(NUL) failed: Windows error $error")
    }
    makeInheritable(handle)
    inheritedHandles += handle
    ownedHandles += handle
    logger.d { "configured NUL handle: access=$access, inherited=${inheritedHandles.size}, owned=${ownedHandles.size}, expected=true" }
    logger.v { "exit nullHandle: access=$access" }
    return handle
}

private fun makeInheritable(handle: HANDLE) {
    logger.v { "enter makeInheritable" }
    if (SetHandleInformation(handle, HANDLE_FLAG_INHERIT.toUInt(), HANDLE_FLAG_INHERIT.toUInt()) == 0) {
        val error = GetLastError()
        logger.e { "SetHandleInformation failed: error=$error" }
        error("SetHandleInformation failed: Windows error $error")
    }
    logger.d { "handle marked inheritable: expected=true" }
    logger.v { "exit makeInheritable" }
}

private fun ULong.toHandle(): HANDLE {
    logger.v { "enter toHandle: fd=$this" }
    val handle = toLong().toCPointer<ByteVar>() ?: run {
        logger.e { "failed to convert fd to handle: fd=$this" }
        error("null handle")
    }
    logger.d { "converted fd to handle: fd=$this, expected=true" }
    logger.v { "exit toHandle: fd=$this" }
    return handle
}

private fun commandLine(executable: String, arguments: List<String>): String {
    logger.v { "enter commandLine: executable=$executable, arguments=${arguments.size}" }
    val line = (listOf(executable) + arguments).joinToString(" ") { windowsQuote(it) }
    logger.d { "command line built: length=${line.length}, expected=${line.isNotBlank()}" }
    logger.v { "exit commandLine" }
    return line
}

private fun windowsQuote(value: String): String {
    logger.v { "enter windowsQuote: length=${value.length}" }
    if (value.isNotEmpty() && value.none { it == ' ' || it == '\t' || it == '"' }) {
        logger.d { "windowsQuote skipped quoting: length=${value.length}, expected=true" }
        logger.v { "exit windowsQuote without quoting" }
        return value
    }
    val quoted = buildString {
        append('"')
        var backslashes = 0
        value.forEach { character ->
            when (character) {
                '\\' -> backslashes++
                '"' -> {
                    append("\\".repeat(backslashes * 2 + 1)); backslashes = 0; append('"')
                }

                else -> {
                    append("\\".repeat(backslashes)); backslashes = 0; append(character)
                }
            }
        }
        append("\\".repeat(backslashes * 2))
        append('"')
    }
    logger.d { "windowsQuote applied quoting: originalLength=${value.length}, quotedLength=${quoted.length}, expected=true" }
    logger.v { "exit windowsQuote with quoting" }
    return quoted
}

private fun closeAllEnds(stdin: IPCAnonymousPipe?, stdout: IPCAnonymousPipe?, stderr: IPCAnonymousPipe?) {
    logger.v { "enter closeAllEnds: stdin=${stdin != null}, stdout=${stdout != null}, stderr=${stderr != null}" }
    stdin?.sink?.close(); stdin?.source?.close()
    stdout?.sink?.close(); stdout?.source?.close()
    stderr?.sink?.close(); stderr?.source?.close()
    logger.d { "closed all pipe ends: expected=true" }
    logger.v { "exit closeAllEnds" }
}

private class WindowsProcess(
    private val handle: HANDLE,
    private val pidValue: Long,
    private val context: kotlin.coroutines.CoroutineContext,
    internal val stdinValue: IPCAnonymousPipeSink?,
    internal val stdoutValue: IPCAnonymousPipeSource?,
    internal val stderrValue: IPCAnonymousPipeSource?,
) : Process {
    private var terminated = false
    override val pid: Long get() = pidValue

    override suspend fun await(): Process.ExitStatus = withContext(context.minusKey(Job)) {
        logger.v { "enter await: pid=$pidValue" }
        logger.i { "waiting for process: pid=$pidValue" }
        while (true) {
            when (WaitForSingleObject(handle, 50u)) {
                WAIT_OBJECT_0 -> {
                    logger.v { "wait finished: pid=$pidValue" }
                    break
                }
                WAIT_TIMEOUT.toUInt() -> {
                    logger.v { "wait timeout tick: pid=$pidValue" }
                    currentCoroutineContext().ensureActive()
                }
                else -> {
                    val error = GetLastError()
                    logger.e { "WaitForSingleObject failed: pid=$pidValue, error=$error" }
                    error("WaitForSingleObject failed: Windows error $error")
                }
            }
        }
        memScoped {
            val exitCode = alloc<UIntVar>()
            if (GetExitCodeProcess(
                    handle,
                    exitCode.ptr
                ) == 0
            ) {
                val error = GetLastError()
                logger.e { "GetExitCodeProcess failed: pid=$pidValue, error=$error" }
                error("GetExitCodeProcess failed: Windows error $error")
            }
            CloseHandle(handle)
            val status = if (terminated) Process.ExitStatus.Killed else Process.ExitStatus.Result(exitCode.value.toInt())
            logger.i { "process ended: pid=$pidValue, status=$status" }
            logger.v { "exit await: pid=$pidValue" }
            status
        }
    }

    override fun shutdown(): Boolean {
        logger.v { "enter shutdown: pid=$pidValue" }
        val result = terminate(0u)
        logger.v { "exit shutdown: pid=$pidValue, result=$result" }
        return result
    }

    override fun kill(): Boolean {
        logger.v { "enter kill: pid=$pidValue" }
        val result = terminate(1u)
        logger.v { "exit kill: pid=$pidValue, result=$result" }
        return result
    }

    private fun terminate(exitCode: UInt): Boolean {
        logger.v { "enter terminate: pid=$pidValue, exitCode=$exitCode" }
        if (TerminateProcess(handle, exitCode) == 0) {
            val error = GetLastError()
            logger.w { "TerminateProcess failed without throwing: pid=$pidValue, exitCode=$exitCode, error=$error" }
            logger.v { "exit terminate: pid=$pidValue, result=false" }
            return false
        }
        terminated = true
        logger.i { "process termination requested: pid=$pidValue, exitCode=$exitCode" }
        logger.v { "exit terminate: pid=$pidValue, result=true" }
        return true
    }
}

private fun processWithRedirectedIO(process: WindowsProcess): Process {
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
            object : Process by process, InheritedStdIn {
                override val stdin get() = process.stdinValue
            }
        }

        process.stdoutValue != null -> {
            logger.v { "wrapping process with stdout redirection: pid=${process.pid}" }
            object : Process by process, InheritedStdOut {
                override val stdout get() = process.stdoutValue
            }
        }

        process.stderrValue != null -> {
            logger.v { "wrapping process with stderr redirection: pid=${process.pid}" }
            object : Process by process, InheritedStdErr {
                override val stderr get() = process.stderrValue
            }
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
