package top.kagg886.milky.console.protocol

import kotlinx.serialization.Serializable
import org.ntqqrev.milky.Event

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/16 11:21
 * ================================================
 */

@Serializable
sealed interface MilkyConsoleEvent {
    sealed interface InternalEvent: MilkyConsoleEvent
    data class ProtocolEvent(val event: Event) : MilkyConsoleEvent
}
