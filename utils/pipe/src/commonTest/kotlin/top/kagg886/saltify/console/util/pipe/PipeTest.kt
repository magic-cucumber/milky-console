package top.kagg886.saltify.console.util.pipe

import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PipeTest {
    @Test
    fun transfersBytes() {
        val pipe = IPCAnonymousPipe.create()
        try {
            val source = Buffer().writeUtf8("anonymous pipe")
            val sink = Buffer()

            pipe.sink.write(source, source.size)

            assertEquals(14L, pipe.source.read(sink, 1024L))
            assertEquals("anonymous pipe", sink.readUtf8())
            assertTrue(source.exhausted())
        } finally {
            pipe.sink.close()
            pipe.source.close()
        }
    }

    @Test
    fun readDetectsClosedWriteEnd() {
        val pipe = IPCAnonymousPipe.create()
        pipe.sink.close()

        assertFailsWith<BrokenPipeException> {
            pipe.source.read(Buffer(), 1L)
        }
        assertTrue(pipe.source.closed)
    }

    @Test
    fun writeDetectsClosedReadEnd() {
        val pipe = IPCAnonymousPipe.create()
        pipe.source.close()

        assertFailsWith<BrokenPipeException> {
            val source = Buffer().writeByte(1)
            pipe.sink.write(source, source.size)
        }
        assertTrue(pipe.sink.closed)
    }
}
