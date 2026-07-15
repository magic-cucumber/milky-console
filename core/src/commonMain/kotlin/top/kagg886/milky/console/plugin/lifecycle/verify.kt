package top.kagg886.milky.console.plugin.lifecycle

import kotlinx.serialization.json.*
import okio.FileSystem
import top.kagg886.milky.console.CoreBuildConfig
import top.kagg886.milky.console.plugin.IPlugin
import top.kagg886.milky.console.plugin.PluginException
import top.kagg886.milky.console.plugin.config.PluginManifest
import top.kagg886.milky.console.plugin.impl.IPluginImpl
import kotlin.experimental.ExperimentalNativeApi

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/15 17:09
 * ================================================
 */

fun IPluginImpl.verify(): Boolean {
    val fs = FileSystem.SYSTEM

    if (!fs.exists(manifestPath)) {
        _state.value = IPlugin.State.Closed(PluginException("缺少 manifest.json"))
        return false
    }

    // 校验插件manifest
    val manifest = fs.read(manifestPath) {
        val json = Json.decodeFromString<JsonObject>(readUtf8())

        val id = try {
            json["id"]?.jsonPrimitive!!.content
        } catch (_: Exception) {
            _state.value = IPlugin.State.Closed(PluginException("无法获取 $manifestPath 代表的插件id。"))
            return false
        }


        val version = try {
            json["metadata"]?.jsonObject["manifest_version"]?.jsonPrimitive?.int
        } catch (_: Exception) {
            null
        }

        if (version == null) {
            _state.value =
                IPlugin.State.Closed(PluginException("插件:$id 无法获取 manifest.json metadata.manifest_version"))
            return false
        }

        val range = CoreBuildConfig.SCHEMA_VERSION_START..CoreBuildConfig.SCHEMA_VERSION_END
        //CoreBuildConfig.SCHEMA_VERSION兼容其版本之前的版本
        if (version in range) {
            _state.value =
                IPlugin.State.Closed(PluginException("此版本的milky-console只支持 schema-version 为 [$range] 的 版本。当前插件:$id 的版本为 $version"))
            return false
        }

        try {
            Json.decodeFromJsonElement<PluginManifest>(json)
        } catch (ex: Exception) {
            _state.value = IPlugin.State.Closed(PluginException("无法序列化插件:$id", ex))
            return false
        }
    }

    //校验动态库是否存在
    if (fs.metadataOrNull(platformPath)?.isDirectory == false) {
        _state.value = IPlugin.State.Closed(PluginException("插件: ${manifest.id} 缺少动态库文件夹"))
        return false
    }

    //文件名：(platform)-(arch).(extension)
    @OptIn(ExperimentalNativeApi::class)
    val dllibFile = fs.list(platformPath).find {
        val platform = Platform.osFamily.name
        val arch = Platform.cpuArchitecture.name
        val extension = when (Platform.osFamily) {
            OsFamily.WINDOWS -> "dll"
            OsFamily.LINUX -> "so"
            OsFamily.MACOSX -> "dylib"
            else -> {
                _state.value = IPlugin.State.Closed(PluginException("插件: ${manifest.id} 不支持本平台"))
            }
        }
        it.name == "$platform-$arch.$extension"
    }

    if (dllibFile == null) {
        _state.value = IPlugin.State.Closed(PluginException("插件: ${manifest.id} 不支持本平台"))
        return false
    }

    _state.value = IPlugin.State.Verified(dllibFile, manifest)
    return true
}
