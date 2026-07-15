package top.kagg886.milky.console.plugin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path
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

/** A verified plugin and the host side of its loader connection. */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class Plugin(val base: Path) {
    lateinit var manifest: PluginManifest
        private set

    private lateinit var defaultConfigBytes: ByteArray
    val defaultConfig: ByteArray
        get() = defaultConfigBytes.copyOf()

    lateinit var dynamicLibrary: Path
        private set

    private val _state = MutableStateFlow<State>(State.UnInitialized)
    val state: StateFlow<State> = _state.asStateFlow()

    private val mutablePackets = MutableSharedFlow<Packet>(extraBufferCapacity = 64)

    /** Packets broadcast by this plugin's loader after they are reassembled. */
    val packets: SharedFlow<Packet> = mutablePackets.asSharedFlow()

    private val sendMutex = Mutex()
    private var scope: CoroutineScope? = null
    private var receiveDispatcher: CoroutineDispatcher? = null
    private var process: PluginProcess? = null
    private var receiving = false

    /** Host-readable end of the child-to-host pipe. */
    var source: IPCAnonymousPipeSource? = null
        private set

    /** Host-writable end of the host-to-child pipe. */
    var sink: IPCAnonymousPipeSink? = null
        private set

    internal fun verified(
        manifest: PluginManifest,
        defaultConfig: ByteArray,
        dynamicLibrary: Path,
    ) {
        this.manifest = manifest
        this.defaultConfigBytes = defaultConfig.copyOf()
        this.dynamicLibrary = dynamicLibrary
        transition(State.UnInitialized, State.Verified)
    }

    internal fun handshaking() {
        transition(State.Verified, State.Handshaking)
    }

    internal fun initialized() {
        transition(State.Handshaking, State.Initialized)
    }

    internal fun startReceiving() {
        check(state.value == State.Initialized) { "Plugin ${manifest.id} is not initialized" }
        check(!receiving) { "Plugin ${manifest.id} receiver has already been started" }
        receiving = true
        val source = checkNotNull(source)
        checkNotNull(scope).launch(checkNotNull(receiveDispatcher)) {
            runWorker { receivePackets(source) }
        }
    }

    internal fun receiveHandshakePacket(): Packet {
        check(state.value == State.Handshaking && !receiving) {
            "Handshake packet must be read before starting the receiver"
        }
        return checkNotNull(source).readPacket()
    }

    internal fun attach(connection: PluginProcess) {
        check(state.value == State.Verified) { "Plugin must be verified before attaching its loader" }
        check(source == null && sink == null) { "Plugin transport has already been attached" }

        process = connection
        source = connection.source
        sink = connection.sink

        val receiveDispatcher = newSingleThreadContext("Plugin-${manifest.id}-receive")
        val scope = CoroutineScope(SupervisorJob())
        this.receiveDispatcher = receiveDispatcher
        this.scope = scope

        scope.launch {
            val exitCode = runCatching { connection.awaitExit() }
                .getOrElse {
                    destroy(it)
                    return@launch
                }
            val current = state.value
            val normalExit = current is State.Closing || current is State.Destroyed ||
                (current == State.Initialized && exitCode == 0)
            destroy(if (normalExit) null else PluginProcessException(manifest.id, exitCode))
        }
    }

    /** Queues one logical packet for this plugin. */
    suspend fun send(packet: Packet) {
        check(state.value == State.Initialized) { "Plugin ${manifest.id} is not initialized" }
        writePacket(packet)
    }

    internal suspend fun sendDuringHandshake(packet: Packet) {
        check(state.value == State.Handshaking) { "Plugin ${manifest.id} is not handshaking" }
        writePacket(packet)
    }

    /** Starts a normal shutdown. Calling this more than once is harmless. */
    fun close() {
        destroy(null)
    }

    internal fun fail(throwable: Throwable) {
        destroy(throwable)
    }

    private fun destroy(throwable: Throwable?) {
        val current = state.value
        if (current is State.Destroyed || current == State.Closing) return
        val terminalState = if (throwable == null) State.Closing else State.Destroyed(throwable)
        if (!_state.compareAndSet(current, terminalState)) {
            destroy(throwable)
            return
        }

        runCatching { sink?.close() }
        runCatching { source?.close() }
        runCatching { process?.close() }
        scope?.cancel()
        (receiveDispatcher as? AutoCloseable)?.close()
        source = null
        sink = null
        process = null
        if (throwable == null) _state.value = State.Destroyed()
    }

    private suspend fun receivePackets(source: IPCAnonymousPipeSource) {
        println("plugin:receive-start")
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

    private suspend fun writePacket(packet: Packet) {
        val sink = checkNotNull(sink) { "Plugin ${manifest.id} transport is not attached" }
        sendMutex.withLock {
            packet.split().forEach(sink::writePacket)
            sink.flush()
        }
    }

    private suspend fun runWorker(block: suspend () -> Unit) {
        try {
            block()
            requestDestroy(null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BrokenPipeException) {
            val connected = state.value == State.Initialized || state.value == State.Closing
            requestDestroy(if (connected) null else e)
        } catch (t: Throwable) {
            requestDestroy(t)
        }
    }

    private fun requestDestroy(throwable: Throwable?) {
        val currentScope = scope
        if (currentScope?.isActive == true) {
            currentScope.launch { destroy(throwable) }
        } else {
            destroy(throwable)
        }
    }

    private fun transition(expected: State, next: State) {
        check(_state.compareAndSet(expected, next)) {
            "Illegal plugin state transition: ${state.value} -> $next"
        }
    }

    sealed interface State {
        data object UnInitialized : State
        data object Verified : State
        data object Handshaking : State
        data object Initialized : State
        data object Closing : State
        data class Destroyed(val exception: Throwable? = null) : State
    }
}

class PluginProcessException(pluginId: String, exitCode: Int) :
    IllegalStateException("Plugin loader for $pluginId exited with code $exitCode")
