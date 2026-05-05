package edge.property_folding.getter_only;

public class Immutable {
    private final String name;

    public Immutable(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
