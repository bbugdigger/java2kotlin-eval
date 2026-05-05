package edge.nullability.optional_return;

import java.util.Optional;

public class UserService {
    public Optional<String> findEmail(int userId) {
        if (userId <= 0) {
            return Optional.empty();
        }
        return Optional.of("user" + userId + "@example.com");
    }
}
