# 插件结构

本文档描述一个基本插件包的文件组成。

## 总览

```text
plugin/
├── *manifest.json
├── *default-config.json
└── platform/
    └── *(platform)-(arch).(extension)
```

## manifest.json

插件清单文件，用于声明插件的基础信息和协议版本。

字段结构：

```json
{
  "metadata": {
    "manifest_version": 1,
    "protocol_version": 1
  },
  "id": "com.example.plugin",
  "name": "plugin-name",
  "version": {
    "name": "1.0.0",
    "code": 1
  },
  "description": "plugin description"
}
```

| 字段                          | 类型     | 说明            |
|-----------------------------|--------|---------------|
| `metadata`                  | object | 清单元信息         |
| `metadata.manifest_version` | int    | 清单格式版本        |
| `metadata.protocol_version` | int    | 插件通信协议版本      |
| `id`                        | string | 插件唯一标识，要求全局唯一 |
| `name`                      | string | 插件名称          |
| `version`                   | object | 插件版本信息        |
| `version.name`              | string | 面向用户展示的版本名    |
| `version.code`              | int    | 用于比较升级的版本号    |
| `description`               | string | 插件描述          |

## default-config.json

插件默认配置文件，用于提供插件首次加载时使用的默认配置。

宿主可以基于该文件初始化插件配置；插件也可以读取其中的默认值作为运行时配置的兜底。

## platform/(platform)-(arch).(extension)

平台相关的插件动态库文件，用于承载插件的实际执行逻辑。

文件名中的占位符含义和可用值：

| 占位符          | 说明          | 可用值                       |
|--------------|-------------|---------------------------|
| `(platform)` | 目标操作系统或运行平台 | `windows`、`linux`、`macos` |
| `(arch)`     | 目标 CPU 架构   | `x64`、`arm64`             |

动态库扩展名extension由目标平台决定：

| 平台        | 扩展名      |
|-----------|----------|
| `windows` | `.dll`   |
| `linux`   | `.so`    |
| `macosx`  | `.dylib` |

示例：

```text
platform/windows-x64.dll
```
