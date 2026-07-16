package top.kagg886.milky.console.plugin.lifecycle

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
import top.kagg886.milky.console.util.readContent
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

data class PluginInboundEvent(
    val pluginId: String,
    val event: MilkyConsoleFromEvent.FromPlugin,
)

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/16 13:15
 * ================================================
 */

@OptIn(ExperimentalSerializationApi::class)
fun Plugin.startPipeJob(
    registry: PluginRegistry,
    send: IPCAnonymousPipeSink,
    receive: IPCAnonymousPipeSource
): Pair<Job, Job> {
    val pluginId = manifest.id

    val sendPipeJob = registry.scope.launch(start = CoroutineStart.UNDISPATCHED) {
        EventBus.subscribe<Pair<String, MilkyConsoleFromEvent.FromHost>>()
            .filter { (id, _) -> id == pluginId }
            .collect { (_, event) ->
                event.toPacket().forEach { packet ->
                    send.writePacket(packet)
                }
            }
    }
    val receivePipeJob = registry.scope.launch {
        val packetsByGroup = LRUCache.create<Uuid, List<Packet>>(1.minutes, 16 * 1024 * 1024) { _, packets ->
            packets.sumOf { it.data.size }
        }
        while (isActive) {
            val packet = receive.readPacket()
            val mergedPacket = if (packet.isSplit) {
                val group = requireNotNull(packet.group)
                val packets = packetsByGroup.getOrPut(group) { emptyList() }!! + packet
                if (packets.size != packet.size) {
                    packetsByGroup.put(group, packets)
                    continue
                }

                val orderedPackets = packets.filter { it.index != null }.sortedBy { it.index!! }
                if (orderedPackets.indices.all { index -> orderedPackets[index].index == index }) {
                    packetsByGroup.remove(group)
                    orderedPackets.merge()
                } else {
                    packetsByGroup.put(group, packets)
                    continue
                }
            } else {
                packet
            }
            EventBus.post(
                PluginInboundEvent(
                    pluginId,
                    mergedPacket.data.readContent<MilkyConsoleFromEvent.FromPlugin>(),
                )
            )
        }
    }

    return sendPipeJob to receivePipeJob
}
