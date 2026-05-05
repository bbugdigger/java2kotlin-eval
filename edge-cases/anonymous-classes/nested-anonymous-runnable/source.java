package edge.anonymous_classes.nested_anonymous_runnable;

public class Scheduler {
    public Runnable build(String label) {
        return new Runnable() {
            @Override
            public void run() {
                Runnable inner = new Runnable() {
                    @Override
                    public void run() {
                        System.out.println("inner: " + label);
                    }
                };
                inner.run();
            }
        };
    }
}
