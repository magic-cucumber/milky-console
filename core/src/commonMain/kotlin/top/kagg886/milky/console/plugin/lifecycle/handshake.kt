package top.kagg886.milky.console.plugin.lifecycle

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

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/15 17:10
 * ================================================
 */

//base-path负责存放插件数据/缓存等
@OptIn(ExperimentalForeignApi::class, ExperimentalSerializationApi::class)
suspend fun Plugin.handshake(registry: PluginRegistry): Boolean {
    //启动 loader 进程。让 loader 负责加载库。
    val sendPipe = IPCAnonymousPipe.create()
    val receivePipe = IPCAnonymousPipe.create()

    sendPipe.source.close()
    receivePipe.sink.close()

    val pluginId = manifest.id
    val send = sendPipe.sink
    val receive = receivePipe.source

    val (sendPipeJob,receivePipeJob) = startPipeJob(registry, send, receive)

    val progress = withContext(Dispatchers.IO) {
        Process.create {
            context(registry.scope.coroutineContext)
            workingDirectory(registry.pluginDataPath(this@handshake).toString())
            executable(registry.loaderPath().toString())
            arguments(
                sendPipe.sink.fd.toString(),
                receivePipe.source.fd.toString(),
                libpath.toString(),
                Json.encodeToString(config),
            )

            /**
            unix模拟windows行为，详见 [process.macos.kt] [process.linux.kt]
             */
            inheritFD(sendPipe.sink.fd, receivePipe.source.fd)
        }
    }

    val result: PluginHandshakeResult = raceN(
        {
            when (val exit = progress.await()) {
                Process.ExitStatus.Killed -> PluginHandshakeResult.Rejected("进程被意外杀死")
                is Process.ExitStatus.Result -> PluginHandshakeResult.Rejected("进程意外退出。${exit.exitCode}")
            }
        },
        {
            coroutineScope {
                val handshakeResult = async(start = CoroutineStart.UNDISPATCHED) {
                    EventBus.subscribe<PluginInboundEvent>()
                        .first { it.pluginId == pluginId && it.event is PluginHandshakeResult }
                        .event as PluginHandshakeResult
                }

                try {
                    //握手请求包
                    EventBus.post(pluginId to HostHandshakeRequest)

                    //等待子进程完成握手
                    withTimeoutOrNull(10.seconds) {
                        handshakeResult.await()
                    }
                } finally {
                    handshakeResult.cancel()
                }
            }
                ?: PluginHandshakeResult.Rejected("握手超时")
        }
    )

    if (result is PluginHandshakeResult.Rejected) {
        sendPipeJob.cancel()
        receivePipeJob.cancel()
        send.close()
        receive.close()
        _state.value = Plugin.State.Closed(IOException("插件握手失败: ${result.message}"))
        return false
    }

    val closeAwaitJob = waitCloseJob(registry,progress)

    _state.value = Plugin.State.Ready(
        libpath,
        manifest,
        config,
        progress,
        receivePipeJob,
        sendPipeJob,
        closeAwaitJob,
    )

    closeAwaitJob.start()

    return true
}
