package edge.nullability.jsr305_nullable

class Repo {
    fun findById(id: String): String? =
        if (id.isEmpty()) null else "row:$id"

    fun mustFind(id: String): String = "row:$id"
}
