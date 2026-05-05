package edge.concurrency.synchronized_block;

public class Cache {
    private final Object lock = new Object();
    private String value = null;

    public String get() {
        synchronized (lock) {
            if (value == null) {
                value = compute();
            }
            return value;
        }
    }

    private String compute() {
        return "expensive";
    }
}
