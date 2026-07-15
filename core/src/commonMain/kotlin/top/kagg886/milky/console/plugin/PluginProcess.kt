package top.kagg886.milky.console.plugin

import okio.Path
import top.kagg886.saltify.console.util.pipe.IPCAnonymousPipeSink
import top.kagg886.saltify.console.util.pipe.IPCAnonymousPipeSource

internal interface PluginProcess : AutoCloseable {
    val source: IPCAnonymousPipeSource
    val sink: IPCAnonymousPipeSink
    suspend fun awaitExit(): Int
}

internal expect object PluginPlatform {
    val dynamicLibraryFileName: String
    val loaderExecutableFileName: String

    fun startLoader(executable: Path, dynamicLibrary: Path): PluginProcess
}
