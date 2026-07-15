package top.kagg886.milky.console.protocol.util

import okio.Buffer
import top.kagg886.milky.console.protocol.BuildConfig
import top.kagg886.milky.console.util.protocol.FIXED_HEADER_SIZE
import top.kagg886.milky.console.util.protocol.MAX_SINGLE_PACKET_DATA_SIZE
import top.kagg886.milky.console.util.protocol.MAX_SPLIT_PACKET_DATA_SIZE
import top.kagg886.milky.console.util.protocol.Packet
import top.kagg886.milky.console.util.protocol.isSplit
import top.kagg886.milky.console.util.protocol.merge
import top.kagg886.milky.console.util.protocol.readPacket
import top.kagg886.milky.console.util.protocol.split
import top.kagg886.milky.console.util.protocol.totalSize
import top.kagg886.milky.console.util.protocol.writePacket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class PacketUtilTest {
    @Test
    fun writesAndReadsWholePacketWithoutConsumingData() {
        val original = Buffer().writeUtf8("already gzipped data")
        val packet = Packet(data = original)
        val wire = Buffer()

        wire.writePacket(packet)

        assertEquals("already gzipped data", original.copy().readUtf8())
        assertContentEquals(BuildConfig.MAGIC_BYTES, wire.copy().readByteArray(BuildConfig.MAGIC_BYTES.size.toLong()))
        val decoded = wire.readPacket()
        assertFalse(decoded.isSplit)
        assertEquals(packet.uuid, decoded.uuid)
        assertEquals("already gzipped data", decoded.data.readUtf8())
    }

    @Test
    fun writesAndReadsSplitPacket() {
        val uuid = Uuid.random()
        val group = Uuid.random()
        val wire = Buffer()
        wire.writePacket(Packet(index = 1, size = 3, uuid = uuid, group = group, data = Buffer().writeUtf8("part")))

        val decoded = wire.readPacket()
        assertTrue(decoded.isSplit)
        assertEquals(uuid, decoded.uuid)
        assertEquals(group, decoded.group)
        assertEquals(1, decoded.index)
        assertEquals(3, decoded.size)
        assertEquals("part", decoded.data.readUtf8())
    }

    @Test
    fun readsOnlyOnePacketAtATime() {
        val wire = Buffer()
        wire.writePacket(Packet(data = Buffer().writeUtf8("first")))
        wire.writePacket(Packet(data = Buffer().writeUtf8("second")))

        assertEquals("first", wire.readPacket().data.readUtf8())
        assertEquals("second", wire.readPacket().data.readUtf8())
    }

    @Test
    fun splitsOversizedDataWithoutConsumingOriginal() {
        val bytes = ByteArray((MAX_SINGLE_PACKET_DATA_SIZE + 1L).toInt()) { (it % 251).toByte() }
        val original = Buffer().write(bytes)

        val packets = Packet(data = original).split()

        assertEquals(2, packets.size)
        assertEquals(BuildConfig.MAX_PACKET_SIZE.toLong(), FIXED_HEADER_SIZE + MAX_SINGLE_PACKET_DATA_SIZE)
        assertEquals(MAX_SPLIT_PACKET_DATA_SIZE, packets[0].data.size)
        assertEquals(bytes.size.toLong() - MAX_SPLIT_PACKET_DATA_SIZE, packets[1].data.size)
        packets.forEachIndexed { index, packet ->
            assertEquals(index, packet.index)
            assertEquals(2, packet.size)
            assertEquals(packets.first().group, packet.group)
            assertTrue(packet.totalSize <= BuildConfig.MAX_PACKET_SIZE)
        }
        val joined = Buffer()
        packets.forEach { it.data.copyTo(joined) }
        assertContentEquals(bytes, joined.readByteArray())
        assertEquals(bytes.size.toLong(), original.size)
    }

    @Test
    fun rejectsOversizedPacketInsteadOfSplittingIt() {
        val packet = Packet(data = Buffer().write(ByteArray((MAX_SINGLE_PACKET_DATA_SIZE + 1L).toInt())))

        assertFailsWith<IllegalArgumentException> {
            Buffer().writePacket(packet)
        }
    }

    @Test
    fun rejectsDeclaredDataSizeAtMaximumPlusOneByte() {
        val wire = Buffer()
            .write(BuildConfig.MAGIC_BYTES)
            .writeShort(BuildConfig.SCHEMA_VERSION.toInt())
            .writeInt((MAX_SINGLE_PACKET_DATA_SIZE + 1L).toInt())
            .write(Uuid.random().toByteArray())
            .writeByte(0)

        assertFailsWith<IllegalArgumentException> {
            wire.readPacket()
        }
    }

    @Test
    fun rejectsIncompleteSplitMetadata() {
        assertFailsWith<IllegalArgumentException> {
            Buffer().writePacket(Packet(index = 0, data = Buffer()))
        }
    }

    @Test
    fun mergesShuffledPacketsWithoutConsumingTheirData() {
        val group = Uuid.random()
        val first = Packet(index = 0, size = 2, group = group, data = Buffer().writeUtf8("first"))
        val second = Packet(index = 1, size = 2, group = group, data = Buffer().writeUtf8("second"))

        val merged = listOf(second, first).merge()

        assertFalse(merged.isSplit)
        assertNotEquals(merged.uuid, first.uuid)
        assertEquals("firstsecond", merged.data.readUtf8())
        assertEquals("first", first.data.copy().readUtf8())
        assertEquals("second", second.data.copy().readUtf8())
    }

    @Test
    fun rejectsPacketsWithDifferentGroups() {
        val packets = listOf(
            Packet(index = 0, size = 2, group = Uuid.random(), data = Buffer().writeUtf8("first")),
            Packet(index = 1, size = 2, group = Uuid.random(), data = Buffer().writeUtf8("second")),
        )

        assertFailsWith<IllegalArgumentException> {
            packets.merge()
        }
    }

    @Test
    fun rejectsIncompleteOrDuplicatedPackets() {
        val group = Uuid.random()

        assertFailsWith<IllegalArgumentException> {
            listOf(Packet(index = 0, size = 2, group = group)).merge()
        }
        assertFailsWith<IllegalArgumentException> {
            listOf(
                Packet(index = 0, size = 2, group = group),
                Packet(index = 0, size = 2, group = group),
            ).merge()
        }
    }
}
