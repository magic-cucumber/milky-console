package top.kagg886.milky.console.plugin.lifecycle

import co.touchlab.kermit.Logger
import kotlinx.io.files.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okio.FileSystem
import okio.Path.Companion.toPath
import top.kagg886.milky.console.CoreBuildConfig
import top.kagg886.milky.console.plugin.Plugin
import top.kagg886.milky.console.plugin.PluginException
import top.kagg886.milky.console.plugin.PluginRegistry
import top.kagg886.milky.console.plugin.config.PluginManifest
import kotlin.experimental.ExperimentalNativeApi
import kotlin.uuid.Uuid

private val pluginVerifyLogger = Logger.withTag("PluginVerify")


fun Plugin.verify(registry: PluginRegistry): Boolean {
    pluginVerifyLogger.i { "enter verify: basePath=$basePath, state=${state.value}" }
    val fs = FileSystem.SYSTEM

    if (!fs.exists(manifestPath)) {
        pluginVerifyLogger.e { "manifest validation failed: missing $manifestPath" }
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
            pluginVerifyLogger.e { "manifest validation failed: unable to read plugin id from $manifestPath" }
            _state.value = Plugin.State.Closed(PluginException("无法获取 $manifestPath 代表的插件id。"))
            return false
        }


        val manifestVersion = try {
            json["metadata"]?.jsonObject["manifest_version"]?.jsonPrimitive?.int
        } catch (_: Exception) {
            null
        }

        if (manifestVersion == null) {
            pluginVerifyLogger.e { "manifest validation failed: missing manifest_version, id=$id" }
            _state.value =
                Plugin.State.Closed(PluginException("插件:$id 无法获取 manifest.json metadata.manifest_version"))
            return false
        }


        val manifestSupportRange = CoreBuildConfig.SCHEMA_VERSION_START..CoreBuildConfig.SCHEMA_VERSION_END
        if (manifestVersion !in manifestSupportRange) {
            pluginVerifyLogger.e { "manifest validation failed: unsupported manifest_version=$manifestVersion, supported=$manifestSupportRange, id=$id" }
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
            pluginVerifyLogger.e { "manifest validation failed: missing protocol_version, id=$id" }
            _state.value =
                Plugin.State.Closed(PluginException("插件:$id 无法获取 manifest.json metadata.protocol_version"))
            return false
        }


        val protocolSupportRange = CoreBuildConfig.PROTOCOL_VERSION_START..CoreBuildConfig.PROTOCOL_VERSION_END
        if (protocolVersion !in protocolSupportRange) {
            pluginVerifyLogger.e { "manifest validation failed: unsupported protocol_version=$protocolVersion, supported=$protocolSupportRange, id=$id" }
            _state.value =
                Plugin.State.Closed(PluginException("此版本的milky-console只支持 schema-version 为 [${protocolSupportRange}] 的 版本。当前插件:$id 的版本为 $manifestVersion"))
            return false
        }


        try {
            Json.decodeFromJsonElement<PluginManifest>(json)
        } catch (ex: Exception) {
            pluginVerifyLogger.e(ex) { "manifest validation failed: deserialization error, id=$id" }
            _state.value = Plugin.State.Closed(PluginException("无法序列化插件:$id", ex))
            return false
        }
    }

    //校验动态库是否存在
    if (fs.metadataOrNull(platformPath)?.isDirectory == false) {
        pluginVerifyLogger.e { "platform validation failed: missing platform directory, id=${manifest.id}" }
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
                pluginVerifyLogger.e { "platform validation failed: unsupported OS=${Platform.osFamily}, id=${manifest.id}" }
                _state.value = Plugin.State.Closed(PluginException("插件: ${manifest.id} 不支持本平台"))
                null
            }
        }
        val expectedName = "$osFamily-$cpuArch.$extension"
        it.name == expectedName
    }

    if (dllibFile == null) {
        pluginVerifyLogger.e { "platform validation failed: expected library not found, id=${manifest.id}, os=$osFamily, arch=$cpuArch" }
        _state.value = Plugin.State.Closed(PluginException("插件: ${manifest.id} 不支持本平台"))
        return false
    }


    val configPath = registry.pluginConfigPath(manifest)
    val defaultConfig = run select@{
        //首先读取插件自身的default config。读取成功返回，否则当文件不存在。
        if (fs.metadataOrNull(configPath)?.isRegularFile == true) {
            val conf =
                fs.read(configPath) { runCatching { Json.decodeFromString<JsonObject>(readUtf8()) }.getOrNull() }
            if (conf != null) return@select conf

            //此时conf一定解码错误。备份并删除文件
            val ext = Uuid.random().toString()
            val target = configPath.parent!!.resolve(configPath.name + "." + ext)
            fs.copy(
                configPath,
                target
            )
            fs.delete(configPath)

            pluginVerifyLogger.w("plugin ${manifest.id} config read failed, backup to $target")
        }

        //如果插件config不存在，从default里复制。
        if (fs.metadataOrNull(defaultConfigPath)?.isRegularFile == true) {
            val conf = fs.read(defaultConfigPath) {
                runCatching { Json.decodeFromString<JsonObject>(readUtf8()) }.getOrDefault(buildJsonObject { })
            }
            fs.write(configPath) {
                writeUtf8(Json.encodeToString(conf))
            }
            return@select conf
        }

        fs.write(configPath) {
            writeUtf8("{}")
        }
        buildJsonObject { }

    }

//    val defaultConfig = if (!fs.exists(defaultConfigPath)) {
//        pluginVerifyLogger.v { "default config absent; using empty config: id=${manifest.id}" }
//        buildJsonObject { }
//    } else fs.read(defaultConfigPath) {
//        try {
//            Json.decodeFromString(readUtf8())
//        } catch (_: Exception) {
//            pluginVerifyLogger.e { "config validation failed: invalid default-config.json, id=${manifest.id}" }
//            _state.value = Plugin.State.Closed(PluginException("插件: ${manifest.id} 提供了错误的default-config.json"))
//            return false
//        }
//    }

    _state.value = Plugin.State.Verified(dllibFile, manifest, defaultConfig)
    pluginVerifyLogger.i { "exit verify successfully: id=${manifest.id}, library=$dllibFile, state=${state.value}" }
    return true
}
