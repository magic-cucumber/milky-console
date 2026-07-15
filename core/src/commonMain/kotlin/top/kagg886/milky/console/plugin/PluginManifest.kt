package top.kagg886.milky.console.plugin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class PluginManifest(
    val metadata: Metadata,
    val id: String,
    val name: String,
    @JsonNames("desctiption") val description: String,
    val version: Version,
) {
    @Serializable
    data class Metadata(
        val protocol: Int,
        val manifest: Int,
    )

    @Serializable
    data class Version(
        val name: String,
        val code: Int,
    )
}
