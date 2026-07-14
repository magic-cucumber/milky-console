package top.kagg886.milky.console.loader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import top.kagg886.milky.console.util.protocol.Packet
import top.kagg886.milky.console.util.protocol.isSplit
import top.kagg886.milky.console.util.protocol.merge
import top.kagg886.milky.console.util.protocol.readPacket
import top.kagg886.milky.console.util.protocol.split
import top.kagg886.milky.console.util.protocol.writePacket
import top.kagg886.saltify.console.util.pipe.IPCAnonymousPipeSink
import top.kagg886.saltify.console.util.pipe.IPCAnonymousPipeSource
import kotlin.uuid.Uuid

/**
 * Owns the loader's pipe transport.
 *
 * Pipe reads and writes are blocking, so each direction is isolated on its own
 * thread. Logical packets may be submitted concurrently, and fragments from
 * the peer may arrive interleaved or out of order.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
data object LoaderApplication {
    private val mutablePackets = MutableSharedFlow<Packet>(extraBufferCapacity = 64)
    val packets: SharedFlow<Packet> = mutablePackets.asSharedFlow()

    private val outgoing = Channel<Packet>(Channel.UNLIMITED)
    private val terminated = CompletableDeferred<Throwable?>()

    private var started = false
    private var scope: CoroutineScope? = null
    private var receiveDispatcher: CoroutineDispatcher? = null
    private var sendDispatcher: CoroutineDispatcher? = null
    private var source: IPCAnonymousPipeSource? = null
    private var sink: IPCAnonymousPipeSink? = null

    fun initialize(source: IPCAnonymousPipeSource, sink: IPCAnonymousPipeSink) {
        check(!started) { "LoaderApplication has already been initialized" }
        started = true
        this.source = source
        this.sink = sink

        val receiveDispatcher = newSingleThreadContext("LoaderApplication-receive")
        val sendDispatcher = newSingleThreadContext("LoaderApplication-send")
        val scope = CoroutineScope(SupervisorJob())
        this.receiveDispatcher = receiveDispatcher
        this.sendDispatcher = sendDispatcher
        this.scope = scope

        scope.launch(receiveDispatcher) {
            runWorker { receivePackets(source) }
        }
        scope.launch(sendDispatcher) {
            runWorker { sendPackets(sink) }
        }
    }

    /** Queues one logical packet. Only the dedicated send thread touches the pipe. */
    suspend fun send(packet: Packet) {
        check(started) { "LoaderApplication has not been initialized" }
        outgoing.send(packet)
    }

    /** Suspends until either pipe worker stops, and rethrows its failure. */
    suspend fun awaitTermination() {
        terminated.await()?.let { throw it }
    }

    fun close() {
        if (!started) return
        outgoing.close()
        runCatching { source?.close() }
        runCatching { sink?.close() }
        scope?.cancel()
        (receiveDispatcher as? AutoCloseable)?.close()
        (sendDispatcher as? AutoCloseable)?.close()
        terminated.complete(null)
    }

    private suspend fun receivePackets(source: IPCAnonymousPipeSource) {
        val fragments = mutableMapOf<Uuid, MutableMap<Int, Packet>>()
        while (scope?.isActive == true) {
            val packet = source.readPacket()
            if (!packet.isSplit) {
                mutablePackets.emit(packet)
                continue
            }

            val index = requireNotNull(packet.index)
            val expectedSize = requireNotNull(packet.size)
            val group = fragments.getOrPut(packet.uuid) { mutableMapOf() }
            require(group.values.all { it.size == expectedSize }) {
                "分包 ${packet.uuid} 声明了不一致的总包数"
            }
            require(group.put(index, packet) == null) {
                "分包 ${packet.uuid} 的 index=$index 重复"
            }

            if (group.size == expectedSize) {
                fragments.remove(packet.uuid)
                mutablePackets.emit(group.values.toList().merge())
            }
        }
    }

    private suspend fun sendPackets(sink: IPCAnonymousPipeSink) {
        for (packet in outgoing) {
            packet.split().forEach(sink::writePacket)
            sink.flush()
        }
    }

    private suspend fun runWorker(block: suspend () -> Unit) {
        try {
            block()
            terminated.complete(null)
        } catch (e: CancellationException) {
            terminated.complete(null)
            throw e
        } catch (t: Throwable) {
            terminated.complete(t)
        }
    }
}
