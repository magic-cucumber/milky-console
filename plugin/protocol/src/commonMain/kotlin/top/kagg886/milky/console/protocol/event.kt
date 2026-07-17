package top.kagg886.milky.console.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.ntqqrev.milky.ApiGeneralResponse
import org.ntqqrev.milky.Event
import kotlin.uuid.Uuid

/** A host event delivered to the loaded native plugin. */
@Serializable
data class HostEvent(val event: Event) : MilkyConsoleFromEvent.FromHost

/** A plugin event delivered to the host. */
@Serializable
data class PluginApiRequest(val type: String, val tag: Uuid = Uuid.random(), val payload: JsonElement) :
    MilkyConsoleFromEvent.FromPlugin

@Serializable
data class PluginApiResponse(val type: String, val tag: Uuid = Uuid.random(), val payload: ApiGeneralResponse) :
    MilkyConsoleFromEvent.FromHost
