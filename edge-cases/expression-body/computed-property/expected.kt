package edge.expression_body.computed_property

class Box(private val width: Int, private val height: Int) {
    val area: Int get() = width * height
    val perimeter: Int get() = 2 * (width + height)
}
