package top.kagg886.milky.console.loader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.kagg886.milky.console.util.protocol.Packet
import top.kagg886.milky.console.util.protocol.isSplit
import top.kagg886.milky.console.util.protocol.merge
import top.kagg886.milky.console.util.protocol.readPacket
import top.kagg886.milky.console.util.protocol.split
import top.kagg886.milky.console.util.protocol.writePacket
import top.kagg886.saltify.console.util.pipe.BrokenPipeException
import top.kagg886.saltify.console.util.pipe.IPCAnonymousPipeSink
import top.kagg886.saltify.console.util.pipe.IPCAnonymousPipeSource
import kotlin.uuid.Uuid

/**
 * Owns the loader's pipe transport.
 *
 * Pipe reads are blocking and run on a dedicated thread. Writes are serialized
 * by a mutex, and fragments from the peer may arrive interleaved or out of order.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
data object LoaderApplication {
    private val mutablePackets = MutableSharedFlow<Packet>(extraBufferCapacity = 64)
    val packets: SharedFlow<Packet> = mutablePackets.asSharedFlow()

    private val terminated = CompletableDeferred<Throwable?>()

    private var started = false
    private var scope: CoroutineScope? = null
    private var receiveDispatcher: CoroutineDispatcher? = null
    private var source: IPCAnonymousPipeSource? = null
    private var sink: IPCAnonymousPipeSink? = null
    private val sendMutex = Mutex()
    private var receiving = false

    fun initialize(source: IPCAnonymousPipeSource, sink: IPCAnonymousPipeSink) {
        check(!started) { "LoaderApplication has already been initialized" }
        started = true
        this.source = source
        this.sink = sink

        val receiveDispatcher = newSingleThreadContext("LoaderApplication-receive")
        val scope = CoroutineScope(SupervisorJob())
        this.receiveDispatcher = receiveDispatcher
        this.scope = scope
    }

    fun startReceiving() {
        check(started) { "LoaderApplication has not been initialized" }
        check(!receiving) { "LoaderApplication receiver has already been started" }
        receiving = true
        val source = checkNotNull(source)
        checkNotNull(scope).launch(checkNotNull(receiveDispatcher)) {
            runWorker { receivePackets(source) }
        }
    }

    fun receiveHandshakePacket(): Packet {
        check(started && !receiving) { "Handshake packet must be read before starting the receiver" }
        return checkNotNull(source).readPacket()
    }

    /** Writes one logical packet and returns only after the pipe has been flushed. */
    suspend fun send(packet: Packet) {
        check(started) { "LoaderApplication has not been initialized" }
        val sink = checkNotNull(sink)
        packet.split().forEach {
            sendMutex.withLock {
                sink.writePacket(it)
                sink.flush()
            }
        }
    }

    /** Suspends until either pipe worker stops, and rethrows its failure. */
    suspend fun awaitTermination() {
        terminated.await()?.let { throw it }
    }

    fun close() {
        if (!started) return
        runCatching { sink?.close() }
        runCatching { source?.close() }
        scope?.cancel()
        (receiveDispatcher as? AutoCloseable)?.close()
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

    private suspend fun runWorker(block: suspend () -> Unit) {
        try {
            block()
            terminated.complete(null)
        } catch (e: CancellationException) {
            terminated.complete(null)
            throw e
        } catch (_: BrokenPipeException) {
            terminated.complete(null)
        } catch (t: Throwable) {
            terminated.complete(t)
        }
    }

}
