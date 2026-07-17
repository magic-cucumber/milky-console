package top.kagg886.milky.console

import co.touchlab.kermit.Logger

private val applicationLogger = Logger.withTag("Application")


object Application {
    init {
        applicationLogger.i { "enter Application initialization" }
        applicationLogger.i { "exit Application initialization successfully" }
    }
}
