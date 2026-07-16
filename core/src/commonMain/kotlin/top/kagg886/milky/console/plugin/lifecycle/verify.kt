package top.kagg886.milky.console.plugin.lifecycle

import co.touchlab.kermit.Logger
import kotlinx.serialization.json.*
import okio.FileSystem
import top.kagg886.milky.console.CoreBuildConfig
import top.kagg886.milky.console.plugin.Plugin
import top.kagg886.milky.console.plugin.PluginException
import top.kagg886.milky.console.plugin.config.PluginManifest
import kotlin.experimental.ExperimentalNativeApi

private val log = Logger.withTag("Verify")

fun Plugin.verify(): Boolean {
    log.i { ">>> Plugin.verify() enter, basePath=$basePath" }
    val fs = FileSystem.SYSTEM

    log.v { "verify: checking manifest.json existence at $manifestPath" }
    if (!fs.exists(manifestPath)) {
        log.e { "verify: manifest.json does not exist" }
        _state.value = Plugin.State.Closed(PluginException("缺少 manifest.json"))
        log.w { "verify: plugin closed due to missing manifest.json, state=Closed" }
        log.i { "<<< Plugin.verify() exit, result=false" }
        return false
    }
    log.d { "[group: manifest-exists] manifest.json exists, expected=true, match=true" }

    // 校验插件manifest
    log.i { "verify: parsing manifest.json" }
    val manifest = fs.read(manifestPath) {
        val raw = readUtf8()
        val json = Json.decodeFromString<JsonObject>(raw)
        log.v { "verify: manifest raw content loaded, size=${raw.length}" }

        val id = try {
            val idVal = json["id"]?.jsonPrimitive!!.content
            log.v { "verify: extracted id=$idVal" }
            idVal
        } catch (_: Exception) {
            log.e { "verify: failed to extract plugin id from manifest" }
            _state.value = Plugin.State.Closed(PluginException("无法获取 $manifestPath 代表的插件id。"))
            log.i { "<<< Plugin.verify() exit, result=false (no id)" }
            return false
        }

        log.d { "[group: manifest-parse] plugin id=$id, expected=non-null, match=true" }

        val manifestVersion = try {
            json["metadata"]?.jsonObject["manifest_version"]?.jsonPrimitive?.int
        } catch (_: Exception) {
            null
        }

        if (manifestVersion == null) {
            log.e { "verify: plugin $id has no manifest_version in metadata" }
            _state.value =
                Plugin.State.Closed(PluginException("插件:$id 无法获取 manifest.json metadata.manifest_version"))
            log.i { "<<< Plugin.verify() exit, result=false (no manifest_version)" }
            return false
        }
        log.v { "verify: manifest_version=$manifestVersion" }
        log.d { "[group: manifest-version] manifestVersion=$manifestVersion, expected=non-null, match=true" }

        val manifestSupportRange = CoreBuildConfig.SCHEMA_VERSION_START..CoreBuildConfig.SCHEMA_VERSION_END
        if (manifestVersion !in manifestSupportRange) {
            log.e { "verify: plugin $id manifest_version=$manifestVersion not in supported range $manifestSupportRange" }
            _state.value =
                Plugin.State.Closed(PluginException("此版本的milky-console只支持 schema-version 为 [$manifestSupportRange] 的 版本。当前插件:$id 的版本为 $manifestVersion"))
            log.i { "<<< Plugin.verify() exit, result=false (manifest_version out of range)" }
            return false
        }
        log.v { "verify: manifest_version $manifestVersion is in supported range" }
        log.d { "[group: manifest-range] manifestVersion=$manifestVersion in $manifestSupportRange, match=true" }


        val protocolVersion = try {
            json["metadata"]?.jsonObject["protocol_version"]?.jsonPrimitive?.int
        } catch (_: Exception) {
            null
        }

        if (protocolVersion == null) {
            log.e { "verify: plugin $id has no protocol_version in metadata" }
            _state.value =
                Plugin.State.Closed(PluginException("插件:$id 无法获取 manifest.json metadata.protocol_version"))
            log.i { "<<< Plugin.verify() exit, result=false (no protocol_version)" }
            return false
        }
        log.v { "verify: protocol_version=$protocolVersion" }
        log.d { "[group: protocol-version] protocolVersion=$protocolVersion, expected=non-null, match=true" }

        val protocolSupportRange = CoreBuildConfig.PROTOCOL_VERSION_START..CoreBuildConfig.PROTOCOL_VERSION_END
        if (protocolVersion !in protocolSupportRange) {
            log.e { "verify: plugin $id protocol_version=$protocolVersion not in supported range $protocolSupportRange" }
            _state.value =
                Plugin.State.Closed(PluginException("此版本的milky-console只支持 schema-version 为 [${protocolSupportRange}] 的 版本。当前插件:$id 的版本为 $manifestVersion"))
            log.i { "<<< Plugin.verify() exit, result=false (protocol_version out of range)" }
            return false
        }
        log.v { "verify: protocol_version $protocolVersion is in supported range" }
        log.d { "[group: protocol-range] protocolVersion=$protocolVersion in $protocolSupportRange, match=true" }

        log.v { "verify: deserializing full PluginManifest" }
        try {
            Json.decodeFromJsonElement<PluginManifest>(json)
        } catch (ex: Exception) {
            log.e { "verify: failed to deserialize PluginManifest for $id: ${ex.message}" }
            _state.value = Plugin.State.Closed(PluginException("无法序列化插件:$id", ex))
            log.i { "<<< Plugin.verify() exit, result=false (deserialization failed)" }
            return false
        }
    }
    log.d { "[group: manifest-schema] PluginManifest deserialized successfully" }

    //校验动态库是否存在
    log.v { "verify: checking platform directory at $platformPath" }
    if (fs.metadataOrNull(platformPath)?.isDirectory == false) {
        log.e { "verify: platform directory is missing or not a directory" }
        _state.value = Plugin.State.Closed(PluginException("插件: ${manifest.id} 缺少动态库文件夹"))
        log.i { "<<< Plugin.verify() exit, result=false (no platform dir)" }
        return false
    }
    log.v { "verify: platform directory exists" }
    log.d { "[group: platform-dir] platform directory exists, expected=true, match=true" }

    //文件名：(platform)-(arch).(extension)
    @OptIn(ExperimentalNativeApi::class)
    val osFamily = Platform.osFamily.name
    @OptIn(ExperimentalNativeApi::class)
    val cpuArch = Platform.cpuArchitecture.name
    log.v { "verify: searching for platform library file, osFamily=$osFamily, arch=$cpuArch" }
    @OptIn(ExperimentalNativeApi::class)
    val dllibFile = fs.list(platformPath).find {
        val extension = when (Platform.osFamily) {
            OsFamily.WINDOWS -> {
                log.v { "verify: OS=WINDOWS, extension=dll" }
                "dll"
            }
            OsFamily.LINUX -> {
                log.v { "verify: OS=LINUX, extension=so" }
                "so"
            }
            OsFamily.MACOSX -> {
                log.v { "verify: OS=MACOSX, extension=dylib" }
                "dylib"
            }
            else -> {
                log.e { "verify: unsupported platform $osFamily" }
                _state.value = Plugin.State.Closed(PluginException("插件: ${manifest.id} 不支持本平台"))
                null
            }
        }
        val expectedName = "$osFamily-$cpuArch.$extension"
        log.v { "verify: checking file=${it.name}, expected=$expectedName, match=${it.name == expectedName}" }
        it.name == expectedName
    }

    if (dllibFile == null) {
        log.e { "verify: no matching library file found for platform $osFamily-$cpuArch" }
        _state.value = Plugin.State.Closed(PluginException("插件: ${manifest.id} 不支持本平台"))
        log.i { "<<< Plugin.verify() exit, result=false (library not found)" }
        return false
    }
    log.v { "verify: found library file: ${dllibFile.name}" }
    log.d { "[group: library-file] library found: ${dllibFile.name}, expected=$osFamily-$cpuArch.*, match=true" }

    log.v { "verify: checking default-config.json at $defaultConfigPath" }
    val defaultConfig = if (!fs.exists(defaultConfigPath)) {
        log.v { "verify: default-config.json does not exist, using empty object" }
        buildJsonObject { }
    } else fs.read(defaultConfigPath) {
        log.v { "verify: default-config.json exists, parsing" }
        try {
            Json.decodeFromString(readUtf8())
        } catch (_: Exception) {
            log.e { "verify: malformed default-config.json for ${manifest.id}" }
            _state.value = Plugin.State.Closed(PluginException("插件: ${manifest.id} 提供了错误的default-config.json"))
            log.i { "<<< Plugin.verify() exit, result=false (bad default config)" }
            return false
        }
    }
    log.d { "[group: default-config] defaultConfig parsed, empty=${(defaultConfig as? JsonObject)?.isEmpty()}" }

    _state.value = Plugin.State.Verified(dllibFile, manifest, defaultConfig)
    log.i { "<<< Plugin.verify() exit, result=true (transition to Verified)" }
    return true
}
