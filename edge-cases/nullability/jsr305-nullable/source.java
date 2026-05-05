package edge.nullability.jsr305_nullable;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Repo {
    @Nullable
    public String findById(@NotNull String id) {
        if (id.isEmpty()) {
            return null;
        }
        return "row:" + id;
    }

    @NotNull
    public String mustFind(@NotNull String id) {
        return "row:" + id;
    }
}
