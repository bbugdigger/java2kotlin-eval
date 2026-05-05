package edge.string_templates.multi_arg_concat

class Reporter {
    fun describe(name: String, count: Int, active: Boolean): String =
        "user $name has $count items${if (active) " and is active" else " and is inactive"}"
}
