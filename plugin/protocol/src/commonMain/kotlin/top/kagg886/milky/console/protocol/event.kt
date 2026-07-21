package top.kagg886.milky.console.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.ntqqrev.milky.ApiGeneralResponse
import org.ntqqrev.milky.Event
import top.kagg886.milky.console.util.MilkyApiResponseSerializer
import top.kagg886.milky.console.util.MilkyElementSerializer
import kotlin.uuid.Uuid

/** A host event delivered to the loaded native plugin. */
@Serializable
data class HostEvent(val event: Event) : MilkyConsoleFromEvent.FromHost

/** A plugin event delivered to the host. */
@Serializable
data class PluginApiRequest(
    val category: String,
    val uin: ULong = 0u,
    val tag: Uuid = Uuid.random(),
    @Serializable(with = MilkyElementSerializer::class)
    val payload: JsonElement,
) :
    MilkyConsoleFromEvent.FromPlugin

@Serializable
data class PluginApiResponse(
    val category: String,
    val tag: Uuid = Uuid.random(),

    @Serializable(with = MilkyApiResponseSerializer::class)
    val payload: ApiGeneralResponse
) :
    MilkyConsoleFromEvent.FromHost
