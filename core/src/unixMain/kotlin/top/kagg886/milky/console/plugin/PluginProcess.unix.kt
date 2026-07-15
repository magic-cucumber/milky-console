@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.experimental.ExperimentalNativeApi::class,
    kotlin.native.SymbolNameIsInternal::class,
)

package top.kagg886.milky.console.plugin

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.Path
import platform.posix.close
import platform.posix.dlsym
import platform.posix.pipe
import platform.posix.waitpid
import top.kagg886.saltify.console.util.pipe.IPCAnonymousPipe
import top.kagg886.saltify.console.util.pipe.IPCAnonymousPipeSink
import top.kagg886.saltify.console.util.pipe.IPCAnonymousPipeSource
import top.kagg886.saltify.console.util.pipe.fromSink
import top.kagg886.saltify.console.util.pipe.fromSource

internal actual object PluginPlatform {
    actual val dynamicLibraryFileName: String = when {
        kotlin.native.Platform.osFamily.name == "MACOSX" -> "macos-aarch64.dylib"
        else -> "linux-x86_64.so"
    }
    actual val loaderExecutableFileName: String = "loader.kexe"

    actual fun startLoader(executable: Path, dynamicLibrary: Path): PluginProcess = memScoped {
        val hostToChild = allocArray<IntVar>(2)
        val childToHost = allocArray<IntVar>(2)
        check(pipe(hostToChild) == 0) { "Unable to create host-to-child pipe" }
        if (pipe(childToHost) != 0) {
            close(hostToChild[0])
            close(hostToChild[1])
            error("Unable to create child-to-host pipe")
        }

        // Darwin stores a pointer here; glibc uses an 80-byte structure. Ten
        // aligned machine words safely provide the required storage on both
        // supported 64-bit Unix targets.
        val fileActions = allocArray<LongVar>(10)
        val initResult = posixSpawnFileActionsInit(fileActions.reinterpret())
        if (initResult != 0) {
            close(hostToChild[0])
            close(hostToChild[1])
            close(childToHost[0])
            close(childToHost[1])
            error("Unable to initialize plugin loader file actions (error $initResult)")
        }

        try {
            val closeHostSinkResult = posixSpawnFileActionsAddClose(fileActions.reinterpret(), hostToChild[1])
            val closeHostSourceResult = posixSpawnFileActionsAddClose(fileActions.reinterpret(), childToHost[0])
            if (closeHostSinkResult != 0 || closeHostSourceResult != 0) {
                close(hostToChild[0])
                close(hostToChild[1])
                close(childToHost[0])
                close(childToHost[1])
                error(
                    "Unable to configure plugin loader file actions " +
                        "(errors $closeHostSinkResult, $closeHostSourceResult)",
                )
            }

            val arguments = listOf(
                executable.toString(),
                "--fd-sendable=${hostToChild[0].toULong()}",
                "--fd-receivable=${childToHost[1].toULong()}",
                "--dynamic-library-path=$dynamicLibrary",
            )
            val argv = allocArray<CPointerVar<ByteVar>>(arguments.size + 1)
            arguments.forEachIndexed { index, argument -> argv[index] = argument.cstr.ptr }
            argv[arguments.size] = null

            val pid = alloc<IntVar>()
            val spawnResult = posixSpawn(
                pid.ptr,
                executable.toString().cstr.ptr,
                fileActions.reinterpret(),
                null,
                argv,
                currentProcessEnvironment(),
            )
            if (spawnResult != 0) {
                close(hostToChild[0])
                close(hostToChild[1])
                close(childToHost[0])
                close(childToHost[1])
                error("Unable to spawn plugin loader (error $spawnResult)")
            }

            // The child keeps hostToChild[0] and childToHost[1]. The parent must
            // release its copies immediately so EOF and broken-pipe propagation
            // reflect the lifetime of the actual peer.
            close(hostToChild[0])
            close(childToHost[1])
            UnixPluginProcess(
                pid = pid.value,
                source = IPCAnonymousPipe.fromSource(childToHost[0].toULong()),
                sink = IPCAnonymousPipe.fromSink(hostToChild[1].toULong()),
            )
        } finally {
            posixSpawnFileActionsDestroy(fileActions.reinterpret())
        }
    }
}

@kotlin.native.SymbolName("posix_spawn")
private external fun posixSpawn(
    pid: CPointer<IntVar>,
    path: CPointer<ByteVar>,
    fileActions: COpaquePointer?,
    attributes: COpaquePointer?,
    arguments: CPointer<CPointerVar<ByteVar>>,
    environment: CPointer<CPointerVar<ByteVar>>,
): Int

@kotlin.native.SymbolName("posix_spawn_file_actions_init")
private external fun posixSpawnFileActionsInit(fileActions: COpaquePointer): Int

@kotlin.native.SymbolName("posix_spawn_file_actions_addclose")
private external fun posixSpawnFileActionsAddClose(fileActions: COpaquePointer, descriptor: Int): Int

@kotlin.native.SymbolName("posix_spawn_file_actions_destroy")
private external fun posixSpawnFileActionsDestroy(fileActions: COpaquePointer): Int

private fun currentProcessEnvironment(): CPointer<CPointerVar<ByteVar>> {
    if (kotlin.native.Platform.osFamily.name == "MACOSX") {
        val defaultHandle = checkNotNull((-2L).toCPointer<CPointed>())
        val symbol = checkNotNull(dlsym(defaultHandle, "_NSGetEnviron")) {
            "Unable to resolve _NSGetEnviron"
        }
        val getEnvironment = symbol.reinterpret<
            CFunction<() -> CPointer<CPointerVar<CPointerVar<ByteVar>>>>
            >()
        return checkNotNull(getEnvironment().pointed.value) { "Unable to access the process environment" }
    }

    val symbol = checkNotNull(dlsym(null, "__environ")) { "Unable to resolve __environ" }
    return checkNotNull(symbol.reinterpret<CPointerVar<CPointerVar<ByteVar>>>().pointed.value) {
        "Unable to access the process environment"
    }
}

private class UnixPluginProcess(
    private val pid: Int,
    override val source: IPCAnonymousPipeSource,
    override val sink: IPCAnonymousPipeSink,
) : PluginProcess {
    override suspend fun awaitExit(): Int = withContext(Dispatchers.IO) {
        memScoped {
            val status = alloc<IntVar>()
            check(waitpid(pid, status.ptr, 0) == pid) { "Unable to wait for plugin loader $pid" }
            val raw = status.value
            if (raw and 0x7f == 0) (raw shr 8) and 0xff else 128 + (raw and 0x7f)
        }
    }

    override fun close() = Unit
}
