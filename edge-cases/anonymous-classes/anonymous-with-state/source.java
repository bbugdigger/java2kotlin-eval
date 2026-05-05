package edge.anonymous_classes.anonymous_with_state;

import java.util.function.IntSupplier;

public class CounterFactory {
    public IntSupplier create(int start) {
        return new IntSupplier() {
            private int count = start;

            @Override
            public int getAsInt() {
                count += 1;
                return count;
            }
        };
    }
}
