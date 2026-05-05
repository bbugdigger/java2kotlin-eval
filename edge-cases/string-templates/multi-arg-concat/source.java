package edge.string_templates.multi_arg_concat;

public class Reporter {
    public String describe(String name, int count, boolean active) {
        return "user " + name + " has " + count + " items"
            + (active ? " and is active" : " and is inactive");
    }
}
