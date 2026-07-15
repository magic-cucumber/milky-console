import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.ulong
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.invoke
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import top.kagg886.milky.console.loader.LoaderApplication
import top.kagg886.milky.console.protocol.HandShakePacket
import top.kagg886.milky.console.protocol.HandShakePacketResponsePacket
import top.kagg886.milky.console.protocol.HandShakeRequestReadyPacket
import top.kagg886.milky.console.util.readContent
import top.kagg886.milky.console.util.toBuffer
import top.kagg886.milky.console.util.protocol.Packet
import top.kagg886.saltify.console.util.dlloader.DLLoader
import top.kagg886.saltify.console.util.pipe.IPCAnonymousPipe
import top.kagg886.saltify.console.util.pipe.fromSink
import top.kagg886.saltify.console.util.pipe.fromSource

private const val ON_LOAD_SYMBOL = "milky_console_load_plugin_onload"
private const val ON_UNLOAD_SYMBOL = "milky_console_load_plugin_onunload"
private const val ON_MESSAGE_SYMBOL = "milky_console_load_plugin_onmessage"

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
fun main(args: Array<String>) {
    val dispatcher = newSingleThreadContext("Application")
    runBlocking(dispatcher) {
        LoaderCommand().main(args)
    }
    dispatcher.close()
}

class LoaderCommand : SuspendingCliktCommand() {
    private val fdSendable by option("--fd-sendable").ulong().required()
    private val fdReceivable by option("--fd-receivable").ulong().required()
    private val dynamicLibraryPath by option("--dynamic-library-path").required()

    @OptIn(ExperimentalForeignApi::class, ExperimentalSerializationApi::class)
    override suspend fun run() {
        val dynamicLibrary = DLLoader(dynamicLibraryPath)

        val onLoad = dynamicLibrary.findSymbol<(CPointer<ByteVar>?) -> Boolean>(ON_LOAD_SYMBOL)
        val onUnload = dynamicLibrary.findSymbol<() -> Int>(ON_UNLOAD_SYMBOL)
        val onMessage = dynamicLibrary.findSymbol<(CPointer<ByteVar>?) -> Unit>(ON_MESSAGE_SYMBOL)

        var unloadInvoked = false
        try {
            val source = IPCAnonymousPipe.fromSource(fdSendable)
            val sink = IPCAnonymousPipe.fromSink(fdReceivable)
            LoaderApplication.initialize(source, sink)

            coroutineScope {
                LoaderApplication.send(Packet(data = HandShakeRequestReadyPacket.toBuffer()))
                val request = LoaderApplication.receiveHandshakePacket().data.readContent<HandShakePacket>()
                val allow = request.config.usePinned { onLoad.invoke(it.addressOf(0)) }
                LoaderApplication.send(
                    Packet(data = HandShakePacketResponsePacket(allow = allow).toBuffer()),
                )
                if (!allow) {
                    onUnload.invoke()
                    unloadInvoked = true
                    return@coroutineScope
                }

                val incoming = Channel<Packet>(Channel.UNLIMITED)
                val subscription = launch(start = CoroutineStart.UNDISPATCHED) {
                    LoaderApplication.packets.collect { incoming.send(it) }
                }
                LoaderApplication.startReceiving()

                try {
                    val messageDispatcher = launch {
                        for (packet in incoming) {
                            val content = packet.data.readByteArray()
                            withContext(Dispatchers.IO) {
                                content.usePinned { pinned ->
                                    onMessage.invoke(pinned.addressOf(0))
                                }
                            }
                        }
                    }
                    try {
                        LoaderApplication.awaitTermination()
                    } finally {
                        messageDispatcher.cancelAndJoin()
                    }
                } finally {
                    subscription.cancelAndJoin()
                    incoming.close()
                }
            }
        } finally {
            if (!unloadInvoked) {
                onUnload.invoke()
            }
            LoaderApplication.close()
            dynamicLibrary.close()
        }
    }
}
