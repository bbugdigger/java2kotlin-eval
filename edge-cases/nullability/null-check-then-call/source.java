package edge.nullability.null_check_then_call;

public class Greeter {
    public String greet(String name) {
        if (name != null) {
            return "Hello, " + name.toUpperCase();
        }
        return "Hello, anonymous";
    }
}
