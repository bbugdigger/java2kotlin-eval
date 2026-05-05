package edge.switch_statements.switch_no_default;

public class Severity {
    public String describe(int level) {
        switch (level) {
            case 0:
                return "info";
            case 1:
                return "warn";
            case 2:
                return "error";
        }
        return "unknown";
    }
}
