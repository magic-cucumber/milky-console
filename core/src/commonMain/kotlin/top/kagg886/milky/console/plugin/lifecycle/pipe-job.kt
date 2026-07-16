package top.kagg886.milky.console.plugin.lifecycle

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import top.kagg886.milky.console.plugin.Plugin
import top.kagg886.milky.console.plugin.PluginRegistry
import top.kagg886.milky.console.plugin.manifest
import top.kagg886.milky.console.protocol.MilkyConsoleFromEvent
import top.kagg886.milky.console.protocol.PluginLog
import top.kagg886.milky.console.util.eventbus.EventBus
import top.kagg886.milky.console.util.eventbus.LRUCache
import top.kagg886.milky.console.util.pipe.IPCAnonymousPipe
import top.kagg886.milky.console.util.pipe.IPCAnonymousPipeSink
import top.kagg886.milky.console.util.pipe.IPCAnonymousPipeSource
import top.kagg886.milky.console.util.protocol.Packet
import top.kagg886.milky.console.util.protocol.isSplit
import top.kagg886.milky.console.util.protocol.merge
import top.kagg886.milky.console.util.protocol.readPacket
import top.kagg886.milky.console.util.protocol.toPacket
import top.kagg886.milky.console.util.protocol.writePacket
import top.kagg886.milky.console.util.logger.log
import top.kagg886.milky.console.util.readContent
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

data class PluginInboundEvent(
    val pluginId: String,
    val event: MilkyConsoleFromEvent.FromPlugin,
)

private val log = Logger.withTag("PipeJob")

@OptIn(ExperimentalSerializationApi::class)
fun Plugin.startPipeJob(
    registry: PluginRegistry,
    send: IPCAnonymousPipeSink,
    receive: IPCAnonymousPipeSource
): Pair<Job, Job> {
    log.i { ">>> Plugin.startPipeJob() enter, pluginId=${manifest.id}" }

    val pluginId = manifest.id

    val sendPipeJob = registry.scope.launch(start = CoroutineStart.UNDISPATCHED) {
        log.i { ">>> sendPipeJob coroutine enter, pluginId=$pluginId" }
        EventBus.subscribe<Pair<String, MilkyConsoleFromEvent.FromHost>>()
            .filter { (id, _) -> id == pluginId }
            .collect { (_, event) ->
                log.v { "sendPipeJob: forwarding event type=${event::class.simpleName} to pipe" }
                event.toPacket().forEach { packet ->
                    send.writePacket(packet)
                }
                log.d { "[group: pipe-send] event ${event::class.simpleName} sent to loader for pluginId=$pluginId" }
            }
        log.i { "<<< sendPipeJob coroutine exit, pluginId=$pluginId" }
    }
    log.d { "[group: coroutine-start] sendPipeJob launched, pluginId=$pluginId" }

    val receivePipeJob = registry.scope.launch {
        log.i { ">>> receivePipeJob coroutine enter, pluginId=$pluginId" }
        val packetsByGroup = LRUCache.create<Uuid, List<Packet>>(1.minutes, 16 * 1024 * 1024) { _, packets ->
            packets.sumOf { it.data.size }
        }
        while (isActive) {
            val packet = try {
                receive.readPacket()
            } catch (_: IllegalArgumentException) {
                log.w { "receivePipeJob: pipe closed unexpectedly (IllegalArgumentException), exiting pluginId=$pluginId" }
                return@launch
            }
            log.v { "receivePipeJob: raw packet read, isSplit=${packet.isSplit}, pluginId=$pluginId" }
            val mergedPacket = if (packet.isSplit) {
                val group = requireNotNull(packet.group)
                val packets = packetsByGroup.getOrPut(group) { emptyList() }!! + packet
                if (packets.size != packet.size) {
                    log.v { "receivePipeJob: split progress group=$group ${packets.size}/${packet.size}, caching" }
                    packetsByGroup.put(group, packets)
                    continue
                }

                val orderedPackets = packets.filter { it.index != null }.sortedBy { it.index!! }
                if (orderedPackets.indices.all { index -> orderedPackets[index].index == index }) {
                    log.v { "receivePipeJob: split group=$group complete, merging ${packets.size} packets" }
                    packetsByGroup.remove(group)
                    orderedPackets.merge()
                } else {
                    log.w { "receivePipeJob: split group=$group not yet continuous, re-caching" }
                    packetsByGroup.put(group, packets)
                    continue
                }
            } else {
                packet
            }
            val event = mergedPacket.data.readContent<MilkyConsoleFromEvent.FromPlugin>()

            // Handle PluginLog events: print them using the core's logger
            if (event is PluginLog) {
                val severity = Severity.entries.getOrElse(event.level) { Severity.Info }
                Logger.withTag(event.tag).log(severity, null, event.message)
                log.v { "receivePipeJob: PluginLog from pluginId=$pluginId printed (tag=${event.tag}, level=$severity)" }
            }

            EventBus.post(
                PluginInboundEvent(
                    pluginId,
                    event,
                )
            )
            log.v { "receivePipeJob: event type=${event::class.simpleName} posted to EventBus, pluginId=$pluginId" }
        }
        log.i { "<<< receivePipeJob coroutine exit, pluginId=$pluginId" }
    }
    log.d { "[group: coroutine-start] receivePipeJob launched, pluginId=$pluginId" }

    log.i { "<<< Plugin.startPipeJob() exit, pluginId=$pluginId, result=(${sendPipeJob}, ${receivePipeJob})" }
    return sendPipeJob to receivePipeJob
}
