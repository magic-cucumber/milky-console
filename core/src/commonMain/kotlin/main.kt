import co.touchlab.kermit.Logger

private val coreMainLogger = Logger.withTag("CoreMain")


fun main(args: Array<String>) {
    coreMainLogger.i { "enter main: argCount=${args.size}" }
    coreMainLogger.i { "exit main successfully: no application bootstrap configured" }
}
