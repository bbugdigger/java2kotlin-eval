package edge.concurrency.synchronized_method

class Counter {
    private var n: Int = 0

    @Synchronized
    fun next(): Int {
        n += 1
        return n
    }
}
