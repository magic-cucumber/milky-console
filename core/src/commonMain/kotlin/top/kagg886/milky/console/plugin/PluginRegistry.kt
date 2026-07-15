package top.kagg886.milky.console.plugin

import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.decodeFromString
import okio.FileSystem
import okio.Path
import top.kagg886.milky.console.protocol.HandShakePacket
import top.kagg886.milky.console.protocol.HandShakePacketResponsePacket
import top.kagg886.milky.console.protocol.HandShakeRequestReadyPacket
import top.kagg886.milky.console.util.readContent
import top.kagg886.milky.console.util.toBuffer
import top.kagg886.milky.console.util.protocol.Packet
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalSerializationApi::class)
data object PluginRegistry {
    const val MANIFEST_VERSION = 1
    const val PROTOCOL_VERSION = 1
    const val DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 15_000L

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val mutablePlugins = linkedMapOf<String, Plugin>()
    private val mutableCandidates = linkedMapOf<Path, Plugin>()

    val plugins: Map<String, Plugin>
        get() = mutablePlugins.toMap()

    val loaderExecutableFileName: String
        get() = PluginPlatform.loaderExecutableFileName

    /**
     * Scans direct child directories. A failure is fail-fast, and the failed
     * candidate remains observable through [candidateAt] in Destroyed state.
     */
    suspend fun loadAll(
        pluginDirectory: Path,
        loaderExecutable: Path,
        fileSystem: FileSystem = FileSystem.SYSTEM,
        handshakeTimeoutMillis: Long = DEFAULT_HANDSHAKE_TIMEOUT_MILLIS,
    ): List<Plugin> {
        val metadata = fileSystem.metadataOrNull(pluginDirectory)
            ?: return emptyList()
        require(metadata.isDirectory) { "$pluginDirectory is not a directory" }

        return fileSystem.list(pluginDirectory)
            .filter { fileSystem.metadataOrNull(it)?.isDirectory == true }
            .sortedBy(Path::name)
            .map { load(it, loaderExecutable, fileSystem, handshakeTimeoutMillis) }
    }

    suspend fun load(
        base: Path,
        loaderExecutable: Path,
        fileSystem: FileSystem = FileSystem.SYSTEM,
        handshakeTimeoutMillis: Long = DEFAULT_HANDSHAKE_TIMEOUT_MILLIS,
    ): Plugin {
        val plugin = verify(base, fileSystem)
        try {
            require(fileSystem.metadataOrNull(loaderExecutable)?.isRegularFile == true) {
                "Plugin loader does not exist: $loaderExecutable"
            }
            startAndHandshake(plugin, loaderExecutable, handshakeTimeoutMillis)
            return plugin
        } catch (t: Throwable) {
            plugin.fail(t)
            if (mutablePlugins[plugin.manifest.id] === plugin) {
                mutablePlugins.remove(plugin.manifest.id)
            }
            throw t
        }
    }

    fun verify(
        base: Path,
        fileSystem: FileSystem = FileSystem.SYSTEM,
    ): Plugin {
        val plugin = Plugin(base)
        mutableCandidates[base] = plugin
        try {
            require(fileSystem.metadataOrNull(base)?.isDirectory == true) {
                "Plugin path is not a directory: $base"
            }

            val manifestPath = base / "manifest.json"
            val configPath = base / "default-config.json"
            val platformPath = base / "platform"
            val dynamicLibrary = platformPath / PluginPlatform.dynamicLibraryFileName

            require(fileSystem.metadataOrNull(manifestPath)?.isRegularFile == true) {
                "Missing plugin manifest: $manifestPath"
            }
            val manifest = json.decodeFromString<PluginManifest>(
                fileSystem.read(manifestPath) { readUtf8() },
            )
            require(manifest.id.isNotBlank()) { "Plugin id must not be blank" }
            require(manifest.metadata.manifest <= MANIFEST_VERSION) {
                "Plugin ${manifest.id} requires manifest version ${manifest.metadata.manifest}, current version is $MANIFEST_VERSION"
            }
            require(manifest.metadata.protocol == PROTOCOL_VERSION) {
                "Plugin ${manifest.id} uses protocol version ${manifest.metadata.protocol}, current version is $PROTOCOL_VERSION"
            }
            require(manifest.id !in mutablePlugins) { "Duplicate plugin id: ${manifest.id}" }

            require(fileSystem.metadataOrNull(configPath)?.isRegularFile == true) {
                "Missing default plugin config: $configPath"
            }
            val config = fileSystem.read(configPath) { readByteArray() }
            require(json.parseToJsonElement(config.decodeToString()) is JsonObject) {
                "Default plugin config must be a JSON object: $configPath"
            }
            require(fileSystem.metadataOrNull(platformPath)?.isDirectory == true) {
                "Missing plugin platform directory: $platformPath"
            }
            require(fileSystem.metadataOrNull(dynamicLibrary)?.isRegularFile == true) {
                "Missing dynamic library for the current platform: $dynamicLibrary"
            }

            plugin.verified(manifest, config, dynamicLibrary)
            mutablePlugins[manifest.id] = plugin
            return plugin
        } catch (t: Throwable) {
            plugin.fail(t)
            mutablePlugins.entries.removeAll { it.value === plugin }
            throw t
        }
    }

    fun candidateAt(base: Path): Plugin? = mutableCandidates[base]

    fun close() {
        mutableCandidates.values.toSet().forEach(Plugin::close)
        mutablePlugins.clear()
        mutableCandidates.clear()
    }

    private suspend fun startAndHandshake(
        plugin: Plugin,
        loaderExecutable: Path,
        timeoutMillis: Long,
    ) {
        plugin.attach(PluginPlatform.startLoader(loaderExecutable, plugin.dynamicLibrary))
        plugin.handshaking()
        withTimeout(timeoutMillis.milliseconds) {
            plugin.receiveHandshakePacket().data.readContent<HandShakeRequestReadyPacket>()
            plugin.sendDuringHandshake(Packet(data = HandShakePacket(plugin.defaultConfig).toBuffer()))
            val response = plugin.receiveHandshakePacket().data.readContent<HandShakePacketResponsePacket>()
            require(response.allow) { "Plugin ${plugin.manifest.id} denied the handshake" }
        }
        plugin.initialized()
        plugin.startReceiving()
    }
}
