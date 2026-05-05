package edge.expression_body.computed_property;

public class Box {
    private final int width;
    private final int height;

    public Box(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public int getArea() {
        return width * height;
    }

    public int getPerimeter() {
        return 2 * (width + height);
    }
}
