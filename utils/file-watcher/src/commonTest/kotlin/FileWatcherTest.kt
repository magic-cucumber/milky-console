import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import top.kagg886.milky.console.util.watcher.FileChange
import top.kagg886.milky.console.util.watcher.watchFileChange
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class FileWatcherTest {
    @Test
    fun reportsMultipleModifications() = withTemporaryDirectory { directory, file ->
        val changes = async {
            withTimeout(5.seconds) {
                FileSystem.SYSTEM.watchFileChange(file)
                    .filter { it == FileChange.MODIFIED }
                    .take(3)
                    .toList()
            }
        }
        delay(1.seconds)
        repeat(3) { index ->
            FileSystem.SYSTEM.write(file) { writeUtf8("change-$index") }
            delay(250.milliseconds)
        }

        assertEquals(List(3) { FileChange.MODIFIED }, changes.await())
    }

    @Test
    fun reportsDeletionThenCompletes() = withTemporaryDirectory { _, file ->
        val changes = async {
            withTimeout(5.seconds) { FileSystem.SYSTEM.watchFileChange(file).toList() }
        }
        delay(1.seconds)
        FileSystem.SYSTEM.delete(file)

        assertEquals(listOf(FileChange.DELETED), changes.await())
    }

    @Test
    fun reportsRenameAsDeletionThenCompletes() = withTemporaryDirectory { directory, file ->
        val renamed = directory / "renamed.txt"
        val changes = async {
            withTimeout(5.seconds) { FileSystem.SYSTEM.watchFileChange(file).toList() }
        }
        delay(1.seconds)
        FileSystem.SYSTEM.atomicMove(file, renamed)

        assertEquals(listOf(FileChange.DELETED), changes.await())
    }

    @Test
    fun rejectsMissingFile() {
        runBlocking {
            val missing = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "missing-${Random.nextLong()}.txt"
            assertFailsWith<Exception> {
                FileSystem.SYSTEM.watchFileChange(missing)
            }
        }
    }

    @Test
    fun rejectsNonSystemFileSystem() {
        assertFailsWith<IllegalStateException> {
            FakeFileSystem().watchFileChange("/watch-me.txt".toPath())
        }
    }

    @Test
    fun ignoresChangesToSiblingFilesAndDirectories() = withTemporaryDirectory { directory, file ->
        val sibling = directory / "sibling.txt"
        val childDirectory = directory / "child"
        val event = async {
            withTimeoutOrNull(700.milliseconds) {
                FileSystem.SYSTEM.watchFileChange(file).first()
            }
        }
        delay(1.seconds)
        FileSystem.SYSTEM.write(sibling) { writeUtf8("unrelated") }
        FileSystem.SYSTEM.createDirectories(childDirectory)

        assertNull(event.await())
    }

    private fun withTemporaryDirectory(block: suspend CoroutineScope.(Path, Path) -> Unit) = runBlocking {
        val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "file-watcher-${Random.nextLong()}"
        val file = directory / "watched.txt"
        FileSystem.SYSTEM.createDirectories(directory)
        FileSystem.SYSTEM.write(file) { writeUtf8("initial") }
        try {
            block(directory, file)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false)
        }
    }
}
