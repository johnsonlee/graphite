package sample.controlflow;

public class ComplexControlFlowExample {

    // Nested if-else
    public int nestedIfElse(int a, int b, int c) {
        if (a > 0) {
            if (b > 0) {
                return a + b;
            } else {
                return a - b;
            }
        } else {
            if (c > 0) {
                return c;
            }
            return 0;
        }
    }

    // Ternary operator (compiled as if-else in bytecode)
    public int ternary(int x) {
        return x > 0 ? x * 2 : x * -1;
    }

    // Method chaining / fluent API
    public String methodChaining(String input) {
        return input.trim().toLowerCase().replace(" ", "_");
    }

    // Loop with method calls inside
    public int loopWithCalls(int[] values) {
        int sum = 0;
        for (int v : values) {
            sum += Math.abs(v);
        }
        return sum;
    }

    // Logical AND/OR conditions
    public boolean logicalConditions(int x, String s) {
        return x > 0 && s != null && s.length() > 0;
    }
}
