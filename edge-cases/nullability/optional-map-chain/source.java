package edge.nullability.optional_map_chain;

import java.util.Optional;

public class Pipeline {
    public String process(Optional<String> input) {
        return input
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(String::toUpperCase)
            .orElse("EMPTY");
    }
}
