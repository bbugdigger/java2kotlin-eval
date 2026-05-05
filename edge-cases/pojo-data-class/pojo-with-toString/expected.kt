package edge.pojo_data_class.pojo_with_to_string

data class Pair(val key: String, val value: String) {
    override fun toString(): String = "Pair{key='$key', value='$value'}"
}
