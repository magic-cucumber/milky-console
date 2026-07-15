package top.kagg886.milky.console.util.pipe

import okio.Sink
import okio.Source


interface IPCAnonymousPipe {
    val sink: top.kagg886.milky.console.util.pipe.IPCAnonymousPipeSink
    val source: top.kagg886.milky.console.util.pipe.IPCAnonymousPipeSource

    companion object
}

interface HasFileDescriptor {
    val fd: ULong
    val closed: Boolean
}

interface IPCAnonymousPipeSource: Source, top.kagg886.milky.console.util.pipe.HasFileDescriptor
interface IPCAnonymousPipeSink: Sink, top.kagg886.milky.console.util.pipe.HasFileDescriptor

expect fun top.kagg886.milky.console.util.pipe.IPCAnonymousPipe.Companion.create(): top.kagg886.milky.console.util.pipe.IPCAnonymousPipe
expect fun top.kagg886.milky.console.util.pipe.IPCAnonymousPipe.Companion.fromSource(fd: ULong): top.kagg886.milky.console.util.pipe.IPCAnonymousPipeSource
expect fun top.kagg886.milky.console.util.pipe.IPCAnonymousPipe.Companion.fromSink(fd: ULong): top.kagg886.milky.console.util.pipe.IPCAnonymousPipeSink
