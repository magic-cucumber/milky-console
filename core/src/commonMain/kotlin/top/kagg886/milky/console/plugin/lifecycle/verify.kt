package top.kagg886.milky.console.plugin.lifecycle

import co.touchlab.kermit.Logger
import kotlinx.serialization.json.*
import okio.FileSystem
import top.kagg886.milky.console.CoreBuildConfig
import top.kagg886.milky.console.plugin.Plugin
import top.kagg886.milky.console.plugin.PluginException
import top.kagg886.milky.console.plugin.config.PluginManifest
import kotlin.experimental.ExperimentalNativeApi



fun Plugin.verify(): Boolean {
    val fs = FileSystem.SYSTEM

    if (!fs.exists(manifestPath)) {
        _state.value = Plugin.State.Closed(PluginException("缺少 manifest.json"))
        
        return false
    }

    // 校验插件manifest
    val manifest = fs.read(manifestPath) {
        val raw = readUtf8()
        val json = Json.decodeFromString<JsonObject>(raw)

        val id = try {
            val idVal = json["id"]?.jsonPrimitive!!.content
            idVal
        } catch (_: Exception) {
            _state.value = Plugin.State.Closed(PluginException("无法获取 $manifestPath 代表的插件id。"))
            return false
        }


        val manifestVersion = try {
            json["metadata"]?.jsonObject["manifest_version"]?.jsonPrimitive?.int
        } catch (_: Exception) {
            null
        }

        if (manifestVersion == null) {
            _state.value =
                Plugin.State.Closed(PluginException("插件:$id 无法获取 manifest.json metadata.manifest_version"))
            return false
        }
        

        val manifestSupportRange = CoreBuildConfig.SCHEMA_VERSION_START..CoreBuildConfig.SCHEMA_VERSION_END
        if (manifestVersion !in manifestSupportRange) {
            _state.value =
                Plugin.State.Closed(PluginException("此版本的milky-console只支持 schema-version 为 [$manifestSupportRange] 的 版本。当前插件:$id 的版本为 $manifestVersion"))
            return false
        }
        


        val protocolVersion = try {
            json["metadata"]?.jsonObject["protocol_version"]?.jsonPrimitive?.int
        } catch (_: Exception) {
            null
        }

        if (protocolVersion == null) {
            _state.value =
                Plugin.State.Closed(PluginException("插件:$id 无法获取 manifest.json metadata.protocol_version"))
            return false
        }
        

        val protocolSupportRange = CoreBuildConfig.PROTOCOL_VERSION_START..CoreBuildConfig.PROTOCOL_VERSION_END
        if (protocolVersion !in protocolSupportRange) {
            _state.value =
                Plugin.State.Closed(PluginException("此版本的milky-console只支持 schema-version 为 [${protocolSupportRange}] 的 版本。当前插件:$id 的版本为 $manifestVersion"))
            return false
        }
        

        try {
            Json.decodeFromJsonElement<PluginManifest>(json)
        } catch (ex: Exception) {
            _state.value = Plugin.State.Closed(PluginException("无法序列化插件:$id", ex))
            return false
        }
    }

    //校验动态库是否存在
    if (fs.metadataOrNull(platformPath)?.isDirectory == false) {
        _state.value = Plugin.State.Closed(PluginException("插件: ${manifest.id} 缺少动态库文件夹"))
        return false
    }
    

    //文件名：(platform)-(arch).(extension)
    @OptIn(ExperimentalNativeApi::class)
    val osFamily = Platform.osFamily.name
    @OptIn(ExperimentalNativeApi::class)
    val cpuArch = Platform.cpuArchitecture.name
    @OptIn(ExperimentalNativeApi::class)
    val dllibFile = fs.list(platformPath).find {
        val extension = when (Platform.osFamily) {
            OsFamily.WINDOWS -> {
                "dll"
            }
            OsFamily.LINUX -> {
                "so"
            }
            OsFamily.MACOSX -> {
                "dylib"
            }
            else -> {
                _state.value = Plugin.State.Closed(PluginException("插件: ${manifest.id} 不支持本平台"))
                null
            }
        }
        val expectedName = "$osFamily-$cpuArch.$extension"
        it.name == expectedName
    }

    if (dllibFile == null) {
        _state.value = Plugin.State.Closed(PluginException("插件: ${manifest.id} 不支持本平台"))
        return false
    }
    

    val defaultConfig = if (!fs.exists(defaultConfigPath)) {
        buildJsonObject { }
    } else fs.read(defaultConfigPath) {
        try {
            Json.decodeFromString(readUtf8())
        } catch (_: Exception) {
            _state.value = Plugin.State.Closed(PluginException("插件: ${manifest.id} 提供了错误的default-config.json"))
            return false
        }
    }

    _state.value = Plugin.State.Verified(dllibFile, manifest, defaultConfig)
    return true
}
