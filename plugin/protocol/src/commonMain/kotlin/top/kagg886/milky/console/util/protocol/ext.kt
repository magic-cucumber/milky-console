package top.kagg886.milky.console.util.protocol

import okio.Buffer
import okio.Sink
import okio.Source
import co.touchlab.kermit.Logger
import top.kagg886.milky.console.protocol.BuildConfig
import kotlin.uuid.Uuid

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/14 13:01
 * ================================================
 */

private val logger = Logger.withTag("ProtocolPacketCodec")

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
    logger.v { "enter readPacket" }
    val header = readExactly(FIXED_HEADER_SIZE)
    logger.d { "read fixed packet header: bytes=${header.size}, expected=$FIXED_HEADER_SIZE" }

    val magicMatched = header.readByteArray(BuildConfig.MAGIC_BYTES.size.toLong()).contentEquals(BuildConfig.MAGIC_BYTES)
    if (!magicMatched) {
        logger.e { "exit readPacket unsuccessfully: invalid magic bytes" }
    }
    require(magicMatched) { "非法的 magic bytes" }
    val schemaVersion = header.readShort()
    if (schemaVersion != BuildConfig.SCHEMA_VERSION) {
        logger.e { "exit readPacket unsuccessfully: unsupported schema version=$schemaVersion, expected=${BuildConfig.SCHEMA_VERSION}" }
    }
    require(schemaVersion == BuildConfig.SCHEMA_VERSION) { "不支持的 schema 版本" }

    val dataSize = header.readInt()
    if (dataSize < 0) {
        logger.e { "exit readPacket unsuccessfully: negative data size=$dataSize" }
    }
    require(dataSize >= 0) { "包体大小不能为负数" }
    val uuid = Uuid.fromByteArray(header.readByteArray(16L))
    return when (val split = header.readByte().toInt()) {
        NOT_SPLIT -> {
            logger.v { "readPacket entered non-split branch: uuid=$uuid, dataSize=$dataSize" }
            if (dataSize > MAX_SINGLE_PACKET_DATA_SIZE) {
                logger.e { "exit readPacket unsuccessfully: non-split payload too large, size=$dataSize, max=$MAX_SINGLE_PACKET_DATA_SIZE" }
            }
            require(dataSize <= MAX_SINGLE_PACKET_DATA_SIZE) { "不能传输过大的bytes" }
            val packet = Packet(uuid = uuid, data = readExactly(dataSize.toLong()))
            logger.i { "read complete packet: uuid=$uuid, bytes=${packet.data.size}" }
            logger.d { "non-split packet read result: totalSize=${packet.totalSize}, expectedWithinLimit=${packet.totalSize <= BuildConfig.MAX_PACKET_SIZE}" }
            logger.v { "exit readPacket successfully: uuid=$uuid, split=false" }
            packet
        }
        IS_SPLIT -> {
            logger.v { "readPacket entered split branch: uuid=$uuid, dataSize=$dataSize" }
            if (dataSize > MAX_SPLIT_PACKET_DATA_SIZE) {
                logger.e { "exit readPacket unsuccessfully: split payload too large, size=$dataSize, max=$MAX_SPLIT_PACKET_DATA_SIZE" }
            }
            require(dataSize <= MAX_SPLIT_PACKET_DATA_SIZE) { "不能传输过大的bytes" }
            val splitHeader = readExactly(SPLIT_HEADER_SIZE)
            logger.d { "read split packet header: bytes=${splitHeader.size}, expected=$SPLIT_HEADER_SIZE" }
            val group = Uuid.fromByteArray(splitHeader.readByteArray(16L))
            val index = splitHeader.readInt()
            val count = splitHeader.readInt()
            validateSplit(index, count)
            val packet = Packet(index = index, size = count, uuid = uuid, group = group, data = readExactly(dataSize.toLong()))
            logger.i { "read split packet: uuid=$uuid, group=$group, index=$index, count=$count, bytes=${packet.data.size}" }
            logger.d { "split packet read result: totalSize=${packet.totalSize}, expectedWithinLimit=${packet.totalSize <= BuildConfig.MAX_PACKET_SIZE}" }
            logger.v { "exit readPacket successfully: uuid=$uuid, split=true" }
            packet
        }

        else -> {
            logger.e { "exit readPacket unsuccessfully: invalid split marker=$split" }
            throw IllegalArgumentException("非法的 split 标记: $split")
        }
    }
}

fun Sink.writePacket(packet: Packet) {
    logger.v { "enter writePacket: uuid=${packet.uuid}, split=${packet.isSplit}, dataBytes=${packet.data.size}" }
    val index = packet.index
    val count = packet.size
    if ((index == null) != (count == null)) {
        logger.e { "exit writePacket unsuccessfully: split index/count mismatch, index=$index, count=$count" }
    }
    require((index == null) == (count == null)) { "分包 index 和总包数必须同时提供" }
    if (packet.isSplit != (packet.group != null)) {
        logger.e { "exit writePacket unsuccessfully: split group mismatch, split=${packet.isSplit}, group=${packet.group}" }
    }
    require(packet.isSplit == (packet.group != null)) { "分包 group 必须且只能在分包时提供" }
    if (index != null && count != null) {
        logger.v { "writePacket entered split validation branch: group=${packet.group}, index=$index, count=$count" }
        validateSplit(index, count)
    } else {
        logger.v { "writePacket entered non-split validation branch: uuid=${packet.uuid}" }
    }

    val dataSize = packet.data.size
    if (packet.totalSize > BuildConfig.MAX_PACKET_SIZE.toLong()) {
        logger.e { "exit writePacket unsuccessfully: packet too large, total=${packet.totalSize}, max=${BuildConfig.MAX_PACKET_SIZE}" }
    }
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
        logger.d { "encoded split packet header: uuid=${packet.uuid}, group=${packet.group}, index=$index, count=$count" }
    } else {
        logger.d { "encoded non-split packet header: uuid=${packet.uuid}" }
    }
    packet.data.copyTo(encoded, byteCount = dataSize)
    logger.d { "encoded packet payload: uuid=${packet.uuid}, payloadBytes=$dataSize, totalBytes=${encoded.size}, expectedWithinLimit=${encoded.size <= BuildConfig.MAX_PACKET_SIZE}" }

    write(encoded, encoded.size)
    logger.i { "wrote packet: uuid=${packet.uuid}, split=${packet.isSplit}, bytes=${packet.totalSize}" }
    logger.v { "exit writePacket successfully: uuid=${packet.uuid}" }
}

fun Packet.split(): List<Packet> {
    logger.v { "enter split: uuid=$uuid, dataBytes=${data.size}, alreadySplit=$isSplit" }
    if (index != null || size != null) {
        logger.e { "exit split unsuccessfully: packet already has split markers, index=$index, size=$size" }
    }
    require(index == null && size == null) { "不能再次拆分一个分包" }
    if (group != null) {
        logger.e { "exit split unsuccessfully: packet already has group=$group" }
    }
    require(group == null) { "不能再次拆分一个分包" }
    if (data.size <= MAX_SINGLE_PACKET_DATA_SIZE) {
        logger.v { "split entered non-split branch: uuid=$uuid, dataBytes=${data.size}, max=$MAX_SINGLE_PACKET_DATA_SIZE" }
        logger.d { "split result: packet kept whole, expected=true" }
        logger.v { "exit split successfully: packetCount=1" }
        return listOf(this)
    }

    logger.v { "split entered chunking branch: uuid=$uuid, dataBytes=${data.size}, chunkSize=$MAX_SPLIT_PACKET_DATA_SIZE" }
    val packetCount = ((data.size + MAX_SPLIT_PACKET_DATA_SIZE - 1L) /
            MAX_SPLIT_PACKET_DATA_SIZE).toInt()
    var offset = 0L
    val group = Uuid.random()

    val packets = List(packetCount) { index ->
        val part = Buffer()
        val byteCount = minOf(data.size - offset, MAX_SPLIT_PACKET_DATA_SIZE)
        data.copyTo(part, offset = offset, byteCount = byteCount)
        offset += byteCount
        logger.v { "created split packet part: group=$group, index=$index, count=$packetCount, bytes=$byteCount, nextOffset=$offset" }
        Packet(index = index, size = packetCount, group = group, data = part)
    }
    logger.i { "split packet payload: group=$group, packetCount=${packets.size}, bytes=${data.size}" }
    logger.d { "split result: parts=${packets.size}, expected=$packetCount, complete=${offset == data.size}" }
    logger.v { "exit split successfully: packetCount=${packets.size}" }
    return packets
}

fun List<Packet>.merge(): Packet {
    logger.v { "enter merge: packetCount=$size" }
    if (isEmpty()) {
        logger.e { "exit merge unsuccessfully: empty packet list" }
    }
    require(isNotEmpty()) { "不能合并空的分包列表" }

    val first = first()
    if (size == 1 && !first.isSplit) {
        logger.v { "merge entered single non-split branch: uuid=${first.uuid}" }
        logger.d { "merge result: packet returned as-is, expected=true" }
        logger.v { "exit merge successfully: split=false, bytes=${first.data.size}" }
        return first
    }

    logger.v { "merge entered split packet branch: packetCount=$size" }
    if (!all(Packet::isSplit)) {
        logger.e { "exit merge unsuccessfully: not all packets are split" }
    }
    require(all(Packet::isSplit)) { "待合并的包必须全部为分包" }

    val group = requireNotNull(first.group)
    if (!all { it.group == group }) {
        logger.e { "exit merge unsuccessfully: inconsistent split group, expected=$group" }
    }
    require(all { it.group == group }) { "不能合并 group 不同的分包" }

    val packetCount = requireNotNull(first.size)
    if (!all { it.size == packetCount }) {
        logger.e { "exit merge unsuccessfully: inconsistent split count, expected=$packetCount" }
    }
    require(all { it.size == packetCount }) { "分包声明的总包数必须一致" }
    if (size != packetCount) {
        logger.w { "merge received incomplete split group: group=$group, expected=$packetCount, actual=$size" }
    }
    require(size == packetCount) { "分包数量不完整，需要 $packetCount 个，实际收到 $size 个" }

    val sortedPackets = sortedBy { it.index }
    sortedPackets.forEachIndexed { expectedIndex, packet ->
        if (packet.index != expectedIndex) {
            logger.e { "exit merge unsuccessfully: split index mismatch, group=$group, expected=$expectedIndex, actual=${packet.index}" }
        }
        require(packet.index == expectedIndex) { "分包 index 不完整或重复，期望 $expectedIndex，实际 ${packet.index}" }
        logger.v { "merge validated split part: group=$group, index=$expectedIndex, bytes=${packet.data.size}" }
    }

    val mergedData = Buffer()
    sortedPackets.forEach { packet ->
        packet.data.copyTo(mergedData)
        logger.v { "merge copied split part: group=$group, index=${packet.index}, mergedBytes=${mergedData.size}" }
    }

    val packet = Packet(data = mergedData)
    logger.i { "merged split packet group: group=$group, parts=$packetCount, bytes=${packet.data.size}" }
    logger.d { "merge result: bytes=${packet.data.size}, expectedNonEmpty=${packet.data.size > 0L}" }
    logger.v { "exit merge successfully: group=$group" }
    return packet
}

private fun Source.readExactly(byteCount: Long): Buffer {
    logger.v { "enter readExactly: byteCount=$byteCount" }
    val result = Buffer()
    while (result.size < byteCount) {
        logger.v { "readExactly loop enter: current=${result.size}, target=$byteCount" }
        val read = read(result, byteCount - result.size)
        if (read == -1L) {
            logger.e { "exit readExactly unsuccessfully: source ended early, expected=$byteCount, actual=${result.size}" }
            throw IllegalArgumentException("数据包不完整，需要 $byteCount bytes，实际读取 ${result.size} bytes")
        }
        if (read <= 0L) {
            logger.e { "exit readExactly unsuccessfully: non-positive read=$read before target, current=${result.size}, target=$byteCount" }
        }
        require(read > 0L) { "Source 在数据包读取完成前返回了 0 bytes" }
        logger.d { "readExactly iteration result: read=$read, current=${result.size}, reached=${result.size >= byteCount}" }
    }
    logger.v { "exit readExactly successfully: bytes=${result.size}" }
    return result
}

private fun validateSplit(index: Int, count: Int) {
    logger.v { "enter validateSplit: index=$index, count=$count" }
    if (count <= 1) {
        logger.e { "exit validateSplit unsuccessfully: count must be greater than 1, count=$count" }
    }
    require(count > 1) { "分包总数必须大于 1" }
    if (index !in 0 until count) {
        logger.e { "exit validateSplit unsuccessfully: index out of range, index=$index, count=$count" }
    }
    require(index in 0 until count) { "分包 index 必须在 0..<总包数 范围内" }
    logger.d { "validated split metadata: index=$index, count=$count, expected=true" }
    logger.v { "exit validateSplit successfully: index=$index, count=$count" }
}
