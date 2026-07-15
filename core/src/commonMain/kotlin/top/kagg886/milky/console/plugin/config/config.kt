package top.kagg886.milky.console.plugin.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/15 17:32
 * ================================================
 */

@Serializable
data class PluginManifest(
    val metadata: ManifestMetadata,
    val id: String,
    val name: String,
    val version: ManifestVersion,
    val description: String
)

@Serializable
data class ManifestMetadata(
    @SerialName("manifest_version")
    val manifestVersion: Int,
    @SerialName("protocol_version")
    val protocolVersion: Int
)

@Serializable
data class ManifestVersion(
    val name: String,
    val code: Int
)
