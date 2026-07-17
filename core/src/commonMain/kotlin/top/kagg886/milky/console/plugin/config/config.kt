package top.kagg886.milky.console.plugin.config

import co.touchlab.kermit.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable



@Serializable
data class PluginManifest(
    val metadata: ManifestMetadata,
    val id: String,
    val name: String,
    val version: ManifestVersion,
    val description: String
) {
    init {
        
    }
}

@Serializable
data class ManifestMetadata(
    @SerialName("manifest_version")
    val manifestVersion: Int,
    @SerialName("protocol_version")
    val protocolVersion: Int
) {
    init {
        
    }
}

@Serializable
data class ManifestVersion(
    val name: String,
    val code: Int
) {
    init {
        
    }
}
