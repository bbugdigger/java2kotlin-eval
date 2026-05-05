package edge.switch_statements.switch_fallthrough;

public class Classifier {
    public String label(int code) {
        StringBuilder out = new StringBuilder();
        switch (code) {
            case 1:
                out.append("one");
                // intentional fallthrough
            case 2:
                out.append("two");
                break;
            case 3:
                out.append("three");
                break;
            default:
                out.append("other");
        }
        return out.toString();
    }
}
