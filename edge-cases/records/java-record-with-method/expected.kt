package edge.records.java_record_with_method

data class Range(val low: Int, val high: Int) {
    fun span(): Int = high - low
    fun contains(n: Int): Boolean = n in low..high
}
