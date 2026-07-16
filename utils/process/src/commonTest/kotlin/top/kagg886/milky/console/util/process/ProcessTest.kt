package top.kagg886.milky.console.util.process

import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.Source
import okio.buffer
import top.kagg886.milky.console.util.pipe.BrokenPipeException
import top.kagg886.milky.console.util.pipe.IPCAnonymousPipe
import top.kagg886.milky.console.util.pipe.create
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessTest {
    @Test
    fun returnsExitCode() = runBlocking {
        val process = Process.create {
            executable(nativeTestExecutablePath())
            arguments("--exit-code", "7")
            stdin(ProcessConfig.IOOptions.None)
            stdout(ProcessConfig.IOOptions.None)
            stderr(ProcessConfig.IOOptions.None)
        }

        assertEquals(Process.ExitStatus.Result(7), process.await())
    }

    @Test
    fun capturesStdout() = runBlocking {
        val process = Process.create {
            executable(nativeTestExecutablePath())
            arguments("--stdout", "hello process")
            stdin(ProcessConfig.IOOptions.None)
            stdout(ProcessConfig.IOOptions.Redirected)
            stderr(ProcessConfig.IOOptions.None)
        }

        assertTrue(process.supportRedirectStdOut)
        assertEquals(Process.ExitStatus.Result(0), process.await())
        assertEquals("hello process", process.stdout.buffer().readUtf8())
    }

    @Test
    fun capturesStderr() = runBlocking {
        val process = Process.create {
            executable(nativeTestExecutablePath())
            arguments("--stderr", "error stream")
            stdin(ProcessConfig.IOOptions.None)
            stdout(ProcessConfig.IOOptions.None)
            stderr(ProcessConfig.IOOptions.Redirected)
        }

        assertTrue(process.supportRedirectStdErr)
        assertEquals(Process.ExitStatus.Result(0), process.await())
        assertEquals("error stream", process.stderr.buffer().readUtf8())
    }

    @Test
    fun writesStdinToChild() = runBlocking {
        val process = Process.create {
            executable(nativeTestExecutablePath())
            argument("--echo-stdin")
            stdin(ProcessConfig.IOOptions.Redirected)
            stdout(ProcessConfig.IOOptions.Redirected)
            stderr(ProcessConfig.IOOptions.None)
        }

        val input = Buffer().writeUtf8("from parent")
        process.stdin.write(input, input.size)
        process.stdin.close()

        assertEquals(Process.ExitStatus.Result(0), process.await())
        assertEquals("from parent", process.stdout.buffer().readUtf8())
    }

    @Test
    fun passesEnvironment() = runBlocking {
        val process = Process.create {
            executable(nativeTestExecutablePath())
            arguments("--env", "PROCESS_TEST_VALUE")
            environment("PROCESS_TEST_VALUE", "milky")
            stdin(ProcessConfig.IOOptions.None)
            stdout(ProcessConfig.IOOptions.Redirected)
            stderr(ProcessConfig.IOOptions.None)
        }

        assertEquals(Process.ExitStatus.Result(0), process.await())
        assertEquals("milky", process.stdout.buffer().readUtf8())
    }

    @Test
    fun writesToInheritedPipe() = runBlocking {
        val pipe = IPCAnonymousPipe.create()
        val nonInheritedProcess = Process.create {
            executable(nativeTestExecutablePath())
            arguments("--not-write-inherited-pipe", pipe.sink.fd.toString())
            stdin(ProcessConfig.IOOptions.None)
            stdout(ProcessConfig.IOOptions.None)
            stderr(ProcessConfig.IOOptions.None)
        }
        val process = Process.create {
            executable(nativeTestExecutablePath())
            arguments("--write-inherited-pipe", pipe.sink.fd.toString())
            inheritFD(pipe.sink.fd)
            stdin(ProcessConfig.IOOptions.None)
            stdout(ProcessConfig.IOOptions.None)
            stderr(ProcessConfig.IOOptions.None)
        }

        pipe.sink.close()

        assertEquals(Process.ExitStatus.Result(0), nonInheritedProcess.await())
        assertEquals(Process.ExitStatus.Result(0), process.await())
        assertEquals("inherited pipe", pipe.source.buffer().readUtf8())
    }
}

internal expect fun nativeTestExecutablePath(): String
