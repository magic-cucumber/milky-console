package top.kagg886.milky.console.plugin

import top.kagg886.milky.console.util.process.Process
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginCloseReasonTest {
    @Test
    fun hostRequestedCloseKeepsReasonAndNeverReloads() {
        val reason = PluginCloseReason.HostRequested("application shutdown", Process.ExitStatus.Result(1))

        assertFalse(reason.shouldReload)
        assertIs<PluginProcessExitException>(reason.exception)
    }

    @Test
    fun successfulUnpromptedProcessExitDoesNotReload() {
        val reason = PluginCloseReason.ProcessExited(Process.ExitStatus.Result(0))

        assertFalse(reason.shouldReload)
        assertNull(reason.exception)
    }

    @Test
    fun killedProcessAndPluginRequestedCloseReload() {
        val killed = PluginCloseReason.ProcessExited(Process.ExitStatus.Killed)
        val requested = PluginCloseReason.PluginRequested("native failure", "stack", Process.ExitStatus.Result(0))

        assertTrue(killed.shouldReload)
        assertIs<PluginProcessExitException>(killed.exception)
        assertTrue(requested.shouldReload)
        assertIs<PluginReportedCloseException>(requested.exception)
    }
}
