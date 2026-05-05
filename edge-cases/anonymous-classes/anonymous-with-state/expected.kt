package edge.anonymous_classes.anonymous_with_state

import java.util.function.IntSupplier

class CounterFactory {
    fun create(start: Int): IntSupplier = object : IntSupplier {
        private var count: Int = start
        override fun getAsInt(): Int {
            count += 1
            return count
        }
    }
}
