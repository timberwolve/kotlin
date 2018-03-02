// EXPECTED_REACHABLE_NODES: 1132
var log = ""

fun log(msg: String) {
    log += "$msg;"
}

lateinit var unit: Unit

fun box(): String {
    log("!!")!!

    unit = log("lateinit")
    log(unit.toString())

    log("elvis-left") ?: log("elvis-right")

    if (log("if") == null) log("then")

    when (null) {
        log("when-pattern") -> log("when-body-1")
    }

    when (log("when-subject")) {
        null -> log("when-body-2")
    }

    log += log("?.")?.toString()

    if (log != "!!;lateinit;kotlin.Unit;elvis-left;if;when-pattern;when-subject;?.;") return "fail: $log"

    return "OK"
}
