@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package top.kagg886.saltify.console.util.pipe

import kotlinx.cinterop.*
import okio.Buffer
import okio.IOException
import platform.windows.GetHandleInformation
import platform.windows.HANDLE_FLAG_INHERIT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PipeWindowsTest {
    @Test
    fun rejectsInvalidPipeHandle() {
        assertFailsWith<InvalidPipeException> { Pipe.fromReceivablePipe(ULong.MAX_VALUE) }
        assertFailsWith<InvalidPipeException> { Pipe.fromSendablePipe(ULong.MAX_VALUE) }
    }

    @Test
    fun endpointsAreInheritableByChildProcesses() = memScoped {
        val (source, sink) = Pipe.create()
        try {
            assertInheritable(source.descriptor)
            assertInheritable(sink.descriptor)
        } finally {
            source.close()
            sink.close()
        }
    }

    @Test
    fun sendsAndReceivesInTheSameProcess() {
        val (source, sink) = Pipe.create()
        try {
            sink.write(Buffer().writeUtf8("pipe payload"), 12)
            sink.flush()

            val received = Buffer()
            assertEquals(12, source.read(received, 12))
            assertEquals("pipe payload", received.readUtf8())
        } finally {
            source.close()
            sink.close()
        }
    }

    @Test
    fun closedEndpointsRejectFurtherOperations() {
        val (source, sink) = Pipe.create()
        source.close()
        sink.close()

        assertTrue(source.closed)
        assertTrue(sink.closed)
        assertFailsWith<IllegalStateException> { source.read(Buffer(), 1) }
        assertFailsWith<IllegalStateException> { sink.write(Buffer().writeByte(1), 1) }
    }

    @Test
    fun closeNotifiesHandlers() {
        val (source, sink) = Pipe.create()
        var sourceNotified = 0
        var sinkNotified = 0
        source.onClose { sourceNotified++ }
        sink.onClose { sinkNotified++ }

        source.close()
        sink.close()

        assertEquals(1, sourceNotified)
        assertEquals(1, sinkNotified)
    }

    @Test
    fun readerClosingMakesTheNextFlushFail() {
        val (source, sink) = Pipe.create()
        source.close()

        sink.write(Buffer().writeByte(1), 1)
        assertFailsWith<IOException> { sink.flush() }
    }

    @Test
    fun writerClosingMakesTheReaderReachEof() {
        val (source, sink) = Pipe.create()
        var notified = 0
        source.onClose { notified++ }
        sink.close()
        try {
            assertEquals(-1, source.read(Buffer(), 1))
            assertTrue(source.closed)
            assertEquals(1, notified)
        } finally {
            source.close()
        }
    }

    private fun assertInheritable(descriptor: ULong) = memScoped {
        val flags = alloc<UIntVar>()
        assertTrue(GetHandleInformation(descriptor.toHandle(), flags.ptr) != 0)
        assertTrue(flags.value and HANDLE_FLAG_INHERIT.toUInt() != 0u)
    }

    private fun ULong.toHandle(): COpaquePointer? = toLong().toCPointer()
}
