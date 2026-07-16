package top.kagg886.milky.console.util.protocol

import okio.Buffer
import okio.Sink
import okio.Source
import top.kagg886.milky.console.protocol.BuildConfig
import kotlin.uuid.Uuid

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/14 13:01
 * ================================================
 */

/** magic、schema、data size、split 标记与包 UUID 占用的固定包头大小。 */
internal val FIXED_HEADER_SIZE: Long = BuildConfig.MAGIC_BYTES.size.toLong() + 2L + 4L + 1L + 16L

/** 分包组 UUID、index 与总包数占用的额外包头大小。 */
internal const val SPLIT_HEADER_SIZE = 16L + 4L + 4L

/** 不分包时，data 可占用的最大字节数。 */
internal val MAX_SINGLE_PACKET_DATA_SIZE: Long = BuildConfig.MAX_PACKET_SIZE - FIXED_HEADER_SIZE

/** 分包时，每个分包 data 可占用的最大字节数。 */
internal val MAX_SPLIT_PACKET_DATA_SIZE: Long = MAX_SINGLE_PACKET_DATA_SIZE - SPLIT_HEADER_SIZE


/** 此包是否为一个分包。 */
val Packet.isSplit: Boolean
    get() = index != null && size != null && group != null

internal val Packet.headerSize: Long
    get() = FIXED_HEADER_SIZE + if (isSplit) SPLIT_HEADER_SIZE else 0L

/** 包头加 data 后的实际传输字节数。 */
internal val Packet.totalSize: Long
    get() = headerSize + data.size



private const val NOT_SPLIT = 0
private const val IS_SPLIT = 1

fun Source.readPacket(): Packet {
    val header = readExactly(FIXED_HEADER_SIZE)

    require(header.readByteArray(BuildConfig.MAGIC_BYTES.size.toLong()).contentEquals(BuildConfig.MAGIC_BYTES)) { "非法的 magic bytes" }
    require(header.readShort() == BuildConfig.SCHEMA_VERSION) { "不支持的 schema 版本" }

    val dataSize = header.readInt()
    require(dataSize >= 0) { "包体大小不能为负数" }
    val uuid = Uuid.fromByteArray(header.readByteArray(16L))
    return when (val split = header.readByte().toInt()) {
        NOT_SPLIT -> {
            require(dataSize <= MAX_SINGLE_PACKET_DATA_SIZE) { "不能传输过大的bytes" }
            Packet(uuid = uuid, data = readExactly(dataSize.toLong()))
        }
        IS_SPLIT -> {
            require(dataSize <= MAX_SPLIT_PACKET_DATA_SIZE) { "不能传输过大的bytes" }
            val splitHeader = readExactly(SPLIT_HEADER_SIZE)
            val group = Uuid.fromByteArray(splitHeader.readByteArray(16L))
            val index = splitHeader.readInt()
            val count = splitHeader.readInt()
            validateSplit(index, count)
            Packet(index = index, size = count, uuid = uuid, group = group, data = readExactly(dataSize.toLong()))
        }

        else -> throw IllegalArgumentException("非法的 split 标记: $split")
    }
}

fun Sink.writePacket(packet: Packet) {
    val index = packet.index
    val count = packet.size
    require((index == null) == (count == null)) { "分包 index 和总包数必须同时提供" }
    require(packet.isSplit == (packet.group != null)) { "分包 group 必须且只能在分包时提供" }
    if (index != null && count != null) {
        validateSplit(index, count)
    }

    val dataSize = packet.data.size
    require(packet.totalSize <= BuildConfig.MAX_PACKET_SIZE.toLong()) { "不能传输过大的bytes" }

    val encoded = Buffer()
        .write(BuildConfig.MAGIC_BYTES)
        .writeShort(BuildConfig.SCHEMA_VERSION.toInt())
        .writeInt(dataSize.toInt())
        .write(packet.uuid.toByteArray())
        .writeByte(if (packet.isSplit) IS_SPLIT else NOT_SPLIT)

    if (index != null && count != null) {
        encoded.write(requireNotNull(packet.group).toByteArray())
        encoded.writeInt(index)
        encoded.writeInt(count)
    }
    packet.data.copyTo(encoded, byteCount = dataSize)

    write(encoded, encoded.size)
}

fun Packet.split(): List<Packet> {
    require(index == null && size == null) { "不能再次拆分一个分包" }
    require(group == null) { "不能再次拆分一个分包" }
    if (data.size <= MAX_SINGLE_PACKET_DATA_SIZE) {
        return listOf(this)
    }

    val packetCount = ((data.size + MAX_SPLIT_PACKET_DATA_SIZE - 1L) /
            MAX_SPLIT_PACKET_DATA_SIZE).toInt()
    var offset = 0L
    val group = Uuid.random()

    return List(packetCount) { index ->
        val part = Buffer()
        val byteCount = minOf(data.size - offset, MAX_SPLIT_PACKET_DATA_SIZE)
        data.copyTo(part, offset = offset, byteCount = byteCount)
        offset += byteCount
        Packet(index = index, size = packetCount, group = group, data = part)
    }
}

fun List<Packet>.merge(): Packet {
    require(isNotEmpty()) { "不能合并空的分包列表" }

    val first = first()
    if (size == 1 && !first.isSplit) {
        return first
    }

    require(all(Packet::isSplit)) { "待合并的包必须全部为分包" }

    val group = requireNotNull(first.group)
    require(all { it.group == group }) { "不能合并 group 不同的分包" }

    val packetCount = requireNotNull(first.size)
    require(all { it.size == packetCount }) { "分包声明的总包数必须一致" }
    require(size == packetCount) { "分包数量不完整，需要 $packetCount 个，实际收到 $size 个" }

    val sortedPackets = sortedBy { it.index }
    sortedPackets.forEachIndexed { expectedIndex, packet ->
        require(packet.index == expectedIndex) { "分包 index 不完整或重复，期望 $expectedIndex，实际 ${packet.index}" }
    }

    val mergedData = Buffer()
    sortedPackets.forEach { packet ->
        packet.data.copyTo(mergedData)
    }

    return Packet(data = mergedData)
}

private fun Source.readExactly(byteCount: Long): Buffer {
    val result = Buffer()
    while (result.size < byteCount) {
        val read = read(result, byteCount - result.size)
        if (read == -1L) {
            throw IllegalArgumentException("数据包不完整，需要 $byteCount bytes，实际读取 ${result.size} bytes")
        }
        require(read > 0L) { "Source 在数据包读取完成前返回了 0 bytes" }
    }
    return result
}

private fun validateSplit(index: Int, count: Int) {
    require(count > 1) { "分包总数必须大于 1" }
    require(index in 0 until count) { "分包 index 必须在 0..<总包数 范围内" }
}
