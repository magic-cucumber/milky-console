import co.touchlab.kermit.Logger

private val log = Logger.withTag("Main")

fun main(args: Array<String>) {
    log.i { ">>> core::main() enter, args=${args.joinToString(", ")}" }
    log.d { "[group: entry-point] console started" }
    log.i { "<<< core::main() exit" }
}
