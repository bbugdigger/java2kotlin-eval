package edge.anonymous_classes.nested_anonymous_runnable

class Scheduler {
    fun build(label: String): Runnable = Runnable {
        val inner = Runnable {
            println("inner: $label")
        }
        inner.run()
    }
}
