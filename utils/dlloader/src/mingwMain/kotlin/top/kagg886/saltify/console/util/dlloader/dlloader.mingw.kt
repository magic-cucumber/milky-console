package top.kagg886.saltify.console.util.dlloader

import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import platform.windows.*
import co.touchlab.kermit.Logger

private val logger = Logger.withTag("WindowsDLLoader")

@OptIn(ExperimentalForeignApi::class)
actual class DLLoader actual constructor(path: String) : AutoCloseable {
    private var handle: HMODULE? = loadLibrary(path)

    @Throws(NoSuchElementException::class, IllegalStateException::class)
    actual fun <T : Function<*>> findSymbol(name: String): CPointer<CFunction<T>> {
        logger.v { "enter findSymbol: name=$name, closed=${handle == null}" }
        val currentHandle = handle
        if (currentHandle == null) {
            logger.e { "findSymbol failed before lookup; library is closed: name=$name" }
            throw IllegalStateException("The library has been closed")
        }

        logger.v { "GetProcAddress enter: name=$name, handle=$currentHandle" }
        val symbol = GetProcAddress(currentHandle, name)
        if (symbol == null) {
            val error = GetLastError()
            logger.e { "GetProcAddress failed; symbol unavailable: name=$name, error=$error" }
            throw NoSuchElementException("Unable to find '$name' (Win32 error $error)")
        }

        logger.d { "symbol lookup completed: name=$name, found=true, expected=true" }
        logger.i { "resolved library symbol: name=$name" }
        logger.v { "exit findSymbol successfully: name=$name" }
        return symbol.reinterpret()
    }

    override fun close() {
        logger.v { "enter close: closed=${handle == null}" }
        val currentHandle = handle
        if (currentHandle == null) {
            logger.v { "close skipped; library already closed" }
            logger.v { "exit close" }
            return
        }

        handle = null
        logger.v { "FreeLibrary enter: handle=$currentHandle" }
        if (FreeLibrary(currentHandle) == 0) {
            val error = GetLastError()
            logger.e { "FreeLibrary failed; library handle already detached: handle=$currentHandle, error=$error" }
            throw IllegalStateException("Unable to unload library (Win32 error $error)")
        }

        logger.d { "library unload completed: handle=$currentHandle, expected=true" }
        logger.i { "unloaded dynamic library: handle=$currentHandle" }
        logger.v { "exit close successfully: handle=$currentHandle" }
    }

    private fun loadLibrary(path: String): HMODULE {
        logger.v { "enter loadLibrary: path=$path" }
        logger.v { "LoadLibraryW enter: path=$path" }
        val loaded = LoadLibraryW(path)
        if (loaded == null) {
            val error = GetLastError()
            logger.e { "LoadLibraryW failed; dynamic library not loaded: path=$path, error=$error" }
            throw IllegalArgumentException("Unable to load '$path' (Win32 error $error)")
        }

        logger.d { "library load completed: path=$path, handle=$loaded, expected=true" }
        logger.i { "loaded dynamic library: path=$path, handle=$loaded" }
        logger.v { "exit loadLibrary successfully: path=$path, handle=$loaded" }
        return loaded
    }
}
