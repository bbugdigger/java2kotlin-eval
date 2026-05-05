package edge.nullability.optional_return

class UserService {
    fun findEmail(userId: Int): String? =
        if (userId <= 0) null else "user$userId@example.com"
}
