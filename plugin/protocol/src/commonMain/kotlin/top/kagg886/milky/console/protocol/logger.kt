package top.kagg886.milky.console.protocol

import kotlinx.serialization.Serializable

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/16 14:52
 * ================================================
 */

@Serializable
data class PluginLog(val level: Int, val tag: String, val message: String,val stacktrace: String? = null)
