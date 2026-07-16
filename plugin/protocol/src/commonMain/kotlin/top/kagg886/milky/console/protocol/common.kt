package top.kagg886.milky.console.protocol

import kotlinx.serialization.Polymorphic
import kotlinx.serialization.Serializable

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/16 13:41
 * ================================================
 */

@Serializable
sealed interface MilkyConsoleFromEvent {
    @Polymorphic
    interface FromPlugin : MilkyConsoleFromEvent

    @Polymorphic
    interface FromHost : MilkyConsoleFromEvent
}
