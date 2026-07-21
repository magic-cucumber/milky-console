package top.kagg886.milky.console.plugin.config

import co.touchlab.kermit.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


private val logger = Logger.withTag("PluginConfig")

@Serializable
data class PluginManifest(
    val metadata: ManifestMetadata,
    val id: String,
    val name: String,
    val version: ManifestVersion,
    val description: String
) {
    init {
        logger.d { "plugin manifest decoded: id=$id, name=$name, version=${version.name}, schema=${metadata.manifestVersion}, protocol=${metadata.protocolVersion}" }
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
        logger.v { "manifest metadata decoded: schema=$manifestVersion, protocol=$protocolVersion" }
    }
}
@Serializable
data class ManifestVersion(
    val name: String,
    val code: Int
) {
    init {
        logger.v { "manifest version decoded: name=$name, code=$code" }
    }
}
