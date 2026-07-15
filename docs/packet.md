# 封包协议

本文档描述 `plugin/protocol` 模块中 `Packet` 的二进制线格式。实现以 `top.kagg886.milky.console.util.protocol.readPacket` 和 `writePacket` 为准。

## 基本结构

每个传输包由固定包头、可选分包头和数据区组成：

```text
+-----------------------+-----------------------+----------------+
| fixedHeader(31 bytes) | splitHeader(24 bytes) | data(dataSize) |
+-----------------------+-----------------------+----------------+
```

所有整数均使用 Okio `Buffer` 的默认写入方式，即大端序：

- `Short`: 2 bytes
- `Int`: 4 bytes
- `Uuid`: 16 bytes，按 `Uuid.toByteArray()` 输出

## 固定包头

固定包头每个包都必须存在。

| 字段         |      长度 | 类型    | 说明                                              |
|------------|--------:|-------|-------------------------------------------------|
| `magic`    | 8 bytes | bytes | 固定为 `MAGIC_BYTES`，当前为 `0C 0A 0F 0E 0B 0A 0B 0E` |
| `schema`   | 2 bytes | short | 协议版本，当前为 `1`                                    |
| `dataSize` | 4 bytes | int   | 数据区长度，不能为负数                                     |
| `uuid`     | 16 bytes | UUID bytes | 本次封包的唯一标识                                      |
| `split`    |  1 byte | byte  | 是否为分包，`0` 表示非分包，`1` 表示分包                        |

当前构建配置：

| 配置                | 值                         |
|-------------------|---------------------------|
| `MAGIC_BYTES`     | `0C 0A 0F 0E 0B 0A 0B 0E` |
| `SCHEMA_VERSION`  | `1`                       |
| `MAX_PACKET_SIZE` | `512 * 1024` bytes        |

## 分包头

当固定包头中的 `split` 为 `1` 时，固定包头后必须紧跟分包头。

| 字段      |       长度 | 类型         | 说明              |
|---------|---------:|------------|-----------------|
| `group` | 16 bytes | UUID bytes | 同一组分包的标识        |
| `index` |  4 bytes | int        | 当前分包下标，从 `0` 开始 |
| `size`  |  4 bytes | int        | 当前组分包总数         |

分包头长度：

```text
16 + 4 + 4 = 24 bytes
```

分包必须满足：

- `size > 1`
- `index in 0 until size`
- 同一组分包的 `group` 必须一致
- 同一组分包声明的 `size` 必须一致
- 合并时必须收齐 `0..size-1` 的所有分包，不能缺失或重复

## 拆包规则

`Packet.split()` 用于将过大的非分包拆成多个分包。

- 如果原始 `data.size <= MAX_SINGLE_PACKET_DATA_SIZE`，返回只包含原包的列表。
- 如果原始 `data.size > MAX_SINGLE_PACKET_DATA_SIZE`，按 `MAX_SPLIT_PACKET_DATA_SIZE` 切分数据。
- 拆分后的每个分包使用同一个 `group`，且各自拥有唯一的 `uuid`。
- 分包 `index` 从 `0` 递增。
- 分包 `size` 等于拆分后的分包总数。
- 不能对已经带有 `index` 或 `size` 的分包再次拆分。

拆包数量计算：

```text
packetCount = ceil(data.size / MAX_SPLIT_PACKET_DATA_SIZE)
```

## 合包规则

`List<Packet>.merge()` 用于将分包合并回一个非分包。

- 空列表不能合并。
- 如果列表只有一个包且该包不是分包，直接返回该包。
- 待合并列表必须全部是分包。
- 所有分包的 `group` 必须相同。
- 所有分包声明的 `size` 必须相同。
- 实际分包数量必须等于声明的 `size`。
- 合并前会按 `index` 排序。
- 排序后 `index` 必须完整连续，即 `0, 1, ..., size - 1`。

合并后的包：

- `index = null`
- `size = null`
- 合并后的包会生成新的唯一 `uuid`
- `group = null`
- `data` 为所有分包数据按 `index` 顺序拼接的结果

## 读取校验

读取端按以下顺序校验：

- 固定包头必须完整可读。
- `magic` 必须等于 `MAGIC_BYTES`。
- `schema` 必须等于当前 `SCHEMA_VERSION`。
- `dataSize` 不能为负数。
- `split` 只能是 `0` 或 `1`。
- 非分包 `dataSize <= MAX_SINGLE_PACKET_DATA_SIZE`。
- 分包 `dataSize <= MAX_SPLIT_PACKET_DATA_SIZE`。
- 分包头必须完整可读。
- 分包的 `index` 与 `size` 必须满足分包规则。
- 数据区必须完整读取 `dataSize` 字节。

任一校验失败都会抛出 `IllegalArgumentException`。

## 写入校验

写入端按以下规则校验：

- `index` 和 `size` 必须同时为空，或同时非空。
- 当 `index` 和 `size` 非空时，按分包写入，并校验分包规则。
- 包总长度不能超过 `MAX_PACKET_SIZE`。
- 写入数据时不会消费原始 `Buffer`，实现使用 `copyTo` 复制数据。

包总长度计算：

```text
totalSize = fixedHeaderSize + optionalSplitHeaderSize + data.size
```

## Packet 字段语义

```kotlin
data class Packet(
    val index: Int? = null,
    val size: Int? = null,
    val uuid: Uuid = Uuid.random(),
    val group: Uuid? = null,
    val data: Buffer = Buffer()
)
```

| 字段 | 说明 |
| --- | --- |
| `index` | 分包下标。非分包为 `null` |
| `size` | 分包总数。非分包为 `null` |
| `uuid` | 封包的唯一标识，写入固定包头 |
| `group` | 分组标识，仅分包时非空，写入分包头 |
| `data` | 实际承载的数据，协议不关心其内容格式 |

是否为分包由 `index != null && size != null` 判断；分包时 `group` 必须非空，非分包时必须为空。
