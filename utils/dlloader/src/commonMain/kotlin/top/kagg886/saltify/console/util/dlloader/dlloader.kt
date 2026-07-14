package top.kagg886.saltify.console.util.dlloader

import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
expect class DLLoader(path: String) : AutoCloseable {
    @Throws(NoSuchElementException::class, IllegalStateException::class)
    fun <T : Function<*>> findSymbol(name: String): CPointer<CFunction<T>>
}
