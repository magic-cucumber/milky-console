package top.kagg886.saltify.console.util.dlloader

import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import co.touchlab.kermit.Logger
import platform.posix.RTLD_LAZY
import platform.posix.dlclose
import platform.posix.dlerror
import platform.posix.dlopen
import platform.posix.dlsym

private val logger = Logger.withTag("UnixDLLoader")

@OptIn(ExperimentalForeignApi::class)
actual class DLLoader actual constructor(path: String) : AutoCloseable {
    private var handle: COpaquePointer? = loadLibrary(path)

    @Throws(NoSuchElementException::class, IllegalStateException::class)
    actual fun <T : Function<*>> findSymbol(name: String): CPointer<CFunction<T>> {
        logger.v { "enter findSymbol: name=$name, closed=${handle == null}" }
        val currentHandle = handle
        if (currentHandle == null) {
            logger.e { "findSymbol failed before lookup; library is closed: name=$name" }
            throw IllegalStateException("The library has been closed")
        }

        logger.v { "clear dlerror before dlsym: name=$name" }
        dlerror()

        logger.v { "dlsym enter: name=$name, handle=$currentHandle" }
        val symbol = dlsym(currentHandle, name)
        val error = dlerror()
        if (error != null) {
            val message = error.toKString()
            logger.e { "dlsym failed; symbol unavailable: name=$name, error=$message" }
            throw NoSuchElementException("Unable to find '$name': $message")
        }
        if (symbol == null) {
            logger.e { "dlsym returned null without dlerror; symbol result is unusable: name=$name" }
            throw NoSuchElementException("Unable to find '$name': unknown dynamic loader error")
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
        logger.v { "dlclose enter: handle=$currentHandle" }
        if (dlclose(currentHandle) != 0) {
            val error = dlError()
            logger.e { "dlclose failed; library handle already detached: handle=$currentHandle, error=$error" }
            throw IllegalStateException("Unable to unload library: $error")
        }

        logger.d { "library unload completed: handle=$currentHandle, expected=true" }
        logger.i { "unloaded dynamic library: handle=$currentHandle" }
        logger.v { "exit close successfully: handle=$currentHandle" }
    }

    private fun loadLibrary(path: String): COpaquePointer {
        logger.v { "enter loadLibrary: path=$path" }
        logger.v { "dlopen enter: path=$path, mode=RTLD_LAZY" }
        val loaded = dlopen(path, RTLD_LAZY)
        if (loaded == null) {
            val error = dlError()
            logger.e { "dlopen failed; dynamic library not loaded: path=$path, error=$error" }
            throw IllegalArgumentException("Unable to load '$path': $error")
        }

        logger.d { "library load completed: path=$path, handle=$loaded, expected=true" }
        logger.i { "loaded dynamic library: path=$path, handle=$loaded" }
        logger.v { "exit loadLibrary successfully: path=$path, handle=$loaded" }
        return loaded
    }

    private fun dlError(): String {
        logger.v { "enter dlError" }
        val message = dlerror()?.toKString() ?: "unknown dynamic loader error"
        logger.d { "resolved dynamic loader error: message=$message" }
        logger.v { "exit dlError" }
        return message
    }
}
