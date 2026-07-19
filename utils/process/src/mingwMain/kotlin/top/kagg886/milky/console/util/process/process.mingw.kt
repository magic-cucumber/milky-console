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
    val stdin = config.stdin.takeIf { it == ProcessConfig.IOOptions.Redirected }?.let { IPCAnonymousPipe.create() }
    val stdout = config.stdout.takeIf { it == ProcessConfig.IOOptions.Redirected }?.let { IPCAnonymousPipe.create() }
    val stderr = config.stderr.takeIf { it == ProcessConfig.IOOptions.Redirected }?.let { IPCAnonymousPipe.create() }

    return try {
        memScoped {
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

            config.inheritedFD.forEach { fd ->
                val handle = fd.toLong().toCPointer<ByteVar>() ?: error("null inherited handle")
                makeInheritable(handle)
                inheritedHandles += handle
            }

            val processInfo = alloc<PROCESS_INFORMATION>()
            val commandLine = commandLine(config.executable, config.arguments)
            val environment = config.environment.takeIf { it.isNotEmpty() }
                ?.entries
                ?.joinToString(separator = "\u0000", postfix = "\u0000") { (name, value) -> "$name=$value" }
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
            if (created == 0) error("CreateProcessA failed: Windows error ${GetLastError()}")

            CloseHandle(processInfo.hThread)
            stdin?.source?.close()
            stdout?.sink?.close()
            stderr?.sink?.close()
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
    ProcessConfig.IOOptions.Inherited -> inheritedStdHandle(STD_INPUT_HANDLE, inheritedHandles)
    ProcessConfig.IOOptions.None -> nullHandle(GENERIC_READ, inheritedHandles, ownedHandles)
    ProcessConfig.IOOptions.Redirected -> requireNotNull(pipe).source.fd.toHandle()
        .also { makeInheritable(it); inheritedHandles += it }
}

private fun standardOutput(
    option: ProcessConfig.IOOptions,
    pipe: IPCAnonymousPipe?,
    standardHandle: UInt,
    inheritedHandles: MutableList<HANDLE>,
    ownedHandles: MutableList<HANDLE>,
): HANDLE = when (option) {
    ProcessConfig.IOOptions.Inherited -> inheritedStdHandle(standardHandle, inheritedHandles)
    ProcessConfig.IOOptions.None -> nullHandle(GENERIC_WRITE.toUInt(), inheritedHandles, ownedHandles)
    ProcessConfig.IOOptions.Redirected -> requireNotNull(pipe).sink.fd.toHandle()
        .also { makeInheritable(it); inheritedHandles += it }
}

private fun inheritedStdHandle(kind: UInt, inheritedHandles: MutableList<HANDLE>): HANDLE {
    val handle = GetStdHandle(kind)
    if (handle == null || handle == INVALID_HANDLE_VALUE) error("GetStdHandle failed: Windows error ${GetLastError()}")
    makeInheritable(handle)
    inheritedHandles += handle
    return handle
}

private fun nullHandle(access: UInt, inheritedHandles: MutableList<HANDLE>, ownedHandles: MutableList<HANDLE>): HANDLE {
    val handle = CreateFileW(
        "NUL",
        access,
        (FILE_SHARE_READ or FILE_SHARE_WRITE).toUInt(),
        null,
        OPEN_EXISTING.toUInt(),
        0u,
        null
    )
    if (handle == null || handle == INVALID_HANDLE_VALUE) error("CreateFileA(NUL) failed: Windows error ${GetLastError()}")
    makeInheritable(handle)
    inheritedHandles += handle
    ownedHandles += handle
    return handle
}

private fun makeInheritable(handle: HANDLE) {
    if (SetHandleInformation(handle, HANDLE_FLAG_INHERIT.toUInt(), HANDLE_FLAG_INHERIT.toUInt()) == 0) {
        error("SetHandleInformation failed: Windows error ${GetLastError()}")
    }
}

private fun ULong.toHandle(): HANDLE = toLong().toCPointer<ByteVar>() ?: error("null handle")

private fun commandLine(executable: String, arguments: List<String>): String =
    (listOf(executable) + arguments).joinToString(" ") { windowsQuote(it) }

private fun windowsQuote(value: String): String {
    if (value.isNotEmpty() && value.none { it == ' ' || it == '\t' || it == '"' }) return value
    return buildString {
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
}

private fun closeAllEnds(stdin: IPCAnonymousPipe?, stdout: IPCAnonymousPipe?, stderr: IPCAnonymousPipe?) {
    stdin?.sink?.close(); stdin?.source?.close()
    stdout?.sink?.close(); stdout?.source?.close()
    stderr?.sink?.close(); stderr?.source?.close()
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
        while (true) {
            when (WaitForSingleObject(handle, 50u)) {
                WAIT_OBJECT_0 -> break
                WAIT_TIMEOUT.toUInt() -> currentCoroutineContext().ensureActive()
                else -> error("WaitForSingleObject failed: Windows error ${GetLastError()}")
            }
        }
        memScoped {
            val exitCode = alloc<UIntVar>()
            if (GetExitCodeProcess(
                    handle,
                    exitCode.ptr
                ) == 0
            ) error("GetExitCodeProcess failed: Windows error ${GetLastError()}")
            CloseHandle(handle)
            if (terminated) Process.ExitStatus.Killed(signal = null) else Process.ExitStatus.Result(exitCode.value.toInt())
        }
    }

    override fun shutdown(): Boolean = terminate(0u)
    override fun kill(): Boolean = terminate(1u)

    private fun terminate(exitCode: UInt): Boolean {
        if (TerminateProcess(handle, exitCode) == 0) return false
        terminated = true
        return true
    }
}

private fun processWithRedirectedIO(process: WindowsProcess): Process = when {
    process.stdinValue != null && process.stdoutValue != null && process.stderrValue != null -> object :
        Process by process, InheritedStdIn, InheritedStdOut, InheritedStdErr {
        override val stdin get() = process.stdinValue
        override val stdout get() = process.stdoutValue
        override val stderr get() = process.stderrValue
    }

    process.stdinValue != null && process.stdoutValue != null -> object : Process by process, InheritedStdIn,
        InheritedStdOut {
        override val stdin get() = process.stdinValue
        override val stdout get() = process.stdoutValue
    }

    process.stdinValue != null && process.stderrValue != null -> object : Process by process, InheritedStdIn,
        InheritedStdErr {
        override val stdin get() = process.stdinValue
        override val stderr get() = process.stderrValue
    }

    process.stdoutValue != null && process.stderrValue != null -> object : Process by process, InheritedStdOut,
        InheritedStdErr {
        override val stdout get() = process.stdoutValue
        override val stderr get() = process.stderrValue
    }

    process.stdinValue != null -> object : Process by process, InheritedStdIn {
        override val stdin get() = process.stdinValue
    }

    process.stdoutValue != null -> object : Process by process, InheritedStdOut {
        override val stdout get() = process.stdoutValue
    }

    process.stderrValue != null -> object : Process by process, InheritedStdErr {
        override val stderr get() = process.stderrValue
    }

    else -> process
}
