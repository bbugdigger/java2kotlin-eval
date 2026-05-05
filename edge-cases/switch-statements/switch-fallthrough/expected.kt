package edge.switch_statements.switch_fallthrough

class Classifier {
    fun label(code: Int): String {
        val out = StringBuilder()
        when (code) {
            1 -> { out.append("one"); out.append("two") }
            2 -> out.append("two")
            3 -> out.append("three")
            else -> out.append("other")
        }
        return out.toString()
    }
}
