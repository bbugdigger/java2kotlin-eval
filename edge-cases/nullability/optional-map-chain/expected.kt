package edge.nullability.optional_map_chain

class Pipeline {
    fun process(input: String?): String =
        input?.trim()?.takeIf { it.isNotEmpty() }?.uppercase() ?: "EMPTY"
}
