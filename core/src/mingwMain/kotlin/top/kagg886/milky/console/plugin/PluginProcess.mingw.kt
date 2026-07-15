@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package top.kagg886.milky.console.plugin

import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toLong
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.IOException
import okio.Path
import platform.windows.CREATE_NO_WINDOW
import platform.windows.CloseHandle
import platform.windows.CreatePipe
import platform.windows.CreateProcessW
import platform.windows.GetExitCodeProcess
import platform.windows.GetLastError
import platform.windows.HANDLE
import platform.windows.HANDLE_FLAG_INHERIT
import platform.windows.PROCESS_INFORMATION
import platform.windows.SECURITY_ATTRIBUTES
import platform.windows.STARTUPINFOW
import platform.windows.SetHandleInformation
import platform.windows.WaitForSingleObject
import platform.windows.INFINITE
import top.kagg886.saltify.console.util.pipe.IPCAnonymousPipe
import top.kagg886.saltify.console.util.pipe.IPCAnonymousPipeSink
import top.kagg886.saltify.console.util.pipe.IPCAnonymousPipeSource
import top.kagg886.saltify.console.util.pipe.fromSink
import top.kagg886.saltify.console.util.pipe.fromSource

internal actual object PluginPlatform {
    actual val dynamicLibraryFileName: String = "windows-x86_64.dll"
    actual val loaderExecutableFileName: String = "loader.exe"

    actual fun startLoader(executable: Path, dynamicLibrary: Path): PluginProcess = memScoped {
        val attributes = alloc<SECURITY_ATTRIBUTES>().apply {
            nLength = sizeOf<SECURITY_ATTRIBUTES>().toUInt()
            lpSecurityDescriptor = null
            bInheritHandle = 1
        }
        val hostToChildRead = alloc<COpaquePointerVar>()
        val hostToChildWrite = alloc<COpaquePointerVar>()
        checkWindows(CreatePipe(hostToChildRead.ptr, hostToChildWrite.ptr, attributes.ptr, 0u), "CreatePipe")
        val childToHostRead = alloc<COpaquePointerVar>()
        val childToHostWrite = alloc<COpaquePointerVar>()
        try {
            checkWindows(CreatePipe(childToHostRead.ptr, childToHostWrite.ptr, attributes.ptr, 0u), "CreatePipe")
            checkWindows(SetHandleInformation(hostToChildWrite.value, HANDLE_FLAG_INHERIT.toUInt(), 0u), "SetHandleInformation")
            checkWindows(SetHandleInformation(childToHostRead.value, HANDLE_FLAG_INHERIT.toUInt(), 0u), "SetHandleInformation")

            val arguments = listOf(
                executable.toString(),
                "--fd-sendable=${hostToChildRead.value.toLong().toULong()}",
                "--fd-receivable=${childToHostWrite.value.toLong().toULong()}",
                "--dynamic-library-path=$dynamicLibrary",
            ).joinToString(" ") { quoteWindowsArgument(it) }
            val startup = alloc<STARTUPINFOW>().apply { cb = sizeOf<STARTUPINFOW>().toUInt() }
            val process = alloc<PROCESS_INFORMATION>()
            checkWindows(
                CreateProcessW(
                    executable.toString(),
                    arguments.wcstr.ptr,
                    null,
                    null,
                    1,
                    CREATE_NO_WINDOW.toUInt(),
                    null,
                    null,
                    startup.ptr,
                    process.ptr,
                ),
                "CreateProcessW",
            )
            CloseHandle(requireNotNull(process.hThread))
            CloseHandle(hostToChildRead.value)
            CloseHandle(childToHostWrite.value)
            WindowsPluginProcess(
                processHandle = requireNotNull(process.hProcess),
                source = IPCAnonymousPipe.fromSource(childToHostRead.value.toLong().toULong()),
                sink = IPCAnonymousPipe.fromSink(hostToChildWrite.value.toLong().toULong()),
            )
        } catch (t: Throwable) {
            CloseHandle(hostToChildRead.value)
            CloseHandle(hostToChildWrite.value)
            CloseHandle(childToHostRead.value)
            CloseHandle(childToHostWrite.value)
            throw t
        }
    }
}

private class WindowsPluginProcess(
    private val processHandle: HANDLE,
    override val source: IPCAnonymousPipeSource,
    override val sink: IPCAnonymousPipeSink,
) : PluginProcess {
    override suspend fun awaitExit(): Int = withContext(Dispatchers.IO) {
        try {
            checkWindows(WaitForSingleObject(processHandle, INFINITE).toInt(), "WaitForSingleObject", expected = 0)
            memScoped {
                val code = alloc<UIntVar>()
                checkWindows(GetExitCodeProcess(processHandle, code.ptr), "GetExitCodeProcess")
                code.value.toInt()
            }
        } finally {
            CloseHandle(processHandle)
        }
    }

    override fun close() = Unit
}

private fun checkWindows(result: Int, operation: String, expected: Int = 1) {
    if (result != expected) throw IOException("$operation failed: Windows error ${GetLastError()}")
}

private fun quoteWindowsArgument(argument: String): String {
    if (argument.none { it.isWhitespace() || it == '"' }) return argument
    val result = StringBuilder("\"")
    var backslashes = 0
    for (character in argument) {
        when (character) {
            '\\' -> backslashes++
            '"' -> {
                repeat(backslashes * 2 + 1) { result.append('\\') }
                result.append('"')
                backslashes = 0
            }
            else -> {
                repeat(backslashes) { result.append('\\') }
                backslashes = 0
                result.append(character)
            }
        }
    }
    repeat(backslashes * 2) { result.append('\\') }
    return result.append('"').toString()
}
