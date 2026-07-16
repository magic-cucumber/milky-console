package top.kagg886.milky.console.protocol

import kotlinx.serialization.Serializable
import org.ntqqrev.milky.Event

/** A host event delivered to the loaded native plugin. */
@Serializable
data class HostEvent(val event: Event) : MilkyConsoleFromEvent.FromHost

/** A plugin event delivered to the host. */
@Serializable
data class PluginEvent(val event: Event) : MilkyConsoleFromEvent.FromPlugin
