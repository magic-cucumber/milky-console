package top.kagg886.saltify.console.util.pipe

import okio.Sink
import okio.Source


interface IPCAnonymousPipe {
    val sink: IPCAnonymousPipeSink
    val source: IPCAnonymousPipeSource

    companion object
}

interface HasFileDescriptor {
    val fd: ULong
    val closed: Boolean
}

interface IPCAnonymousPipeSource: Source, HasFileDescriptor
interface IPCAnonymousPipeSink: Sink, HasFileDescriptor

expect fun IPCAnonymousPipe.Companion.create(): IPCAnonymousPipe
expect fun IPCAnonymousPipe.Companion.fromSource(fd: ULong): IPCAnonymousPipeSource
expect fun IPCAnonymousPipe.Companion.fromSink(fd: ULong): IPCAnonymousPipeSink
