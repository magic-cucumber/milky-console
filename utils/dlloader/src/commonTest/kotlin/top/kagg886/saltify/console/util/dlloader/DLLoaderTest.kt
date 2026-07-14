package top.kagg886.saltify.console.util.dlloader

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.invoke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalForeignApi::class)
class DLLoaderTest {
    @Test
    fun findsAndInvokesNoArgumentFunction() {
        DLLoader(nativeTestLibraryPath()).use { loader ->
            val testFunction = loader.findSymbol<() -> Int>("dlloader_test_return_42")

            assertEquals(42, testFunction.invoke())
        }
    }

    @Test
    fun findsAndInvokesFunctionWithArguments() {
        DLLoader(nativeTestLibraryPath()).use { loader ->
            val testFunction = loader.findSymbol<(Int, Int) -> Int>("dlloader_test_add")

            assertEquals(42, testFunction.invoke(19, 23))
        }
    }

    @Test
    fun rejectsMissingSymbol() {
        DLLoader(nativeTestLibraryPath()).use { loader ->
            assertFailsWith<NoSuchElementException> {
                loader.findSymbol<() -> Int>("dlloader_test_missing")
            }
        }
    }

    @Test
    fun rejectsLookupAfterClose() {
        val loader = DLLoader(nativeTestLibraryPath())
        loader.close()

        assertFailsWith<IllegalStateException> {
            loader.findSymbol<() -> Int>("dlloader_test_return_42")
        }
    }
}

internal expect fun nativeTestLibraryPath(): String
