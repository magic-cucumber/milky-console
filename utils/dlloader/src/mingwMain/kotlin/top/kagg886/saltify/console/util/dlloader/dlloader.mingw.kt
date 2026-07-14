package top.kagg886.saltify.console.util.dlloader

import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import platform.windows.*

@OptIn(ExperimentalForeignApi::class)
actual class DLLoader actual constructor(path: String) : AutoCloseable {
    private var handle: HMODULE? = LoadLibraryW(path)
        ?: throw IllegalArgumentException("Unable to load '$path' (Win32 error ${GetLastError()})")

    @Throws(NoSuchElementException::class, IllegalStateException::class)
    actual fun <T : Function<*>> findSymbol(name: String): CPointer<CFunction<T>> {
        val currentHandle = checkNotNull(handle) { "The library has been closed" }
        return GetProcAddress(currentHandle, name)?.reinterpret()
            ?: throw NoSuchElementException("Unable to find '$name' (Win32 error ${GetLastError()})")
    }

    override fun close() {
        val currentHandle = handle ?: return
        handle = null
        check(FreeLibrary(currentHandle) != 0) {
            "Unable to unload library (Win32 error ${GetLastError()})"
        }
    }
}
