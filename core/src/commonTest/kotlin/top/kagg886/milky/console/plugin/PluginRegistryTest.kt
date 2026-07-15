package top.kagg886.milky.console.plugin

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PluginRegistryTest {
    private val fileSystem = FileSystem.SYSTEM
    private val root = "build/plugin-registry-test-${Uuid.random()}".toPath()

    @AfterTest
    fun cleanUp() {
        PluginRegistry.close()
        if (fileSystem.exists(root)) fileSystem.deleteRecursively(root)
    }

    @Test
    fun verifiesRequiredFilesAndMovesToVerified() {
        val base = createPlugin("valid")

        val plugin = PluginRegistry.verify(base, fileSystem)

        assertEquals(Plugin.State.Verified, plugin.state.value)
        assertEquals("valid", plugin.manifest.id)
        assertEquals("{}", plugin.defaultConfig.decodeToString())
        assertEquals(plugin, PluginRegistry.plugins["valid"])
    }

    @Test
    fun rejectsNonObjectDefaultConfigAndKeepsDestroyedCandidate() {
        val base = createPlugin("bad-config", config = "[]")

        assertFailsWith<IllegalArgumentException> {
            PluginRegistry.verify(base, fileSystem)
        }

        val destroyed = assertIs<Plugin.State.Destroyed>(
            assertNotNull(PluginRegistry.candidateAt(base)).state.value,
        )
        assertNotNull(destroyed.exception)
    }

    @Test
    fun rejectsNewerManifestVersion() {
        val base = createPlugin("future", manifestVersion = PluginRegistry.MANIFEST_VERSION + 1)

        assertFailsWith<IllegalArgumentException> {
            PluginRegistry.verify(base, fileSystem)
        }

        assertIs<Plugin.State.Destroyed>(assertNotNull(PluginRegistry.candidateAt(base)).state.value)
    }

    @Test
    fun rejectsDuplicatePluginIdWithoutReplacingOriginal() {
        val first = PluginRegistry.verify(createPlugin("same", directory = "first"), fileSystem)
        val duplicateBase = createPlugin("same", directory = "second")

        assertFailsWith<IllegalArgumentException> {
            PluginRegistry.verify(duplicateBase, fileSystem)
        }

        assertEquals(first, PluginRegistry.plugins["same"])
        assertIs<Plugin.State.Destroyed>(assertNotNull(PluginRegistry.candidateAt(duplicateBase)).state.value)
    }

    @Test
    fun loadsNativePluginDeliversMessageAndCloses() = runBlocking {
        val environment = nativePluginTestEnvironment()
        val marker = environment.messageMarker.toPath()
        marker.parent?.let(fileSystem::createDirectories)
        if (fileSystem.exists(marker)) fileSystem.delete(marker)
        val base = createPlugin("native-accept", dynamicLibrary = environment.acceptLibrary.toPath())

        val plugin = PluginRegistry.load(base, environment.loader.toPath(), fileSystem)

        assertEquals(Plugin.State.Initialized, plugin.state.value)
        plugin.send(
            top.kagg886.milky.console.util.protocol.Packet(
                data = Buffer().write(byteArrayOf(1, 2, 3)),
            ),
        )
        withTimeout(5_000) {
            while (runCatching {
                    fileSystem.exists(marker) && fileSystem.read(marker) { readUtf8() } == "received"
                }.getOrDefault(false).not()
            ) {
                delay(25)
            }
        }
        assertEquals("received", fileSystem.read(marker) { readUtf8() })

        plugin.close()
        val destroyed = assertIs<Plugin.State.Destroyed>(plugin.state.value)
        assertEquals(null, destroyed.exception)
    }

    @Test
    fun rejectedNativePluginIsDestroyedWithException() = runBlocking {
        val environment = nativePluginTestEnvironment()
        val base = createPlugin("native-reject", dynamicLibrary = environment.rejectLibrary.toPath())

        assertFailsWith<IllegalArgumentException> {
            PluginRegistry.load(
                base,
                environment.loader.toPath(),
                fileSystem,
                handshakeTimeoutMillis = 1_000,
            )
        }

        val destroyed = assertIs<Plugin.State.Destroyed>(
            assertNotNull(PluginRegistry.candidateAt(base)).state.value,
        )
        assertNotNull(destroyed.exception)
        assertTrue("native-reject" !in PluginRegistry.plugins)
    }

    private fun createPlugin(
        id: String,
        directory: String = id,
        manifestVersion: Int = PluginRegistry.MANIFEST_VERSION,
        config: String = "{}",
        dynamicLibrary: Path? = null,
    ): Path {
        val base = root / directory
        fileSystem.createDirectories(base / "platform")
        fileSystem.write(base / "manifest.json") {
            writeUtf8(
                """
                {
                  "metadata": {"protocol": ${PluginRegistry.PROTOCOL_VERSION}, "manifest": $manifestVersion},
                  "id": "$id",
                  "name": "$id",
                  "description": "test plugin",
                  "version": {"name": "1.0.0", "code": 1}
                }
                """.trimIndent(),
            )
        }
        fileSystem.write(base / "default-config.json") { writeUtf8(config) }
        val destination = base / "platform" / PluginPlatform.dynamicLibraryFileName
        if (dynamicLibrary == null) {
            fileSystem.write(destination) { }
        } else {
            fileSystem.copy(dynamicLibrary, destination)
        }
        return base
    }
}

internal data class NativePluginTestEnvironment(
    val acceptLibrary: String,
    val rejectLibrary: String,
    val loader: String,
    val messageMarker: String,
)

internal expect fun nativePluginTestEnvironment(): NativePluginTestEnvironment
