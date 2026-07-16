package top.kagg886.milky.console.plugin.config

import co.touchlab.kermit.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val log = Logger.withTag("Config")

@Serializable
data class PluginManifest(
    val metadata: ManifestMetadata,
    val id: String,
    val name: String,
    val version: ManifestVersion,
    val description: String
) {
    init {
        log.v { "PluginManifest created: id=$id, name=$name, version=${version.name}(${version.code})" }
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
        log.v { "ManifestMetadata created: manifestVersion=$manifestVersion, protocolVersion=$protocolVersion" }
    }
}

@Serializable
data class ManifestVersion(
    val name: String,
    val code: Int
) {
    init {
        log.v { "ManifestVersion created: name=$name, code=$code" }
    }
}
