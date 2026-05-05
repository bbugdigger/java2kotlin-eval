package edge.records.java_record_with_method;

public record Range(int low, int high) {
    public int span() {
        return high - low;
    }

    public boolean contains(int n) {
        return n >= low && n <= high;
    }
}
