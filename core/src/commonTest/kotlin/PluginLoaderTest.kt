/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/16 14:10
 * ================================================
 */

package top.kagg886.milky.console.plugin

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PluginLoaderTest {
    @Test
    fun validPluginCompletesHandshake() = runBlocking {
        val container = pluginLoaderTestContainer().toPath()
        val registry = PluginRegistry(container)
        val plugin = registry.make(container / "plugin" / "plugin-loader-test")

        val ready = withTimeout(10.seconds) {
            plugin.state.filterIsInstance<Plugin.State.Ready>().first()
        }
        assertIs<Plugin.State.Ready>(ready)

        assertTrue(ready.process.kill())
        withTimeout(10.seconds) {
            plugin.state.filterIsInstance<Plugin.State.Closed>().first()
        }
        Unit
    }
}

internal expect fun pluginLoaderTestContainer(): String
