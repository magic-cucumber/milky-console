package top.kagg886.milky.console

import co.touchlab.kermit.Logger

private val log = Logger.withTag("Application")

object Application {
    init {
        log.i { ">>> Application init enter" }
        log.d { "[group: app-init] Application singleton initialized" }
        log.i { "<<< Application init exit" }
    }
}
