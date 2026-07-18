# Milky Console

Milky Console 是一个 Kotlin/Native 控制台宿主：它连接 Saltify 服务的事件流，将收到的 Milky 事件转发给本机原生插件，并让插件通过宿主 API 调用 Saltify HTTP 接口。

本说明面向部署者，目标是让你从源码构建并运行一个可工作的宿主与示例插件。插件包格式、生命周期和底层封包格式另见 [`docs/`](docs/)。

> 当前项目仍在开发中。插件在启动时加载；运行中的热插拔尚未实现。

## 工作方式

```text
Saltify 服务 ── HTTP / WebSocket（或 SSE）──> Milky Console
                                                │
                                                ├── loader 进程 ──> 原生插件动态库
                                                │                         │
                                                └── 插件调用 C Host API ───┘
```

宿主启动时扫描工作目录中 `milky-console/plugin/` 下的插件。每个插件由独立的 `loader` 进程加载，以 C ABI 与动态库交互；收到 Saltify 的事件后，宿主会将事件 JSON 发送给所有已就绪插件。

## 支持的平台

| 平台 | Kotlin/Native 目标 | 宿主/插件文件格式 |
| --- | --- | --- |
| Linux x86_64 | `linuxX64` | 可执行文件、`.so` |
| macOS Apple Silicon | `macosArm64` | 可执行文件、`.dylib` |
| Windows x86_64 | `mingwX64` | `.exe`、`.dll` |

请在目标平台上构建和运行对应产物。项目使用 Gradle Wrapper（Gradle 9.5.1）和 Kotlin 2.4.0；首次构建会下载 Kotlin/Native 工具链。

## 准备环境

部署机需要：

- Git；
- JDK（用于运行 Gradle；建议使用当前 Kotlin/Gradle 兼容的 LTS JDK）；
- 目标平台的 C/C++ 编译工具链和 CMake（构建测试原生插件时需要）；
- 一个可访问的 Saltify 服务实例及其访问令牌。

Linux/macOS 使用 `./gradlew`；Windows 使用 `gradlew.bat`。以下命令以 Linux x86_64 为例；macOS 和 Windows 只需把 Gradle 任务中的目标名替换为 `MacosArm64` 或 `MingwX64`。

## 从源码部署

### 1. 获取代码并配置 Saltify

```bash
git clone <仓库地址> milky-console
cd milky-console
```

在 [`core/src/commonMain/kotlin/main.kt`](core/src/commonMain/kotlin/main.kt) 的 `SaltifyApplication` 配置中填入服务地址和访问令牌：

```kotlin
connection {
    baseUrl = "http://<Saltify 主机>:30001"
    accessToken = "<访问令牌>"
    events {
        type = EventConnectionType.WebSocket
        autoReconnect = true
    }
}
```

如你的服务使用 SSE，可将 `EventConnectionType.WebSocket` 改为相应的 `SSE` 枚举值。请勿把含真实令牌的修改提交到公共仓库。

### 2. 构建宿主和 loader

在 Linux x86_64 上执行：

```bash
./gradlew :core:linkReleaseExecutableLinuxX64 \
          :plugin:loader:linkReleaseExecutableLinuxX64
```

生成的关键文件为：

```text
core/build/bin/linuxX64/releaseExecutable/core.kexe
plugin/loader/build/bin/linuxX64/releaseExecutable/loader.kexe
```

创建一个发布目录，并将 loader 重命名为宿主所要求的文件名：

```bash
mkdir -p dist/milky-console
cp core/build/bin/linuxX64/releaseExecutable/core.kexe dist/milky-console/milky-console
mkdir -p dist/milky-console/milky-console
cp plugin/loader/build/bin/linuxX64/releaseExecutable/loader.kexe dist/milky-console/milky-console/loader
chmod +x dist/milky-console/milky-console dist/milky-console/milky-console/loader
```

在 macOS 上，产物目录中的目标名为 `macosArm64`；在 Windows 上为 `mingwX64`，并将宿主命名为 `milky-console.exe`、loader 命名为 `loader.exe`。loader 必须位于宿主工作目录的 `milky-console/` 下，具体位置见下一节。

### 3. 构建并投放示例插件

示例插件提供 `help`、`/help`、`echo <内容>` 和 `/echo <内容>` 命令。为当前构建机打包：

```bash
./gradlew :plugin:sample:packagePlugin
```

插件输出位于 `plugin/sample/build/plugin/`。将其复制到发布目录下的插件目录：

```bash
mkdir -p dist/milky-console/milky-console/plugin/sample
cp -R plugin/sample/build/plugin/. dist/milky-console/milky-console/plugin/sample/
```

发布目录以下文统一称为 `base/`。三个平台的最终布局分别如下：

**Linux x86_64**

```text
base/
├── milky-console                      # 宿主可执行文件
└── milky-console/                     # 宿主的工作目录
    ├── loader                          # loader 可执行文件，名称必须精确匹配
    └── plugin/
        └── sample/
            ├── manifest.json
            ├── default-config.json
            └── platform/
                └── LINUX-X64.so
```

**macOS Apple Silicon**

```text
base/
├── milky-console
└── milky-console/
    ├── loader
    └── plugin/
        └── sample/
            ├── manifest.json
            ├── default-config.json
            └── platform/
                └── MACOSX-ARM64.dylib
```

**Windows x86_64**

```text
base/
├── milky-console.exe
└── milky-console/
    ├── loader.exe
    └── plugin/
        └── sample/
            ├── manifest.json
            ├── default-config.json
            └── platform/
                └── WINDOWS-X64.dll
```

注意 `platform/` 中的文件名区分大小写。加载器会按当前运行平台查找上述精确文件名，而不是任意名称的动态库。

### 4. 启动并验证

宿主会以**当前工作目录**下的 `milky-console/` 作为数据目录，因此应从发布目录启动：

```bash
cd dist/milky-console
./milky-console
```

首次启动会自动创建：

```text
milky-console/
├── config/       # 每个插件的运行配置，文件名为 <插件 id>.json
├── container/    # 按插件 id 隔离的数据目录
└── plugin/       # 待加载的插件包
```

日志中出现已连接 Saltify 与成功加载插件的记录，即表示部署完成。向机器人发送 `help` 或 `echo hello` 可验证示例插件是否能接收事件并回复。

## 管理插件

每个插件包放在 `milky-console/plugin/<任意目录名>/` 下；目录名不必等于插件 ID，但包内必须包含：

```text
<plugin>/
├── manifest.json
├── default-config.json                # 可选；必须是 JSON 对象
└── platform/
    └── <平台>-<架构>.<扩展名>
```

`manifest.json` 至少需要包含唯一 `id`、名称、版本、描述以及当前支持的 `manifest_version: 1` 和 `protocol_version: 1`。完整字段说明见 [插件包结构](docs/plugin.md)。

宿主在首次加载时将 `default-config.json` 复制为 `milky-console/config/<插件 id>.json`；之后应修改这一份运行配置。配置损坏时，宿主会备份损坏文件并重新初始化配置。新增、删除或替换插件后请重启宿主。

## 使用 C API 开发插件

公共 C 头文件是 [`plugin/api/include/milky_console.h`](plugin/api/include/milky_console.h)。插件须导出：

```c
const milky_console_plugin_api_t *
milky_plugin_get_api(unsigned int requested_abi_version);
```

返回的 `milky_console_plugin_api_t` 必须声明匹配的 ABI 版本和结构体大小，并提供以下回调：

- `on_load(config_json, host_api)`：初始化插件并保存宿主 API；成功返回 `MILKY_TRUE`。
- `on_message(message)`：接收以 NUL 结尾的 UTF-8 事件 JSON。
- `on_unload()`：停止使用宿主 API、释放资源并返回结果码。

宿主 API 的 `send_message(type, message)` 可调用 Saltify 接口；例如 `type` 为 `/send_private_message` 或 `/send_group_message`，`message` 为对应请求 JSON。`wait_message_result` 可按消息 UUID 等待调用结果。不要释放或修改宿主传入的 API 结构体，也不要在 `on_unload` 返回后继续使用它。

可参考 Kotlin/Native 实现的 [示例插件源码](plugin/sample/src/nativeMain/kotlin/top/kagg886/milky/console/sample/SamplePlugin.kt)。C/C++ 插件同样只需包含该头文件、导出相同符号，并按上述插件包格式放置动态库。

## 常用构建与检查命令

```bash
# 调试构建并直接运行宿主（仍需预先放置 loader 和插件）
./gradlew :core:runDebugExecutableLinuxX64

# 运行当前平台的核心测试
./gradlew :core:linuxX64Test

# 打包指定目标的示例插件
./gradlew :plugin:sample:packageReleaseLinuxX64Plugin
./gradlew :plugin:sample:packageReleaseMacosArm64Plugin
./gradlew :plugin:sample:packageReleaseMingwX64Plugin
```

## 排障

| 现象 | 检查方式 |
| --- | --- |
| 提示找不到 loader | 确认 `milky-console/loader`（Windows 为 `milky-console/loader.exe`）存在并具有执行权限。 |
| 插件未加载 | 检查 `manifest.json`、版本字段、动态库名称与大小写，以及目标平台/架构是否匹配。 |
| 已加载但没有回复 | 确认 Saltify 地址和令牌正确、事件连接已建立，并检查插件是否处理该事件类型。 |
| 配置没有生效 | 修改 `milky-console/config/<插件 id>.json`，而不是仅修改包内的 `default-config.json`，然后重启。 |
| 动态库握手失败 | 确认导出了 `milky_plugin_get_api`，ABI 版本为 1，且 API 结构体大小与头文件匹配。 |

## 参考文档与目录

- [插件包结构](docs/plugin.md)
- [插件生命周期与握手](docs/plugin-lifecycle.md)
- [进程间封包协议](docs/packet.md)
- `core/`：宿主应用、插件注册与 Saltify 事件连接。
- `plugin/loader/`：隔离加载动态库的原生 loader。
- `plugin/api/`：公共 C ABI。
- `plugin/sample/`：可直接打包部署的示例插件。
- `utils/`：管道、进程、动态库加载、日志和事件总线等基础模块。
