package top.kagg886.saltify.console.util.dlloader

import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import platform.posix.RTLD_LAZY
import platform.posix.dlclose
import platform.posix.dlerror
import platform.posix.dlopen
import platform.posix.dlsym

@OptIn(ExperimentalForeignApi::class)
actual class DLLoader actual constructor(path: String) : AutoCloseable {
    private var handle: COpaquePointer? = dlopen(path, RTLD_LAZY)
        ?: throw IllegalArgumentException("Unable to load '$path': ${dlError()}")

    actual fun <T : Function<*>> findSymbol(name: String): CPointer<CFunction<T>> {
        val currentHandle = checkNotNull(handle) { "The library has been closed" }
        dlerror()
        val symbol = dlsym(currentHandle, name)
        val error = dlerror()
        if (error != null) {
            throw NoSuchElementException("Unable to find '$name': ${error.toKString()}")
        }
        return checkNotNull(symbol).reinterpret()
    }

    override fun close() {
        val currentHandle = handle ?: return
        handle = null
        check(dlclose(currentHandle) == 0) { "Unable to unload library: ${dlError()}" }
    }

    private fun dlError(): String = dlerror()?.toKString() ?: "unknown dynamic loader error"
}
