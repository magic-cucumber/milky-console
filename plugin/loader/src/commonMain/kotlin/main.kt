import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.ulong
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Job
import okio.buffer
import top.kagg886.saltify.console.util.dlloader.DLLoader
import top.kagg886.saltify.console.util.pipe.Pipe

/**
 * ================================================
 * Author:     886kagg
 * Created on: 2026/7/13 22:04
 * ================================================
 */

fun main(args: Array<String>) = LoaderCommand().main(args)
class LoaderCommand : CliktCommand() {
    private val fdSendable by option("--fd-sendable").ulong().required()
    private val fdReceivable by option("--fd-receivable").ulong().required()
    private val dynamicLibraryPath by option("--dynamic-library-path").required()

    @OptIn(ExperimentalForeignApi::class)
    override fun run() {
        val sendPipe = Pipe.fromSendablePipe(fdSendable)
        val receivePipe = Pipe.fromReceivablePipe(fdReceivable)
        val dlLoader = DLLoader(dynamicLibraryPath)
    }
}
