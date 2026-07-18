# milky-console sample plugin

这是一个仅依赖 `milky-types` 与 `:plugin:api` 的 Kotlin/Native 动态库插件示例，未依赖 `:plugin:protocol`。

它响应好友和群聊中的以下文本命令：

- `help` 或 `/help`：显示命令说明。
- `echo <内容>` 或 `/echo <内容>`：原样回复内容。

执行 `./gradlew :plugin:sample:packagePlugin` 会将当前平台的发行库和插件元数据组装到
`plugin/sample/build/plugin/`；目录结构符合宿主加载器要求：

```text
build/plugin/
├── manifest.json
├── default-config.json
└── platform/
    └── LINUX-X64.so
```

也可使用 `packageReleaseLinuxX64Plugin`、`packageReleaseMacosArm64Plugin` 或
`packageReleaseMingwX64Plugin` 明确打包某个目标。打包任务会自行依赖对应的 release link 任务。
