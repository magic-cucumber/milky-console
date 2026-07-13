@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package top.kagg886.saltify.console.util.pipe

import okio.Buffer
import okio.IOException
import kotlinx.cinterop.refTo
import platform.posix._exit
import platform.posix.fork
import platform.posix.SIGPIPE
import platform.posix.SIG_IGN
import platform.posix.signal
import platform.posix.waitpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PipeUnixTest {
    @Test
    fun rejectsInvalidPipeDescriptor() {
        assertFailsWith<InvalidPipeException> { Pipe.fromReceivablePipe(ULong.MAX_VALUE) }
        assertFailsWith<InvalidPipeException> { Pipe.fromSendablePipe(ULong.MAX_VALUE) }
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
    fun eofNotifiesReaderCloseHandlers() {
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

    @Test
    fun parentAndChildCanExchangeDataAfterFork() {
        val (source, sink) = Pipe.create()
        when (val pid = fork()) {
            0 -> {
                source.close()
                try {
                    sink.write(Buffer().writeUtf8("child"), 5)
                    sink.close()
                    _exit(0)
                } catch (_: Throwable) {
                    _exit(1)
                }
            }

            -1 -> {
                source.close()
                sink.close()
                error("fork() failed")
            }

            else -> {
                sink.close()
                try {
                    val received = Buffer()
                    assertEquals(5, source.read(received, 5))
                    assertEquals("child", received.readUtf8())
                    assertEquals(-1, source.read(Buffer(), 1))
                    assertChildSucceeded(pid)
                } finally {
                    source.close()
                }
            }
        }
    }

    @Test
    fun childWriteFailsWhenEveryReaderWasClosed() {
        val (source, sink) = Pipe.create()
        val (readySource, readySink) = Pipe.create()
        when (val pid = fork()) {
            0 -> {
                source.close()
                readySink.close()
                signal(SIGPIPE, SIG_IGN)
                try {
                    assertEquals(1, readySource.read(Buffer(), 1))
                    sink.write(Buffer().writeByte(1), 1)
                    sink.flush()
                    _exit(1)
                } catch (_: IOException) {
                    _exit(0)
                } catch (_: Throwable) {
                    _exit(2)
                }
            }

            -1 -> {
                source.close()
                sink.close()
                readySource.close()
                readySink.close()
                error("fork() failed")
            }

            else -> {
                source.close()
                sink.close()
                readySource.close()
                readySink.write(Buffer().writeByte(1), 1)
                readySink.close()
                assertChildSucceeded(pid)
            }
        }
    }

    private fun assertChildSucceeded(pid: Int) {
        val status = IntArray(1)
        assertEquals(pid, waitpid(pid, status.refTo(0), 0))
        assertFalse(status[0] and 0x7f != 0, "child was terminated by signal ${status[0] and 0x7f}")
        assertEquals(0, (status[0] shr 8) and 0xff)
    }
}
