package top.kagg886.milky.console.plugin.lifecycle

import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import okio.IOException
import top.kagg886.milky.console.plugin.Plugin
import top.kagg886.milky.console.plugin.PluginRegistry
import top.kagg886.milky.console.plugin.config
import top.kagg886.milky.console.plugin.libpath
import top.kagg886.milky.console.plugin.manifest
import top.kagg886.milky.console.protocol.HostHandshakeRequest
import top.kagg886.milky.console.protocol.PluginHandshakeResult
import top.kagg886.milky.console.util.eventbus.EventBus
import top.kagg886.milky.console.util.eventbus.LRUCache
import top.kagg886.milky.console.util.pipe.IPCAnonymousPipe
import top.kagg886.milky.console.util.pipe.create
import top.kagg886.milky.console.util.process.Process
import top.kagg886.milky.console.util.process.ProcessConfig
import top.kagg886.milky.console.util.process.create
import top.kagg886.milky.console.util.protocol.Packet
import top.kagg886.milky.console.util.protocol.merge
import top.kagg886.milky.console.util.protocol.readPacket
import top.kagg886.milky.console.util.protocol.toPacket
import top.kagg886.milky.console.util.protocol.writePacket
import top.kagg886.milky.console.util.raceN
import top.kagg886.milky.console.util.readContent
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val log = Logger.withTag("Handshake")

@OptIn(ExperimentalForeignApi::class, ExperimentalSerializationApi::class)
suspend fun Plugin.handshake(registry: PluginRegistry): Boolean {
    log.i { ">>> Plugin.handshake() enter, pluginId=${manifest.id}" }

    log.i { "handshake: creating IPC pipes" }
    val sendPipe = IPCAnonymousPipe.create()
    val receivePipe = IPCAnonymousPipe.create()
    log.d { "[group: pipe-create] sendPipe and receivePipe created" }

    val pluginId = manifest.id
    val send = sendPipe.sink
    val receive = receivePipe.source
    log.v { "handshake: pluginId=$pluginId, send pipe sink, receive pipe source obtained" }

    log.i { "handshake: starting pipe jobs (send/receive)" }
    val (sendPipeJob, receivePipeJob) = startPipeJob(registry, send, receive)
    log.d { "[group: pipe-jobs] sendPipeJob and receivePipeJob created" }

    log.i { "handshake: spawning loader process" }
    val progress = withContext(Dispatchers.IO) {
        log.v { "handshake: Process.create with args: receivePipe.sink.fd=${receivePipe.sink.fd}, sendPipe.source.fd=${sendPipe.source.fd}, libpath=$libpath" }
        Process.create {
            context(registry.scope.coroutineContext)
            workingDirectory(registry.pluginDataPath(this@handshake).toString())
            executable(registry.loaderPath().toString())
            stdin(ProcessConfig.IOOptions.None)
            stdout(ProcessConfig.IOOptions.None)
            stderr(ProcessConfig.IOOptions.None)
            arguments(
                receivePipe.sink.fd.toString(),
                sendPipe.source.fd.toString(),
                libpath.toString(),
                Json.encodeToString(config),
            )

            inheritFD(sendPipe.source.fd, receivePipe.sink.fd)
        }
    }
    log.v { "handshake: loader process spawned" }
    log.d { "[group: process-spawn] loader process created" }
    sendPipe.source.close()
    receivePipe.sink.close()
    log.v { "handshake: parent's unused pipe ends closed" }

    _state.value = Plugin.State.Handshaking(libpath, manifest, config)
    log.i { "handshake: state transition to Handshaking" }

    log.i { "handshake: entering raceN (process exit vs handshake result)" }
    val result: PluginHandshakeResult = raceN(
        {
            log.v { "race: monitoring process exit" }
            when (val exit = progress.await()) {
                Process.ExitStatus.Killed -> {
                    log.w { "race: process killed unexpectedly" }
                    PluginHandshakeResult.Rejected("进程被意外杀死")
                }
                is Process.ExitStatus.Result -> {
                    log.w { "race: process exited with code ${exit.exitCode}" }
                    PluginHandshakeResult.Rejected("进程意外退出。${exit.exitCode}")
                }
            }
        },
        {
            log.v { "race: waiting for handshake result via EventBus" }
            coroutineScope {
                val handshakeResult = async(start = CoroutineStart.UNDISPATCHED) {
                    log.v { "handshake: subscribing to PluginInboundEvent for pluginId=$pluginId" }
                    EventBus.subscribe<PluginInboundEvent>()
                        .first { it.pluginId == pluginId && it.event is PluginHandshakeResult }
                        .event as PluginHandshakeResult
                }

                try {
                    log.i { "handshake: posting HostHandshakeRequest for pluginId=$pluginId" }
                    EventBus.post(pluginId to HostHandshakeRequest)
                    log.v { "handshake: HostHandshakeRequest posted, awaiting result (timeout=10s)" }

                    val hsResult = withTimeoutOrNull(10.seconds) {
                        handshakeResult.await()
                    }
                    if (hsResult == null) {
                        log.w { "handshake: timed out waiting for PluginHandshakeResult" }
                    } else {
                        log.v { "handshake: PluginHandshakeResult received: ${hsResult::class.simpleName}" }
                    }
                    hsResult
                } finally {
                    handshakeResult.cancel()
                }
            }
                ?: PluginHandshakeResult.Rejected("握手超时")
        }
    )
    log.d { "[group: handshake-result] result=${result::class.simpleName}" }

    if (result is PluginHandshakeResult.Rejected) {
        log.e { "handshake: rejected, reason=${result.message}" }
        sendPipeJob.cancel()
        receivePipeJob.cancel()
        send.close()
        receive.close()
        _state.value = Plugin.State.Closed(IOException("插件握手失败: ${result.message}"))
        log.i { "<<< Plugin.handshake() exit, result=false (rejected)" }
        return false
    }

    log.i { "handshake: successful, creating waitCloseJob" }
    val closeAwaitJob = waitCloseJob(registry)
    log.d { "[group: close-await] waitCloseJob created" }

    _state.value = Plugin.State.Ready(
        libpath,
        manifest,
        config,
        progress,
        receivePipeJob,
        sendPipeJob,
        closeAwaitJob,
    )
    log.i { "handshake: state transition to Ready" }

    closeAwaitJob.start()
    log.v { "handshake: closeAwaitJob started" }

    log.i { "<<< Plugin.handshake() exit, result=true" }
    return true
}
