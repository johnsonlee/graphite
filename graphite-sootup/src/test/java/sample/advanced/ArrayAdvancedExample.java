package sample.advanced;

public class ArrayAdvancedExample {

    // Multi-dimensional array
    public int[][] create2DArray(int rows, int cols) {
        int[][] matrix = new int[rows][cols];
        matrix[0][0] = 42;
        return matrix;
    }

    // Array of objects
    public String[] createStringArray() {
        return new String[]{"hello", "world"};
    }

    // Varargs method
    public int sum(int... values) {
        int total = 0;
        for (int v : values) {
            total += v;
        }
        return total;
    }

    public int useVarargs() {
        return sum(1, 2, 3, 4, 5);
    }
}
