package edge.switch_statements.switch_no_default

class Severity {
    fun describe(level: Int): String = when (level) {
        0 -> "info"
        1 -> "warn"
        2 -> "error"
        else -> "unknown"
    }
}
