package edge.concurrency.synchronized_method;

public class Counter {
    private int n = 0;

    public synchronized int next() {
        n += 1;
        return n;
    }
}
