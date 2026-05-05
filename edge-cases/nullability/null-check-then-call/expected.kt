package edge.nullability.null_check_then_call

class Greeter {
    fun greet(name: String?): String =
        if (name != null) "Hello, ${name.uppercase()}" else "Hello, anonymous"
}
