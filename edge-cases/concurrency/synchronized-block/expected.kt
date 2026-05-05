package edge.concurrency.synchronized_block

class Cache {
    private val lock = Any()
    private var value: String? = null

    fun get(): String = synchronized(lock) {
        if (value == null) {
            value = compute()
        }
        value!!
    }

    private fun compute(): String = "expensive"
}
